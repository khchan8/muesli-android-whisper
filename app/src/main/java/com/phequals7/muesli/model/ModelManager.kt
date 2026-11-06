package com.phequals7.muesli.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Represents the installation and download state of a `SpeechModelSpec` in Settings -> Models.
 */
data class ModelInstallStatus(
    val modelId: String,
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val isExtracting: Boolean = false,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val diskUsageBytes: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Manages resumable/cancellable downloads, automatic `huggingface.co` -> `hf-mirror.com` DNS fallback,
 * streaming `.tar.bz2` archive extraction, and composite bundle installation for Muesli v2.0.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val BUFFER_SIZE = 64 * 1024

        @Volatile
        private var INSTANCE: ModelManager? = null

        fun getInstance(context: Context): ModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    val modelsRootDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    private val downloadsTempDir: File
        get() = File(context.cacheDir, "model_downloads").apply { if (!exists()) mkdirs() }

    private val _statuses = MutableStateFlow<Map<String, ModelInstallStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ModelInstallStatus>> = _statuses.asStateFlow()

    init {
        refreshAllStatuses()
    }

    /** True when every required file of [spec] (default: the selected model) is on disk. */
    fun isDownloaded(spec: SpeechModelSpec = SpeechModels.selected(context)): Boolean =
        spec.isInstalled(modelsRootDir)

    /** Bytes already on disk for [spec], including partial `.part` downloads. */
    fun downloadedBytes(spec: SpeechModelSpec = SpeechModels.selected(context)): Long =
        computeModelDiskUsage(spec, modelsRootDir)

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024f)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024f / 1024f)
        else -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
    }

    /**
     * Scans `files/models/` and updates installation & disk usage status for all registered models.
     */
    fun refreshAllStatuses() {
        scope.launch {
            val root = modelsRootDir
            val updated = mutableMapOf<String, ModelInstallStatus>()
            val currentMap = _statuses.value

            for (spec in SpeechModels.allModels) {
                val prev = currentMap[spec.id]
                if (prev != null && (prev.isDownloading || prev.isExtracting)) {
                    updated[spec.id] = prev
                } else {
                    val installed = spec.isInstalled(root)
                    val diskBytes = if (installed) computeModelDiskUsage(spec, root) else 0L
                    updated[spec.id] = ModelInstallStatus(
                        modelId = spec.id,
                        isInstalled = installed,
                        isDownloading = false,
                        isExtracting = false,
                        progress = if (installed) 1.0f else 0f,
                        diskUsageBytes = diskBytes,
                        errorMessage = prev?.errorMessage
                    )
                }
            }
            _statuses.value = updated
        }
    }

    /**
     * Starts an asynchronous, resumable download and installation for `spec` (and also ensures
     * the CAM++ Speaker Diarization model is installed so meeting diarization is ready).
     */
    fun downloadModel(
        spec: SpeechModelSpec,
        alsoEnsureCamPlusPlusDiarizer: Boolean = true
    ) {
        if (activeJobs[spec.id]?.isActive == true) return

        val job = scope.launch {
            try {
                installModelInternal(spec, statusKeyId = spec.id)

                // Proactively ensure the 28.3 MB CAM++ speaker embedding model is present for meetings
                if (alsoEnsureCamPlusPlusDiarizer &&
                    spec.category == ModelCategory.ASR_MULTILINGUAL &&
                    !SpeechModels.CAMPLUS_ZH_EN_SPEAKER_EMBEDDING.isInstalled(modelsRootDir)
                ) {
                    runCatching {
                        installModelInternal(
                            spec = SpeechModels.CAMPLUS_ZH_EN_SPEAKER_EMBEDDING,
                            statusKeyId = SpeechModels.CAMPLUS_ZH_EN_SPEAKER_EMBEDDING.id
                        )
                    }
                }
            } catch (ce: CancellationException) {
                updateModelStatus(spec.id) {
                    it.copy(
                        isDownloading = false,
                        isExtracting = false,
                        errorMessage = "Download paused (can resume)"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install model ${spec.id}", e)
                updateModelStatus(spec.id) {
                    it.copy(
                        isDownloading = false,
                        isExtracting = false,
                        errorMessage = e.localizedMessage ?: "Download failed"
                    )
                }
            } finally {
                activeJobs.remove(spec.id)
                refreshAllStatuses()
            }
        }
        activeJobs[spec.id] = job
    }

    /**
     * Cancels an in-flight download while preserving partial `.part` files on disk so the user
     * can resume later via HTTP `Range`.
     */
    fun cancelDownload(modelId: String) {
        activeJobs.remove(modelId)?.cancel()
    }

    /**
     * Deletes an installed model's directory or files and clears any partial download artifacts.
     */
    suspend fun deleteModel(spec: SpeechModelSpec) = withContext(Dispatchers.IO) {
        cancelDownload(spec.id)

        when (val delivery = spec.delivery) {
            is ModelDeliverySpec.CompositeBundle -> {
                for (compId in delivery.componentModelIds) {
                    SpeechModels.findById(compId)?.let { compSpec ->
                        deleteSingleSpecFiles(compSpec)
                    }
                }
            }
            else -> deleteSingleSpecFiles(spec)
        }
        refreshAllStatuses()
    }

    private fun deleteSingleSpecFiles(spec: SpeechModelSpec) {
        if (spec.targetFolderName.isNotEmpty()) {
            val targetDir = File(modelsRootDir, spec.targetFolderName)
            if (targetDir.exists()) targetDir.deleteRecursively()
        } else {
            for (relPath in spec.requiredRelativeFiles) {
                val f = File(modelsRootDir, relPath)
                if (f.exists()) f.delete()
            }
        }
        File(downloadsTempDir, "${spec.id}.tar.bz2.part").delete()
    }

    private suspend fun installModelInternal(spec: SpeechModelSpec, statusKeyId: String) {
        updateModelStatus(statusKeyId) {
            it.copy(
                isDownloading = true,
                isExtracting = false,
                progress = 0.01f,
                errorMessage = null
            )
        }

        when (val delivery = spec.delivery) {
            is ModelDeliverySpec.CompositeBundle -> {
                val components = delivery.componentModelIds.mapNotNull { SpeechModels.findById(it) }
                for ((idx, comp) in components.withIndex()) {
                    coroutineContext.ensureActive()
                    if (!comp.isInstalled(modelsRootDir)) {
                        installModelInternal(comp, statusKeyId = statusKeyId)
                    }
                    val overallProgress = (idx + 1).toFloat() / components.size.toFloat()
                    updateModelStatus(statusKeyId) { it.copy(progress = overallProgress) }
                }
            }

            is ModelDeliverySpec.TarBz2Archive -> {
                installFromTarBz2Archive(spec, delivery, statusKeyId)
            }

            is ModelDeliverySpec.DirectFileManifest -> {
                installFromDirectManifest(spec, delivery, statusKeyId)
            }
        }

        val installedOk = spec.isInstalled(modelsRootDir)
        if (!installedOk && spec.id == SpeechModels.QWEN3_ASR_0_6B_INT8.id) {
            // Automatic fallback to per-file HuggingFace manifest if the .tar.bz2 archive layout differed
            Log.i(TAG, "Falling back to direct HuggingFace file manifest for Qwen3-ASR 0.6B")
            installFromDirectManifest(
                spec = spec,
                manifest = SpeechModels.QWEN3_ASR_0_6B_DIRECT_MANIFEST,
                statusKeyId = statusKeyId
            )
        }

        val finalVerified = spec.isInstalled(modelsRootDir)
        if (!finalVerified) {
            throw IOException("Model verification failed after extraction for ${spec.title}")
        }

        val diskUsage = computeModelDiskUsage(spec, modelsRootDir)
        updateModelStatus(statusKeyId) {
            it.copy(
                isInstalled = true,
                isDownloading = false,
                isExtracting = false,
                progress = 1.0f,
                diskUsageBytes = diskUsage,
                errorMessage = null
            )
        }
    }

    private suspend fun installFromTarBz2Archive(
        spec: SpeechModelSpec,
        archiveSpec: ModelDeliverySpec.TarBz2Archive,
        statusKeyId: String
    ) {
        val partFile = File(downloadsTempDir, "${spec.id}.tar.bz2.part")
        val candidateUrls = buildCandidateUrlList(archiveSpec.primaryUrl, archiveSpec.fallbackUrls)

        downloadFileWithResumeAndFallback(
            candidateUrls = candidateUrls,
            destinationPartFile = partFile,
            onProgress = { downloaded, total ->
                val ratio = if (total > 0L) {
                    (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.85f)
                } else {
                    0.40f
                }
                updateModelStatus(statusKeyId) {
                    it.copy(
                        isDownloading = true,
                        isExtracting = false,
                        progress = ratio,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }
            }
        )

        updateModelStatus(statusKeyId) {
            it.copy(
                isDownloading = false,
                isExtracting = true,
                progress = 0.88f
            )
        }

        extractTarBz2Archive(
            archiveFile = partFile,
            modelsRoot = modelsRootDir,
            expectedTargetFolder = spec.targetFolderName,
            archiveRootOverride = archiveSpec.archiveRootFolderOverride
        )

        partFile.delete()
    }

    private suspend fun installFromDirectManifest(
        spec: SpeechModelSpec,
        manifest: ModelDeliverySpec.DirectFileManifest,
        statusKeyId: String
    ) {
        val baseTargetDir = if (spec.targetFolderName.isEmpty()) {
            modelsRootDir
        } else {
            File(modelsRootDir, spec.targetFolderName).apply { mkdirs() }
        }

        val totalFiles = manifest.files.size.coerceAtLeast(1)
        for ((index, remoteFile) in manifest.files.withIndex()) {
            coroutineContext.ensureActive()
            val finalFile = File(baseTargetDir, remoteFile.relativeDestinationPath)
            if (finalFile.exists() && finalFile.length() >= remoteFile.expectedMinBytes) {
                continue
            }
            finalFile.parentFile?.mkdirs()
            val partFile = File(finalFile.parentFile, "${finalFile.name}.part")
            val candidateUrls = buildCandidateUrlList(remoteFile.primaryUrl, remoteFile.fallbackUrls)

            downloadFileWithResumeAndFallback(
                candidateUrls = candidateUrls,
                destinationPartFile = partFile,
                onProgress = { downloaded, total ->
                    val fileFraction = if (total > 0L) downloaded.toFloat() / total.toFloat() else 0.5f
                    val overall = ((index + fileFraction) / totalFiles.toFloat()).coerceIn(0.01f, 0.99f)
                    updateModelStatus(statusKeyId) {
                        it.copy(
                            isDownloading = true,
                            progress = overall,
                            downloadedBytes = downloaded,
                            totalBytes = total
                        )
                    }
                }
            )

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
        }
    }

    /**
     * Expands primary and fallback URLs by appending automatic `hf-mirror.com` equivalents
     * for any `huggingface.co` URL so downloads succeed even when HuggingFace DNS is blocked.
     */
    private fun buildCandidateUrlList(primaryUrl: String, fallbackUrls: List<String>): List<String> {
        val result = mutableListOf<String>()
        val allRaw = listOf(primaryUrl) + fallbackUrls
        for (url in allRaw) {
            if (url !in result) result.add(url)
            if (url.contains("huggingface.co/")) {
                val mirrorUrl = url.replace("huggingface.co/", "hf-mirror.com/")
                if (mirrorUrl !in result) result.add(mirrorUrl)
            }
        }
        return result
    }

    /**
     * Downloads a single remote file to `destinationPartFile` supporting HTTP `Range` resume
     * (`206 Partial Content`) and automatic failover across `candidateUrls`.
     */
    private suspend fun downloadFileWithResumeAndFallback(
        candidateUrls: List<String>,
        destinationPartFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (urlStr in candidateUrls) {
            coroutineContext.ensureActive()
            var connection: HttpURLConnection? = null
            try {
                val existingBytes = if (destinationPartFile.exists()) destinationPartFile.length() else 0L
                connection = openFollowingRedirects(urlStr, existingBytes)
                val responseCode = connection.responseCode

                val isResumed = (responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                val isFreshOk = (responseCode == HttpURLConnection.HTTP_OK)

                if (responseCode == 416) {
                    // Range Not Satisfiable -> file is likely already completely downloaded
                    onProgress(existingBytes, existingBytes)
                    return@withContext
                }

                if (!isResumed && !isFreshOk) {
                    throw IOException("HTTP $responseCode from $urlStr")
                }

                val contentLength = connection.contentLengthLong
                val totalBytes = if (contentLength > 0L) {
                    if (isResumed) existingBytes + contentLength else contentLength
                } else {
                    -1L
                }

                RandomAccessFile(destinationPartFile, "rw").use { raf ->
                    if (isResumed) {
                        raf.seek(existingBytes)
                    } else {
                        raf.setLength(0L)
                        raf.seek(0L)
                    }

                    var totalWritten = if (isResumed) existingBytes else 0L
                    val buf = ByteArray(BUFFER_SIZE)
                    BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            if (read > 0) {
                                raf.write(buf, 0, read)
                                totalWritten += read
                                onProgress(totalWritten, totalBytes)
                            }
                        }
                    }
                }
                return@withContext
            } catch (ce: CancellationException) {
                throw ce
            } catch (uhe: UnknownHostException) {
                Log.w(TAG, "DNS resolution failed for $urlStr, trying next fallback/mirror...", uhe)
                lastException = uhe
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt failed from $urlStr, trying next fallback...", e)
                lastException = e
            } finally {
                connection?.disconnect()
            }
        }

        throw lastException ?: IOException("All download mirrors failed")
    }

    private fun openFollowingRedirects(initialUrl: String, rangeStartBytes: Long): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Muesli-Android/2.0 (arm64-v8a; SherpaOnnx)")
                if (rangeStartBytes > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStartBytes-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect missing Location header")
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw IOException("Too many HTTP redirects for $initialUrl")
    }

    /**
     * Streams and decompresses a `.tar.bz2` archive into `modelsRoot`, remapping the top-level
     * archive directory to `expectedTargetFolder` if `archiveRootOverride` is set (e.g., mapping
     * `sherpa-onnx-whisper-turbo/` -> `sherpa-onnx-whisper-large-v3-turbo/`).
     */
    private suspend fun extractTarBz2Archive(
        archiveFile: File,
        modelsRoot: File,
        expectedTargetFolder: String,
        archiveRootOverride: String?
    ) = withContext(Dispatchers.IO) {
        val canonicalModelsRoot = modelsRoot.canonicalFile

        BufferedInputStream(FileInputStream(archiveFile), BUFFER_SIZE).use { fileIn ->
            BZip2CompressorInputStream(fileIn, true).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        coroutineContext.ensureActive()
                        var entryName = entry.name.removePrefix("./").trimStart('/')

                        if (archiveRootOverride != null &&
                            expectedTargetFolder.isNotEmpty() &&
                            entryName.startsWith("$archiveRootOverride/")
                        ) {
                            entryName = expectedTargetFolder + entryName.removePrefix(archiveRootOverride)
                        }

                        if (entryName.isNotEmpty()) {
                            val outFile = File(canonicalModelsRoot, entryName)
                            val canonicalOut = outFile.canonicalFile
                            if (!canonicalOut.path.startsWith(canonicalModelsRoot.path)) {
                                throw SecurityException("Blocked zip-slip tar entry: ${entry.name}")
                            }

                            if (entry.isDirectory) {
                                canonicalOut.mkdirs()
                            } else {
                                canonicalOut.parentFile?.mkdirs()
                                FileOutputStream(canonicalOut).use { outStream ->
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val read = tarIn.read(buffer)
                                        if (read < 0) break
                                        if (read > 0) outStream.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    private fun computeModelDiskUsage(spec: SpeechModelSpec, modelsRoot: File): Long {
        if (spec.delivery is ModelDeliverySpec.CompositeBundle) {
            return spec.delivery.componentModelIds.sumOf { compId ->
                SpeechModels.findById(compId)?.let { computeModelDiskUsage(it, modelsRoot) } ?: 0L
            }
        }
        return if (spec.targetFolderName.isNotEmpty()) {
            val dir = File(modelsRoot, spec.targetFolderName)
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            spec.requiredRelativeFiles.sumOf { rel ->
                val f = File(modelsRoot, rel)
                if (f.exists()) f.length() else 0L
            }
        }
    }

    private fun updateModelStatus(
        modelId: String,
        transform: (ModelInstallStatus) -> ModelInstallStatus
    ) {
        _statuses.update { current ->
            val existing = current[modelId] ?: ModelInstallStatus(modelId = modelId)
            current + (modelId to transform(existing))
        }
    }
}