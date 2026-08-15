package com.rhodesisland.terminal.data.remote

import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [SeedanceClient] 契约测试（Task 5，MockWebServer）。
 *
 * 覆盖：POST/GET/DELETE 路径与 Bearer 鉴权、content 顺序与 role=reference_image、
 * generate_audio=true、模型 ID、参数编码（分辨率/画幅/时长/水印）、无 fps/seed/camera、
 * 响应可空字段与未知字段容错、全部官方状态映射、错误分类（敏感/配额/鉴权/429/500/参数，
 * 含官方审核错误码 InputText/Image/OutputVideoSensitiveContentDetected）、
 * 非 JSON 错误体、request-id 捕获、取消传播、5xx httpStatus 透传、离线连接失败
 * 判 AMBIGUOUS_TRANSPORT，以及 API Key / base64 不泄漏。
 */
class SeedanceClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SeedanceClient

    private val testJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val character = SeedanceImageContent("image/png", "aGVsbG8=")      // data:image/png;base64,aGVsbG8=
    private val background = SeedanceImageContent("image/jpeg", "d29ybGQ=")    // data:image/jpeg;base64,d29ybGQ=

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SeedanceClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(apiKey: String = TEST_API_KEY): SeedanceConfig = SeedanceConfig(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = apiKey,
    )

    private fun request(
        variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
        resolution: SeedanceResolution = SeedanceResolution.P720,
        ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
        durationSeconds: Int = 5,
        watermark: Boolean = false,
        background: SeedanceImageContent? = null,
    ): CreateSeedanceTask = CreateSeedanceTask(
        finalPrompt = "一位少女在夕阳下回眸",
        character = character,
        background = background,
        variant = variant,
        resolution = resolution,
        ratio = ratio,
        durationSeconds = durationSeconds,
        watermark = watermark,
    )

    /** 从最近一次请求体解析 JSON 对象。 */
    private fun lastRequestBody() = testJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    /** 执行一次 createTask，断言抛出 [SeedanceApiException] 并返回之。 */
    private suspend fun expectApiException(): SeedanceApiException {
        val ex = runCatching { client.createTask(config(), request()) }.exceptionOrNull()
        assertNotNull("期望抛出 SeedanceApiException", ex)
        assertTrue("期望 SeedanceApiException，实际 ${ex!!.javaClass.simpleName}", ex is SeedanceApiException)
        return ex as SeedanceApiException
    }

    // ---- 创建任务：路径 / 鉴权 / 请求体 ----

    @Test
    fun createTask_postsToContentsGenerationsTasks_withBearerHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        val resp = client.createTask(config(), request())
        assertEquals("cgt-abc", resp.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/contents/generations/tasks", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    @Test
    fun createTask_encodesModelGenerateAudioAndParameters() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(
            config(),
            request(
                variant = SeedanceModelVariant.FAST,
                resolution = SeedanceResolution.P1080,
                ratio = SeedanceRatio.LANDSCAPE,
                durationSeconds = 12,
                watermark = true,
            ),
        )

        val body = lastRequestBody()
        assertEquals("doubao-seedance-2-0-fast-260128", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["generate_audio"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("1080p", body["resolution"]!!.jsonPrimitive.content)
        assertEquals("16:9", body["ratio"]!!.jsonPrimitive.content)
        assertEquals(12, body["duration"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["watermark"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun createTask_resolutionApiValueUses4kForP4K() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(resolution = SeedanceResolution.P4K))
        val body = lastRequestBody()
        assertEquals("4k", body["resolution"]!!.jsonPrimitive.content)
    }

    @Test
    fun createTask_contentOrderIsTextThenCharacterReferenceImage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request())

        val content = lastRequestBody()["content"]!!.jsonArray
        assertEquals(2, content.size)

        val textItem = content[0].jsonObject
        assertEquals("text", textItem["type"]!!.jsonPrimitive.content)
        assertEquals("一位少女在夕阳下回眸", textItem["text"]!!.jsonPrimitive.content)
        assertNull("text 项不应携带 role", textItem["role"])

        val imageItem = content[1].jsonObject
        assertEquals("image_url", imageItem["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", imageItem["role"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,aGVsbG8=",
            imageItem["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun createTask_backgroundAppendedAsSecondReferenceImage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))

        val content = lastRequestBody()["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", content[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", content[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,d29ybGQ=",
            content[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun createTask_neverSerializesFpsSeedOrCamera() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))

        val body = lastRequestBody()
        val keys = body.keys
        assertFalse("请求体不得含 fps", keys.contains("fps"))
        assertFalse("请求体不得含 seed", keys.contains("seed"))
        assertFalse("请求体不得含 camera", keys.contains("camera"))

        val contentKeys = body["content"]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet()
        assertFalse("content 项不得含 fps", contentKeys.contains("fps"))
        assertFalse("content 项不得含 seed", contentKeys.contains("seed"))
        assertFalse("content 项不得含 camera", contentKeys.contains("camera"))
    }

    // ---- 查询任务：GET 路径 / 鉴权 / 响应解析 ----

    @Test
    fun getTask_getsById_withBearerHeader_andParsesFullResponse() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("X-Request-Id", "req-123").setBody(
                """
                {
                  "id": "cgt-abc",
                  "status": "succeeded",
                  "output": {"video_url": "https://cdn.example/v.mp4?sign=SECRET", "last_frame_url": "https://cdn.example/f.jpg"},
                  "usage": {"total_tokens": 42},
                  "created_at": 1700000000,
                  "updated_at": 1700000100
                }
                """.trimIndent()
            )
        )
        val resp = client.getTask(config(), "cgt-abc")

        assertEquals("cgt-abc", resp.id)
        assertEquals(SeedanceRemoteStatus.SUCCEEDED, resp.remoteStatus)
        assertEquals("https://cdn.example/v.mp4?sign=SECRET", resp.output?.videoUrl)
        assertEquals("https://cdn.example/f.jpg", resp.output?.lastFrameUrl)
        assertNotNull(resp.usage)
        assertEquals(1700000000L, resp.createdAt)
        assertEquals(1700000100L, resp.updatedAt)
        assertEquals("req-123", resp.requestId)
        assertNull(resp.error)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/contents/generations/tasks/cgt-abc", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    @Test
    fun response_toleratesMissingAndUnknownFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"cgt-abc","unknown_field":true,"output":{"extra":1}}"""
            )
        )
        val resp = client.getTask(config(), "cgt-abc")
        assertEquals("cgt-abc", resp.id)
        assertNull(resp.status)
        assertNull(resp.remoteStatus)
        assertNull(resp.output?.videoUrl)
        assertNull(resp.error)
        assertNull(resp.requestId)
    }

    @Test
    fun response_mapsEveryOfficialStatus() = runBlocking {
        val expected = mapOf(
            "queued" to SeedanceRemoteStatus.QUEUED,
            "running" to SeedanceRemoteStatus.RUNNING,
            "cancelled" to SeedanceRemoteStatus.CANCELLED,
            "succeeded" to SeedanceRemoteStatus.SUCCEEDED,
            "failed" to SeedanceRemoteStatus.FAILED,
            "expired" to SeedanceRemoteStatus.EXPIRED,
        )
        for ((wire, status) in expected) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x","status":"$wire"}"""))
            assertEquals("status=$wire 应映射为 $status", status, client.getTask(config(), "x").remoteStatus)
        }
    }

    @Test
    fun response_unknownStatusFallsBackToFailed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x","status":"weird-status"}"""))
        assertEquals(SeedanceRemoteStatus.FAILED, client.getTask(config(), "x").remoteStatus)
    }

    @Test
    fun response_failedStatusCarriesErrorBody() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"x","status":"failed","error":{"code":"SomeError","message":"生成失败"}}"""
            )
        )
        val resp = client.getTask(config(), "x")
        assertEquals(SeedanceRemoteStatus.FAILED, resp.remoteStatus)
        assertEquals("SomeError", resp.error?.code)
        assertEquals("生成失败", resp.error?.message)
    }

    // ---- 错误分类 ----

    @Test
    fun error_sensitiveContent_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"SensitiveContentError","message":"内容审核不通过"}}""")
        )
        assertEquals(SeedanceError.SENSITIVE_CONTENT, expectApiException().classification)
    }

    @Test
    fun error_quotaExceeded_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"QuotaExceeded","message":"额度不足"}}""")
        )
        assertEquals(SeedanceError.QUOTA_EXCEEDED, expectApiException().classification)
    }

    @Test
    fun error_auth_isClassifiedOn401() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"InvalidApiKey","message":"bad key"}}""")
        )
        assertEquals(SeedanceError.AUTH, expectApiException().classification)
    }

    @Test
    fun error_429_isClassifiedTransient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"RateLimited"}}"""))
        assertEquals(SeedanceError.TRANSIENT_429_5XX, expectApiException().classification)
    }

    @Test
    fun error_500_isClassifiedTransient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"InternalError"}}"""))
        assertEquals(SeedanceError.TRANSIENT_429_5XX, expectApiException().classification)
    }

    @Test
    fun error_invalidParameter_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"InvalidParameter","message":"resolution 非法"}}""")
        )
        assertEquals(SeedanceError.INVALID_PARAMETER, expectApiException().classification)
    }

    // ---- 内容审核（官方错误码） ----

    @Test
    fun error_inputTextSensitiveContentDetected_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"InputTextSensitiveContentDetected","message":"输入文本触发安全策略"}}"""
            )
        )
        assertEquals(SeedanceError.SENSITIVE_CONTENT, expectApiException().classification)
    }

    @Test
    fun error_inputImageSensitiveContentDetected_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"InputImageSensitiveContentDetected","message":"输入图片未通过审核"}}"""
            )
        )
        assertEquals(SeedanceError.SENSITIVE_CONTENT, expectApiException().classification)
    }

    @Test
    fun error_outputVideoSensitiveContentDetected_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"OutputVideoSensitiveContentDetected","message":"生成结果命中审核策略"}}"""
            )
        )
        assertEquals(SeedanceError.SENSITIVE_CONTENT, expectApiException().classification)
    }

    // ---- 鉴权 / 5xx httpStatus / 离线 ----

    @Test
    fun error_403_isClassifiedAuth() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"Forbidden","message":"no permission"}}""")
        )
        assertEquals(SeedanceError.AUTH, expectApiException().classification)
    }

    @Test
    fun error_5xxSurfacesHttpStatusForCostConfirmation() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody("""{"error":{"code":"BadGateway","message":"upstream error"}}""")
        )
        val ex = expectApiException()
        assertEquals(SeedanceError.TRANSIENT_429_5XX, ex.classification)
        assertEquals(502, ex.httpStatus)
    }

    @Test
    fun error_offlineConnectionRefused_isAmbiguousTransport() = runBlocking {
        // 指向一个已关闭的端口 -> 连接失败 -> IOException -> AMBIGUOUS_TRANSPORT（不误判为配额/参数）。
        val dead = MockWebServer()
        dead.start()
        val deadBaseUrl = dead.url("/").toString().trimEnd('/')
        dead.shutdown()
        val cfg = config().copy(baseUrl = deadBaseUrl)
        val ex = runCatching { client.createTask(cfg, request()) }.exceptionOrNull()
        assertTrue("期望 SeedanceApiException，实际 ${ex?.javaClass?.simpleName}", ex is SeedanceApiException)
        val apiEx = ex as SeedanceApiException
        assertEquals(SeedanceError.AMBIGUOUS_TRANSPORT, apiEx.classification)
        assertNull("离线失败不应携带 HTTP 状态", apiEx.httpStatus)
    }

    @Test
    fun error_nonJsonBody_isTolerated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        assertEquals(SeedanceError.TRANSIENT_429_5XX, expectApiException().classification)
    }

    @Test
    fun error_requestIdCapturedFromHeaders() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setHeader("X-Request-Id", "req-err-1").setBody("""{"error":{}}""")
        )
        assertEquals("req-err-1", expectApiException().requestId)
    }

    @Test
    fun error_retryAfterHeaderSurfacedAsMillis() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("""{"error":{"code":"RateLimited"}}""")
        )
        val ex = expectApiException()
        assertEquals(SeedanceError.TRANSIENT_429_5XX, ex.classification)
        assertEquals(120_000L, ex.retryAfterMillis)
    }

    @Test
    fun error_retryAfterNonNumericIsNull() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setHeader("Retry-After", "soon").setBody("""{"error":{}}""")
        )
        assertNull(expectApiException().retryAfterMillis)
    }

    // ---- 取消（已确认 DELETE） ----

    @Test
    fun cancelEndpointIsVerified() {
        assertTrue(CANCEL_ENDPOINT_VERIFIED)
    }

    @Test
    fun cancelQueuedTask_deletesById_andSynthesizesCancelled() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val resp = client.cancelQueuedTask(config(), "cgt-abc")

        assertEquals("cgt-abc", resp.id)
        assertEquals(SeedanceRemoteStatus.CANCELLED, resp.remoteStatus)

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/contents/generations/tasks/cgt-abc", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    // ---- 取消传播与秘密脱敏 ----

    @Test
    fun cancellationPropagatesToCall() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("""{"id":"x"}"""))
        var completed = false
        val job = launch {
            try {
                client.createTask(config(), request())
                completed = true
            } catch (e: CancellationException) {
                throw e
            }
        }
        delay(300)
        job.cancel()
        job.join()
        assertFalse("取消后 createTask 不得正常完成", completed)
    }

    @Test
    fun errorMessageNeverLeaksApiKeyOrBase64() = runBlocking {
        // 服务端在错误消息中恶意回显密钥与 base64，客户端消息必须剥离。
        val echoedSecret = "$TEST_API_KEY $characterBase64"
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"InvalidParameter","message":"bad: $echoedSecret"}}"""
            )
        )
        val msg = expectApiException().message.orEmpty()
        assertFalse("异常消息不得泄漏 API Key", msg.contains(TEST_API_KEY))
        assertFalse("异常消息不得泄漏 base64", msg.contains(characterBase64))
        assertFalse("异常消息不得回显服务端原文", msg.contains(echoedSecret))
    }

    @Test
    fun requestBodyNeverContainsApiKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))
        val body = server.takeRequest().body.readUtf8()
        assertFalse("请求体不得含 API Key", body.contains(TEST_API_KEY))
    }

    // ---- 服务地址归一化（自定义/中转站地址）----

    @Test
    fun resolveCollectionEndpoint_officialBase_appendsSuffix() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3$SEEDANCE_TASKS_SUFFIX",
            resolveSeedanceTaskCollectionEndpoint("https://ark.cn-beijing.volces.com/api/v3"),
        )
    }

    @Test
    fun resolveCollectionEndpoint_fullEndpoint_usedVerbatim_noDoubleAppend() {
        val full = "https://relay.example.com/custom/prefix$SEEDANCE_TASKS_SUFFIX"
        assertEquals(full, resolveSeedanceTaskCollectionEndpoint(full))
    }

    @Test
    fun resolveCollectionEndpoint_fullEndpointWithTrailingSlash_trimsAndKeepsVerbatim() {
        val full = "https://relay.example.com$SEEDANCE_TASKS_SUFFIX/"
        assertEquals("https://relay.example.com$SEEDANCE_TASKS_SUFFIX", resolveSeedanceTaskCollectionEndpoint(full))
    }

    @Test
    fun resolveCollectionEndpoint_bareHost_appendsSuffix() {
        assertEquals("https://relay.example.com$SEEDANCE_TASKS_SUFFIX", resolveSeedanceTaskCollectionEndpoint("https://relay.example.com"))
    }

    @Test
    fun resolveCollectionEndpoint_versionBase_appendsSuffix() {
        assertEquals(
            "https://relay.example.com/v1$SEEDANCE_TASKS_SUFFIX",
            resolveSeedanceTaskCollectionEndpoint("https://relay.example.com/v1"),
        )
    }

    @Test
    fun resolveCollectionEndpoint_apiBase_appendsSuffix() {
        assertEquals(
            "https://relay.example.com/api$SEEDANCE_TASKS_SUFFIX",
            resolveSeedanceTaskCollectionEndpoint("https://relay.example.com/api"),
        )
    }

    @Test
    fun resolveCollectionEndpoint_relayResourcePath_isUsedVerbatim() {
        // 中转站带资源路径的完整接口地址：原样作为创建任务接口，绝不追加。
        val relay = "https://api.lk888.ai/v1/media/generate"
        assertEquals(relay, resolveSeedanceTaskCollectionEndpoint(relay))
    }

    @Test
    fun resolveCollectionEndpoint_relayResourcePathWithTrailingSlash_isTrimmedAndVerbatim() {
        assertEquals(
            "https://api.lk888.ai/v1/media/generate",
            resolveSeedanceTaskCollectionEndpoint("https://api.lk888.ai/v1/media/generate/"),
        )
    }

    @Test
    fun resolveCollectionEndpoint_whitespacePadding_isTrimmed() {
        assertEquals(
            "https://relay.example.com$SEEDANCE_TASKS_SUFFIX",
            resolveSeedanceTaskCollectionEndpoint("  https://relay.example.com  "),
        )
    }

    @Test
    fun resolveTaskEndpoint_appendsTaskIdAfterResolvedCollection() {
        assertEquals(
            "https://relay.example.com$SEEDANCE_TASKS_SUFFIX/cgt-123",
            resolveSeedanceTaskEndpoint("https://relay.example.com", "cgt-123"),
        )
    }

    @Test
    fun resolveTaskEndpoint_relayResourcePath_appendsTaskIdVerbatim() {
        assertEquals(
            "https://api.lk888.ai/v1/media/generate/cgt-123",
            resolveSeedanceTaskEndpoint("https://api.lk888.ai/v1/media/generate", "cgt-123"),
        )
    }

    @Test
    fun createTask_fullEndpointBase_hitsVerbatimPath() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        val full = server.url("/api/v3$SEEDANCE_TASKS_SUFFIX").toString().trimEnd('/')
        client.createTask(SeedanceConfig(baseUrl = full), request())
        assertEquals("/api/v3$SEEDANCE_TASKS_SUFFIX", server.takeRequest().path)
    }

    @Test
    fun createTask_relayResourcePathBase_hitsVerbatimPath() = runBlocking {
        // 路径含 /media/generate → 自动切换媒体协议：POST 原路径并解析 data.task_id。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"msg":"任务创建成功","data":{"task_id":123456}}"""))
        val relay = server.url("/v1/media/generate").toString().trimEnd('/')
        val resp = client.createTask(SeedanceConfig(baseUrl = relay), request())
        assertEquals("/v1/media/generate", server.takeRequest().path)
        assertEquals("123456", resp.id)
        assertEquals(SeedanceRemoteStatus.QUEUED, resp.remoteStatus)
    }

    @Test
    fun createTask_seedance20_stillSendsTwoReferenceImages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))
        val content = lastRequestBody()["content"]!!.jsonArray
        assertEquals(3, content.size) // text + 角色 reference_image + 背景 reference_image
        assertEquals("reference_image", content[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("reference_image", content[2].jsonObject["role"]?.jsonPrimitive?.content)
    }

    // ---- 中转站媒体协议（POST /v1/media/generate + GET /v1/media/status）----

    /** 媒体协议配置：路径含 /media/generate 触发协议切换。 */
    private fun mediaConfig(relayModelId: String = "kwvideo-v2-ref"): SeedanceConfig = SeedanceConfig(
        baseUrl = server.url("/v1/media/generate").toString().trimEnd('/'),
        apiKey = TEST_API_KEY,
        relayModelId = relayModelId,
    )

    @Test
    fun mediaCreate_postsMediaBodyWithMappingsAndImages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"msg":"任务创建成功","data":{"task_id":"t-42"}}"""))
        val resp = client.createTask(
            mediaConfig(),
            request(
                variant = SeedanceModelVariant.STANDARD,
                resolution = SeedanceResolution.P4K,
                ratio = SeedanceRatio.LANDSCAPE,
                durationSeconds = 12,
                background = background,
            ),
        )
        assertEquals("t-42", resp.id)
        assertEquals(SeedanceRemoteStatus.QUEUED, resp.remoteStatus)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/media/generate", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
        val body = testJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("kwvideo-v2-ref", body["model"]!!.jsonPrimitive.content)
        assertEquals("一位少女在夕阳下回眸", body["prompt"]!!.jsonPrimitive.content)
        val params = body["params"]!!.jsonObject
        assertEquals("标准", params["version"]!!.jsonPrimitive.content)
        assertEquals("12", params["duration"]!!.jsonPrimitive.content)
        assertEquals("16:9", params["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("4K", params["resolution"]!!.jsonPrimitive.content) // 媒体协议 4K 为大写 K
        val images = params["images"]!!.jsonArray
        assertEquals(2, images.size)
        assertEquals("data:image/png;base64,aGVsbG8=", images[0].jsonPrimitive.content) // 立绘在前
        assertEquals("data:image/jpeg;base64,d29ybGQ=", images[1].jsonPrimitive.content) // 背景在后
    }

    @Test
    fun mediaCreate_fastVariant_mapsVersionKuaisu() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"data":{"task_id":1}}"""))
        client.createTask(mediaConfig(), request(variant = SeedanceModelVariant.FAST, resolution = SeedanceResolution.P720))
        val body = testJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("快速", body["params"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals("720p", body["params"]!!.jsonObject["resolution"]!!.jsonPrimitive.content)
    }

    @Test
    fun mediaCreate_blankRelayModelId_fallsBackToDefault() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"data":{"task_id":1}}"""))
        client.createTask(mediaConfig(relayModelId = "  "), request())
        val body = testJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(DEFAULT_RELAY_MODEL_ID, body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun mediaCreate_numericTaskId_isParsedAsText() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"msg":"任务创建成功","data":{"task_id":123456}}"""))
        val resp = client.createTask(mediaConfig(), request())
        assertEquals("123456", resp.id)
    }

    @Test
    fun mediaCreate_flatTaskIdWithoutWrapper_isParsed() = runBlocking {
        // 无包装直接平铺 task_id 的兼容渠道。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"task_id":"flat-9"}"""))
        val resp = client.createTask(mediaConfig(), request())
        assertEquals("flat-9", resp.id)
    }

    @Test
    fun mediaCreate_successWithoutTaskId_returnsEmptyResponse() = runBlocking {
        // 2xx 但拿不到任务 ID：交给协调器按歧义处理（可能已产生费用），客户端不抛错。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"msg":"ok","data":{}}"""))
        val resp = client.createTask(mediaConfig(), request())
        assertNull(resp.id)
        assertNull(resp.remoteStatus)
    }

    @Test
    fun mediaCreate_businessCode500_insufficientBalance_isClassifiedQuota() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"code":500,"msg":"任务创建失败","data":{"失败数量":1,"失败原因":["余额不足"]}}""")
        )
        val ex = expectMediaApiException()
        assertEquals(SeedanceError.QUOTA_EXCEEDED, ex.classification)
        assertEquals(500, ex.httpStatus)
    }

    @Test
    fun mediaCreate_businessCode429_isClassifiedTransient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":429,"msg":"繁忙"}"""))
        val ex = expectMediaApiException()
        assertEquals(SeedanceError.TRANSIENT_429_5XX, ex.classification)
    }

    private suspend fun expectMediaApiException(): SeedanceApiException {
        val ex = runCatching { client.createTask(mediaConfig(), request()) }.exceptionOrNull()
        assertNotNull("期望抛出 SeedanceApiException", ex)
        assertTrue("期望 SeedanceApiException，实际 ${ex!!.javaClass.simpleName}", ex is SeedanceApiException)
        return ex as SeedanceApiException
    }

    @Test
    fun mediaStatus_getsStatusPathWithTaskIdQuery() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"task_id":123456,"state":"running","status":"处理中","status_group":"进行中","is_final":false,"progress":"45%","result_url":"","result_type":"","error":"","cost":0}"""
            )
        )
        val resp = client.getTask(mediaConfig(), "123456")
        assertEquals("123456", resp.id)
        assertEquals(SeedanceRemoteStatus.RUNNING, resp.remoteStatus)
        assertNull(resp.output?.videoUrl)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/media/status?task_id=123456", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    @Test
    fun mediaStatus_successWithUrl_mapsSucceededWithVideoUrl() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"task_id":1,"state":"success","is_final":true,"progress":"100%","result_url":"https://cdn.example.com/output.mp4","result_type":"video","error":"","cost":0.23}"""
            )
        )
        val resp = client.getTask(mediaConfig(), "1")
        assertEquals(SeedanceRemoteStatus.SUCCEEDED, resp.remoteStatus)
        assertEquals("https://cdn.example.com/output.mp4", resp.output?.videoUrl)
    }

    @Test
    fun mediaStatus_successWithoutUrl_keepsRunningToPollAgain() = runBlocking {
        // 完成但 URL 未就绪：继续轮询，绝不误进「成功无产物」分支。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"task_id":1,"state":"success","is_final":true,"progress":"100%","result_url":"","result_type":"","error":""}"""
            )
        )
        val resp = client.getTask(mediaConfig(), "1")
        assertEquals(SeedanceRemoteStatus.RUNNING, resp.remoteStatus)
    }

    @Test
    fun mediaStatus_pending_mapsQueued() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"task_id":1,"state":"pending","is_final":false}"""))
        assertEquals(SeedanceRemoteStatus.QUEUED, client.getTask(mediaConfig(), "1").remoteStatus)
    }

    @Test
    fun mediaStatus_failed_carriesErrorMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"task_id":1,"state":"failed","is_final":true,"error":"内容审核未通过"}"""
            )
        )
        val resp = client.getTask(mediaConfig(), "1")
        assertEquals(SeedanceRemoteStatus.FAILED, resp.remoteStatus)
        assertEquals("内容审核未通过", resp.error?.message)
    }

    @Test
    fun mediaStatus_unknownStateNotFinal_keepsRunning() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"task_id":1,"state":"weird","is_final":false}"""))
        assertEquals(SeedanceRemoteStatus.RUNNING, client.getTask(mediaConfig(), "1").remoteStatus)
    }

    @Test
    fun mediaStatus_unknownStateFinal_fallsBackToFailed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"task_id":1,"state":"weird","is_final":true}"""))
        assertEquals(SeedanceRemoteStatus.FAILED, client.getTask(mediaConfig(), "1").remoteStatus)
    }

    @Test
    fun mediaStatus_wrappedInData_isParsed() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":200,"msg":"ok","data":{"task_id":7,"state":"running","is_final":false}}"""
            )
        )
        val resp = client.getTask(mediaConfig(), "7")
        assertEquals(SeedanceRemoteStatus.RUNNING, resp.remoteStatus)
        assertEquals("7", resp.id)
    }

    @Test
    fun mediaStatus_errorWrapperTaskNotFound_isClassifiedNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":404,"msg":"任务不存在"}"""))
        val ex = runCatching { client.getTask(mediaConfig(), "999") }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("期望 SeedanceApiException，实际 ${ex!!.javaClass.simpleName}", ex is SeedanceApiException)
        assertEquals(SeedanceError.NOT_FOUND, (ex as SeedanceApiException).classification)
    }

    @Test
    fun mediaCancel_throwsUnsupported() = runBlocking {
        val ex = runCatching { client.cancelQueuedTask(mediaConfig(), "1") }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("期望 UnsupportedOperationException，实际 ${ex!!.javaClass.simpleName}", ex is UnsupportedOperationException)
    }

    @Test
    fun mediaProbe_getsMediaStatusPath() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":404,"msg":"任务不存在"}"""))
        val result = client.probeEndpoint(mediaConfig())
        assertTrue("期望 Ok，实际 $result", result is SeedanceProbeResult.Ok)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/v1/media/status?task_id="))
    }

    @Test
    fun mediaProbe_401_reportsKeyInvalid() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"msg":"未授权"}"""))
        val result = client.probeEndpoint(mediaConfig())
        assertTrue("期望 Failed，实际 $result", result is SeedanceProbeResult.Failed)
        assertTrue((result as SeedanceProbeResult.Failed).message.contains("API Key"))
    }

    // ---- 协议识别与媒体端点解析 ----

    @Test
    fun protocolDetection_mediaPath_isMediaRelay() {
        assertEquals(SeedanceProtocol.MEDIA_RELAY, seedanceProtocolFor("https://api.lk888.ai/v1/media/generate"))
        assertEquals(SeedanceProtocol.MEDIA_RELAY, seedanceProtocolFor("https://relay.example.com/v2/media/generate/"))
    }

    @Test
    fun protocolDetection_knownRelayHostBareOrV1_isMediaRelay() {
        assertEquals(SeedanceProtocol.MEDIA_RELAY, seedanceProtocolFor("https://api.lk888.ai"))
        assertEquals(SeedanceProtocol.MEDIA_RELAY, seedanceProtocolFor("https://api.lk888.ai/v1"))
        assertEquals(SeedanceProtocol.MEDIA_RELAY, seedanceProtocolFor("https://api.lingkeai.ai"))
        assertEquals(SeedanceProtocol.MEDIA_RELAY, seedanceProtocolFor("https://dm1124.com"))
    }

    @Test
    fun protocolDetection_officialAndUnknownBases_stayArk() {
        assertEquals(SeedanceProtocol.ARK, seedanceProtocolFor("https://ark.cn-beijing.volces.com/api/v3"))
        assertEquals(SeedanceProtocol.ARK, seedanceProtocolFor("https://relay.example.com"))
        assertEquals(SeedanceProtocol.ARK, seedanceProtocolFor("https://relay.example.com/v1"))
        assertEquals(SeedanceProtocol.ARK, seedanceProtocolFor(""))
    }

    @Test
    fun resolveMediaGenerateEndpoint_fullPathUsedVerbatim() {
        assertEquals(
            "https://api.lk888.ai/v1/media/generate",
            resolveMediaGenerateEndpoint("https://api.lk888.ai/v1/media/generate/"),
        )
    }

    @Test
    fun resolveMediaGenerateEndpoint_bareHostAndV1_appendMediaGenerate() {
        assertEquals("https://api.lk888.ai/v1/media/generate", resolveMediaGenerateEndpoint("https://api.lk888.ai"))
        assertEquals("https://api.lk888.ai/v1/media/generate", resolveMediaGenerateEndpoint("https://api.lk888.ai/v1"))
    }

    @Test
    fun resolveMediaStatusEndpoint_replacesGenerateWithStatus() {
        assertEquals("https://api.lk888.ai/v1/media/status", resolveMediaStatusEndpoint("https://api.lk888.ai/v1/media/generate"))
        assertEquals("https://api.lk888.ai/v1/media/status", resolveMediaStatusEndpoint("https://api.lk888.ai"))
    }

    // ---- 404/405 → BAD_ENDPOINT ----

    @Test
    fun error_404_html_isClassifiedBadEndpoint_withActionableMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html><body>Not Found</body></html>"))
        val ex = expectApiException()
        assertEquals(SeedanceError.BAD_ENDPOINT, ex.classification)
        assertEquals(404, ex.httpStatus)
        assertNotNull("错误文案应提示核对地址", ex.message)
        assertTrue("错误文案应包含接口后缀提示", ex.message.orEmpty().contains(SEEDANCE_TASKS_SUFFIX))
    }

    @Test
    fun error_404_jsonBody_isClassifiedNotFound_notBadEndpoint() = runBlocking {
        // 结构化 404（API 层已理解请求）是「模型/任务不存在」，不是「路径错误」。
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"ModelNotFound","message":"model not found"}}""")
        )
        val ex = expectApiException()
        assertEquals(SeedanceError.NOT_FOUND, ex.classification)
        assertTrue("文案应指向模型 ID 或区域", ex.message.orEmpty().contains("模型或任务不存在"))
    }

    @Test
    fun error_405_jsonBody_isClassifiedNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405).setBody("""{"error":{"code":"MethodNotAllowed","message":"no"}}"""))
        val ex = expectApiException()
        assertEquals(SeedanceError.NOT_FOUND, ex.classification)
    }

    @Test
    fun error_404_modelNotOpen_isClassifiedModelNotOpen() = runBlocking {
        // 方舟对「已存在但未开通」的模型返回 ModelNotOpen，与「模型不存在」语义不同。
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"ModelNotOpen","message":"Your account has not activated the model. Please activate the model service in the Ark Console."}}""")
        )
        val ex = expectApiException()
        assertEquals(SeedanceError.MODEL_NOT_OPEN, ex.classification)
        assertTrue("文案应指向控制台开通", ex.message.orEmpty().contains("开通"))
    }

    // ---- “测试连接”探测 ----

    @Test
    fun probe_2xx_returnsOk() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"probe"}"""))
        val result = client.probeEndpoint(config())
        assertTrue("期望 Ok，实际 $result", result is SeedanceProbeResult.Ok)
    }

    @Test
    fun probe_401_reportsKeyInvalid() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"AuthError"}}"""))
        val result = client.probeEndpoint(config())
        assertTrue("期望 Failed，实际 $result", result is SeedanceProbeResult.Failed)
        assertTrue((result as SeedanceProbeResult.Failed).message.contains("API Key"))
    }

    @Test
    fun probe_404_withJsonBody_returnsOk_pathIsCorrect() = runBlocking {
        // 路径正确时，对不存在的探测任务返回 JSON 错误体 → 判定接口可达、路径正确。
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"TaskNotFound","message":"任务不存在"}}""")
        )
        val result = client.probeEndpoint(config())
        assertTrue("期望 Ok（JSON 404 = 预期返回），实际 $result", result is SeedanceProbeResult.Ok)
    }

    @Test
    fun probe_404_withHtmlBody_returnsFailed_pathMayBeWrong() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not Found</html>"))
        val result = client.probeEndpoint(config())
        assertTrue("期望 Failed（HTML 404 = 路径不对），实际 $result", result is SeedanceProbeResult.Failed)
        assertTrue((result as SeedanceProbeResult.Failed).message.contains("路径可能不正确"))
    }

    @Test
    fun probe_connectionRefused_returnsFailed() = runBlocking {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadBase = deadServer.url("/").toString().trimEnd('/')
        deadServer.shutdown()
        val result = client.probeEndpoint(SeedanceConfig(baseUrl = deadBase))
        assertTrue("期望 Failed（连接失败），实际 $result", result is SeedanceProbeResult.Failed)
    }

    companion object {
        private const val TEST_API_KEY = "test-seedance-key-123"
        private const val characterBase64 = "aGVsbG8="
    }
}
