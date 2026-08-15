package com.rhodesisland.terminal.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SeedanceVideoDao 仪器测试（Task 2）。
 *
 * 内存库按 v5 最新 schema 直接创建（不经迁移）；覆盖：
 * - insertIgnore 的 (sourceAssistantMessageId, triggerType) 唯一性；
 * - remoteTaskId 唯一索引的可空语义（多行 NULL 允许）；
 * - CAS 认领（claim）单赢者语义；
 * - 两个观察流排序（会话内 ASC / 全局 DESC）；
 * - 删除会话与聊天历史不级联删除视频行；
 * - 会话自动视频开关 updateAutoVideoEnabled 与默认值。
 */
@RunWith(AndroidJUnit4::class)
class SeedanceVideoDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun videoEntity(
        taskUuid: String = "uuid-${COUNTER++}",
        sourceConversationId: Long = 1,
        sourceAssistantMessageId: Long = 1,
        triggerType: String = "auto",
        state: String = SeedanceVideoState.SNAPSHOT_PENDING.storageKey,
        createdAt: Long = 1,
        remoteTaskId: String? = null,
        nextRetryAt: Long? = null,
    ) = SeedanceVideoEntity(
        taskUuid = taskUuid,
        triggerType = triggerType,
        sourceConversationId = sourceConversationId,
        sourceUserMessageId = null,
        sourceAssistantMessageId = sourceAssistantMessageId,
        characterIdSnapshot = "char-1",
        characterNameSnapshot = "阿米娅",
        characterRoleSnapshot = "罗德岛领袖",
        characterSystemPromptSnapshot = "你是阿米娅",
        userTextSnapshot = "你好",
        assistantTextSnapshot = "你好呀",
        sceneDescriptionSnapshot = "",
        promptBaseUrlSnapshot = "https://api.example.com/v1",
        promptModelSnapshot = "doubao-text-pro",
        promptJson = null,
        finalPrompt = null,
        characterImageSourceSnapshot = "asset://amiya.png",
        backgroundImageSourceSnapshot = null,
        characterImagePath = null,
        characterImageMime = null,
        characterImageSha256 = null,
        backgroundImagePath = null,
        backgroundImageMime = null,
        backgroundImageSha256 = null,
        modelVariant = SeedanceModelVariant.STANDARD.modelId,
        resolution = SeedanceResolution.P720.name,
        ratio = SeedanceRatio.PORTRAIT.apiValue,
        durationSeconds = 5,
        generateAudio = true,
        watermark = false,
        state = state,
        remoteStatus = null,
        generationAttempt = 0,
        submissionAttemptId = null,
        submissionStartedAt = null,
        requestFingerprint = null,
        remoteTaskId = remoteTaskId,
        remoteVideoUrl = null,
        remoteVideoUrlObservedAt = null,
        remoteVideoUrlExpiresAt = null,
        remoteRequestId = null,
        previousRemoteTasksJson = "",
        localVideoPath = null,
        videoMime = null,
        videoByteSize = null,
        videoSha256 = null,
        downloadedAt = null,
        automaticRetryCount = 0,
        nextRetryAt = nextRetryAt,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = false,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun insertIgnore_enforcesUniquenessBySourceAssistantMessageIdAndTriggerType() = runBlocking {
        val dao = db.seedanceVideoDao()
        assertTrue(dao.insertIgnore(videoEntity(taskUuid = "u1", sourceAssistantMessageId = 11)) > 0)
        // 同一助手回复 + 同一触发类型：IGNORE，返回 -1，不产生第二行
        assertEquals(-1L, dao.insertIgnore(videoEntity(taskUuid = "u2", sourceAssistantMessageId = 11)))
        // 不同触发类型 / 不同助手回复：正常插入
        assertTrue(dao.insertIgnore(videoEntity(taskUuid = "u3", sourceAssistantMessageId = 11, triggerType = "manual")) > 0)
        assertTrue(dao.insertIgnore(videoEntity(taskUuid = "u4", sourceAssistantMessageId = 12)) > 0)
        assertEquals(3, dao.observeAll().first().size)
    }

    @Test
    fun insertIgnore_allowsMultipleRowsWithNullRemoteTaskId() = runBlocking {
        // SQLite 唯一索引允许任意多行 NULL：未提交远端前 remoteTaskId 恒为 null，必须可共存。
        val dao = db.seedanceVideoDao()
        assertTrue(dao.insertIgnore(videoEntity(taskUuid = "u1", sourceAssistantMessageId = 11)) > 0)
        assertTrue(dao.insertIgnore(videoEntity(taskUuid = "u2", sourceAssistantMessageId = 12)) > 0)
        assertEquals(2, dao.observeAll().first().size)
    }

    @Test
    fun claim_casUpdateSingleWinner() = runBlocking {
        val dao = db.seedanceVideoDao()
        val id = dao.insertIgnore(
            videoEntity(taskUuid = "u1", state = SeedanceVideoState.PROMPT_PENDING.storageKey)
        )

        // 首次认领成功：state 变更 + updatedAt 刷新
        val claimedAt = System.currentTimeMillis()
        assertEquals(
            1,
            dao.claim(id, SeedanceVideoState.PROMPT_PENDING.storageKey, SeedanceVideoState.PROMPTING.storageKey, claimedAt)
        )
        val after = dao.getById(id)!!
        assertEquals(SeedanceVideoState.PROMPTING.storageKey, after.state)
        assertEquals(claimedAt, after.updatedAt)

        // 以旧状态再次认领失败（已被抢占）：第二个 Worker 不会重复进入
        assertEquals(
            0,
            dao.claim(id, SeedanceVideoState.PROMPT_PENDING.storageKey, SeedanceVideoState.PROMPTING.storageKey, claimedAt + 1)
        )
        // 状态不符的认领失败
        assertEquals(
            0,
            dao.claim(id, SeedanceVideoState.SUBMISSION_PENDING.storageKey, SeedanceVideoState.SUBMITTING.storageKey, claimedAt + 2)
        )
        // 不存在的行返回 0
        assertEquals(
            0,
            dao.claim(9999, SeedanceVideoState.PROMPT_PENDING.storageKey, SeedanceVideoState.PROMPTING.storageKey, claimedAt + 3)
        )
    }

    @Test
    fun observeByConversation_ordersByCreatedAtAscending() = runBlocking {
        val dao = db.seedanceVideoDao()
        // 故意乱序插入：ASC 顺序应由 createdAt 决定，与插入顺序无关
        dao.insertIgnore(videoEntity(taskUuid = "u3", sourceConversationId = 7, sourceAssistantMessageId = 13, createdAt = 300))
        dao.insertIgnore(videoEntity(taskUuid = "u1", sourceConversationId = 7, sourceAssistantMessageId = 11, createdAt = 100))
        dao.insertIgnore(videoEntity(taskUuid = "u2", sourceConversationId = 7, sourceAssistantMessageId = 12, createdAt = 200))
        dao.insertIgnore(videoEntity(taskUuid = "other", sourceConversationId = 8, sourceAssistantMessageId = 21, createdAt = 50))

        val ids = dao.observeByConversation(7).first().map { it.taskUuid }
        assertEquals(listOf("u1", "u2", "u3"), ids)
    }

    @Test
    fun observeAll_ordersByCreatedAtDescending() = runBlocking {
        val dao = db.seedanceVideoDao()
        dao.insertIgnore(videoEntity(taskUuid = "u1", sourceConversationId = 7, sourceAssistantMessageId = 11, createdAt = 100))
        dao.insertIgnore(videoEntity(taskUuid = "u3", sourceConversationId = 8, sourceAssistantMessageId = 13, createdAt = 300))
        dao.insertIgnore(videoEntity(taskUuid = "u2", sourceConversationId = 8, sourceAssistantMessageId = 12, createdAt = 200))

        val ids = dao.observeAll().first().map { it.taskUuid }
        assertEquals(listOf("u3", "u2", "u1"), ids)
    }

    @Test
    fun deletingConversationAndHistory_doesNotDeleteVideos() = runBlocking {
        val convId = db.conversationDao().insert(
            ConversationEntity(characterId = "c1", title = "t", createdAt = 1, updatedAt = 1)
        )
        val msgId = db.chatDao().insert(
            ChatHistoryEntity(
                characterId = "c1", conversationId = convId,
                role = "assistant", content = "hi", timestamp = 2,
            )
        )
        val videoId = db.seedanceVideoDao().insertIgnore(
            videoEntity(sourceConversationId = convId, sourceAssistantMessageId = msgId)
        )

        db.conversationDao().deleteConversation(convId)

        // 会话与聊天记录已删除……
        assertNull(db.conversationDao().getById(convId))
        assertEquals(0, db.chatDao().count(convId))
        // ……但视频行保留：不声明级联外键，视频快照不随聊天删除/裁剪而丢失
        assertNotNull(db.seedanceVideoDao().getById(videoId))
    }

    @Test
    fun updateAutoVideoEnabled_togglesConversationFlag() = runBlocking {
        val convId = db.conversationDao().insert(
            ConversationEntity(characterId = "c1", title = "t", createdAt = 1, updatedAt = 1)
        )
        // 新会话默认关闭
        assertFalse(db.conversationDao().getById(convId)!!.autoVideoEnabled)

        assertEquals(1, db.conversationDao().updateAutoVideoEnabled(convId, true))
        assertTrue(db.conversationDao().getById(convId)!!.autoVideoEnabled)

        assertEquals(1, db.conversationDao().updateAutoVideoEnabled(convId, false))
        assertFalse(db.conversationDao().getById(convId)!!.autoVideoEnabled)

        // 不存在的会话返回 0
        assertEquals(0, db.conversationDao().updateAutoVideoEnabled(9999, true))
    }

    @Test
    fun listRecoverable_returnsExactlyTheSevenAutoClaimableStates() = runBlocking {
        // 钉住 listRecoverable 的 7 个硬编码状态字面量（与 DAO SQL 一一对应）：
        // snapshot_pending / prompt_pending / submission_pending / queued / running /
        // cancel_requested / download_pending。PROMPTING/SUBMITTING/DOWNLOADING 由恢复流程
        // 显式重置，不在此直接扫描；终态与失败态一律不出现。
        val dao = db.seedanceVideoDao()
        val recoverable = listOf(
            SeedanceVideoState.SNAPSHOT_PENDING,
            SeedanceVideoState.PROMPT_PENDING,
            SeedanceVideoState.SUBMISSION_PENDING,
            SeedanceVideoState.QUEUED,
            SeedanceVideoState.RUNNING,
            SeedanceVideoState.CANCEL_REQUESTED,
            SeedanceVideoState.DOWNLOAD_PENDING,
        )
        recoverable.forEachIndexed { i, state ->
            dao.insertIgnore(
                videoEntity(taskUuid = "rec-$i", sourceAssistantMessageId = 100L + i, state = state.storageKey)
            )
        }
        val nonRecoverable = listOf(
            SeedanceVideoState.PROMPTING,
            SeedanceVideoState.SUBMITTING,
            SeedanceVideoState.DOWNLOADING,
            SeedanceVideoState.READY,
            SeedanceVideoState.CANCELLED,
            SeedanceVideoState.EXPIRED,
            SeedanceVideoState.FAILED_SNAPSHOT,
            SeedanceVideoState.FAILED_PROMPT,
            SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED,
            SeedanceVideoState.FAILED_SUBMISSION,
            SeedanceVideoState.FAILED_REMOTE,
            SeedanceVideoState.FAILED_QUERY,
            SeedanceVideoState.FAILED_DOWNLOAD,
        )
        nonRecoverable.forEachIndexed { i, state ->
            dao.insertIgnore(
                videoEntity(taskUuid = "not-$i", sourceAssistantMessageId = 200L + i, state = state.storageKey)
            )
        }

        val ids = dao.listRecoverable(now = 1L).map { it.taskUuid }.toSet()
        assertEquals((0..6).map { "rec-$it" }.toSet(), ids)
    }

    @Test
    fun listRecoverable_excludesRowsWithFutureNextRetryAt() = runBlocking {
        val dao = db.seedanceVideoDao()
        dao.insertIgnore(
            videoEntity(taskUuid = "due", sourceAssistantMessageId = 11, state = SeedanceVideoState.QUEUED.storageKey, nextRetryAt = 100L)
        )
        dao.insertIgnore(
            videoEntity(taskUuid = "future", sourceAssistantMessageId = 12, state = SeedanceVideoState.QUEUED.storageKey, nextRetryAt = 200L)
        )

        // now = 100：到期任务可见，退避未到的不可见
        assertEquals(listOf("due"), dao.listRecoverable(now = 100L).map { it.taskUuid })
        // 放宽到 200 后两者都可见
        assertEquals(setOf("due", "future"), dao.listRecoverable(now = 200L).map { it.taskUuid }.toSet())
    }

    companion object {
        private var COUNTER = 0
    }
}
