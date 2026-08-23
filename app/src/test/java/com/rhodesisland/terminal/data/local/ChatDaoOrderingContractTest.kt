package com.rhodesisland.terminal.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ChatDao 历史查询排序稳定性契约测试。
 *
 * 背景：历史查询仅按 `timestamp` 排序，同毫秒写入的两条消息（并发落库、后台问候与
 * 用户消息同时刻）顺序不确定，导致发送给 LLM 的 history 前缀字节不稳定，破坏上游
 * prompt 前缀缓存。契约：所有按 timestamp 排序的查询必须带 `id` 作为第二排序键，
 * 保证同毫秒行的相对顺序确定。
 *
 * 注：SQL 语义本身由 Room 编译期校验 + androidTest 覆盖；本测试锁定「排序键契约」
 * 不被回退（防止未来改动把 id tie-break 删掉）。
 */
class ChatDaoOrderingContractTest {

    /** 从 AppDatabase.kt 源码提取全部 @Query 文本，锁定排序契约。 */
    private fun allQueries(): List<String> {
        // Gradle 单测默认 working dir 为模块目录；buildDir 重定向到 D:/ai-build 时为
        // <module-build>/，故同时尝试两种回退。找不到即视为环境配置错误。
        val candidates = listOf(
            File("src/main/java/com/rhodesisland/terminal/data/local/AppDatabase.kt"),
            File("../../app/src/main/java/com/rhodesisland/terminal/data/local/AppDatabase.kt"),
            File("D:/ai/cc Programm/聊天终端安卓本地/app/src/main/java/com/rhodesisland/terminal/data/local/AppDatabase.kt"),
        )
        val text = candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: return emptyList()
        return Regex("""@Query\("([^"]*(?:""[^"]*)*)"\)""").findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun chatHistoryTimestampQueries_haveIdTieBreak() {
        val queries = allQueries()
        assertTrue("未能读取 AppDatabase.kt 源码，测试环境配置错误", queries.isNotEmpty())
        val historyQueries = queries.filter { it.contains("FROM chat_history") && it.contains("ORDER BY") }
        assertTrue("应存在至少两条 chat_history 排序查询", historyQueries.size >= 3)
        for (q in historyQueries) {
            assertTrue(
                "chat_history 时间排序查询必须以 id 作为第二排序键（tie-break）: $q",
                Regex("""ORDER BY timestamp (ASC|DESC), id (ASC|DESC)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(q),
            )
        }
    }

    @Test
    fun trimOldest_keepsDeterministicOldestOrder() {
        val queries = allQueries()
        // 用「内层 SELECT id FROM」子查询唯一锁定 trimOldest（clearHistory/deleteById 没有子查询）
        val trim = queries.firstOrNull {
            it.contains("DELETE FROM chat_history") && it.contains("SELECT id FROM chat_history")
        }
        assertTrue("应存在 trimOldest 的 DELETE 查询", trim != null)
        assertTrue(
            "DELETE 子查询同样需要 id tie-break，保证修剪哪条在同毫秒下是确定的: $trim",
            Regex("""ORDER BY timestamp ASC, id ASC""", RegexOption.IGNORE_CASE).containsMatchIn(trim.orEmpty()),
        )
    }
}
