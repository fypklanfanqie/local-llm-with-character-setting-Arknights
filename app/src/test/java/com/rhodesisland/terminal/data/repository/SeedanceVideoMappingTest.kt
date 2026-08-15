package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.SeedanceVideoEntity
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.data.model.prepareRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SeedanceVideo 实体<->领域映射测试（Task 2）。
 *
 * 纯 JVM 单测：映射函数为顶层 internal 纯函数（[toDomain]/[toEntity]/
 * [AutoVideoOutboxDraft.toEntity]），无 Android 依赖。
 *
 * 核心断言：
 * - 全字段（含两个来源快照列）双向往返不丢字段；
 * - state/modelVariant/resolution/ratio 按存储键往返；
 * - 未知存储键保守回落（state -> FAILED_SUBMISSION，其余 -> 配置默认值）；
 * - previousRemoteTasksJson 原样透传（JSON 内容逐字节保留）；
 * - outbox 草稿落库为 SNAPSHOT_PENDING，Worker 后续字段全部置空/零值。
 */
class SeedanceVideoMappingTest {

    /** 全字段实体样例（含全部快照与终态字段）。 */
    private fun entity() = SeedanceVideoEntity(
        id = 42,
        taskUuid = "uuid-42",
        triggerType = "auto",
        sourceConversationId = 7,
        sourceUserMessageId = 11,
        sourceAssistantMessageId = 13,
        characterIdSnapshot = "char-1",
        characterNameSnapshot = "阿米娅",
        characterRoleSnapshot = "罗德岛领袖",
        characterSystemPromptSnapshot = "你是阿米娅，冷静可靠。",
        userTextSnapshot = "今天天气如何？",
        assistantTextSnapshot = "天气不错，适合出去走走。",
        sceneDescriptionSnapshot = "夜晚的街道，霓虹闪烁",
        promptBaseUrlSnapshot = "https://api.example.com/v1",
        promptModelSnapshot = "doubao-text-pro",
        promptJson = "{\"finalPrompt\":\"p1\"}",
        finalPrompt = "镜头缓慢推进，阿米娅站在街角。",
        characterImageSourceSnapshot = "asset://amiya.png",
        backgroundImageSourceSnapshot = "file:///data/bg.png",
        characterImagePath = "filesDir/seedance/tasks/uuid-42/references/character.png",
        characterImageMime = "image/png",
        characterImageSha256 = "abc123",
        backgroundImagePath = "filesDir/seedance/tasks/uuid-42/references/background.png",
        backgroundImageMime = "image/png",
        backgroundImageSha256 = "def456",
        modelVariant = SeedanceModelVariant.FAST.modelId,
        resolution = SeedanceResolution.P480.name,
        ratio = SeedanceRatio.LANDSCAPE.apiValue,
        durationSeconds = 8,
        generateAudio = true,
        watermark = false,
        state = SeedanceVideoState.RUNNING.storageKey,
        remoteStatus = "running",
        generationAttempt = 1,
        submissionAttemptId = "attempt-1",
        submissionStartedAt = 1000,
        requestFingerprint = "fp-1",
        remoteTaskId = "t-123",
        remoteVideoUrl = "https://cdn.example.com/v.mp4?expires=1",
        remoteVideoUrlObservedAt = 2000,
        remoteVideoUrlExpiresAt = 3000,
        remoteRequestId = "req-1",
        previousRemoteTasksJson = """[{"remoteTaskId":"t-old","archivedAt":1700000000000}]""",
        localVideoPath = "filesDir/seedance/tasks/uuid-42/output/video.mp4",
        videoMime = "video/mp4",
        videoByteSize = 4096,
        videoSha256 = "sha-1",
        downloadedAt = 4000,
        automaticRetryCount = 2,
        nextRetryAt = 5000,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = true,
        createdAt = 6000,
        updatedAt = 7000,
    )

    /** 全字段领域样例（与 [entity] 语义一致）。 */
    private fun domain() = SeedanceVideo(
        id = 42,
        taskUuid = "uuid-42",
        triggerType = "auto",
        sourceConversationId = 7,
        sourceUserMessageId = 11,
        sourceAssistantMessageId = 13,
        characterIdSnapshot = "char-1",
        characterNameSnapshot = "阿米娅",
        characterRoleSnapshot = "罗德岛领袖",
        characterSystemPromptSnapshot = "你是阿米娅，冷静可靠。",
        userTextSnapshot = "今天天气如何？",
        assistantTextSnapshot = "天气不错，适合出去走走。",
        sceneDescriptionSnapshot = "夜晚的街道，霓虹闪烁",
        promptBaseUrlSnapshot = "https://api.example.com/v1",
        promptModelSnapshot = "doubao-text-pro",
        promptJson = "{\"finalPrompt\":\"p1\"}",
        finalPrompt = "镜头缓慢推进，阿米娅站在街角。",
        characterImageSourceSnapshot = "asset://amiya.png",
        backgroundImageSourceSnapshot = "file:///data/bg.png",
        characterImagePath = "filesDir/seedance/tasks/uuid-42/references/character.png",
        characterImageMime = "image/png",
        characterImageSha256 = "abc123",
        backgroundImagePath = "filesDir/seedance/tasks/uuid-42/references/background.png",
        backgroundImageMime = "image/png",
        backgroundImageSha256 = "def456",
        modelVariant = SeedanceModelVariant.FAST,
        resolution = SeedanceResolution.P480,
        ratio = SeedanceRatio.LANDSCAPE,
        durationSeconds = 8,
        generateAudio = true,
        watermark = false,
        state = SeedanceVideoState.RUNNING,
        remoteStatus = "running",
        generationAttempt = 1,
        submissionAttemptId = "attempt-1",
        submissionStartedAt = 1000,
        requestFingerprint = "fp-1",
        remoteTaskId = "t-123",
        remoteVideoUrl = "https://cdn.example.com/v.mp4?expires=1",
        remoteVideoUrlObservedAt = 2000,
        remoteVideoUrlExpiresAt = 3000,
        remoteRequestId = "req-1",
        previousRemoteTasksJson = """[{"remoteTaskId":"t-old","archivedAt":1700000000000}]""",
        localVideoPath = "filesDir/seedance/tasks/uuid-42/output/video.mp4",
        videoMime = "video/mp4",
        videoByteSize = 4096,
        videoSha256 = "sha-1",
        downloadedAt = 4000,
        automaticRetryCount = 2,
        nextRetryAt = 5000,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = true,
        createdAt = 6000,
        updatedAt = 7000,
    )

    // ===== 全字段映射 =====

    @Test
    fun entityToDomain_mapsAllFields() {
        val e = entity()
        val d = e.toDomain()

        assertEquals(42, d.id)
        assertEquals("uuid-42", d.taskUuid)
        assertEquals("auto", d.triggerType)
        assertEquals(7, d.sourceConversationId)
        assertEquals(11L, d.sourceUserMessageId)
        assertEquals(13L, d.sourceAssistantMessageId)
        // 快照字段（含两个来源快照列）
        assertEquals("char-1", d.characterIdSnapshot)
        assertEquals("阿米娅", d.characterNameSnapshot)
        assertEquals("罗德岛领袖", d.characterRoleSnapshot)
        assertEquals("你是阿米娅，冷静可靠。", d.characterSystemPromptSnapshot)
        assertEquals("今天天气如何？", d.userTextSnapshot)
        assertEquals("天气不错，适合出去走走。", d.assistantTextSnapshot)
        assertEquals("夜晚的街道，霓虹闪烁", d.sceneDescriptionSnapshot)
        assertEquals("https://api.example.com/v1", d.promptBaseUrlSnapshot)
        assertEquals("doubao-text-pro", d.promptModelSnapshot)
        assertEquals("asset://amiya.png", d.characterImageSourceSnapshot)
        assertEquals("file:///data/bg.png", d.backgroundImageSourceSnapshot)
        // 提示词与图片产物
        assertEquals("{\"finalPrompt\":\"p1\"}", d.promptJson)
        assertEquals("镜头缓慢推进，阿米娅站在街角。", d.finalPrompt)
        assertEquals("filesDir/seedance/tasks/uuid-42/references/character.png", d.characterImagePath)
        assertEquals("image/png", d.characterImageMime)
        assertEquals("abc123", d.characterImageSha256)
        assertEquals("filesDir/seedance/tasks/uuid-42/references/background.png", d.backgroundImagePath)
        assertEquals("image/png", d.backgroundImageMime)
        assertEquals("def456", d.backgroundImageSha256)
        // 参数快照（枚举按存储键还原）
        assertEquals(SeedanceModelVariant.FAST, d.modelVariant)
        assertEquals(SeedanceResolution.P480, d.resolution)
        assertEquals(SeedanceRatio.LANDSCAPE, d.ratio)
        assertEquals(8, d.durationSeconds)
        assertTrue(d.generateAudio)
        assertEquals(false, d.watermark)
        // 远端状态
        assertEquals(SeedanceVideoState.RUNNING, d.state)
        assertEquals("running", d.remoteStatus)
        assertEquals(1, d.generationAttempt)
        assertEquals("attempt-1", d.submissionAttemptId)
        assertEquals(1000L, d.submissionStartedAt)
        assertEquals("fp-1", d.requestFingerprint)
        assertEquals("t-123", d.remoteTaskId)
        assertEquals("https://cdn.example.com/v.mp4?expires=1", d.remoteVideoUrl)
        assertEquals(2000L, d.remoteVideoUrlObservedAt)
        assertEquals(3000L, d.remoteVideoUrlExpiresAt)
        assertEquals("req-1", d.remoteRequestId)
        // 本地产物
        assertEquals("filesDir/seedance/tasks/uuid-42/output/video.mp4", d.localVideoPath)
        assertEquals("video/mp4", d.videoMime)
        assertEquals(4096L, d.videoByteSize)
        assertEquals("sha-1", d.videoSha256)
        assertEquals(4000L, d.downloadedAt)
        // 重试/错误/元信息
        assertEquals(2, d.automaticRetryCount)
        assertEquals(5000L, d.nextRetryAt)
        assertNull(d.errorStage)
        assertNull(d.errorCode)
        assertNull(d.errorMessage)
        assertNull(d.retryDisposition)
        assertTrue(d.requiresCostConfirmation)
        assertEquals(6000L, d.createdAt)
        assertEquals(7000L, d.updatedAt)
    }

    @Test
    fun domainToEntity_mapsAllFields() {
        val d = domain()
        val e = d.toEntity()

        assertEquals(42, e.id)
        assertEquals("uuid-42", e.taskUuid)
        assertEquals("auto", e.triggerType)
        assertEquals(7, e.sourceConversationId)
        assertEquals(11L, e.sourceUserMessageId)
        assertEquals(13L, e.sourceAssistantMessageId)
        assertEquals("char-1", e.characterIdSnapshot)
        assertEquals("阿米娅", e.characterNameSnapshot)
        assertEquals("罗德岛领袖", e.characterRoleSnapshot)
        assertEquals("你是阿米娅，冷静可靠。", e.characterSystemPromptSnapshot)
        assertEquals("今天天气如何？", e.userTextSnapshot)
        assertEquals("天气不错，适合出去走走。", e.assistantTextSnapshot)
        assertEquals("夜晚的街道，霓虹闪烁", e.sceneDescriptionSnapshot)
        assertEquals("https://api.example.com/v1", e.promptBaseUrlSnapshot)
        assertEquals("doubao-text-pro", e.promptModelSnapshot)
        assertEquals("asset://amiya.png", e.characterImageSourceSnapshot)
        assertEquals("file:///data/bg.png", e.backgroundImageSourceSnapshot)
        // 枚举按存储键持久化
        assertEquals(SeedanceModelVariant.FAST.modelId, e.modelVariant)
        assertEquals("P480", e.resolution)
        assertEquals("16:9", e.ratio)
        assertEquals(8, e.durationSeconds)
        assertTrue(e.generateAudio)
        assertEquals(false, e.watermark)
        assertEquals(SeedanceVideoState.RUNNING.storageKey, e.state)
        assertEquals("running", e.remoteStatus)
        assertEquals(1, e.generationAttempt)
        assertEquals("attempt-1", e.submissionAttemptId)
        assertEquals(1000L, e.submissionStartedAt)
        assertEquals("fp-1", e.requestFingerprint)
        assertEquals("t-123", e.remoteTaskId)
        assertEquals("https://cdn.example.com/v.mp4?expires=1", e.remoteVideoUrl)
        assertEquals(2000L, e.remoteVideoUrlObservedAt)
        assertEquals(3000L, e.remoteVideoUrlExpiresAt)
        assertEquals("req-1", e.remoteRequestId)
        assertEquals("""[{"remoteTaskId":"t-old","archivedAt":1700000000000}]""", e.previousRemoteTasksJson)
        assertEquals("filesDir/seedance/tasks/uuid-42/output/video.mp4", e.localVideoPath)
        assertEquals("video/mp4", e.videoMime)
        assertEquals(4096L, e.videoByteSize)
        assertEquals("sha-1", e.videoSha256)
        assertEquals(4000L, e.downloadedAt)
        assertEquals(2, e.automaticRetryCount)
        assertEquals(5000L, e.nextRetryAt)
        assertNull(e.errorStage)
        assertNull(e.errorCode)
        assertNull(e.errorMessage)
        assertNull(e.retryDisposition)
        assertTrue(e.requiresCostConfirmation)
        assertEquals(6000L, e.createdAt)
        assertEquals(7000L, e.updatedAt)
    }

    @Test
    fun roundTrip_entityDomainEntity_preservesEverything() {
        assertEquals(entity(), entity().toDomain().toEntity())
    }

    @Test
    fun roundTrip_domainEntityDomain_preservesEverything() {
        assertEquals(domain(), domain().toEntity().toDomain())
    }

    // ===== 枚举存储键 =====

    @Test
    fun stateStorageKeys_roundTrip() {
        for (state in SeedanceVideoState.entries) {
            assertEquals(state, SeedanceVideoState.fromStorageKey(state.storageKey))
        }
    }

    @Test
    fun configEnumStorageKeys_roundTrip() {
        for (variant in SeedanceModelVariant.entries) {
            assertEquals(variant, SeedanceModelVariant.fromStorageKey(variant.storageKey))
        }
        for (resolution in SeedanceResolution.entries) {
            assertEquals(resolution, SeedanceResolution.fromStorageKey(resolution.storageKey))
        }
        for (ratio in SeedanceRatio.entries) {
            assertEquals(ratio, SeedanceRatio.fromStorageKey(ratio.storageKey))
        }
    }

    @Test
    fun unknownStateStorageKey_fallsBackToFailedSubmission() {
        // 未知状态既不冒充 READY（播放未校验文件），也不被 Worker 自动认领（重复提交）。
        for (bad in listOf("bogus", "")) {
            val e = entity().copy(state = bad)
            assertEquals(SeedanceVideoState.DEFAULT, e.toDomain().state)
            assertEquals(SeedanceVideoState.FAILED_SUBMISSION, e.toDomain().state)
        }
        // state 列 NOT NULL 不可为空；null 分支由解析器防御性回落（Task 1 已测，此处锁定行为）。
        assertEquals(SeedanceVideoState.FAILED_SUBMISSION, SeedanceVideoState.fromStorageKey(null))
    }

    @Test
    fun unknownConfigStorageKeys_fallBackToSeedanceDefaults() {
        val e = entity().copy(
            modelVariant = "bogus-variant",
            resolution = "P8K",
            ratio = "999:1",
        )
        val d = e.toDomain()
        assertEquals(SeedanceModelVariant.STANDARD, d.modelVariant)
        assertEquals(SeedanceResolution.P720, d.resolution)
        assertEquals(SeedanceRatio.PORTRAIT, d.ratio)
    }

    // ===== JSON previousRemoteTasks =====

    @Test
    fun previousRemoteTasksJson_preservedVerbatimInBothDirections() {
        // 归档 JSON 由 Task 6 定义结构；映射层逐字节透传，不做解析/重排，
        // 保证历史归档内容（含转义/数字精度）不因往返变化。
        val json = """[{"remoteTaskId":"t-1","archivedAt":1700000000000},{"remoteTaskId":"t-2","archivedAt":1700000000001}]"""
        val e = entity().copy(previousRemoteTasksJson = json)
        assertEquals(json, e.toDomain().previousRemoteTasksJson)
        assertEquals(json, e.toDomain().toEntity().previousRemoteTasksJson)
    }

    // ===== outbox 草稿 =====

    @Test
    fun outboxDraft_toEntity_persistsSnapshotsAndPendingDefaults() {
        val draft = AutoVideoOutboxDraft(
            taskUuid = "uuid-9",
            triggerType = "auto",
            sourceConversationId = 7,
            sourceUserMessageId = 11,
            characterIdSnapshot = "char-1",
            characterNameSnapshot = "阿米娅",
            characterRoleSnapshot = "罗德岛领袖",
            characterSystemPromptSnapshot = "你是阿米娅",
            userTextSnapshot = "你好",
            assistantTextSnapshot = "你好呀",
            sceneDescriptionSnapshot = "夜晚的街道",
            promptBaseUrlSnapshot = "https://api.example.com/v1",
            promptModelSnapshot = "doubao-text-pro",
            characterImageSourceSnapshot = "asset://amiya.png",
            backgroundImageSourceSnapshot = "file:///data/bg.png",
            modelVariant = SeedanceModelVariant.FAST,
            resolution = SeedanceResolution.P480,
            ratio = SeedanceRatio.LANDSCAPE,
            durationSeconds = 8,
            generateAudio = true,
            watermark = false,
        )

        val e = draft.toEntity(sourceAssistantMessageId = 42)

        // 身份与来源
        assertEquals(0, e.id)
        assertEquals("uuid-9", e.taskUuid)
        assertEquals("auto", e.triggerType)
        assertEquals(7, e.sourceConversationId)
        assertEquals(11L, e.sourceUserMessageId)
        assertEquals(42L, e.sourceAssistantMessageId)
        // 快照原样落库
        assertEquals("char-1", e.characterIdSnapshot)
        assertEquals("阿米娅", e.characterNameSnapshot)
        assertEquals("罗德岛领袖", e.characterRoleSnapshot)
        assertEquals("你是阿米娅", e.characterSystemPromptSnapshot)
        assertEquals("你好", e.userTextSnapshot)
        assertEquals("你好呀", e.assistantTextSnapshot)
        assertEquals("夜晚的街道", e.sceneDescriptionSnapshot)
        assertEquals("https://api.example.com/v1", e.promptBaseUrlSnapshot)
        assertEquals("doubao-text-pro", e.promptModelSnapshot)
        assertEquals("asset://amiya.png", e.characterImageSourceSnapshot)
        assertEquals("file:///data/bg.png", e.backgroundImageSourceSnapshot)
        // 参数快照（枚举 -> 存储键）
        assertEquals(SeedanceModelVariant.FAST.modelId, e.modelVariant)
        assertEquals("P480", e.resolution)
        assertEquals("16:9", e.ratio)
        assertEquals(8, e.durationSeconds)
        assertTrue(e.generateAudio)
        assertEquals(false, e.watermark)
        // 初始状态：待复制参考图快照
        assertEquals(SeedanceVideoState.SNAPSHOT_PENDING.storageKey, e.state)
        // Worker 后续填充的字段全部为空/零值
        assertNull(e.promptJson)
        assertNull(e.finalPrompt)
        assertNull(e.characterImagePath)
        assertNull(e.characterImageMime)
        assertNull(e.characterImageSha256)
        assertNull(e.backgroundImagePath)
        assertNull(e.backgroundImageMime)
        assertNull(e.backgroundImageSha256)
        assertNull(e.remoteStatus)
        assertEquals(0, e.generationAttempt)
        assertNull(e.submissionAttemptId)
        assertNull(e.submissionStartedAt)
        assertNull(e.requestFingerprint)
        assertNull(e.remoteTaskId)
        assertNull(e.remoteVideoUrl)
        assertNull(e.remoteVideoUrlObservedAt)
        assertNull(e.remoteVideoUrlExpiresAt)
        assertNull(e.remoteRequestId)
        assertEquals("", e.previousRemoteTasksJson)
        assertNull(e.localVideoPath)
        assertNull(e.videoMime)
        assertNull(e.videoByteSize)
        assertNull(e.videoSha256)
        assertNull(e.downloadedAt)
        assertEquals(0, e.automaticRetryCount)
        assertNull(e.nextRetryAt)
        assertNull(e.errorStage)
        assertNull(e.errorCode)
        assertNull(e.errorMessage)
        assertNull(e.retryDisposition)
        assertEquals(false, e.requiresCostConfirmation)
        // 时间戳：同一次落库内一致
        assertEquals(e.updatedAt, e.createdAt)
        assertTrue(e.createdAt > 0)
    }

    @Test
    fun outboxDraft_toEntity_allowsNullableSources() {
        val draft = AutoVideoOutboxDraft(
            taskUuid = "uuid-10",
            triggerType = "auto",
            sourceConversationId = 7,
            sourceUserMessageId = null,
            characterIdSnapshot = "char-1",
            characterNameSnapshot = "阿米娅",
            characterRoleSnapshot = "罗德岛领袖",
            characterSystemPromptSnapshot = "你是阿米娅",
            userTextSnapshot = "你好",
            assistantTextSnapshot = "你好呀",
            sceneDescriptionSnapshot = "",
            promptBaseUrlSnapshot = "https://api.example.com/v1",
            promptModelSnapshot = "doubao-text-pro",
            characterImageSourceSnapshot = "asset://amiya.png",
            backgroundImageSourceSnapshot = null,
            modelVariant = SeedanceModelVariant.STANDARD,
            resolution = SeedanceResolution.P720,
            ratio = SeedanceRatio.PORTRAIT,
            durationSeconds = 5,
            generateAudio = true,
            watermark = false,
        )

        val e = draft.toEntity(sourceAssistantMessageId = 1)
        assertNull(e.sourceUserMessageId)
        assertNull(e.backgroundImageSourceSnapshot)
        assertEquals("", e.sceneDescriptionSnapshot)
    }

    // ===== 手动重试准备（prepareRetry，Task 10 验收） =====

    /**
     * 重新生成重试（目标 SUBMISSION_PENDING）：归档当前 remoteTaskId 追加进
     * previousRemoteTasksJson、generationAttempt += 1；重置无条件生效。
     */
    @Test
    fun prepareRetry_submissionPending_archivesRemoteTaskAndBumpsAttempt() {
        val v = domain().copy(
            state = SeedanceVideoState.FAILED_REMOTE,
            remoteTaskId = "t-123",
            previousRemoteTasksJson = """["t-old-1","t-old-2"]""",
            generationAttempt = 1,
            automaticRetryCount = 2,
            requiresCostConfirmation = true,
        )

        val prepared = v.prepareRetry(SeedanceVideoState.SUBMISSION_PENDING)

        // 归档追加当前 remoteTaskId（JSON 数组按序）
        assertEquals("""["t-old-1","t-old-2","t-123"]""", prepared.previousRemoteTasksJson)
        assertEquals(2, prepared.generationAttempt)
        // 重置对所有手动重试无条件生效
        assertEquals(0, prepared.automaticRetryCount)
        assertEquals(false, prepared.requiresCostConfirmation)
    }

    /** 继续查询（FAILED_QUERY -> QUEUED）：复用同一 remoteTaskId，不归档也不加次数。 */
    @Test
    fun prepareRetry_continueQuery_doesNotArchiveActiveTaskNorBump() {
        val v = domain().copy(
            state = SeedanceVideoState.FAILED_QUERY,
            remoteTaskId = "t-active",
            previousRemoteTasksJson = """["t-old-1"]""",
            generationAttempt = 3,
            automaticRetryCount = 2,
            requiresCostConfirmation = true,
        )

        val prepared = v.prepareRetry(SeedanceVideoState.QUEUED)

        assertEquals("""["t-old-1"]""", prepared.previousRemoteTasksJson)
        assertEquals(3, prepared.generationAttempt)
        assertEquals(0, prepared.automaticRetryCount)
        assertEquals(false, prepared.requiresCostConfirmation)
    }

    /** 重新下载（FAILED_DOWNLOAD -> DOWNLOAD_PENDING）：复用同一 remoteTaskId，不归档也不加次数。 */
    @Test
    fun prepareRetry_redownload_doesNotArchiveActiveTaskNorBump() {
        val v = domain().copy(
            state = SeedanceVideoState.FAILED_DOWNLOAD,
            remoteTaskId = "t-active",
            previousRemoteTasksJson = """["t-old-1"]""",
            generationAttempt = 3,
            automaticRetryCount = 2,
            requiresCostConfirmation = true,
        )

        val prepared = v.prepareRetry(SeedanceVideoState.DOWNLOAD_PENDING)

        assertEquals("""["t-old-1"]""", prepared.previousRemoteTasksJson)
        assertEquals(3, prepared.generationAttempt)
        assertEquals(0, prepared.automaticRetryCount)
        assertEquals(false, prepared.requiresCostConfirmation)
    }

    /** 快照/提示词重试：从未创建远端任务（remoteTaskId 为空），不归档也不加次数。 */
    @Test
    fun prepareRetry_snapshotOrPrompt_noRemoteTask_doesNotArchiveNorBump() {
        val snap = domain().copy(
            state = SeedanceVideoState.FAILED_SNAPSHOT,
            remoteTaskId = null,
            previousRemoteTasksJson = "",
            generationAttempt = 0,
            automaticRetryCount = 2,
            requiresCostConfirmation = true,
        )

        val preparedSnap = snap.prepareRetry(SeedanceVideoState.SNAPSHOT_PENDING)
        assertEquals("", preparedSnap.previousRemoteTasksJson)
        assertEquals(0, preparedSnap.generationAttempt)
        assertEquals(0, preparedSnap.automaticRetryCount)
        assertEquals(false, preparedSnap.requiresCostConfirmation)

        val prompt = domain().copy(
            state = SeedanceVideoState.FAILED_PROMPT,
            remoteTaskId = null,
            previousRemoteTasksJson = "",
            generationAttempt = 1,
            automaticRetryCount = 2,
            requiresCostConfirmation = true,
        )

        val preparedPrompt = prompt.prepareRetry(SeedanceVideoState.PROMPT_PENDING)
        assertEquals("", preparedPrompt.previousRemoteTasksJson)
        assertEquals(1, preparedPrompt.generationAttempt)
        assertEquals(0, preparedPrompt.automaticRetryCount)
        assertEquals(false, preparedPrompt.requiresCostConfirmation)
    }
}
