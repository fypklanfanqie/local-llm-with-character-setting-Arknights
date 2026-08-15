package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room MIGRATION_3_4 测试（Task 6）。
 *
 * 仪器测试（需 Android SQLite）：手工建一张 v3 chat_history 表（含 modelContent 列、不含
 * completionState 列），插入带 modelContent 的行，运行 [AppDatabase.MIGRATION_3_4]，
 * 断言 content/modelContent 原样保留、completionState 新增且默认 'complete'。
 *
 * 不依赖 Room 导出的 schema JSON（exportSchema=false）：直接用 SupportSQLiteOpenHelper 建库 + 调 migrate()。
 */
@RunWith(AndroidJUnit4::class)
class Migration3To4Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** v3 时期的 chat_history 建表语句（含 modelContent、不含 completionState）。 */
    private val v3CreateChatHistory = """
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
            modelContent TEXT
        )
    """.trimIndent()

    @Test
    fun migration_addsCompletionState_andPreservesContentAndModelContent() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v3CreateChatHistory)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO chat_history (characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp, modelContent) " +
                "VALUES ('c1', 1, 'assistant', 'display<think>r</think>body', 'raw<think>r</think>body', '', '', '', 12345)"
        )

        // 运行 3->4 迁移
        AppDatabase.MIGRATION_3_4.migrate(db)

        db.query("SELECT content, modelContent, completionState FROM chat_history WHERE conversationId = 1").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("display<think>r</think>body", c.getString(c.getColumnIndexOrThrow("content")))
            assertEquals("raw<think>r</think>body", c.getString(c.getColumnIndexOrThrow("modelContent")))
            assertEquals("complete", c.getString(c.getColumnIndexOrThrow("completionState")))
        }
        helper.close()
    }

    @Test
    fun migration_defaultsAllRowsToComplete() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v3CreateChatHistory)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO chat_history (characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp, modelContent) VALUES ('c1', 1, 'user', 'q1', '', '', '', 1, NULL)"
        )
        db.execSQL(
            "INSERT INTO chat_history (characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp, modelContent) VALUES ('c1', 1, 'assistant', 'a1', 'raw1', '', '', '', 2)"
        )
        AppDatabase.MIGRATION_3_4.migrate(db)
        db.query("SELECT completionState FROM chat_history WHERE conversationId = 1").use { c ->
            var rows = 0
            while (c.moveToNext()) {
                assertEquals("complete", c.getString(c.getColumnIndexOrThrow("completionState")))
                rows++
            }
            assertEquals(2, rows)
        }
        helper.close()
    }
}
