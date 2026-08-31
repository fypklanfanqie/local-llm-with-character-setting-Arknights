package com.rhodesisland.terminal.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 滚动摘要存储层契约测试（源码断言式，沿用 ChatDaoOrderingContractTest 先例）。
 *
 * 契约（滚动摘要上下文压缩方案）：
 * - `conversation` 表新增 summaryText / summarizedUpToMessageId 两列（非破坏式 ALTER 迁移）；
 *   summarizedUpToMessageId 为水位线——其后的行是未摘要原文，其前（含）已被压缩进摘要文本；
 * - 存在按水位线取「最旧一批未摘要原文」与其计数的查询；
 * - 存在把新摘要与推进后水位线一并写回会话行的更新语句；
 * - 迁移必须注册进 addMigrations（否则升级用户崩库）。
 */
class RollingSummaryStoreContractTest {

    private fun readSource(relativePath: String): String {
        val roots = listOf(
            File("src/main/java/com/rhodesisland/terminal"),
            File("../../app/src/main/java/com/rhodesisland/terminal"),
            File("D:/ai/cc Programm/聊天终端安卓本地/app/src/main/java/com/rhodesisland/terminal"),
        )
        return roots.asSequence()
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            .orEmpty()
    }

    private fun appDatabaseSource() = readSource("data/local/AppDatabase.kt")

    @Test
    fun databaseVersion_bumpedToThirteen_withRegisteredMigration() {
        val source = appDatabaseSource()
        assertTrue("未能读取 AppDatabase.kt", source.isNotEmpty())
        val version = Regex("""version\s*=\s*(\d+)""").find(source)
            ?.groupValues?.get(1)?.toIntOrNull()
        assertTrue(
            "Room 版本应 ≥ 13（滚动摘要两列迁移），实际 = $version",
            (version ?: 0) >= 13,
        )
        assertTrue(
            "MIGRATION_12_13 必须注册进 addMigrations 链",
            source.contains("MIGRATION_12_13") &&
                Regex("""addMigrations\(([^)]*)\)""").find(source)!!.groupValues[1].contains("MIGRATION_12_13"),
        )
    }

    @Test
    fun migration_addsBothColumnsNonDestructively() {
        val source = appDatabaseSource()
        val migrationBody = Regex("""MIGRATION_12_13[\s\S]{0,400}?override[\s\S]{0,800}?\n\s*\}""")
            .find(source)?.value.orEmpty()
        assertTrue("应存在 MIGRATION_12_13 定义", migrationBody.isNotEmpty())
        assertTrue(
            "conversation.summaryText 列（非空默认空串）",
            migrationBody.contains("ALTER TABLE conversation ADD COLUMN summaryText TEXT NOT NULL DEFAULT ''"),
        )
        assertTrue(
            "conversation.summarizedUpToMessageId 水位列（可空）",
            migrationBody.contains("ALTER TABLE conversation ADD COLUMN summarizedUpToMessageId INTEGER"),
        )
    }

    @Test
    fun chatDao_exposesUnfoldedQueriesWithWatermark() {
        val queries = Regex("""@Query\("([^"]*(?:""[^"]*)*)"\)""")
            .findAll(appDatabaseSource()).map { it.groupValues[1] }.toList()
        // 取最旧一批未摘要行：id > :watermark 升序 + LIMIT :limit
        val batchQuery = queries.firstOrNull {
            it.contains("FROM chat_history") && it.contains("id > :watermark") && it.contains("LIMIT :limit")
        }
        assertTrue("缺少按水位线取最旧未摘要批次的查询", batchQuery != null)
        assertTrue(
            "批次查询须以 timestamp ASC, id ASC 排序保证跨毫秒确定性",
            Regex("""ORDER BY timestamp ASC, id ASC""", RegexOption.IGNORE_CASE).containsMatchIn(batchQuery!!),
        )
        val countQuery = queries.firstOrNull {
            it.contains("COUNT(*) FROM chat_history") && it.contains("id > :watermark")
        }
        assertTrue("缺少未摘要计数查询", countQuery != null)
    }

    @Test
    fun conversationDao_hasSummaryWriteBack() {
        val queries = Regex("""@Query\("([^"]*(?:""[^"]*)*)"\)""")
            .findAll(appDatabaseSource()).map { it.groupValues[1] }.toList()
        val writeBack = queries.firstOrNull {
            it.contains("UPDATE conversation SET summaryText = :summaryText") &&
                it.contains("summarizedUpToMessageId = :upToMessageId")
        }
        assertTrue("缺少摘要与水位线一并写回的更新语句", writeBack != null)
    }

    /** 实体列与迁移语句的成对存在性：漏改实体或漏写迁移都会被 Room schema 校验拦下——此处锁“两边都写了”。 */
    @Test
    fun entity_declaresBothSummaryColumns() {
        val source = appDatabaseSource()
        assertTrue(source.contains("val summaryText: String"))
        assertTrue(source.contains("val summarizedUpToMessageId: Long?"))
        // 默认值声明（旧行回退语义在实体侧同样成立）
        assertEquals(2, Regex("""summaryText: String = ""|summarizedUpToMessageId: Long\? = null""")
            .findAll(source).count())
    }
}
