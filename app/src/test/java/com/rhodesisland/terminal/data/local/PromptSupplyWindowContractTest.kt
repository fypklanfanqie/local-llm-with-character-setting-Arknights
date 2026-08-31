package com.rhodesisland.terminal.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prompt 历史供给窗口契约测试。
 *
 * 背景（单聊锚定失效）：insertAndTrim 与历史查询曾一律以 `MAX_HISTORY_PER_CONVERSATION`
 * (=请求 cap `MAX_CONTEXT_MESSAGES`) 截断，单聊主路径 `PromptWindowAnchor.anchoredWindow`
 * 收到的列表恒 ≤ max 而退化为 no-op，真正的滑动发生在 DB 层（每轮掉一条），
 * 长对话云端前缀缓存起点逐轮漂移、命中率归零。
 *
 * 契约：
 * 1. `MAX_PROMPT_SUPPLY` 必须大于等于「请求 cap + 锚定步长」——给 anchoredWindow 留出
 *    至少一个完整量子块的溢出余量，量子截断才可能生效；
 * 2. DB 存一条独立的 prompt 供给查询（LIMIT = MAX_PROMPT_SUPPLY），供 LLM 路径取数；
 *    UI Flow 查询保持原窗口不变；
 * 3. insertAndTrim 的修剪目标同为 MAX_PROMPT_SUPPLY——否则表里永远不足额，
 *    供给查询形同虚设。
 *
 * 注：SQL 语义由 Room 编译期校验；本测试锁定「常量关系与查询形态契约」不被回退
 * （沿用 [ChatDaoOrderingContractTest] 读源码断言的先例）。
 */
class PromptSupplyWindowContractTest {

    /** 从源码全文提取（AppDatabase.kt + AppConfig.kt 分别读取）。 */
    private fun readSource(relativePath: String): String {
        // Gradle 单测默认 working dir 为模块目录；buildDir 重定向到 D:/ai-build 时为
        // <module-build>/，故同时尝试多种回退。找不到即视为环境配置错误。
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
    private fun appConfigSource() = readSource("config/AppConfig.kt")

    // ---------- 契约 1：供给量 ≥ 请求 cap + 锚定步长 ----------

    @Test
    fun promptSupply_coversRequestCapPlusTrimStep() {
        val source = appConfigSource()
        assertTrue("未能读取 AppConfig.kt 源码，测试环境配置错误", source.isNotEmpty())
        assertTrue(
            "AppConfig 缺少 MAX_PROMPT_SUPPLY 常量",
            source.contains("MAX_PROMPT_SUPPLY"),
        )
        val supply = com.rhodesisland.terminal.config.AppConfig.MAX_PROMPT_SUPPLY
        val cap = com.rhodesisland.terminal.config.AppConfig.MAX_CONTEXT_MESSAGES
        val step = com.rhodesisland.terminal.util.PromptWindowAnchor.TRIM_STEP
        assertTrue(
            "MAX_PROMPT_SUPPLY($supply) 必须 ≥ MAX_CONTEXT_MESSAGES($cap) + TRIM_STEP($step)，" +
                "否则单聊 anchoredWindow 收不到溢出余量、量子截断失效",
            supply >= cap + step,
        )
    }

    // ---------- 契约 2：存在独立供给查询（LIMIT = MAX_PROMPT_SUPPLY）----------

    @Test
    fun chatDao_hasPromptSupplyQuery_forLlmPath() {
        val queries = queriesOf(appDatabaseSource())
        assertTrue("未能读取 AppDatabase.kt 源码，测试环境配置错误", queries.isNotEmpty())
        val supplyQuery = queries.firstOrNull {
            it.contains("FROM chat_history") &&
                it.contains("""LIMIT ${'$'}{AppConfig.MAX_PROMPT_SUPPLY}""")
        }
        assertTrue(
            "ChatDao 缺少以 MAX_PROMPT_SUPPLY 为 LIMIT 的 prompt 供给查询（LLM 路径专用）：$queries",
            supplyQuery != null,
        )
        // 供给查询同样需要确定性排序（id tie-break），否则同毫秒并发写入破坏字节前缀
        assertTrue(
            "prompt 供给查询必须带 id tie-break 排序键: $supplyQuery",
            Regex("""ORDER BY timestamp DESC, id DESC""", RegexOption.IGNORE_CASE)
                .containsMatchIn(supplyQuery.orEmpty()),
        )
    }

    // ---------- 契约 3：修剪目标 = 供给量（表内有足额行可供供给查询读取）----------

    @Test
    fun insertAndTrim_keepsPromptSupplyHeadroom() {
        val source = appDatabaseSource()
        assertTrue("未能读取 AppDatabase.kt 源码，测试环境配置错误", source.isNotEmpty())
        assertTrue(
            "insertAndTrim 必须修剪到 MAX_PROMPT_SUPPLY（保留一个量子块的锚定余量）；" +
                "若仍裁到 MAX_HISTORY_PER_CONVERSATION，供给查询永远拿不到超额行，锚定失效回潮",
            source.contains("> AppConfig.MAX_PROMPT_SUPPLY") &&
                source.contains("- AppConfig.MAX_PROMPT_SUPPLY"),
        )
    }

    /** 从 @Query("...") 提取 SQL 文本（含 $"""""" 转义对；与 OrderingContract 同法）。 */
    private fun queriesOf(text: String): List<String> =
        Regex("""@Query\("([^"]*(?:""[^"]*)*)"\)""").findAll(text)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun uiFlowQuery_staysAtUserWindow() {
        // 防回归护栏：UI Flow 查询不得被顺手改成供给上限（聊天页展示窗口语义保持不变）。
        val flowQuery = queriesOf(appDatabaseSource()).filter {
            it.contains("FROM chat_history") && it.contains("LIMIT")
        }
        assertTrue(flowQuery.isNotEmpty())
        val uiWindows = flowQuery.filter { it.contains(AppConfigMarker.MAX_HISTORY_PER_CONVERSATION_LITERAL) }
        assertEquals("UI Flow 查询应保留 MAX_HISTORY_PER_CONVERSATION 窗口", 2, uiWindows.size)
    }
}

/** 字面量集中声明（避免测试内散落魔法串）。 */
private object AppConfigMarker {
    const val MAX_HISTORY_PER_CONVERSATION_LITERAL = """${'$'}{AppConfig.MAX_HISTORY_PER_CONVERSATION}"""
}
