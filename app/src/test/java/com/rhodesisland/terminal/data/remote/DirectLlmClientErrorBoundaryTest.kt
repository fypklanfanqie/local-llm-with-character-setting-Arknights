package com.rhodesisland.terminal.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DirectLlmClientErrorBoundaryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DirectLlmClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = DirectLlmClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun openAiUrl() = server.url("/").toString().trimEnd('/')
    private fun anthropicUrl() = server.url("/v1/messages").toString().trimEnd('/')
    private fun messages() = listOf(ChatMessageDto("user", JsonPrimitive("hello")))

    @Test
    fun allProviderAndModeHttpFailuresAreStructuredAndRedacted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret body LogID=abc https://private.example"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("raw upstream body"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("anthropic raw body"))
        server.enqueue(MockResponse().setResponseCode(422).setBody("invalid raw body"))

        val failures = listOf(
            runCatching { client.chatOnce(openAiUrl(), "key", "model", messages()) }.exceptionOrNull(),
            runCatching { client.chatStream(openAiUrl(), "key", "model", messages(), {}) }.exceptionOrNull(),
            runCatching { client.chatStream(anthropicUrl(), "key", "model", messages(), {}) }.exceptionOrNull(),
            runCatching { client.chatOnce(anthropicUrl(), "key", "model", messages()) }.exceptionOrNull(),
        )

        failures.forEach { failure ->
            assertTrue(failure is DirectLlmException)
            assertFalse(failure!!.message.orEmpty().contains("raw"))
            assertFalse(failure.message.orEmpty().contains("LogID"))
            assertFalse(failure.message.orEmpty().contains("http"))
        }
        failures.forEach { failure ->
            assertEquals(DirectLlmFailure.HTTP, (failure as DirectLlmException).failure)
        }
    }

    @Test
    fun allProviderAndModeIoFailuresAreStructured() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }

        val failures = listOf(
            runCatching { client.chatOnce(openAiUrl(), "key", "model", messages()) }.exceptionOrNull(),
            runCatching { client.chatStream(openAiUrl(), "key", "model", messages(), {}) }.exceptionOrNull(),
            runCatching { client.chatStream(anthropicUrl(), "key", "model", messages(), {}) }.exceptionOrNull(),
            runCatching { client.chatOnce(anthropicUrl(), "key", "model", messages()) }.exceptionOrNull(),
        )

        failures.forEach { failure ->
            assertTrue(failure is DirectLlmException)
            assertEquals(DirectLlmFailure.NETWORK, (failure as DirectLlmException).failure)
            assertFalse(failure.message.orEmpty().contains("Connection"))
        }
    }
}
