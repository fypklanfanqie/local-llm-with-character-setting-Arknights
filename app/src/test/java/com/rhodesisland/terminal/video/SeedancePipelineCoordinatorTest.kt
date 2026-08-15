package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.data.remote.CreateSeedanceTask
import com.rhodesisland.terminal.data.remote.SeedanceApiException
import com.rhodesisland.terminal.data.remote.SeedanceError
import com.rhodesisland.terminal.data.remote.SeedanceImageContent
import com.rhodesisland.terminal.data.remote.SeedanceTaskOutput
import com.rhodesisland.terminal.data.remote.SeedanceTaskResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * [SeedancePipelineCoordinator] 状态机契约测试（Task 6 + Task 10 加固，纯 JVM，假 store/submitter/llm/clock）。
 *
 * 覆盖：CAS 单胜者、提示词重试、配置变更门禁、POST 歧义（不自动重发 + requiresCostConfirmation）、
 * 明确 4xx（含审核/配额/鉴权）失败、5xx 费用确认、裸 IOException（提交=歧义、轮询=有界重试）、
 * 重复调度不重复提交、排队->运行->下载轮询、取消竞态/取消终态、下载->READY 仅限有效最终文件、
 * 进程死亡（SUBMITTING/DOWNLOADING 复位）、残留 .part、URL 过期、磁盘满、既有有效成品幂等 READY。
 */
class SeedancePipelineCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ===== 测试装配 =====

    private class FakeStore(initial: SeedanceVideo) : SeedancePipelineStore {
        private val lock = Any()
        private val rows = mutableMapOf(initial.id to initial)
        fun current(id: Long): SeedanceVideo = rows.getValue(id)

        override suspend fun getById(id: Long): SeedanceVideo? = synchronized(lock) { rows[id] }
        override suspend fun claim(id: Long, from: SeedanceVideoState, to: SeedanceVideoState): Boolean =
            synchronized(lock) {
                val row = rows[id] ?: return false
                if (row.state != from) return false
                rows[id] = row.copy(state = to)
                true
            }
        override suspend fun update(video: SeedanceVideo) {
            synchronized(lock) { rows[video.id] = video }
        }
        override suspend fun transition(
            id: Long,
            from: SeedanceVideoState,
            to: SeedanceVideoState,
            mutate: (SeedanceVideo) -> SeedanceVideo,
        ): Boolean = synchronized(lock) {
            val row = rows[id] ?: return false
            if (row.state != from) return false
            rows[id] = mutate(row.copy(state = to))
            true
        }
        override suspend fun listRecoverable(now: Long): List<SeedanceVideo> = synchronized(lock) {
            rows.values.filter { it.state in RECOVERABLE && (it.nextRetryAt == null || it.nextRetryAt <= now) }
        }
        override suspend fun listByStates(states: Set<SeedanceVideoState>): List<SeedanceVideo> =
            synchronized(lock) { rows.values.filter { it.state in states } }

        companion object {
            val RECOVERABLE = setOf(
                SeedanceVideoState.SNAPSHOT_PENDING, SeedanceVideoState.PROMPT_PENDING,
                SeedanceVideoState.SUBMISSION_PENDING, SeedanceVideoState.QUEUED,
                SeedanceVideoState.RUNNING, SeedanceVideoState.CANCEL_REQUESTED,
                SeedanceVideoState.DOWNLOAD_PENDING,
            )
        }
    }

    private class FakePromptProvider {
        var invokeCount = 0
        var failTransient: Throwable? = null
        var failParse = false
        var delayMillis = 0L
        var lastInput: SeedancePromptInput? = null
        suspend fun generate(apiConfig: ApiConfig, input: SeedancePromptInput): SeedancePromptDocument {
            invokeCount++
            lastInput = input
            failTransient?.let { throw it }
            if (failParse) throw SeedancePromptParseException("bad json")
            if (delayMillis > 0) delay(delayMillis)
            return SeedancePromptDocument(subject = "主体", action = "动作", environment = "环境", finalPrompt = "最终提示词")
        }
    }

    private class FakeSnapshooter {
        var fail = false
        suspend fun snapshot(
            taskUuid: String,
            character: Character,
            builtInAssetPath: String?,
            backgroundImagePath: String?,
        ): Result<SeedanceReferenceSnapshot> {
            if (fail) return Result.failure(IllegalStateException("复制失败"))
            return Result.success(SeedanceReferenceSnapshot(
                characterPath = "/t/$taskUuid/references/character.png",
                characterMime = "image/png",
                characterSha256 = "character-sha",
                backgroundPath = backgroundImagePath?.let { "/t/$taskUuid/references/background.png" },
                backgroundMime = backgroundImagePath?.let { "image/png" },
                backgroundSha256 = backgroundImagePath?.let { "background-sha" },
            ))
        }
    }

    private class FakeSubmitter : SeedanceSubmitter {
        var createCount = 0
        var createDelayMillis = 0L
        var createResult: (() -> SeedanceTaskResponse)? = null
        var createError: (() -> Throwable)? = null
        var getResult: (() -> SeedanceTaskResponse)? = null
        var getError: (() -> Throwable)? = null
        var cancelCount = 0

        override suspend fun create(config: SeedanceConfig, request: CreateSeedanceTask): SeedanceTaskResponse {
            createCount++
            createError?.let { throw it() }
            if (createDelayMillis > 0) delay(createDelayMillis)
            return createResult?.invoke() ?: SeedanceTaskResponse(id = "remote-1", status = "queued")
        }
        override suspend fun get(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
            getError?.let { throw it() }
            return getResult?.invoke() ?: SeedanceTaskResponse(id = taskId, status = "queued")
        }
        override suspend fun cancel(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
            cancelCount++
            return SeedanceTaskResponse(id = taskId, status = "cancelled")
        }
    }

    private class FakeDownloader {
        var bytes: ByteArray? = mp4TestBytes()
        var mime: String? = "video/mp4"
        var contentLength: Long? = null
        suspend fun download(url: String): SeedanceVideoDownload? = bytes?.let {
            SeedanceVideoDownload(mime, contentLength, ByteArrayInputStream(it))
        }
    }

    private class FakeEncoder : SeedanceImageEncoder {
        var fail = false
        val budgets = mutableListOf<Long>()
        override suspend fun encode(path: String, mime: String, maxBytes: Long): SeedanceImageContent {
            budgets += maxBytes
            if (fail) throw IOException("参考图缺失或不可读")
            return SeedanceImageContent(mime, "base64-of-$path")
        }
    }

    private class Env(initial: SeedanceVideo, root: File) {
        var clockNow = 1_000_000L
        val store = FakeStore(initial)
        val submitter = FakeSubmitter()
        val promptProvider = FakePromptProvider()
        val snapshooter = FakeSnapshooter()
        val downloader = FakeDownloader()
        val encoder = FakeEncoder()
        var apiConfig = ApiConfig(baseUrl = "https://api.deepseek.com/v1", apiKey = "sk-test", model = "deepseek-chat")
        var seedanceConfig = SeedanceConfig(baseUrl = "https://ark.cn-beijing.volces.com/api/v3", apiKey = "sk-seedance")
        /** 前情对话假提供者：记录入参，返回脚本文本；抛错时协调器应静默降级为空。 */
        var contextScript: String = ""
        var contextError: Throwable? = null
        var contextCallArgs: Triple<Long, String, String>? = null
        val fileStore = SeedanceVideoFileStore(root) { clockNow }
        val retryPolicy = SeedanceRetryPolicy(baseBackoffMillis = 1_000L, maxBackoffMillis = 4_000L)

        val coordinator = SeedancePipelineCoordinator(
            store = store,
            submitter = submitter,
            promptProvider = promptProvider::generate,
            conversationContextProvider = SeedanceConversationContextProvider { conversationId, userText, assistantText, _ ->
                contextCallArgs = Triple(conversationId, userText, assistantText)
                contextError?.let { throw it }
                contextScript
            },
            snapshooter = snapshooter::snapshot,
            resolveSnapshotSources = SeedanceSnapshotSourceResolver { t ->
                SeedanceSnapshotSources(
                    character = Character(
                        id = t.characterIdSnapshot, name = t.characterNameSnapshot, code = "c",
                        role = t.characterRoleSnapshot, race = "r", systemPrompt = t.characterSystemPromptSnapshot,
                    ),
                    builtInAssetPath = "characters/neighbor.webp",
                    backgroundImagePath = t.backgroundImageSourceSnapshot,
                )
            },
            downloadVideo = downloader::download,
            fileStore = fileStore,
            encoder = encoder,
            apiConfigProvider = { apiConfig },
            seedanceConfigProvider = { seedanceConfig },
            clock = { clockNow },
            idGenerator = { "attempt-$clockNow" },
            retryPolicy = retryPolicy,
        )
    }

    // ===== 任务工厂 =====

    private fun task(
        id: Long = 1,
        state: SeedanceVideoState = SeedanceVideoState.SNAPSHOT_PENDING,
        promptBaseUrl: String = "https://api.deepseek.com/v1",
        promptModel: String = "deepseek-chat",
        characterImagePath: String? = null,
        characterImageMime: String? = "image/png",
        characterImageSha256: String? = "character-sha",
        backgroundImagePath: String? = null,
        finalPrompt: String? = null,
        remoteTaskId: String? = null,
        remoteVideoUrl: String? = null,
        remoteVideoUrlExpiresAt: Long? = null,
        videoSha256: String? = null,
        automaticRetryCount: Int = 0,
        nextRetryAt: Long? = null,
    ): SeedanceVideo = SeedanceVideo(
        id = id, taskUuid = "uuid-$id", triggerType = "auto",
        sourceConversationId = 1, sourceUserMessageId = 1, sourceAssistantMessageId = id,
        characterIdSnapshot = "neighbor", characterNameSnapshot = "邻居", characterRoleSnapshot = "角色",
        characterSystemPromptSnapshot = "设定", userTextSnapshot = "你好", assistantTextSnapshot = "你好呀",
        sceneDescriptionSnapshot = "", promptBaseUrlSnapshot = promptBaseUrl, promptModelSnapshot = promptModel,
        promptJson = null, finalPrompt = finalPrompt,
        characterImageSourceSnapshot = "characters/neighbor.webp",
        backgroundImageSourceSnapshot = backgroundImagePath,
        characterImagePath = characterImagePath, characterImageMime = characterImageMime, characterImageSha256 = characterImageSha256,
        backgroundImagePath = backgroundImagePath, backgroundImageMime = backgroundImagePath?.let { "image/png" },
        backgroundImageSha256 = backgroundImagePath?.let { "background-sha" },
        modelVariant = SeedanceModelVariant.STANDARD, resolution = SeedanceResolution.P720, ratio = SeedanceRatio.PORTRAIT,
        durationSeconds = 5, generateAudio = true, watermark = false,
        state = state, remoteStatus = null, generationAttempt = 0,
        submissionAttemptId = null, submissionStartedAt = null, requestFingerprint = null,
        remoteTaskId = remoteTaskId, remoteVideoUrl = remoteVideoUrl,
        remoteVideoUrlObservedAt = null, remoteVideoUrlExpiresAt = remoteVideoUrlExpiresAt,
        remoteRequestId = null, previousRemoteTasksJson = "",
        localVideoPath = null, videoMime = null, videoByteSize = null, videoSha256 = videoSha256, downloadedAt = null,
        automaticRetryCount = automaticRetryCount, nextRetryAt = nextRetryAt,
        errorStage = null, errorCode = null, errorMessage = null, retryDisposition = null,
        requiresCostConfirmation = false, createdAt = 0, updatedAt = 0,
    )

    private fun env(state: SeedanceVideoState, config: SeedanceVideo.() -> SeedanceVideo = { this }): Env =
        Env(task(state = state).config(), tmp.newFolder())

    private val submitTask: SeedanceVideo.() -> SeedanceVideo = {
        copy(
            characterImagePath = "/t/refs/character.png", characterImageMime = "image/png",
            characterImageSha256 = "character-sha", finalPrompt = "最终提示词",
        )
    }

    // ===== 测试 =====

    @Test
    fun snapshotThenPrompt() = runBlocking {
        val e = env(SeedanceVideoState.SNAPSHOT_PENDING)
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.PROMPT_PENDING, t.state)
        assertEquals("character-sha", t.characterImageSha256)
        assertNotNull(t.characterImagePath)
    }

    @Test
    fun snapshotFailureTransitionsToFailedSnapshot() = runBlocking {
        val e = env(SeedanceVideoState.SNAPSHOT_PENDING)
        e.snapshooter.fail = true
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SNAPSHOT, t.state)
        assertEquals("SNAPSHOT", t.errorStage)
    }

    @Test
    fun promptClaimSingleWinner() = runBlocking {
        val e = env(SeedanceVideoState.PROMPT_PENDING)
        e.promptProvider.delayMillis = 10L
        val jobs = listOf(
            launch { e.coordinator.advance(1) },
            launch { e.coordinator.advance(1) },
        )
        jobs.forEach { it.join() }
        assertEquals(1, e.promptProvider.invokeCount)
        assertEquals(SeedanceVideoState.SUBMISSION_PENDING, e.store.current(1).state)
        assertNotNull(e.store.current(1).finalPrompt)
    }

    @Test
    fun promptInputReceivesConversationContext() = runBlocking {
        val e = env(SeedanceVideoState.PROMPT_PENDING)
        e.contextScript = "用户：之前我们聊过海边\n角色：对，海边的日落很美"
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val input = e.promptProvider.lastInput
        assertNotNull(input)
        assertEquals("用户：之前我们聊过海边\n角色：对，海边的日落很美", input!!.recentContext)
        val args = e.contextCallArgs
        assertNotNull(args)
        assertEquals(1L, args!!.first)
        assertEquals("你好", args.second)
        assertEquals("你好呀", args.third)
        assertEquals(SeedanceVideoState.SUBMISSION_PENDING, e.store.current(1).state)
    }

    @Test
    fun contextProviderFailureDegradesToEmptyContext() = runBlocking {
        val e = env(SeedanceVideoState.PROMPT_PENDING)
        e.contextError = IOException("db 忙")
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        assertEquals("", e.promptProvider.lastInput?.recentContext)
        assertEquals(SeedanceVideoState.SUBMISSION_PENDING, e.store.current(1).state)
    }

    @Test
    fun promptInputReceivesBackgroundReferenceFlag() = runBlocking {
        val withBg = env(SeedanceVideoState.PROMPT_PENDING) {
            copy(backgroundImageSourceSnapshot = "file:///data/bg.png")
        }
        assertTrue(withBg.coordinator.advance(1) is PipelineOutcome.Reschedule)
        assertTrue("有背景参考图时应为 true", withBg.promptProvider.lastInput?.hasBackgroundReference == true)

        val withoutBg = env(SeedanceVideoState.PROMPT_PENDING)
        assertTrue(withoutBg.coordinator.advance(1) is PipelineOutcome.Reschedule)
        assertFalse("无背景参考图时应为 false", withoutBg.promptProvider.lastInput?.hasBackgroundReference == true)
    }

    @Test
    fun transientPromptFailureSchedulesRetry() = runBlocking {
        val e = env(SeedanceVideoState.PROMPT_PENDING)
        e.promptProvider.failTransient = IOException("网络错误")
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.PROMPT_PENDING, t.state)
        assertEquals(1, t.automaticRetryCount)
        assertEquals(e.clockNow + 1_000L, t.nextRetryAt)
        assertEquals("PROMPT", t.errorStage)
    }

    @Test
    fun configChangedGateBlocksPrompt() = runBlocking {
        val e = env(SeedanceVideoState.PROMPT_PENDING) { copy(promptBaseUrlSnapshot = "https://old", promptModelSnapshot = "old-model") }
        e.apiConfig = ApiConfig(baseUrl = "https://new", apiKey = "sk", model = "new-model")
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED, t.state)
        assertEquals("PROMPT", t.errorStage)
        assertEquals(0, e.promptProvider.invokeCount)
    }

    @Test
    fun ambiguousPostNeverAutoResubmits() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = { SeedanceApiException(SeedanceError.AMBIGUOUS_TRANSPORT, "网络错误") }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals(ERROR_CODE_AMBIGUOUS_POST, t.errorCode)
        assertTrue(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
        assertNotNull(t.submissionAttemptId)
        assertNotNull(t.requestFingerprint)
    }

    @Test
    fun clear4xxFailsSubmissionNotAmbiguous() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = { SeedanceApiException(SeedanceError.INVALID_PARAMETER, "参数不合法") }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("INVALID_PARAMETER", t.errorCode)
        assertFalse(t.requiresCostConfirmation)
    }

    @Test
    fun transient429FailsSubmissionNeverResubmits() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = {
            SeedanceApiException(SeedanceError.TRANSIENT_429_5XX, "视频服务暂时繁忙（HTTP 429），请稍后重试")
        }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("TRANSIENT_429_5XX", t.errorCode)
        assertEquals("manual", t.retryDisposition)
        assertFalse(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
        assertNotNull(t.submissionAttemptId)
        assertNotNull(t.requestFingerprint)
    }

    @Test
    fun transient5xxFailsSubmissionRequiresCostConfirmation() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        // 502/504 网关错误可能在服务端已创建任务后才返回：须费用确认，绝不自动重发。
        e.submitter.createError = {
            SeedanceApiException(SeedanceError.TRANSIENT_429_5XX, "视频服务暂时繁忙（HTTP 502），请稍后重试", httpStatus = 502)
        }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("manual", t.retryDisposition)
        assertTrue(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
    }

    @Test
    fun submitModerationFailureFailsSubmissionWithoutCostConfirmation() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = {
            SeedanceApiException(SeedanceError.SENSITIVE_CONTENT, "视频生成内容未通过审核，请修改角色或场景描述后重试", httpStatus = 400)
        }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("SENSITIVE_CONTENT", t.errorCode)
        assertFalse(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
    }

    @Test
    fun submitQuotaFailureFailsSubmissionWithoutCostConfirmation() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = {
            SeedanceApiException(SeedanceError.QUOTA_EXCEEDED, "额度不足或已达上限，请稍后重试", httpStatus = 400)
        }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("QUOTA_EXCEEDED", t.errorCode)
        assertFalse(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
    }

    @Test
    fun submitAuthFailureFailsSubmissionWithoutCostConfirmation() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = {
            SeedanceApiException(SeedanceError.AUTH, "Seedance API Key 无效或未授权", httpStatus = 401)
        }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("AUTH", t.errorCode)
        assertFalse(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
    }

    @Test
    fun submitRawIoExceptionIsAmbiguousAndCostConfirming() = runBlocking {
        // 客户端把离线/传输异常统一映射为 AMBIGUOUS_TRANSPORT；此处模拟 submitter 抛裸 IOException。
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createError = { IOException("socket closed") }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals(ERROR_CODE_AMBIGUOUS_POST, t.errorCode)
        assertTrue(t.requiresCostConfirmation)
        assertEquals(1, e.submitter.createCount)
    }

    @Test
    fun pollRawIoExceptionKeepsRecoverableState() = runBlocking {
        val e = env(SeedanceVideoState.QUEUED) { copy(remoteTaskId = "remote-1") }
        e.submitter.getError = { IOException("socket closed") }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.QUEUED, t.state)
        assertEquals(1, t.automaticRetryCount)
        assertNotNull(t.nextRetryAt)
    }

    @Test
    fun duplicateAdvanceDoesNotDoubleSubmit() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        // 让 create 挂起，制造两个并发 advance 同时撞上 SUBMISSION_PENDING 的窗口；
        // CAS 认领保证只有一个 Worker 真正发起 POST。
        e.submitter.createDelayMillis = 50
        e.submitter.createResult = { SeedanceTaskResponse(id = "remote-9", status = "queued") }
        val jobs = listOf(launch { e.coordinator.advance(1) }, launch { e.coordinator.advance(1) })
        jobs.forEach { it.join() }
        assertEquals(1, e.submitter.createCount)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.QUEUED, t.state)
        assertEquals("remote-9", t.remoteTaskId)
    }

    @Test
    fun cancelQueuedTransitionsToCancelled() = runBlocking {
        val e = env(SeedanceVideoState.CANCEL_REQUESTED) { copy(remoteTaskId = "remote-1") }
        e.submitter.getResult = { SeedanceTaskResponse(id = "remote-1", status = "cancelled") }
        assertEquals(PipelineOutcome.Complete, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.CANCELLED, t.state)
        assertEquals(1, e.submitter.cancelCount)
    }

    @Test
    fun imageEncodeFailureFailsSubmissionWithSnapshotStage() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.encoder.fail = true
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals("SNAPSHOT", t.errorStage)
        assertEquals("manual", t.retryDisposition)
        assertFalse(t.requiresCostConfirmation)
        assertEquals(0, e.submitter.createCount)
        assertNotNull(t.submissionAttemptId)
    }

    @Test
    fun submitSuccessCreatesRemoteTask() = runBlocking {
        val e = env(SeedanceVideoState.SUBMISSION_PENDING, submitTask)
        e.submitter.createResult = { SeedanceTaskResponse(id = "remote-9", status = "queued") }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.QUEUED, t.state)
        assertEquals("remote-9", t.remoteTaskId)
        assertNotNull(t.submissionAttemptId)
    }

    @Test
    fun pollQueuedToRunningToDownload() = runBlocking {
        val e = env(SeedanceVideoState.QUEUED) { copy(remoteTaskId = "remote-1") }
        e.submitter.getResult = { SeedanceTaskResponse(id = "remote-1", status = "queued") }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        assertEquals(SeedanceVideoState.QUEUED, e.store.current(1).state)

        e.submitter.getResult = { SeedanceTaskResponse(id = "remote-1", status = "running") }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        assertEquals(SeedanceVideoState.RUNNING, e.store.current(1).state)

        e.submitter.getResult = {
            SeedanceTaskResponse(id = "remote-1", status = "succeeded", output = SeedanceTaskOutput(videoUrl = "https://v/1.mp4"))
        }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.DOWNLOAD_PENDING, t.state)
        assertEquals("https://v/1.mp4", t.remoteVideoUrl)
        assertNotNull(t.remoteVideoUrlExpiresAt)
    }

    @Test
    fun cancelRaceServerStatusWins() = runBlocking {
        val e = env(SeedanceVideoState.CANCEL_REQUESTED) { copy(remoteTaskId = "remote-1") }
        e.submitter.getResult = { SeedanceTaskResponse(id = "remote-1", status = "running") }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        assertEquals(SeedanceVideoState.RUNNING, e.store.current(1).state)
    }

    @Test
    fun downloadLeadsToReady() = runBlocking {
        val e = env(SeedanceVideoState.DOWNLOAD_PENDING) {
            copy(remoteTaskId = "remote-1", remoteVideoUrl = "https://v/1.mp4", remoteVideoUrlExpiresAt = Long.MAX_VALUE)
        }
        assertEquals(PipelineOutcome.Complete, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.READY, t.state)
        assertNotNull(t.localVideoPath)
        assertNotNull(t.videoSha256)
        assertTrue(File(t.localVideoPath!!).isFile)
    }

    @Test
    fun normalizeStaleSubmittingBecomesAmbiguous() = runBlocking {
        val e = env(SeedanceVideoState.SUBMITTING)
        e.coordinator.normalizeStaleInProgress()
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals(ERROR_CODE_AMBIGUOUS_POST, t.errorCode)
        assertTrue(t.requiresCostConfirmation)
    }

    @Test
    fun normalizeFreshSubmittingIsNotReset() = runBlocking {
        // clockNow=1_000_000；100 秒前开始提交，仍在 POST 超时内，不得复位（避免撞在途 Worker）。
        val e = env(SeedanceVideoState.SUBMITTING) { copy(submissionStartedAt = 900_000L) }
        e.coordinator.normalizeStaleInProgress()
        assertEquals(SeedanceVideoState.SUBMITTING, e.store.current(1).state)
    }

    @Test
    fun normalizeStaleSubmittingWithOldStartedAtIsReset() = runBlocking {
        // 900 秒前开始提交，超过 5 分钟阈值，应复位为歧义失败。
        val e = env(SeedanceVideoState.SUBMITTING) { copy(submissionStartedAt = 100_000L) }
        e.coordinator.normalizeStaleInProgress()
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, t.state)
        assertEquals(ERROR_CODE_AMBIGUOUS_POST, t.errorCode)
    }

    @Test
    fun normalizeStaleDownloadingResetsToPending() = runBlocking {
        val e = env(SeedanceVideoState.DOWNLOADING)
        e.coordinator.normalizeStaleInProgress()
        assertEquals(SeedanceVideoState.DOWNLOAD_PENDING, e.store.current(1).state)
    }

    @Test
    fun expiredUrlTransitionsToExpired() = runBlocking {
        val e = env(SeedanceVideoState.DOWNLOAD_PENDING) {
            copy(remoteVideoUrl = "https://v/1.mp4", remoteVideoUrlExpiresAt = 999_999L) // < clockNow
        }
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.EXPIRED, t.state)
        assertEquals("URL_EXPIRED", t.errorCode)
    }

    @Test
    fun diskFullFailsDownload() = runBlocking {
        val e = env(SeedanceVideoState.DOWNLOAD_PENDING) {
            copy(remoteVideoUrl = "https://v/1.mp4", remoteVideoUrlExpiresAt = Long.MAX_VALUE)
        }
        // 预创建同名目录占据 .part 路径 -> 写入失败（模拟磁盘满/占用）
        File(e.fileStore.taskDir("uuid-1"), "video.part").mkdirs()
        assertEquals(PipelineOutcome.WaitingForUser, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.FAILED_DOWNLOAD, t.state)
        assertEquals("DOWNLOAD", t.errorStage)
    }

    @Test
    fun existingValidFileSkipsRedownload() = runBlocking {
        val e = env(SeedanceVideoState.DOWNLOAD_PENDING) {
            copy(remoteVideoUrl = "https://v/1.mp4", remoteVideoUrlExpiresAt = Long.MAX_VALUE)
        }
        val data = mp4TestBytes()
        val saved = e.fileStore.save("uuid-1", "video/mp4", data.size.toLong(), ByteArrayInputStream(data)).getOrThrow()
        e.store.update(e.store.current(1).copy(videoSha256 = saved.sha256))
        e.downloader.bytes = null // 若误触下载会拿到 null -> 失败
        assertEquals(PipelineOutcome.Complete, e.coordinator.advance(1))
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.READY, t.state)
        assertEquals(saved.sha256, t.videoSha256)
    }

    @Test
    fun partialPartNotTreatedAsReady() = runBlocking {
        val e = env(SeedanceVideoState.DOWNLOAD_PENDING) {
            copy(remoteVideoUrl = "https://v/1.mp4", remoteVideoUrlExpiresAt = Long.MAX_VALUE)
        }
        // 残留 .part（无最终成品）
        val dir = e.fileStore.taskDir("uuid-1")
        dir.mkdirs()
        File(dir, "video.part").writeBytes(mp4TestBytes())
        // 真实下载到新文件 -> 仍应完成 READY（.part 不被误判为成品，且会被覆盖）
        assertEquals(PipelineOutcome.Complete, e.coordinator.advance(1))
        assertEquals(SeedanceVideoState.READY, e.store.current(1).state)
    }

    @Test
    fun pollTransientFailureKeepsRecoverableState() = runBlocking {
        val e = env(SeedanceVideoState.QUEUED) { copy(remoteTaskId = "remote-1") }
        e.submitter.getError = { SeedanceApiException(SeedanceError.AMBIGUOUS_TRANSPORT, "网络错误") }
        assertTrue(e.coordinator.advance(1) is PipelineOutcome.Reschedule)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.QUEUED, t.state)
        assertEquals(1, t.automaticRetryCount)
        assertNotNull(t.nextRetryAt)
    }

    @Test
    fun pollRetryHonorsRetryAfterMillis() = runBlocking {
        val e = env(SeedanceVideoState.QUEUED) { copy(remoteTaskId = "remote-1") }
        e.submitter.getError = {
            SeedanceApiException(SeedanceError.TRANSIENT_429_5XX, "繁忙", retryAfterMillis = 2_000L)
        }
        val outcome = e.coordinator.advance(1)
        assertTrue(outcome is PipelineOutcome.Reschedule)
        assertEquals(2_000L, (outcome as PipelineOutcome.Reschedule).delayMillis)
        val t = e.store.current(1)
        assertEquals(SeedanceVideoState.QUEUED, t.state)
        assertEquals(1, t.automaticRetryCount)
    }

    @Test
    fun normalizeStalePromptingResetsToPending() = runBlocking {
        val e = env(SeedanceVideoState.PROMPTING)
        e.coordinator.normalizeStaleInProgress()
        assertEquals(SeedanceVideoState.PROMPT_PENDING, e.store.current(1).state)
    }

    @Test
    fun advanceOnInFlightStateIsNoop() = runBlocking {
        val e1 = env(SeedanceVideoState.PROMPTING)
        assertEquals(PipelineOutcome.Complete, e1.coordinator.advance(1))
        assertEquals(SeedanceVideoState.PROMPTING, e1.store.current(1).state)

        val e2 = env(SeedanceVideoState.SUBMITTING)
        assertEquals(PipelineOutcome.Complete, e2.coordinator.advance(1))
        assertEquals(SeedanceVideoState.SUBMITTING, e2.store.current(1).state)

        val e3 = env(SeedanceVideoState.DOWNLOADING)
        assertEquals(PipelineOutcome.Complete, e3.coordinator.advance(1))
        assertEquals(SeedanceVideoState.DOWNLOADING, e3.store.current(1).state)
    }

    @Test
    fun missingTaskIsComplete() = runBlocking {
        val e = env(SeedanceVideoState.PROMPT_PENDING)
        assertEquals(PipelineOutcome.Complete, e.coordinator.advance(999L))
    }

    @Test
    fun readyIsTerminal() = runBlocking {
        val e = env(SeedanceVideoState.READY)
        assertEquals(PipelineOutcome.Complete, e.coordinator.advance(1))
    }

    companion object {
        fun mp4TestBytes(payload: ByteArray = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)): ByteArray =
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + payload
    }
}
