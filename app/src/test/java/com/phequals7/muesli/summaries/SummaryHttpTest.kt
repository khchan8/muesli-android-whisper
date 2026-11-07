package com.phequals7.muesli.summaries

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Real-HTTP tests (MockWebServer) for the shared chat-completions transport:
 * request shape, credentials, error mapping, redirects, cancellation.
 */
class SummaryHttpTest {

    private fun ok(content: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":${org.json.JSONObject.quote(content)}}}]}""")

    @Test
    fun `callChatCompletions sends configured path model messages max_tokens and bearer key`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(ok("hello notes"))
        server.start()
        try {
            val out = SummaryHttp.callChatCompletions(
                endpoint = server.url("/v1/chat/completions").toString(),
                apiKey = "sk-secret",
                model = "model-x",
                systemPrompt = "SYS",
                userPrompt = "USER",
                maxTokens = 123,
            )
            assertEquals("hello notes", out)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", recorded.path)
            assertEquals("Bearer sk-secret", recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader("X-OpenRouter-Title"))
            val body = org.json.JSONObject(recorded.body.readUtf8())
            assertEquals("model-x", body.getString("model"))
            assertEquals(123, body.getInt("max_tokens"))
            assertEquals("SYS", body.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("role"))
            assertEquals("USER", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blank api key omits Authorization and openRouterTitle adds the header`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(ok("a"))
        server.enqueue(ok("b"))
        server.start()
        try {
            SummaryHttp.callChatCompletions(
                endpoint = server.url("/x").toString(),
                apiKey = "  ",
                model = "m",
                systemPrompt = "s",
                userPrompt = "u",
                maxTokens = 5,
            )
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(first.getHeader("Authorization"))

            SummaryHttp.callChatCompletions(
                endpoint = server.url("/y").toString(),
                apiKey = "k",
                model = "m",
                systemPrompt = "s",
                userPrompt = "u",
                maxTokens = 5,
                openRouterTitle = "Muesli",
            )
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Muesli", second.getHeader("X-OpenRouter-Title"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http errors map to transport exceptions with server message`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            val e1 = runCatching {
                SummaryHttp.callChatCompletions(server.url("/").toString(), "k", "m", "s", "u", 5)
            }.exceptionOrNull()
            assertTrue("Expected TransportException, got $e1", e1 is SummaryHttp.TransportException)
            assertTrue(e1!!.message!!.contains("HTTP 401"))
            assertTrue(e1.message!!.contains("bad key"))

            val e2 = runCatching {
                SummaryHttp.callChatCompletions(server.url("/").toString(), "k", "m", "s", "u", 5)
            }.exceptionOrNull()
            assertTrue(e2 is SummaryHttp.TransportException)
            assertTrue(e2!!.message!!.contains("HTTP 500"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty and malformed choices bodies do not crash the transport`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(ok(""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"unexpected": true}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        server.start()
        try {
            assertEquals("", SummaryHttp.callChatCompletions(server.url("/").toString(), "k", "m", "s", "u", 5))
            assertEquals("", SummaryHttp.callChatCompletions(server.url("/").toString(), "k", "m", "s", "u", 5))
            val malformed = runCatching {
                SummaryHttp.callChatCompletions(server.url("/").toString(), "k", "m", "s", "u", 5)
            }.exceptionOrNull()
            assertTrue("Expected malformed-response TransportException, got $malformed", malformed is SummaryHttp.TransportException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cross-origin redirects are rejected without forwarding credentials`() = runBlocking {
        val origin = MockWebServer()
        val other = MockWebServer()
        origin.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", other.url("/evil").toString())
        )
        origin.start()
        try {
            val e = runCatching {
                SummaryHttp.callChatCompletions(origin.url("/v1/chat/completions").toString(), "sk-secret", "m", "s", "u", 5)
            }.exceptionOrNull()
            assertTrue("Expected TransportException, got $e", e is SummaryHttp.TransportException)
            assertTrue(e!!.message!!.contains("refusing to forward credentials"))
            assertEquals("Credential-bearing request must never reach the other origin", 0, other.requestCount)
        } finally {
            origin.shutdown()
            other.shutdown()
        }
    }

    @Test
    fun `same-origin redirect is followed once and still authorized`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/v2/chat/completions").toString()))
        server.enqueue(ok("redirected result"))
        try {
            val out = SummaryHttp.callChatCompletions(server.url("/v1/chat/completions").toString(), "sk", "m", "s", "u", 5)
            assertEquals("redirected result", out)
            assertEquals(2, server.requestCount)
            server.takeRequest(2, TimeUnit.SECONDS)
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v2/chat/completions", second.path)
            assertEquals("Bearer sk", second.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancelling the caller cancels the in-flight HTTP call`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(ok("slow").setBodyDelay(2, TimeUnit.SECONDS))
        server.start()
        try {
            val deferred = async {
                SummaryHttp.callChatCompletions(server.url("/").toString(), "k", "m", "s", "u", 5)
            }
            val started = System.nanoTime()
            kotlinx.coroutines.delay(300)
            deferred.cancel()
            val cancelled = runCatching { deferred.await() }.exceptionOrNull()
            assertTrue("Expected CancellationException, got $cancelled", cancelled is CancellationException)
            assertTrue("Cancellation took too long to propagate", (System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(10))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `customEndpointError validates url scheme userinfo fragment and model`() {
        assertEquals(
            "Add the full chat-completions endpoint URL, e.g. https://host.example/v1/chat/completions",
            SummaryHttp.customEndpointError("", "m")
        )
        assertTrue(SummaryHttp.customEndpointError("not a url", "m")!!.contains("not valid"))
        assertTrue(SummaryHttp.customEndpointError("http://host.example/v1/chat/completions", "m")!!.contains("HTTPS"))
        assertTrue(SummaryHttp.customEndpointError("https://user:pw@host.example/v1/chat/completions", "m")!!.contains("user:password"))
        assertTrue(SummaryHttp.customEndpointError("https://host.example/v1/chat/completions#frag", "m")!!.contains("fragment"))
        assertTrue(SummaryHttp.customEndpointError("https://host.example/v1/chat/completions", " ")!!.contains("model id"))
        assertNull(SummaryHttp.customEndpointError("https://host.example/v1/chat/completions", "m"))
        assertFalse(SummaryHttp.customEndpointError("https://host.example/v1/chat/completions", "m") != null)
    }
}
