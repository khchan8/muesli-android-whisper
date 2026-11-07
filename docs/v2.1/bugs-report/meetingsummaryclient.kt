package com.phequals7.muesli.summaries

import android.content.Context
import android.util.Log
import com.phequals7.muesli.engine.SherpaRecognizerHolder
import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Selects which LLM backend generates the Meeting Minutes and Title.
 */
enum class SummaryBackendMode {
    /**
     * Runs `Qwen3-1.7B-Q4_K_M.gguf` or `Qwen3-4B-Q4_K_M.gguf` in-process via embedded `llama.cpp` JNI (`libllama.so`).
     */
    LOCAL_QWEN_JNI,

    /**
     * Connects to an on-device OpenAI-compatible loopback server (`http://127.0.0.1:8080/v1/chat/completions`
     * or `11434/v1`) powered by `llama-server`, MLC-LLM, or Termux Ollama — zero JNI changes required.
     */
    LOCAL_QWEN_LOOPBACK_HTTP,

    /**
     * Uses OpenRouter / OpenAI-compatible cloud endpoint (Muesli's original BYOK mode).
     */
    CLOUD_OPENAI_COMPATIBLE
}

/**
 * Output language preference for the generated Meeting Minutes.
 */
enum class SummaryOutputLanguage(val code: String, val displayName: String, val instruction: String) {
    AUTO(
        code = "auto",
        displayName = "Auto (Match Dominant Meeting Language)",
        instruction = "Write the meeting minutes in the primary language used in the transcript (English, Thai, or Chinese), preserving technical terms and speaker names verbatim."
    ),
    ENGLISH(
        code = "en",
        displayName = "English",
        instruction = "Write the entire meeting minutes in clear, professional English (translating Thai or Chinese utterances accurately while keeping speaker names intact)."
    ),
    THAI(
        code = "th",
        displayName = "ภาษาไทย (Thai)",
        instruction = "เขียนสรุปรายงานการประชุมทั้งหมดเป็นภาษาไทยที่เป็นทางการและเข้าใจง่าย โดยคงชื่อผู้พูดและศัพท์เทคนิคภาษาอังกฤษที่สำคัญไว้"
    ),
    CHINESE_SIMPLIFIED(
        code = "zh-CN",
        displayName = "简体中文 (Simplified Chinese)",
        instruction = "请使用专业、清晰的简体中文撰写整份会议纪要，保留说话人姓名及关键英文技术术语。"
    ),
    CHINESE_TRADITIONAL(
        code = "zh-TW",
        displayName = "繁體中文 (Traditional Chinese / Cantonese)",
        instruction = "請使用專業、清晰的繁體中文撰寫整份會議紀要，保留說話人姓名及關鍵英文技術術語。"
    )
}

/**
 * Supported Muesli meeting templates with tailored structural instructions.
 */
enum class MeetingTemplateType(val templateId: String, val structurePrompt: String) {
    GENERAL(
        templateId = "General",
        structurePrompt = """
            Use these exact Markdown sections:
            ## Executive Summary
            ## Key Discussion Points (by Speaker)
            ## Decisions Made
            ## Action Items & Owners
        """.trimIndent()
    ),
    ONE_ON_ONE(
        templateId = "1:1",
        structurePrompt = """
            Use these exact Markdown sections:
            ## 1:1 Check-In Highlights
            ## Updates & Progress by Participant
            ## Blockers & Support Needed
            ## Agreed Next Steps & Follow-Ups
        """.trimIndent()
    ),
    STANDUP(
        templateId = "Standup",
        structurePrompt = """
            Use these exact Markdown sections:
            ## Completed Since Last Standup (by Speaker)
            ## Planned Today (by Speaker)
            ## Blockers & Risks
        """.trimIndent()
    ),
    INTERVIEW(
        templateId = "Interview",
        structurePrompt = """
            Use these exact Markdown sections:
            ## Candidate Overview
            ## Key Questions & Candidate Responses
            ## Demonstrated Strengths & Evidence
            ## Open Questions & Hiring Follow-Ups
        """.trimIndent()
    ),
    LECTURE(
        templateId = "Lecture",
        structurePrompt = """
            Use these exact Markdown sections:
            ## Core Topic & Thesis
            ## Detailed Concept Breakdown
            ## Examples & Q&A Highlights
            ## Key Takeaways
        """.trimIndent()
    ),
    CUSTOMER_CALL(
        templateId = "Customer Call",
        structurePrompt = """
            Use these exact Markdown sections:
            ## Customer Context & Pain Points
            ## Feature Requests & Technical Requirements
            ## Objections & Pricing/Timeline Notes
            ## Follow-Up Action Items
        """.trimIndent()
    ),
    PLANNING(
        templateId = "Planning",
        structurePrompt = """
            Use these exact Markdown sections:
            ## Project Scope & Objectives
            ## Architecture / Milestone Decisions
            ## Risks & Dependencies
            ## Assigned Tasks & Deadlines
        """.trimIndent()
    );

    companion object {
        fun fromId(id: String?): MeetingTemplateType {
            return entries.firstOrNull { it.templateId.equals(id, ignoreCase = true) } ?: GENERAL
        }
    }
}

/**
 * Configuration for `MeetingSummaryClient`.
 */
data class SummaryClientConfig(
    val backendMode: SummaryBackendMode = SummaryBackendMode.LOCAL_QWEN_LOOPBACK_HTTP,
    val localGgufFileName: String = "Qwen3-1.7B-Q4_K_M.gguf", // Placed in files/models/
    val localLoopbackEndpoint: String = "http://127.0.0.1:8080/v1/chat/completions",
    val localModelIdentifier: String = "qwen3-1.7b",
    val cloudEndpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val cloudApiKey: String = "",
    val cloudModelIdentifier: String = "qwen/qwen3-14b:free",
    val outputLanguage: SummaryOutputLanguage = SummaryOutputLanguage.AUTO,
    val enableQwenNoThinkDirective: Boolean = true,
    val releaseAsrMemoryBeforeLocalSummary: Boolean = true,
    val cpuThreads: Int = 6, // Snapdragon 8 Gen 2: 1x Cortex-X3 + 4x A715/A710 performance cores
    val maxCharsPerChunk: Int = 10_000
)

/**
 * Optional JNI bridge interface for in-process `llama.cpp` (`libllama.so`) execution on Android.
 * If `libllama-jni.so` is bundled in `app/src/main/jniLibs/arm64-v8a/`, it executes GGUF inference directly;
 * otherwise `MeetingSummaryClient` automatically falls back to `LOCAL_QWEN_LOOPBACK_HTTP`.
 */
object LocalQwenLlamaJniBridge {
    private const val TAG = "LocalQwenLlamaJniBridge"
    private var nativeLibraryLoaded = false
    private var modelContextPtr: Long = 0L
    private var loadedModelPath: String? = null
    private val jniMutex = Mutex()

    init {
        nativeLibraryLoaded = try {
            System.loadLibrary("llama-jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    val isAvailable: Boolean
        get() = nativeLibraryLoaded

    suspend fun generateCompletion(
        ggufFile: File,
        systemPrompt: String,
        userPrompt: String,
        numThreads: Int = 6,
        maxNewTokens: Int = 1024,
        temperature: Float = 0.2f,
        onTokenStream: ((String) -> Unit)? = null
    ): String = jniMutex.withLock {
        if (!nativeLibraryLoaded) {
            throw IllegalStateException("libllama-jni.so is not bundled; use LOCAL_QWEN_LOOPBACK_HTTP or bundle llama-jni.")
        }
        if (!ggufFile.exists()) {
            throw IOException("Local Qwen3 GGUF model not found at ${ggufFile.absolutePath}")
        }

        if (modelContextPtr == 0L || loadedModelPath != ggufFile.absolutePath) {
            unloadInternal()
            modelContextPtr = nativeInitModel(
                modelPath = ggufFile.absolutePath,
                nCtx = 8192,
                nThreads = numThreads,
                nGpuLayers = 0 // Set >0 if compiled with Adreno Vulkan backend
            )
            if (modelContextPtr == 0L) {
                throw IOException("Failed to initialize Qwen3 GGUF context from ${ggufFile.absolutePath}")
            }
            loadedModelPath = ggufFile.absolutePath
        }

        // Format ChatML prompt with Qwen3 /no_think directive so <think> blocks are skipped
        val formattedChatMl = buildString {
            append("<|im_start|>system\n")
            append(systemPrompt.trim())
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userPrompt.trim())
            append("\n/no_think<|im_end|>\n")
            append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        }

        val fullBuilder = StringBuilder()
        nativeCompletionStream(
            contextPtr = modelContextPtr,
            prompt = formattedChatMl,
            maxTokens = maxNewTokens,
            temperature = temperature
        ) { tokenPiece ->
            fullBuilder.append(tokenPiece)
            onTokenStream?.invoke( stripQwenThinkBlocks(fullBuilder.toString()) )
        }

        stripQwenThinkBlocks(fullBuilder.toString())
    }

    suspend fun release() = jniMutex.withLock {
        unloadInternal()
    }

    private fun unloadInternal() {
        if (modelContextPtr != 0L && nativeLibraryLoaded) {
            runCatching { nativeFreeModel(modelContextPtr) }
        }
        modelContextPtr = 0L
        loadedModelPath = null
    }

    // Native JNI signatures when `libllama-jni.so` is compiled from llama.cpp/examples/llama.android
    @JvmStatic
    private external fun nativeInitModel(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

    @JvmStatic
    private external fun nativeCompletionStream(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: (String) -> Unit
    )

    @JvmStatic
    private external fun nativeFreeModel(contextPtr: Long)
}

/**
 * Strips any residual `<think>...</think>` reasoning tags emitted by Qwen3 models so only clean
 * Markdown meeting minutes are displayed and persisted.
 */
fun stripQwenThinkBlocks(raw: String): String {
    val completeThinkRegex = Regex("<think>[\\s\\S]*?</think>\\s*", RegexOption.IGNORE_CASE)
    var cleaned = raw.replace(completeThinkRegex, "")
    // If streaming is currently inside an unclosed <think> tag, hide the partial thought block
    val openThinkIdx = cleaned.indexOf("<think>", ignoreCase = true)
    if (openThinkIdx >= 0) {
        cleaned = cleaned.substring(0, openThinkIdx)
    }
    return cleaned.trim()
}

/**
 * Upgraded `MeetingSummaryClient` for Muesli v2.0.
 *
 * Features:
 * 1. Generates structured Meeting Minutes and concise Meeting Titles on-device using `Qwen3-1.7B` or `Qwen3-4B`.
 * 2. Automatically releases `SherpaRecognizerHolder` ASR weights before local LLM execution so RAM usage on the
 *    Xiaomi Mix Fold 3 (12GB RAM) stays well under 3.5 GB.
 * 3. Uses Qwen3's `/no_think` directive to skip chain-of-thought overhead (~3x faster meeting summary generation).
 * 4. Preserves enrolled Speaker Profile labels (`Khun Somchai`, `Alice`, `Speaker 1`) and timestamps (`[MM:SS]`).
 * 5. Handles 60+ minute meetings via automatic Map-Reduce window chunking when transcripts exceed `maxCharsPerChunk`.
 */
class MeetingSummaryClient(
    private val context: Context,
    @Volatile var config: SummaryClientConfig = SummaryClientConfig()
) {
    companion object {
        private const val TAG = "MeetingSummaryClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 180_000
    }

    data class GeneratedMeetingMinutes(
        val suggestedTitle: String,
        val markdownSummary: String,
        val backendUsed: SummaryBackendMode
    )

    /**
     * Generates structured Markdown meeting minutes and a concise meeting title from speaker-diarized
     * transcript blocks (`List<DiarizedTranscriptSegment>`).
     *
     * @param onStreamingMarkdown Invoked progressively on the caller's coroutine context as tokens arrive.
     */
    suspend fun generateMeetingMinutes(
        segments: List<DiarizedTranscriptSegment>,
        templateId: String = "General",
        manualNotes: String = "",
        currentTitle: String = "Meeting",
        onStreamingMarkdown: ((String) -> Unit)? = null
    ): GeneratedMeetingMinutes = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "Transcript segments are empty; record speech before summarizing." }

        val formattedTranscript = formatSegmentsForPrompt(segments)
        val template = MeetingTemplateType.fromId(templateId)

        // Free ASR ONNX memory (~2.0 GB) before loading local Qwen3 text LLM on-device
        val isLocalMode = config.backendMode != SummaryBackendMode.CLOUD_OPENAI_COMPATIBLE
        if (isLocalMode && config.releaseAsrMemoryBeforeLocalSummary) {
            SherpaRecognizerHolder.release()
        }

        try {
            val systemPrompt = buildSystemPrompt(template, config.outputLanguage)
            val finalMarkdown = if (formattedTranscript.length <= config.maxCharsPerChunk) {
                val userPrompt = buildSinglePassUserPrompt(
                    formattedTranscript = formattedTranscript,
                    manualNotes = manualNotes,
                    enableNoThink = config.enableQwenNoThinkDirective
                )
                executeInference(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = 1200,
                    onPartialText = onStreamingMarkdown
                )
            } else {
                // Map-Reduce summarization for long (30–90 minute) meetings
                summarizeLongMeetingMapReduce(
                    segments = segments,
                    template = template,
                    systemPrompt = systemPrompt,
                    manualNotes = manualNotes,
                    onPartialText = onStreamingMarkdown
                )
            }

            // Generate a concise 4–8 word meeting title if the title is still generic
            val shouldGenerateTitle = currentTitle.isBlank() ||
                currentTitle.equals("Meeting", ignoreCase = true) ||
                currentTitle.equals("New Meeting", ignoreCase = true)

            val resolvedTitle = if (shouldGenerateTitle) {
                runCatching {
                    generateConciseTitle(finalMarkdown)
                }.getOrDefault(currentTitle.ifBlank { "Meeting" })
            } else {
                currentTitle
            }

            GeneratedMeetingMinutes(
                suggestedTitle = resolvedTitle,
                markdownSummary = finalMarkdown,
                backendUsed = config.backendMode
            )
        } finally {
            // Unload local GGUF weights after summarization completes so RAM is returned to the OS
            if (config.backendMode == SummaryBackendMode.LOCAL_QWEN_JNI) {
                LocalQwenLlamaJniBridge.release()
            }
        }
    }

    /**
     * Map-Reduce pipeline for long meetings: summarizes chronological chunks first, then synthesizes
     * the unified meeting minutes.
     */
    private suspend fun summarizeLongMeetingMapReduce(
        segments: List<DiarizedTranscriptSegment>,
        template: MeetingTemplateType,
        systemPrompt: String,
        manualNotes: String,
        onPartialText: ((String) -> Unit)?
    ): String {
        val chunks = partitionSegmentsByCharLimit(segments, config.maxCharsPerChunk)
        val chunkSummaries = mutableListOf<String>()

        for ((index, chunkText) in chunks.withIndex()) {
            currentCoroutineContext().ensureActive()
            onPartialText?.invoke("_Summarizing part ${index + 1} of ${chunks.size} on-device..._")

            val mapUserPrompt = buildString {
                append("Extract all key points, speaker statements, numbers, decisions, and action items ")
                append("from Part ${index + 1}/${chunks.size} of this meeting transcript. ")
                append("Keep speaker names and timestamps accurate.\n\n")
                append("TRANSCRIPT PART ${index + 1}:\n")
                append(chunkText)
                if (config.enableQwenNoThinkDirective) append("\n/no_think")
            }

            val partSummary = executeInference(
                systemPrompt = "You are a precise multilingual meeting analyst (English, Thai, Chinese). /no_think",
                userPrompt = mapUserPrompt,
                maxTokens = 600,
                onPartialText = null
            )
            chunkSummaries.add("### Part ${index + 1} Notes\n$partSummary")
        }

        val reduceUserPrompt = buildString {
            append("Synthesize the following chronological meeting part summaries into a single cohesive ")
            append("Meeting Minutes document.\n\n")
            if (manualNotes.isNotBlank()) {
                append("USER MANUAL NOTES:\n$manualNotes\n\n")
            }
            append("CHRONOLOGICAL PART SUMMARIES:\n")
            append(chunkSummaries.joinToString("\n\n"))
            if (config.enableQwenNoThinkDirective) append("\n/no_think")
        }

        return executeInference(
            systemPrompt = systemPrompt,
            userPrompt = reduceUserPrompt,
            maxTokens = 1400,
            onPartialText = onPartialText
        )
    }

    private suspend fun generateConciseTitle(summaryMarkdown: String): String {
        val titlePrompt = buildString {
            append("Based on the meeting summary below, output ONLY a concise 4-to-7 word meeting title ")
            append("in the same language as the summary (no quotes, no prefix, no punctuation at the end):\n\n")
            append(summaryMarkdown.take(2500))
            if (config.enableQwenNoThinkDirective) append("\n/no_think")
        }
        val rawTitle = executeInference(
            systemPrompt = "You generate concise 4-to-7 word meeting titles. /no_think",
            userPrompt = titlePrompt,
            maxTokens = 32,
            onPartialText = null
        )
        return rawTitle
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("^[\"'#*\\-\\s]+|[\"'#*\\s]+$"), "")
            ?.take(64)
            ?.ifBlank { "Meeting" }
            ?: "Meeting"
    }

    private suspend fun executeInference(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        onPartialText: ((String) -> Unit)?
    ): String {
        return when (config.backendMode) {
            SummaryBackendMode.LOCAL_QWEN_JNI -> {
                val ggufFile = File(File(context.filesDir, "models"), config.localGgufFileName)
                if (LocalQwenLlamaJniBridge.isAvailable && ggufFile.exists()) {
                    LocalQwenLlamaJniBridge.generateCompletion(
                        ggufFile = ggufFile,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        numThreads = config.cpuThreads,
                        maxNewTokens = maxTokens,
                        temperature = 0.2f,
                        onTokenStream = onPartialText
                    )
                } else {
                    Log.i(TAG, "JNI bridge or GGUF not found in files/models/, falling back to local loopback HTTP")
                    executeOpenAiCompatibleSseRequest(
                        endpointUrl = config.localLoopbackEndpoint,
                        apiKey = "",
                        modelId = config.localModelIdentifier,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = maxTokens,
                        onPartialText = onPartialText
                    )
                }
            }

            SummaryBackendMode.LOCAL_QWEN_LOOPBACK_HTTP -> {
                executeOpenAiCompatibleSseRequest(
                    endpointUrl = config.localLoopbackEndpoint,
                    apiKey = "",
                    modelId = config.localModelIdentifier,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    onPartialText = onPartialText
                )
            }

            SummaryBackendMode.CLOUD_OPENAI_COMPATIBLE -> {
                executeOpenAiCompatibleSseRequest(
                    endpointUrl = config.cloudEndpoint,
                    apiKey = config.cloudApiKey,
                    modelId = config.cloudModelIdentifier,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    onPartialText = onPartialText
                )
            }
        }
    }

    /**
     * Streams tokens over Server-Sent Events (`stream: true`) from either a local on-device
     * `llama-server` / `MLC-LLM` / `Ollama` endpoint (`http://127.0.0.1:8080/v1/chat/completions`)
     * or a cloud OpenRouter/OpenAI endpoint.
     */
    private suspend fun executeOpenAiCompatibleSseRequest(
        endpointUrl: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        onPartialText: ((String) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                }
            )
        }

        val payload = JSONObject().apply {
            put("model", modelId)
            put("messages", messagesArray)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            put("stream", true)
            // Disable Qwen3 thinking blocks on servers that support chat_template_kwargs (llama.cpp / vLLM)
            put(
                "chat_template_kwargs",
                JSONObject().apply {
                    put("enable_thinking", false)
                }
            )
        }

        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val errBody = runCatching {
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                throw IOException("Summary endpoint returned HTTP $status: $errBody")
            }

            val accumulated = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    currentCoroutineContext().ensureActive()
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val dataJson = trimmed.removePrefix("data:").trim()
                        if (dataJson == "[DONE]") break
                        runCatching {
                            val obj = JSONObject(dataJson)
                            val choices = obj.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val first = choices.getJSONObject(0)
                                val delta = first.optJSONObject("delta")
                                val token = delta?.optString("content", "")
                                    ?: first.optJSONObject("message")?.optString("content", "")
                                    ?: ""
                                if (token.isNotEmpty()) {
                                    accumulated.append(token)
                                    val visible = stripQwenThinkBlocks(accumulated.toString())
                                    if (visible.isNotEmpty()) {
                                        onPartialText?.invoke(visible)
                                    }
                                }
                            }
                        }
                    } else if (trimmed.startsWith("{") && accumulated.isEmpty()) {
                        // Non-streaming JSON response fallback
                        runCatching {
                            val obj = JSONObject(trimmed)
                            val content = obj.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("message")
                                ?.optString("content", "")
                                .orEmpty()
                            if (content.isNotEmpty()) {
                                accumulated.append(content)
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }

            val finalCleaned = stripQwenThinkBlocks(accumulated.toString())
            onPartialText?.invoke(finalCleaned)
            finalCleaned
        } finally {
            connection.disconnect()
        }
    }

    private fun buildSystemPrompt(
        template: MeetingTemplateType,
        outputLanguage: SummaryOutputLanguage
    ): String {
        return buildString {
            append("You are an expert multilingual executive meeting assistant fluent in English, Thai (ภาษาไทย), ")
            append("Mandarin Chinese (中文), and Cantonese (粵語).\n")
            append("Your job is to produce accurate, well-structured Meeting Minutes strictly grounded in the ")
            append("provided speaker-diarized transcript and user notes.\n\n")
            append("RULES:\n")
            append("1. ${outputLanguage.instruction}\n")
            append("2. Attribute key points, decisions, and action items to the exact speaker names shown in the transcript (e.g., Khun Somchai, Alice, Speaker 1).\n")
            append("3. Never invent facts, numbers, or commitments not stated in the transcript.\n")
            append("4. ${template.structurePrompt}\n")
            if (config.enableQwenNoThinkDirective) {
                append("/no_think")
            }
        }
    }

    private fun buildSinglePassUserPrompt(
        formattedTranscript: String,
        manualNotes: String,
        enableNoThink: Boolean
    ): String {
        return buildString {
            if (manualNotes.isNotBlank()) {
                append("USER MANUAL NOTES:\n")
                append(manualNotes.trim())
                append("\n\n")
            }
            append("SPEAKER-DIARIZED MEETING TRANSCRIPT:\n")
            append(formattedTranscript)
            if (enableNoThink) {
                append("\n/no_think")
            }
        }
    }

    private fun formatSegmentsForPrompt(segments: List<DiarizedTranscriptSegment>): String {
        return segments.joinToString(separator = "\n") { seg ->
            val totalSec = (seg.startMs.coerceAtLeast(0L)) / 1000L
            val mm = totalSec / 60L
            val ss = totalSec % 60L
            val timeTag = String.format(Locale.US, "%02d:%02d", mm, ss)
            "[$timeTag] ${seg.speakerLabel}: ${seg.text}"
        }
    }

    private fun partitionSegmentsByCharLimit(
        segments: List<DiarizedTranscriptSegment>,
        maxCharsPerChunk: Int
    ): List<String> {
        val chunks = mutableListOf<String>()
        val currentBuilder = StringBuilder()

        for (seg in segments) {
            val totalSec = (seg.startMs.coerceAtLeast(0L)) / 1000L
            val line = String.format(
                Locale.US,
                "[%02d:%02d] %s: %s\n",
                totalSec / 60L,
                totalSec % 60L,
                seg.speakerLabel,
                seg.text
            )
            if (currentBuilder.length + line.length > maxCharsPerChunk && currentBuilder.isNotEmpty()) {
                chunks.add(currentBuilder.toString().trim())
                currentBuilder.clear()
            }
            currentBuilder.append(line)
        }
        if (currentBuilder.isNotEmpty()) {
            chunks.add(currentBuilder.toString().trim())
        }
        return chunks
    }
}