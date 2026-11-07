package com.phequals7.muesli.meetings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phequals7.muesli.MainActivity
import com.phequals7.muesli.R
import com.phequals7.muesli.data.SharedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground `dataSync` service that serializes every embedded-subtitle MP4
 * export. Jobs are queued per session (coalesced; a manual request upgrades
 * a queued automatic one), so one slow encode can never drop another
 * meeting's request. Reuses the summary service's foreground/cancellation
 * conventions but owns no microphone or LLM gate: a failed or cancelled MP4
 * export leaves the audio/SRT exports untouched and records only the
 * MP4-specific error column.
 */
class MeetingMp4ExportService : Service() {

    companion object {
        private const val TAG = "MeetingMp4Export"
        const val ACTION_EXPORT = "com.phequals7.muesli.meetings.EXPORT_MP4"
        const val ACTION_CANCEL = "com.phequals7.muesli.meetings.CANCEL_MP4"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FORCE = "force"
        private const val CHANNEL_ID = "meeting_mp4_export"
        private const val NOTIFICATION_ID = 44
        internal const val CANCEL_MESSAGE = "MP4 export cancelled. You can export it again."
        internal const val TIMEOUT_MESSAGE = "The MP4 export was interrupted by the system. Try again."
        internal const val INTERRUPTED_MESSAGE = "The MP4 export was interrupted. Try again."
        private const val STARTUP_FAILURE_MESSAGE = "Could not start the MP4 export service. Try again."

        private val stateLock = Any()
        private val queue = Mp4RequestQueue()
        private val _progress = MutableStateFlow<Mp4ExportProgress?>(null)
        private val queuedFlow = MutableStateFlow<Set<String>>(emptySet())

        @Volatile
        private var activeRun: ActiveRun? = null

        @Volatile
        private var workerRunning: Boolean = false

        /** Live progress of the one active export, or null when idle. */
        val progress: StateFlow<Mp4ExportProgress?> = _progress.asStateFlow()

        /** Session ids waiting behind the active job. */
        val queuedSessionIds: StateFlow<Set<String>> = queuedFlow.asStateFlow()

        private class ActiveRun(
            val sessionId: String,
            val cancelledByUser: AtomicBoolean = AtomicBoolean(false),
            @Volatile var job: Job? = null,
        )

        private fun publishQueued() {
            queuedFlow.value = queue.snapshot()
        }

        internal fun hasMessageInChain(t: Throwable?, expected: String): Boolean {
            var curr = t
            while (curr != null) {
                if (curr.message == expected) return true
                curr = curr.cause
            }
            return false
        }

        /**
         * Enqueues an export for [sessionId]. Automatic requests are dropped
         * while the setting is off; a `force` request bypasses the setting
         * without changing it and upgrades an already-queued automatic
         * request for the same meeting.
         */
        fun requestExport(context: Context, sessionId: String, force: Boolean) {
            val appContext = context.applicationContext
            if (!force && !SharedStore(appContext).exportEmbeddedMp4Enabled) return
            val shouldStartService = synchronized(stateLock) {
                val changed = queue.add(sessionId, force)
                publishQueued()
                changed || !workerRunning
            }
            if (!shouldStartService) return
            val intent = Intent(appContext, MeetingMp4ExportService::class.java)
                .setAction(ACTION_EXPORT)
            try {
                appContext.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start the MP4 export service", t)
                val shouldRecordFailure = synchronized(stateLock) {
                    if (!workerRunning) {
                        queue.remove(sessionId)
                        publishQueued()
                        true
                    } else {
                        false
                    }
                }
                if (shouldRecordFailure) {
                    recordExportErrorAsync(appContext, sessionId, STARTUP_FAILURE_MESSAGE)
                }
            }
        }

        private fun recordExportErrorAsync(appContext: Context, sessionId: String, message: String?) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    val store = SharedStore(appContext)
                    if (store.getRecordingSessionById(sessionId) != null) {
                        store.updateMp4ExportError(sessionId, message)
                    }
                }
            }
        }

        private fun cancelSession(appContext: Context, requestedSessionId: String?): Boolean {
            var removedQueued = false
            var cancelledActive = false
            var targetId: String? = null
            synchronized(stateLock) {
                val currentRun = activeRun
                targetId = requestedSessionId?.takeIf { it.isNotBlank() }
                    ?: currentRun?.sessionId
                    ?: _progress.value?.sessionId
                    ?: queue.peekSessionId()
                val id = targetId
                if (id != null) {
                    removedQueued = queue.remove(id)
                    if (removedQueued) {
                        publishQueued()
                    }
                    if (currentRun != null && currentRun.sessionId == id) {
                        currentRun.cancelledByUser.set(true)
                        _progress.value = _progress.value?.takeIf { it.sessionId == id }
                            ?.copy(cancelling = true)
                        currentRun.job?.cancel(CancellationException(CANCEL_MESSAGE))
                        cancelledActive = true
                    }
                }
            }
            if (removedQueued && !cancelledActive && targetId != null) {
                recordExportErrorAsync(appContext, targetId!!, null)
            }
            return cancelledActive
        }

        /** Cancels the live export for [sessionId] or drops it from the queue. */
        fun cancel(context: Context, sessionId: String) {
            val appContext = context.applicationContext
            cancelSession(appContext, sessionId)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var workerJob: Job? = null
    private var lastStartId: Int = 0
    @Volatile
    private var timedOut: Boolean = false
    private lateinit var store: SharedStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SharedStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_EXPORT -> {
                val initialSessionId = synchronized(stateLock) {
                    activeRun?.sessionId ?: _progress.value?.sessionId ?: queue.peekSessionId()
                }
                try {
                    startForegroundWithNotification(initialSessionId)
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not enter foreground for MP4 export", t)
                    if (workerJob?.isActive != true) {
                        val dropped = synchronized(stateLock) {
                            val ids = queue.drainAll()
                            workerRunning = false
                            activeRun = null
                            _progress.value = null
                            publishQueued()
                            ids
                        }
                        dropped.forEach { id ->
                            recordExportErrorAsync(applicationContext, id, STARTUP_FAILURE_MESSAGE)
                        }
                        stopSelf(startId)
                    }
                    return START_NOT_STICKY
                }
                if (workerJob?.isActive != true) startWorker()
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID)
                cancelSession(applicationContext, id)
                if (workerJob?.isActive != true && synchronized(stateLock) { activeRun == null && queue.isEmpty() }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> {
                if (workerJob?.isActive != true) {
                    synchronized(stateLock) {
                        workerRunning = false
                        activeRun = null
                        _progress.value = null
                        publishQueued()
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground timeout (fgsType=$fgsType); cancelling MP4 export")
        timedOut = true
        val dropped = synchronized(stateLock) {
            val ids = queue.drainAll()
            publishQueued()
            ids
        }
        dropped.forEach { id ->
            recordExportErrorAsync(applicationContext, id, TIMEOUT_MESSAGE)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val timeoutCause = CancellationException(TIMEOUT_MESSAGE)
        synchronized(stateLock) { activeRun?.job }?.cancel(timeoutCause)
        val worker = workerJob
        if (worker?.isActive == true) {
            worker.cancel(timeoutCause)
        } else {
            synchronized(stateLock) {
                workerRunning = false
                activeRun = null
                _progress.value = null
                publishQueued()
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (workerJob?.isActive == true || activeRun != null) {
            synchronized(stateLock) {
                queue.drainAll()
                workerRunning = false
                activeRun = null
                _progress.value = null
                publishQueued()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startWorker() {
        timedOut = false
        synchronized(stateLock) {
            workerRunning = true
        }
        workerJob = serviceScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { pruneOrphanedCacheDirs() }
            }
            try {
                outer@ while (isActive) {
                    while (isActive) {
                        val next = synchronized(stateLock) {
                            val pair = queue.takeNext()
                            if (pair == null) {
                                workerRunning = false
                                activeRun = null
                                _progress.value = null
                                publishQueued()
                                null
                            } else {
                                workerRunning = true
                                val (sessionId, force) = pair
                                val run = ActiveRun(sessionId)
                                activeRun = run
                                if (force) {
                                    _progress.value = Mp4ExportProgress(sessionId, "Preparing")
                                }
                                publishQueued()
                                pair to run
                            }
                        } ?: break

                        val (pair, run) = next
                        val (sessionId, force) = pair
                        runCatching { startForegroundWithNotification(sessionId) }

                        val job = launch(SupervisorJob(coroutineContext[Job])) {
                            try {
                                runJob(sessionId, force, run)
                            } catch (ce: CancellationException) {
                                withContext(NonCancellable) {
                                    runCatching {
                                        if (store.getRecordingSessionById(sessionId) != null) {
                                            // A user cancel keeps the failure column clean; a
                                            // system timeout/interrupt stays visible for retry.
                                            if (run.cancelledByUser.get() || hasMessageInChain(ce, CANCEL_MESSAGE)) {
                                                store.updateMp4ExportError(sessionId, null)
                                            } else {
                                                val msg = if (timedOut || hasMessageInChain(ce, TIMEOUT_MESSAGE)) {
                                                    TIMEOUT_MESSAGE
                                                } else {
                                                    INTERRUPTED_MESSAGE
                                                }
                                                store.updateMp4ExportError(sessionId, msg)
                                            }
                                        }
                                    }
                                }
                                throw ce
                            } catch (t: Throwable) {
                                Log.e(TAG, "MP4 export failed for $sessionId", t)
                                withContext(NonCancellable) {
                                    runCatching {
                                        if (store.getRecordingSessionById(sessionId) != null) {
                                            store.updateMp4ExportError(
                                                sessionId,
                                                t.message?.takeIf { it.isNotBlank() } ?: "MP4 export failed. Try again."
                                            )
                                        }
                                    }
                                }
                            } finally {
                                withContext(NonCancellable) {
                                    synchronized(stateLock) {
                                        if (activeRun === run) {
                                            activeRun = null
                                            _progress.value = null
                                        }
                                        publishQueued()
                                    }
                                }
                            }
                        }

                        synchronized(stateLock) {
                            run.job = job
                            if (run.cancelledByUser.get()) {
                                job.cancel(CancellationException(CANCEL_MESSAGE))
                            }
                        }

                        withContext(NonCancellable) {
                            job.join()
                        }
                    }
                    if (!isActive || synchronized(stateLock) { queue.isEmpty() }) break@outer
                }
            } finally {
                val workerCancelled = !isActive || timedOut
                if (workerCancelled) {
                    withContext(NonCancellable) {
                        val dropped = synchronized(stateLock) {
                            workerRunning = false
                            activeRun = null
                            _progress.value = null
                            val ids = queue.drainAll()
                            publishQueued()
                            ids
                        }
                        val msg = if (timedOut) TIMEOUT_MESSAGE else INTERRUPTED_MESSAGE
                        for (id in dropped) {
                            runCatching {
                                if (store.getRecordingSessionById(id) != null) {
                                    store.updateMp4ExportError(id, msg)
                                }
                            }
                        }
                    }
                } else {
                    synchronized(stateLock) {
                        workerRunning = false
                        activeRun = null
                        _progress.value = null
                        publishQueued()
                    }
                }
                workerJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (workerCancelled) stopSelf() else stopSelf(lastStartId)
            }
        }
    }

    private fun updateStage(run: ActiveRun, stage: String) {
        synchronized(stateLock) {
            if (activeRun === run) {
                _progress.value = Mp4ExportProgress(
                    sessionId = run.sessionId,
                    stage = stage,
                    cancelling = run.cancelledByUser.get()
                )
            }
        }
    }

    private suspend fun runJob(sessionId: String, force: Boolean, run: ActiveRun) {
        suspend fun checkNotCancelled() {
            if (run.cancelledByUser.get()) {
                throw CancellationException(CANCEL_MESSAGE)
            }
            currentCoroutineContext().ensureActive()
        }

        checkNotCancelled()

        // Toggle re-check when automatic work begins: disable = drop quietly.
        if (!force && !store.exportEmbeddedMp4Enabled) return

        val session = store.getRecordingSessionById(sessionId) ?: return
        if (session.phase != "completed") return

        val audioPath = session.audioFileName?.takeIf { it.isNotBlank() }
        val ineligibleReason = when {
            audioPath == null ->
                "This meeting has no retained audio, so no MP4 can be exported."
            !audioPath.endsWith(".m4a", ignoreCase = true) ->
                "This meeting retains legacy WAV audio; the MP4 export needs the AAC M4A format."
            store.recordingsTreeUri.isBlank() ->
                "Choose a recordings folder to save the MP4 beside the audio and SRT."
            else -> null
        }
        if (ineligibleReason != null) {
            store.updateMp4ExportError(sessionId, ineligibleReason)
            return
        }

        if (force) {
            updateStage(run, "Preparing")
        }
        checkNotCancelled()

        val snapshot = MeetingStorageManager.prepareMp4ExportSnapshot(applicationContext, store, sessionId)
        if (snapshot == null) {
            checkNotCancelled()
            val latest = store.getRecordingSessionById(sessionId) ?: return
            if (latest.phase != "completed") return
            if (!force && !store.exportEmbeddedMp4Enabled) return
            store.updateMp4ExportError(sessionId, "The retained M4A audio is missing. Retry the full export first.")
            return
        }

        var channelClosed = false
        try {
            checkNotCancelled()
            // Skip an up-to-date automatic export: same fingerprint, owned
            // document still in the selected tree with the expected name and
            // a positive provider-reported size.
            if (!force && snapshot.ownedMp4Uri != null &&
                snapshot.ownedMp4Fingerprint == snapshot.fingerprint
            ) {
                val upToDate = withContext(Dispatchers.IO) {
                    runCatching {
                        val gateway = ContentResolverTreeGateway(
                            applicationContext, Uri.parse(snapshot.treeUri)
                        )
                        MeetingStorageManager.canSkipAutomaticMp4Export(
                            gateway = gateway,
                            force = force,
                            audioUri = snapshot.ownedAudioUri,
                            ownedMp4Uri = snapshot.ownedMp4Uri,
                            ownedMp4Fingerprint = snapshot.ownedMp4Fingerprint,
                            currentFingerprint = snapshot.fingerprint,
                            mp4Subfolder = if (store.mp4SubfolderEnabled)
                                MeetingStorageManager.MP4_SUBFOLDER else null,
                        )
                    }.getOrDefault(false)
                }
                if (upToDate) {
                    if (session.mp4ExportError != null) {
                        store.updateMp4ExportError(sessionId, null)
                    }
                    return
                }
            }

            updateStage(run, "Encoding video canvas")
            checkNotCancelled()
            val cacheDir = File(
                applicationContext.cacheDir,
                "${MeetingStorageManager.MP4_EXPORT_CACHE_DIR_PREFIX}${sessionId}_${UUID.randomUUID()}"
            )
            try {
                withContext(Dispatchers.IO) { cacheDir.mkdirs() }
                val output = File(cacheDir, "export.mp4")
                snapshot.audioChannel.use { channel ->
                    channelClosed = true
                    Mp4SubtitleMuxer.muxM4aAndSegmentsToMp4(
                        channel, snapshot.segments, output, summaryText = snapshot.summaryText
                    )
                }

                checkNotCancelled()
                // Second toggle check: a disable during encoding keeps every
                // existing file and drops the freshly generated output.
                if (!force && !store.exportEmbeddedMp4Enabled) return

                updateStage(run, "Saving to recordings folder")
                checkNotCancelled()
                val outcome = MeetingStorageManager.publishMp4Export(
                    applicationContext, store, snapshot, output
                )
                when {
                    outcome.publishedUri != null && outcome.error == null ->
                        store.updateMp4ExportError(sessionId, null)
                    outcome.publishedUri != null ->
                        store.updateMp4ExportError(sessionId, outcome.error)
                    outcome.stale -> {
                        checkNotCancelled()
                        // Inputs moved under us: discard and coalesce a fresh
                        // request with the same intent.
                        if (force || store.exportEmbeddedMp4Enabled) {
                            requestExport(applicationContext, sessionId, force)
                        }
                    }
                    else -> {
                        checkNotCancelled()
                        if (store.getRecordingSessionById(sessionId) != null) {
                            val recovery = outcome.recoveryUri
                                ?.let { " (backup kept at: $it)" } ?: ""
                            store.updateMp4ExportError(sessionId, (outcome.error ?: "MP4 export failed.") + recovery)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { cacheDir.deleteRecursively() }
                }
            }
        } finally {
            if (!channelClosed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { snapshot.audioChannel.close() }
                }
            }
        }
    }


    private fun pruneOrphanedCacheDirs() {
        applicationContext.cacheDir.listFiles { dir, name ->
            dir.isDirectory && name.startsWith(MeetingStorageManager.MP4_EXPORT_CACHE_DIR_PREFIX)
        }?.forEach { it.deleteRecursively() }
    }


    // ── notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Meeting MP4 Exports",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while Muesli exports MP4s with embedded subtitles" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(sessionId: String?) {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MeetingMp4ExportService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_SESSION_ID, sessionId.orEmpty()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Exporting MP4")
            .setContentText("Muesli")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "Cancel export", cancelIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

/** Live progress snapshot of the one active MP4 export. */
data class Mp4ExportProgress(
    val sessionId: String,
    val stage: String,
    val cancelling: Boolean = false,
)

/** Pure per-session coalescing queue: duplicates merge in place, a forced
 *  request upgrades a queued automatic one, different meetings never
 *  displace each other. */
internal class Mp4RequestQueue {
    private val entries = LinkedHashMap<String, Boolean>() // sessionId -> force

    @Synchronized
    fun add(sessionId: String, force: Boolean): Boolean {
        val existing = entries[sessionId]
        return when {
            existing == null -> { entries[sessionId] = force; true }
            existing || !force -> false
            else -> { entries[sessionId] = true; true } // automatic → forced upgrade
        }
    }

    @Synchronized
    fun remove(sessionId: String): Boolean = entries.remove(sessionId) != null

    @Synchronized
    fun takeNext(): Pair<String, Boolean>? {
        val head = entries.entries.firstOrNull() ?: return null
        entries.remove(head.key)
        return head.key to head.value
    }

    @Synchronized
    fun snapshot(): Set<String> = entries.keys.toSet()

    @Synchronized
    fun peekSessionId(): String? = entries.keys.firstOrNull()

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun drainAll(): List<String> {
        val keys = entries.keys.toList()
        entries.clear()
        return keys
    }
}
