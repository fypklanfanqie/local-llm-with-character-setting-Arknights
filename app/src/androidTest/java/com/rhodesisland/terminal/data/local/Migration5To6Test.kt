package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room MIGRATION_5_6 测试（群聊）。
 *
 * 手工建一张 v5 库（conversation 含 autoVideoEnabled、无 isGroup/memberIdsJson；chat_history 全列；
 * seedance_video 表由 4->5 迁移产生、5->6 不再重建，故 v5 库必须已含该表与全部索引），
 * 写入历史数据后交给 Room 打开——Room 执行 [AppDatabase.MIGRATION_5_6] 并立即做 schema 校验。
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val dbName = "migration-5-to-6-test.db"

    /** v5 时期 conversation 建表语句（含 autoVideoEnabled，无 isGroup/memberIdsJson）。 */
    private val v5CreateConversation = """
        CREATE TABLE conversation (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            characterId TEXT NOT NULL,
            title TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            autoVideoEnabled INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    private val v5CreateChatHistory = """
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

    /** 与 MIGRATION_4_5 完全一致的 seedance_video 建表（5->6 不重建，v5 库必须已含）。 */
    private val v5CreateSeedanceVideo = """
        CREATE TABLE seedance_video (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            taskUuid TEXT NOT NULL,
            triggerType TEXT NOT NULL,
            sourceConversationId INTEGER NOT NULL,
            sourceUserMessageId INTEGER,
            sourceAssistantMessageId INTEGER NOT NULL,
            characterIdSnapshot TEXT NOT NULL,
            characterNameSnapshot TEXT NOT NULL,
            characterRoleSnapshot TEXT NOT NULL,
            characterSystemPromptSnapshot TEXT NOT NULL,
            userTextSnapshot TEXT NOT NULL,
            assistantTextSnapshot TEXT NOT NULL,
            sceneDescriptionSnapshot TEXT NOT NULL,
            promptBaseUrlSnapshot TEXT NOT NULL,
            promptModelSnapshot TEXT NOT NULL,
            promptJson TEXT,
            finalPrompt TEXT,
            characterImageSourceSnapshot TEXT NOT NULL,
            backgroundImageSourceSnapshot TEXT,
            characterImagePath TEXT,
            characterImageMime TEXT,
            characterImageSha256 TEXT,
            backgroundImagePath TEXT,
            backgroundImageMime TEXT,
            backgroundImageSha256 TEXT,
            modelVariant TEXT NOT NULL,
            resolution TEXT NOT NULL,
            ratio TEXT NOT NULL,
            durationSeconds INTEGER NOT NULL,
            generateAudio INTEGER NOT NULL,
            watermark INTEGER NOT NULL,
            state TEXT NOT NULL,
            remoteStatus TEXT,
            generationAttempt INTEGER NOT NULL,
            submissionAttemptId TEXT,
            submissionStartedAt INTEGER,
            requestFingerprint TEXT,
            remoteTaskId TEXT,
            remoteVideoUrl TEXT,
            remoteVideoUrlObservedAt INTEGER,
            remoteVideoUrlExpiresAt INTEGER,
            remoteRequestId TEXT,
            previousRemoteTasksJson TEXT NOT NULL,
            localVideoPath TEXT,
            videoMime TEXT,
            videoByteSize INTEGER,
            videoSha256 TEXT,
            downloadedAt INTEGER,
            automaticRetryCount INTEGER NOT NULL,
            nextRetryAt INTEGER,
            errorStage TEXT,
            errorCode TEXT,
            errorMessage TEXT,
            retryDisposition TEXT,
            requiresCostConfirmation INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )
    """.trimIndent()

    private fun openMigratedRoomDatabase(): AppDatabase {
        ctx.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v5CreateConversation)
                    db.execSQL(
                        "CREATE INDEX index_conversation_characterId_updatedAt " +
                            "ON conversation (characterId, updatedAt)"
                    )
                    db.execSQL(v5CreateChatHistory)
                    db.execSQL(
                        "CREATE INDEX index_chat_history_conversationId_timestamp " +
                            "ON chat_history (conversationId, timestamp)"
                    )
                    db.execSQL(v5CreateSeedanceVideo)
                    db.execSQL(
                        "CREATE UNIQUE INDEX index_seedance_video_sourceAssistantMessageId_triggerType " +
                            "ON seedance_video (sourceAssistantMessageId, triggerType)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_seedance_video_sourceConversationId_createdAt " +
                            "ON seedance_video (sourceConversationId, createdAt)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_seedance_video_state_nextRetryAt " +
                            "ON seedance_video (state, nextRetryAt)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX index_seedance_video_remoteTaskId " +
                            "ON seedance_video (remoteTaskId)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO conversation (id, characterId, title, createdAt, updatedAt, autoVideoEnabled) " +
                "VALUES (7, 'c1', '旧对话', 100, 200, 0)"
        )
        db.execSQL(
            "INSERT INTO chat_history (id, characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp, modelContent, completionState) " +
                "VALUES (11, 'c1', 7, 'assistant', 'display', '', '', '', 300, null, 'complete')"
        )
        helper.close()

        // Room 打开既有 v5 库：先跑 MIGRATION_5_6，再校验全部实体表/索引。
        // （多群聊 v7 在 5→6 之后链式追加 MIGRATION_6_7。）
        return Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
            )
            .build()
    }

    @Test
    fun migration_preservesV5Data_andPassesRoomSchemaValidation() {
        val roomDb = openMigratedRoomDatabase()
        try {
            val raw = roomDb.openHelper.writableDatabase

            // 旧会话行保留，且新增列取默认值（isGroup=0 / memberIdsJson='' / coverImagePath=null）。
            raw.query("SELECT title, isGroup, memberIdsJson, coverImagePath FROM conversation WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("旧对话", c.getString(c.getColumnIndexOrThrow("title")))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("isGroup")))
                assertEquals("", c.getString(c.getColumnIndexOrThrow("memberIdsJson")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("coverImagePath")))
            }

            // 旧聊天行保留。
            raw.query("SELECT content FROM chat_history WHERE id = 11").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("display", c.getString(c.getColumnIndexOrThrow("content")))
            }

            // 群聊行可写入并按标记查询读回（DAO 路径）；含 v7 封面列往返。
            val groupId = runBlocking {
                roomDb.conversationDao().insert(
                    ConversationEntity(
                        characterId = "group_chat", title = "群聊",
                        createdAt = 400, updatedAt = 400,
                        isGroup = true, memberIdsJson = "[\"a\",\"b\"]",
                        coverImagePath = "file:///cover.png",
                    )
                )
            }
            assertTrue(groupId > 0)
            val group = runBlocking { roomDb.conversationDao().listGroups().firstOrNull() }
            assertTrue(group != null)
            assertEquals("group_chat", group!!.characterId)
            assertTrue(group.isGroup)
            assertEquals("[\"a\",\"b\"]", group.memberIdsJson)
            assertEquals("file:///cover.png", group.coverImagePath)
        } finally {
            roomDb.close()
            ctx.deleteDatabase(dbName)
        }
    }
}