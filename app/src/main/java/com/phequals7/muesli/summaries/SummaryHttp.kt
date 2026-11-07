package com.phequals7.muesli.summaries

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared cancellation-aware HTTP transport for every summary backend.
 * Calls are enqueued (never blocking a thread) and cancelled with
 * [Call.cancel] when the calling coroutine is cancelled; redirects are
 * followed manually only within the same origin so an Authorization header
 * is never forwarded to a different endpoint.
 */
internal object SummaryHttp {

    class TransportException(message: String) : Exception(message)

    private val CLIENT: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // summaries can stream for a while
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private data class HttpResult(val code: Int, val location: String?, val body: String)

    private suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        HttpResult(it.code, it.header("Location"), it.body?.string().orEmpty())
                    }
                    if (cont.isActive) cont.resume(result)
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private suspend fun execute(request: Request, label: String): HttpResult {
        val response = CLIENT.newCall(request).await()
        if (response.code !in 300..399) return response
        val target = response.location?.let { request.url.resolve(it) }
            ?: throw TransportException("$label returned an invalid redirect.")
        val origin = request.url
        if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port) {
            throw TransportException("$label redirected to a different endpoint (${target.host}); refusing to forward credentials.")
        }
        return CLIENT.newCall(request.newBuilder().url(target).build()).await()
    }

    /** Validates a custom OpenAI-compatible endpoint; null = usable. */
    fun customEndpointError(rawUrl: String, model: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            return "Add the full chat-completions endpoint URL, e.g. https://host.example/v1/chat/completions"
        }
        val parsed = url.toHttpUrlOrNull()
            ?: return "That endpoint URL is not valid. Use the full https://…/v1/chat/completions URL."
        if (!parsed.isHttps) return "The endpoint must use HTTPS. Plain HTTP URLs are not supported."
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return "Remove the user:password part from the endpoint URL."
        }
        if (!parsed.fragment.isNullOrEmpty()) return "Remove the #fragment from the endpoint URL."
        if (model.isBlank()) return "Enter the model id that this endpoint should use."
        return null
    }

    /**
     * OpenAI-compatible `POST /chat/completions` shared by the OpenRouter and
     * custom backends. [openRouterTitle] adds the `X-OpenRouter-Title` header
     * for OpenRouter only. A blank [apiKey] omits Authorization entirely.
     */
    suspend fun callChatCompletions(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        openRouterTitle: String? = null,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
            )
            .put("max_tokens", maxTokens)

        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        openRouterTitle?.let { builder.header("X-OpenRouter-Title", it) }

        val response = execute(builder.build(), "Summary endpoint")
        if (response.code !in 200..299) {
            throw TransportException("Summary request failed (HTTP ${response.code}): ${extractErrorMessage(response.body).take(300)}")
        }
        val choices = try {
            JSONObject(response.body).optJSONArray("choices")
        } catch (e: org.json.JSONException) {
            throw TransportException("The endpoint returned a malformed response.")
        }
        return choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    }

    /** ChatGPT WHAM responses endpoint (SSE); returns the accumulated output text. */
    suspend fun callWham(
        url: String,
        token: String,
        accountId: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("stream", true)
            .put("instructions", systemPrompt)
            .put(
                "input", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content", JSONArray().put(
                                JSONObject().put("type", "input_text").put("text", userPrompt)
                            )
                        )
                )
            )

        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
        if (accountId.isNotEmpty()) builder.header("ChatGPT-Account-Id", accountId)

        val response = execute(builder.build(), "ChatGPT")
        if (response.code !in 200..299) {
            throw TransportException("ChatGPT failed (HTTP ${response.code}): ${extractErrorMessage(response.body).take(300)}")
        }
        val fullText = StringBuilder()
        for (line in response.body.lineSequence()) {
            if (!line.startsWith("data: ")) continue
            val payload = line.removePrefix("data: ").trim()
            if (payload == "[DONE]") break
            try {
                val json = JSONObject(payload)
                val outputText = json.optString("output_text")
                if (outputText.isNotEmpty()) {
                    fullText.setLength(0)
                    fullText.append(outputText)
                }
                if (json.optString("type") == "response.output_text.delta") {
                    fullText.append(json.optString("delta"))
                }
            } catch (_: org.json.JSONException) {
                // Ignore malformed SSE frames.
            }
        }
        return fullText.toString().trim()
    }

    fun extractErrorMessage(body: String): String = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")
            ?: json.optString("message").ifEmpty { json.optString("detail") }
                .ifEmpty { body.take(300) }
    } catch (e: Exception) {
        body.take(300)
    }
}
