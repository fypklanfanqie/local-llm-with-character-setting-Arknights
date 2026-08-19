package com.rhodesisland.terminal.tts

import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.VoiceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VolcTtsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: VolcTtsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = VolcTtsClient(server.url("/").toString().removeSuffix("/"), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun apiKeyRequestUsesOfficialHeadersAndVoiceResource() = runBlocking {
        server.enqueue(successResponse())

        val bytes = client.synthesize(
            text = "你好",
            characterId = "amiya",
            ttsConfig = TtsConfig(apiKey = "test-api-key"),
            voice = VoiceConfig("S_cn", "seed-icl-2.0"),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        assertEquals("test-api-key", request.getHeader("X-Api-Key"))
        assertEquals("seed-icl-2.0", request.getHeader("X-Api-Resource-Id"))
        assertNull(request.getHeader("X-Api-App-Id"))
        assertNull(request.getHeader("X-Api-Access-Key"))
        assertTrue(request.getHeader("X-Api-Request-Id")!!.isNotBlank())
        assertTrue(request.body.readUtf8().contains("\"speaker\":\"S_cn\""))
        assertEquals("abc", bytes.decodeToString())
    }

    @Test
    fun legacyRequestUsesOfficialLegacyHeaders() = runBlocking {
        server.enqueue(successResponse())

        client.synthesize(
            text = "你好",
            characterId = "amiya",
            ttsConfig = TtsConfig(appId = "legacy-app", accessKey = "legacy-access"),
            voice = VoiceConfig("S_cn", "seed-icl-2.0"),
        )

        val request = server.takeRequest()
        assertEquals("legacy-app", request.getHeader("X-Api-App-Id"))
        assertEquals("legacy-access", request.getHeader("X-Api-Access-Key"))
        assertEquals("seed-icl-2.0", request.getHeader("X-Api-Resource-Id"))
        assertNull(request.getHeader("X-Api-Key"))
    }

    @Test
    fun missingCredentialsDoesNotOpenNetworkCall() = runBlocking {
        try {
            client.synthesize(
                text = "你好",
                characterId = "amiya",
                ttsConfig = TtsConfig(),
                voice = VoiceConfig("S_cn", "seed-icl-2.0"),
            )
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun incompleteVoiceBindingDoesNotOpenNetworkCall() = runBlocking {
        try {
            client.synthesize(
                text = "你好",
                characterId = "amiya",
                ttsConfig = TtsConfig(apiKey = "test-api-key"),
                voice = VoiceConfig("S_cn"),
            )
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun parserRejectsServiceError() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "{\"code\":55000000,\"message\":\"resource ID is mismatched with speaker related resource\",\"data\":null}\n",
            ),
        )

        try {
            client.synthesize(
                text = "你好",
                characterId = "amiya",
                ttsConfig = TtsConfig(apiKey = "test-api-key"),
                voice = VoiceConfig("S_cn", "seed-icl-2.0"),
            )
        } catch (error: Exception) {
            assertTrue(error.message!!.contains("55000000"))
            return@runBlocking
        }

        throw AssertionError("Expected server error")
    }

    private fun successResponse() = MockResponse().setBody(
        "{\"code\":0,\"message\":\"\",\"data\":\"YWJj\"}\n" +
            "{\"code\":20000000,\"message\":\"ok\",\"data\":null}\n",
    )
}
