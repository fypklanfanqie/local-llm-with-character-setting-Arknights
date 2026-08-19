package com.rhodesisland.terminal.tts

import com.rhodesisland.terminal.data.model.TtsConfig
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

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = VolcTtsClient(server.url("/").toString().removeSuffix("/"), OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun simpleApiKeyRequestUsesFixedCloneResourceAndDefaultSpeaker() = runBlocking {
        server.enqueue(successResponse())
        val bytes = client.synthesize(
            text = "你好",
            characterId = "amiya",
            ttsConfig = TtsConfig(apiKey = "test-api-key", defaultVoiceId = "S_default"),
            speakerId = "S_default",
        )
        val request = server.takeRequest()
        assertEquals("test-api-key", request.getHeader("X-Api-Key"))
        assertEquals("seed-icl-2.0", request.getHeader("X-Api-Resource-Id"))
        assertNull(request.getHeader("X-Api-App-Id"))
        assertNull(request.getHeader("X-Api-Access-Key"))
        assertTrue(request.getHeader("X-Api-Request-Id")!!.isNotBlank())
        assertTrue(request.body.readUtf8().contains("\"speaker\":\"S_default\""))
        assertEquals("abc", bytes.decodeToString())
    }

    @Test
    fun missingApiKeyOrSpeakerDoesNotOpenNetworkCall() = runBlocking {
        val missingKey = runCatching {
            client.synthesize("你好", "amiya", TtsConfig(defaultVoiceId = "S_default"), "S_default")
        }.exceptionOrNull()
        val missingSpeaker = runCatching {
            client.synthesize("你好", "amiya", TtsConfig(apiKey = "key"), "")
        }.exceptionOrNull()
        assertTrue(missingKey?.message?.contains("API Key") == true)
        assertTrue(missingSpeaker?.message?.contains("speaker_id") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun parserRejectsServiceError() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"code\":55000000,\"message\":\"resource mismatch\",\"data\":null}\n"))
        val error = runCatching {
            client.synthesize("你好", "amiya", TtsConfig(apiKey = "key", defaultVoiceId = "S"), "S")
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("55000000") == true)
    }

    private fun successResponse() = MockResponse().setBody(
        "{\"code\":0,\"message\":\"\",\"data\":\"YWJj\"}\n" +
            "{\"code\":20000000,\"message\":\"ok\",\"data\":null}\n",
    )
}
