package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room MIGRATION_2_3 测试（Task 3 Step 1）。
 *
 * 仪器测试（需 Android SQLite）：手工建一张 v2 chat_history 表（无 modelContent 列），
 * 插入一行，运行 [AppDatabase.MIGRATION_2_3]，断言 content 不变、modelContent 新增且为 null。
 *
 * 不依赖 Room 导出的 schema JSON（exportSchema=false）：直接用 SupportSQLiteOpenHelper 建库 + 调 migrate()。
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** v2 时期的 chat_history 建表语句（无 modelContent 列）。 */
    private val v2CreateChatHistory = """
        CREATE TABLE chat_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            characterId TEXT NOT NULL,
            conversationId INTEGER NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            imagesJson TEXT NOT NULL,
            filesJson TEXT NOT NULL,
            fileNamesJson TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )
    """.trimIndent()

    @Test
    fun migration_addsModelContentColumn_andPreservesContent() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v2CreateChatHistory)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO chat_history (characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp) " +
                "VALUES ('c1', 1, 'assistant', 'hello-world', '', '', '', 12345)"
        )

        // 运行 2->3 迁移
        AppDatabase.MIGRATION_2_3.migrate(db)

        // content 原样保留；modelContent 新列存在且为 null
        db.query("SELECT content, modelContent FROM chat_history WHERE conversationId = 1").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("hello-world", c.getString(c.getColumnIndexOrThrow("content")))
            assertNull(c.getString(c.getColumnIndexOrThrow("modelContent")))
        }
        helper.close()
    }

    @Test
    fun migration_isIdempotent_safeToCallOnFreshColumn() {
        // 迁移后再次查询多行，确认列对全部行可见且默认 null
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v2CreateChatHistory)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO chat_history (characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp) VALUES ('c1', 1, 'user', 'q1', '', '', '', 1)"
        )
        db.execSQL(
            "INSERT INTO chat_history (characterId, conversationId, role, content, " +
                "imagesJson, filesJson, fileNamesJson, timestamp) VALUES ('c1', 1, 'assistant', 'a1', '', '', '', 2)"
        )
        AppDatabase.MIGRATION_2_3.migrate(db)
        db.query("SELECT modelContent FROM chat_history WHERE conversationId = 1").use { c ->
            var rows = 0
            while (c.moveToNext()) {
                assertNull(c.getString(c.getColumnIndexOrThrow("modelContent")))
                rows++
            }
            assertEquals(2, rows)
        }
        helper.close()
    }
}
