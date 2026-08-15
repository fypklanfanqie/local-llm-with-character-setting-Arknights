package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room MIGRATION_4_5 测试（Task 2）。
 *
 * 仪器测试（需 Android SQLite）：手工建一张 v4 库（conversation 无 autoVideoEnabled；
 * chat_history 含 modelContent + completionState），写入历史数据后交给 Room 打开——
 * Room 执行 [AppDatabase.MIGRATION_4_5] 并立即做 schema 校验，任何列/索引不匹配都会抛
 * IllegalStateException（比手工断言更强：迁移 DDL 与实体逐列核对）。
 *
 * 不依赖 Room 导出的 schema JSON（exportSchema=false）。
 */
@RunWith(AndroidJUnit4::class)
class Migration4To5Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val dbName = "migration-4-to-5-test.db"

    /** v4 时期的 conversation 建表语句（无 autoVideoEnabled 列）。 */
    private val v4CreateConversation = """
        CREATE TABLE conversation (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            characterId TEXT NOT NULL,
            title TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )
    """.trimIndent()

    /** v4 时期的 chat_history 建表语句（含 modelContent、completionState）。 */
    private val v4CreateChatHistory = """
        CREATE TABLE chat_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            characterId TEXT NOT NULL,
            conversationId INTEGER NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            imagesJson TEXT NOT NULL,
            filesJson TEXT NOT NULL,
            fileNamesJson TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            modelContent TEXT,
            completionState TEXT NOT NULL DEFAULT 'complete'
        )
    """.trimIndent()

    /**
     * 手工建 v4 库并写入历史数据，随后由 Room 打开执行 4->5 迁移 + schema 校验。
     * 返回已打开的 AppDatabase（调用方负责 close 与删除测试库）。
     */
    private fun openMigratedRoomDatabase(): AppDatabase {
        ctx.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v4CreateConversation)
                    db.execSQL(
                        "CREATE INDEX index_conversation_characterId_updatedAt " +
                            "ON conversation (characterId, updatedAt)"
                    )
                    db.execSQL(v4CreateChatHistory)
                    db.execSQL(
                        "CREATE INDEX index_chat_history_conversationId_timestamp " +
                            "ON chat_history (conversationId, timestamp)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO conversation (id, characterId, title, createdAt, updatedAt) " +
                "VALUES (7, 'c1', '旧对话', 100, 200)"
        )
        db.execSQL(
            "INSERT INTO chat_history (id, characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp, modelContent, completionState) " +
                "VALUES (11, 'c1', 7, 'assistant', 'display', '', '', '', 300, " +
                "'raw<think>x</think>', 'stopped_partial')"
        )
        helper.close()

        // Room 打开既有 v4 库：先跑 MIGRATION_4_5，再校验全部实体表/索引。
        return Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
    }

    private fun videoEntity(sourceConversationId: Long, sourceAssistantMessageId: Long) = SeedanceVideoEntity(
        taskUuid = "uuid-migration-test",
        triggerType = "auto",
        sourceConversationId = sourceConversationId,
        sourceUserMessageId = 11,
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
        state = SeedanceVideoState.SNAPSHOT_PENDING.storageKey,
        remoteStatus = null,
        generationAttempt = 0,
        submissionAttemptId = null,
        submissionStartedAt = null,
        requestFingerprint = null,
        remoteTaskId = null,
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
        nextRetryAt = null,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = false,
        createdAt = 400,
        updatedAt = 400,
    )

    @Test
    fun migration_preservesV4Data_andPassesRoomSchemaValidation() {
        val roomDb = openMigratedRoomDatabase()
        try {
            val raw = roomDb.openHelper.writableDatabase

            // v4 聊天行原样保留（content/modelContent/completionState 不丢）。
            raw.query("SELECT content, modelContent, completionState FROM chat_history WHERE id = 11").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("display", c.getString(c.getColumnIndexOrThrow("content")))
                assertEquals("raw<think>x</think>", c.getString(c.getColumnIndexOrThrow("modelContent")))
                assertEquals("stopped_partial", c.getString(c.getColumnIndexOrThrow("completionState")))
            }

            // v4 会话行保留，且新增列默认关闭（0）。
            raw.query("SELECT title, autoVideoEnabled FROM conversation WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("旧对话", c.getString(c.getColumnIndexOrThrow("title")))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("autoVideoEnabled")))
            }

            // 新表列集与实体一致（否则 INSERT 失败）；写入后可按 id 读回。
            val insertedId = runBlocking {
                roomDb.seedanceVideoDao().insertIgnore(
                    videoEntity(sourceConversationId = 7, sourceAssistantMessageId = 11)
                )
            }
            assertTrue(insertedId > 0)
            val row = runBlocking { roomDb.seedanceVideoDao().getById(insertedId) }
            assertTrue(row != null)
            assertEquals(SeedanceVideoState.SNAPSHOT_PENDING.storageKey, row!!.state)
        } finally {
            roomDb.close()
            ctx.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration_createsSeedanceVideoIndices() {
        val roomDb = openMigratedRoomDatabase()
        try {
            val raw = roomDb.openHelper.writableDatabase
            raw.query("PRAGMA index_list('seedance_video')").use { c ->
                val indices = mutableMapOf<String, Boolean>() // name -> unique
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    if (name.startsWith("index_seedance_video")) {
                        indices[name] = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                    }
                }
                assertEquals(4, indices.size)
                // 自动视频唯一性：同一助手回复 + 同一触发类型只允许一条任务
                assertEquals(true, indices["index_seedance_video_sourceAssistantMessageId_triggerType"])
                assertEquals(false, indices["index_seedance_video_sourceConversationId_createdAt"])
                assertEquals(false, indices["index_seedance_video_state_nextRetryAt"])
                assertEquals(true, indices["index_seedance_video_remoteTaskId"])
            }
        } finally {
            roomDb.close()
            ctx.deleteDatabase(dbName)
        }
    }
}
