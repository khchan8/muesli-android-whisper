package com.phequals7.muesli.summaries

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * In-process GGUF inference runtime backed by `libmuesli-qwen.so` (llama.cpp).
 *
 * One model is loaded for the duration of a [withModel] block (covering every
 * map, reduce, and title call of a meeting summary) and deterministically freed
 * in `finally`. UTF-8 travels across JNI as raw `ByteArray`s so split multibyte
 * tokens (Thai/Chinese) cannot be corrupted by Modified-UTF8 conversion.
 */
internal object LocalQwenRuntime {

    const val CONTEXT_TOKENS = 8192
    private const val MAX_THREADS = 6

    private val sessionMutex = Mutex()

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var libraryLoadError: Throwable? = null

    fun ensureLoaded() {
        if (libraryLoaded) return
        synchronized(this) {
            if (libraryLoaded) return
            try {
                System.loadLibrary("muesli-qwen")
                libraryLoaded = true
                libraryLoadError = null
            } catch (t: Throwable) {
                libraryLoadError = t
                throw IOException("On-device Qwen native runtime is unavailable: ${t.message}", t)
            }
        }
    }

    val isNativeAvailable: Boolean
        get() = try {
            ensureLoaded()
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Loads [modelFile], runs [block] with an exclusive [Session] on
     * [Dispatchers.IO], and closes the native handle before releasing the
     * mutex even when cancelled.
     */
    suspend fun <T> withModel(
        modelFile: File,
        contextTokens: Int = CONTEXT_TOKENS,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_THREADS),
        block: suspend (Session) -> T
    ): T = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded()
            if (!modelFile.exists() || !modelFile.isFile) {
                throw IOException("Qwen GGUF model file not found: ${modelFile.absolutePath}")
            }
            coroutineContext.ensureActive()
            val handle = nativeOpen(modelFile.absolutePath, contextTokens, threads.coerceIn(1, MAX_THREADS))
            if (handle == 0L) {
                throw IOException("Failed to open Qwen GGUF model: ${modelFile.name}")
            }
            val session = Session(handle)
            try {
                // If cancellation raced model load, bail out immediately and close in finally.
                coroutineContext.ensureActive()
                block(session)
            } finally {
                withContext(NonCancellable) {
                    session.close()
                }
            }
        }
    }

    internal class Session internal constructor(private var handle: Long) {

        private fun requireHandle(): Long {
            val h = handle
            check(h != 0L) { "LocalQwenRuntime.Session is already closed" }
            return h
        }

        fun tokenCount(prompt: String): Int =
            nativeTokenCount(requireHandle(), prompt.toByteArray(Charsets.UTF_8))

        fun generate(
            prompt: String,
            maxTokens: Int,
            shouldCancel: () -> Boolean,
            onTokens: (generatedTokens: Int) -> Unit = {},
        ): String {
            val bytes = nativeGenerate(
                requireHandle(),
                prompt.toByteArray(Charsets.UTF_8),
                maxTokens,
                shouldCancel,
                onTokens
            )
            return String(bytes, Charsets.UTF_8)
        }

        internal fun close() {
            val h = handle
            if (h != 0L) {
                handle = 0L
                nativeClose(h)
            }
        }
    }

    @JvmStatic
    private external fun nativeOpen(modelPath: String, contextTokens: Int, threads: Int): Long

    @JvmStatic
    private external fun nativeTokenCount(handle: Long, promptUtf8: ByteArray): Int

    @JvmStatic
    private external fun nativeGenerate(
        handle: Long,
        promptUtf8: ByteArray,
        maxTokens: Int,
        shouldCancel: () -> Boolean,
        onTokens: (generatedTokens: Int) -> Unit
    ): ByteArray

    @JvmStatic
    private external fun nativeClose(handle: Long)
}
