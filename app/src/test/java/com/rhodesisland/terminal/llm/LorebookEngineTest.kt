package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookEntry
import com.rhodesisland.terminal.data.model.LorebookGlobalConfig
import com.rhodesisland.terminal.data.model.LorebookInsertPosition
import com.rhodesisland.terminal.data.model.LorebookScopeType
import com.rhodesisland.terminal.data.model.LorebookSecondaryLogic
import com.rhodesisland.terminal.data.model.matchesScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LorebookEngine] 纯函数测试：覆盖常驻直过、四种子逻辑、大小写/全词/CJK 退化、概率确定性、
 * 递归轮上限与递归开关、预算装配丢弃顺序、order 排序方向、扫描深度窗口、静态头/动态尾缓存布局、
 * 作用域过滤。
 */
class LorebookEngineTest {

    private val config = LorebookGlobalConfig(
        masterEnabled = true,
        scanDepth = 2,
        recursiveScanning = false,
        budgetCapTokens = 0, // 默认不限，便于单测聚焦匹配逻辑
    )

    private fun msg(text: String) = ChatMessage(role = "user", content = text)

    private var seq = 0

    private fun entry(
        keys: List<String> = emptyList(),
        content: String = "设定正文",
        title: String = "",
        constant: Boolean = false,
        enabled: Boolean = true,
        position: LorebookInsertPosition = LorebookInsertPosition.AFTER_CHAR,
        depth: Int = 4,
        order: Int = 100,
        probability: Int = 100,
        secondaryKeys: List<String> = emptyList(),
        logic: LorebookSecondaryLogic = LorebookSecondaryLogic.AND_ANY,
        caseSensitive: Boolean = false,
        wholeWords: Boolean = false,
        scanDepthOverride: Int? = null,
        preventRecursion: Boolean = false,
        excludeRecursion: Boolean = false,
    ) = LorebookEntry(
        id = "lbe-test-${seq++}",
        title = title, keys = keys, secondaryKeys = secondaryKeys, logic = logic,
        content = content, constant = constant, enabled = enabled, position = position,
        depth = depth, order = order, probability = probability,
        caseSensitive = caseSensitive, matchWholeWords = wholeWords,
        scanDepthOverride = scanDepthOverride,
        preventRecursion = preventRecursion, excludeRecursion = excludeRecursion,
    )

    private fun book(vararg entries: LorebookEntry, enabled: Boolean = true) =
        Lorebook(id = "lb-test-${seq++}", name = "测试书", enabled = enabled, entries = entries.toList())

    // ===== 基础触发 =====

    @Test
    fun constantEntryGoesToStaticHead() {
        val act = LorebookEngine.activate(
            listOf(book(entry(constant = true, content = "世界是修真的"))),
            config, listOf(msg("随便聊聊")),
        )
        assertTrue(act.staticHead.contains("世界是修真的"))
        assertTrue(act.tailInjection.isEmpty())
    }

    @Test
    fun keywordHitInjectsIntoTail() {
        val act = LorebookEngine.activate(
            listOf(book(entry(keys = listOf("蟹堡王"), content = "蟹堡王是一家快餐店"))),
            config, listOf(msg("海绵宝宝出发去蟹堡王工作")),
        )
        assertTrue(act.tailInjection.contains("蟹堡王是一家快餐店"))
        assertTrue(act.staticHead.isEmpty()) // 动态命中绝不进 system 头部（缓存契约）
    }

    @Test
    fun noKeywordNoActivation() {
        val act = LorebookEngine.activate(
            listOf(book(entry(keys = listOf("蟹堡王")))),
            config, listOf(msg("今天天气不错")),
        )
        assertTrue(act.isEmpty)
    }

    @Test
    fun emptyKeysNonConstantNeverFires() {
        val act = LorebookEngine.activate(listOf(book(entry())), config, listOf(msg("任意文本 设定正文")))
        assertTrue(act.isEmpty)
    }

    @Test
    fun disabledBookAndEntrySkipped() {
        val disabledBook = book(entry(keys = listOf("关键词")), enabled = false)
        val disabledEntry = book(entry(keys = listOf("关键词"), enabled = false))
        for (b in listOf(disabledBook, disabledEntry)) {
            val act = LorebookEngine.activate(listOf(b), config, listOf(msg("关键词")))
            assertTrue(act.isEmpty)
        }
    }

    @Test
    fun masterDisabledProducesEmpty() {
        val act = LorebookEngine.activate(
            listOf(book(entry(constant = true))),
            config.copy(masterEnabled = false), listOf(msg("x")),
        )
        assertTrue(act.isEmpty)
    }

    // ===== 次级关键词四逻辑 =====

    private fun logicCase(logic: LorebookSecondaryLogic) = LorebookEngine.activate(
        listOf(book(entry(keys = listOf("星球"), secondaryKeys = listOf("小T"), logic = logic, content = "命中"))),
        config, listOf(msg("星球和兔子的故事")),
    )

    @Test
    fun logicAndAnyRequiresSecondary() {
        assertTrue(logicCase(LorebookSecondaryLogic.AND_ANY).isEmpty) // 次级「小T」不在
        val hit = LorebookEngine.activate(
            listOf(book(entry(keys = listOf("星球"), secondaryKeys = listOf("兔子"), logic = LorebookSecondaryLogic.AND_ANY))),
            config, listOf(msg("星球和兔子")),
        )
        assertFalse(hit.isEmpty)
    }

    @Test
    fun logicNotAnyFiresWhenSecondaryAbsent() {
        assertFalse(logicCase(LorebookSecondaryLogic.NOT_ANY).isEmpty)
        val blocked = LorebookEngine.activate(
            listOf(book(entry(keys = listOf("星球"), secondaryKeys = listOf("兔子"), logic = LorebookSecondaryLogic.NOT_ANY))),
            config, listOf(msg("星球和兔子")),
        )
        assertTrue(blocked.isEmpty)
    }

    @Test
    fun logicNotAllFiresWhenNotAllPresent() {
        // 次级配两个词：「兔子」在、「乌龟」不在 → NOT_ALL 应命中（非全在）
        val act = LorebookEngine.activate(
            listOf(book(entry(
                keys = listOf("星球"),
                secondaryKeys = listOf("兔子", "乌龟"),
                logic = LorebookSecondaryLogic.NOT_ALL,
                content = "命中",
            ))),
            config, listOf(msg("星球和兔子")),
        )
        assertFalse(act.isEmpty)
    }

    @Test
    fun logicAndAllRequiresEverySecondary() {
        val partial = LorebookEngine.activate(
            listOf(book(entry(
                keys = listOf("星球"),
                secondaryKeys = listOf("兔子", "乌龟"),
                logic = LorebookSecondaryLogic.AND_ALL,
                content = "命中",
            ))),
            config, listOf(msg("星球和兔子")),
        )
        assertTrue(partial.isEmpty)
        val full = LorebookEngine.activate(
            listOf(book(entry(
                keys = listOf("星球"),
                secondaryKeys = listOf("兔子", "乌龟"),
                logic = LorebookSecondaryLogic.AND_ALL,
                content = "命中",
            ))),
            config, listOf(msg("星球、兔子与乌龟")),
        )
        assertFalse(full.isEmpty)
    }

    @Test
    fun emptySecondaryAlwaysPassesGate() {
        val act = LorebookEngine.activate(
            listOf(book(entry(keys = listOf("星球"), content = "命中"))),
            config, listOf(msg("星球")),
        )
        assertFalse(act.isEmpty)
    }

    // ===== 大小写 / 全词 / CJK =====

    @Test
    fun caseSensitiveRespected() {
        val sensitive = book(entry(keys = listOf("Rose"), caseSensitive = true))
        assertTrue(LorebookEngine.activate(listOf(sensitive), config, listOf(msg("rose 是玫瑰"))).isEmpty)
        assertFalse(LorebookEngine.activate(listOf(sensitive), config, listOf(msg("Rose 在喝茶"))).isEmpty)
    }

    @Test
    fun defaultMatchingIgnoresCase() {
        val b = book(entry(keys = listOf("rose")))
        assertFalse(LorebookEngine.activate(listOf(b), config, listOf(msg("ROSE"))).isEmpty)
    }

    @Test
    fun wholeWordBlocksSubstring() {
        val b = book(entry(keys = listOf("dog"), wholeWords = true))
        assertTrue(LorebookEngine.activate(listOf(b), config, listOf(msg("I like hotdog"))).isEmpty)
        assertFalse(LorebookEngine.activate(listOf(b), config, listOf(msg("walk the dog now"))).isEmpty)
    }

    @Test
    fun cjkKeywordDegradesToSubstringEvenWithWholeWords() {
        val b = book(entry(keys = listOf("青云宗"), wholeWords = true))
        // 中文无词界：即使开了全词匹配也按子串命中
        assertFalse(LorebookEngine.activate(listOf(b), config, listOf(msg("前往青云宗山门"))).isEmpty)
    }

    // ===== 概率 =====

    @Test
    fun probabilityDeterministicPerSeed() {
        val b = book(entry(keys = listOf("星"), probability = 50))
        val msgs = listOf(msg("星"))
        val a1 = LorebookEngine.activate(listOf(b), config, msgs, random = kotlin.random.Random(42))
        val a2 = LorebookEngine.activate(listOf(b), config, msgs, random = kotlin.random.Random(42))
        assertEquals(a1.activatedCount, a2.activatedCount)
    }

    @Test
    fun probability100AlwaysFires() {
        val b = book(entry(keys = listOf("星"), probability = 100))
        repeat(5) { seed ->
            val act = LorebookEngine.activate(listOf(b), config, listOf(msg("星")), random = kotlin.random.Random(seed))
            assertFalse(act.isEmpty)
        }
    }

    // ===== 递归 =====

    @Test
    fun recursionChainsActivations() {
        val b = book(
            entry(keys = listOf("贝西"), content = "贝西是一头奶牛，鲁弗斯是她的朋友"),
            entry(keys = listOf("鲁弗斯"), content = "鲁弗斯是一条狗"),
        )
        val off = LorebookEngine.activate(listOf(b), config, listOf(msg("贝西")))
        assertEquals(1, off.activatedCount)

        val on = LorebookEngine.activate(
            listOf(b), config.copy(recursiveScanning = true), listOf(msg("贝西")),
        )
        assertEquals(2, on.activatedCount)
    }

    @Test
    fun recursionCappedAtThreeRounds() {
        val b = book(
            entry(keys = listOf("a"), content = "提到 bb"),
            entry(keys = listOf("bb"), content = "提到 cc"),
            entry(keys = listOf("cc"), content = "提到 dd"),
            entry(keys = listOf("dd"), content = "提到 ee"),
            entry(keys = listOf("ee"), content = "终点"),
        )
        val act = LorebookEngine.activate(
            listOf(b), config.copy(recursiveScanning = true), listOf(msg("a")),
        )
        // a 直连 + 3 轮递归(bb/cc/dd)；ee 需第 4 轮被上限挡住
        assertEquals(4, act.activatedCount)
    }

    @Test
    fun preventRecursionStopsSpread() {
        val b = book(
            entry(keys = listOf("贝西"), content = "贝西的伙伴是鲁弗斯", preventRecursion = true),
            entry(keys = listOf("鲁弗斯"), content = "鲁弗斯是一条狗"),
        )
        val act = LorebookEngine.activate(
            listOf(b), config.copy(recursiveScanning = true), listOf(msg("贝西")),
        )
        assertEquals(1, act.activatedCount)
    }

    @Test
    fun excludeRecursionOnlyDirectlyReachable() {
        val b = book(
            entry(keys = listOf("贝西"), content = "贝西的伙伴是鲁弗斯"),
            entry(keys = listOf("鲁弗斯"), content = "狗", excludeRecursion = true),
        )
        // 对话文本不含「鲁弗斯」：B 只能靠递归激活，而它被排除 → 只剩 A
        val act = LorebookEngine.activate(
            listOf(b), config.copy(recursiveScanning = true), listOf(msg("贝西")),
        )
        assertEquals(1, act.activatedCount)
    }

    // ===== 扫描深度窗口 =====

    @Test
    fun globalScanDepthLimitsWindow() {
        val b = book(entry(keys = listOf("古董")))
        val msgs = listOf(msg("古董花瓶很值钱"), msg("无关消息一"), msg("无关消息二"))
        // scanDepth=2 只扫最近两条，最旧的关键词不可见
        assertTrue(LorebookEngine.activate(listOf(b), config, msgs).isEmpty)
        assertFalse(LorebookEngine.activate(listOf(b), config.copy(scanDepth = 3), msgs).isEmpty)
    }

    @Test
    fun entryScanDepthOverrideShrinksWindow() {
        val narrow = book(entry(keys = listOf("古董"), scanDepthOverride = 1))
        val msgs = listOf(msg("古董在旧消息里"), msg("新消息没有"))
        assertTrue(LorebookEngine.activate(listOf(narrow), config.copy(scanDepth = 2), msgs).isEmpty)
    }

    // ===== 预算与排序 =====

    private fun longEntry(order: Int, marker: String) = entry(
        keys = listOf(marker), content = "$marker " + "长".repeat(60), order = order,
    )

    @Test
    fun budgetKeepsHighOrderFirst() {
        val b = book(longEntry(300, "甲"), longEntry(200, "乙"), longEntry(100, "丙"))
        // 每条块约 66 token：cap=150 装得下甲+乙（~132），丙装不下
        val act = LorebookEngine.activate(listOf(b), config.copy(budgetCapTokens = 150), listOf(msg("甲乙丙")))
        assertTrue(act.tailInjection.contains("甲"))
        assertTrue(act.tailInjection.contains("乙"))
        assertFalse(act.tailInjection.contains("丙"))
        assertEquals(2, act.activatedCount)
    }

    @Test
    fun budgetConstantSurvivesOverDirect() {
        val b = book(
            entry(keys = listOf("低"), content = "低优先级 " + "字".repeat(60), order = 999),
            entry(constant = true, content = "常驻核心 " + "字".repeat(60), order = 10),
        )
        val act = LorebookEngine.activate(listOf(b), config.copy(budgetCapTokens = 70), listOf(msg("低")))
        assertTrue(act.staticHead.contains("常驻核心"))
        assertFalse(act.tailInjection.contains("低优先级"))
    }

    @Test
    fun budgetNeverDropsConstant_evenWhenBlueTotalExceedsCap() {
        // 缓存+保真契约：constant 是世界观核心，装配顺序里天然居前——但多蓝灯总成本超上限时，
        // 旧 break 逻辑会整块丢弃后排蓝灯（低 order 先丢），核心设定凭空消失且静态头缩水。
        // 新契约：蓝灯豁免裁剪软超限保留；硬上限只约束动态条目。
        val b = book(
            entry(constant = true, content = "蓝大 " + "字".repeat(150), order = 900),
            entry(constant = true, content = "蓝小必须活", order = 1),
            entry(keys = listOf("低"), content = "绿灯 filler " + "字".repeat(60)),
        )
        val act = LorebookEngine.activate(listOf(b), config.copy(budgetCapTokens = 160), listOf(msg("低")))
        assertTrue("后排蓝灯不得被预算整块丢弃", act.staticHead.contains("蓝小必须活"))
        assertTrue(act.staticHead.contains("蓝大"))
    }

    @Test
    fun budgetConstantsSoftExceedCap_whileDynamicsStayHardCapped() {
        // 蓝灯自身总成本超上限：软超限保留（保缓存稳定 > 预算字面量）；绿灯全部出局。
        val b = book(
            entry(constant = true, content = "蓝一 " + "字".repeat(80)),
            entry(constant = true, content = "蓝二 " + "字".repeat(80)),
            entry(keys = listOf("低"), content = "绿灯 " + "字".repeat(60)),
        )
        val act = LorebookEngine.activate(listOf(b), config.copy(budgetCapTokens = 100), listOf(msg("低")))
        assertTrue(act.staticHead.contains("蓝一"))
        assertTrue(act.staticHead.contains("蓝二"))
        assertFalse(act.tailInjection.contains("绿灯"))
    }

    /** position 仅作排序语义：BEFORE_CHAR 排在动态尾更靠前，AFTER/@D 更靠后；不再产生独立通道。 */
    @Test
    fun positionOnlyAffectsTailOrdering() {
        val b = book(
            entry(keys = listOf("前缀"), content = "内容前缀", position = LorebookInsertPosition.BEFORE_CHAR),
            entry(keys = listOf("后缀"), content = "内容后缀"),
        )
        val act = LorebookEngine.activate(listOf(b), config, listOf(msg("提到前缀和后缀")))
        // 两组都进动态尾（不拆分），且 BEFORE 在前
        assertTrue(act.tailInjection.contains("内容前缀"))
        assertTrue(act.tailInjection.contains("内容后缀"))
        val idxPre = act.tailInjection.indexOf("内容前缀")
        val idxPost = act.tailInjection.indexOf("内容后缀")
        assertTrue(idxPre in 0 until idxPost)
        assertTrue(act.staticHead.isEmpty())
    }

    @Test
    fun higherOrderPlacedLaterWithinSamePosition() {
        val b = book(
            entry(keys = listOf("甲"), content = "内容甲", order = 100),
            entry(keys = listOf("乙"), content = "内容乙", order = 200),
        )
        val act = LorebookEngine.activate(listOf(b), config, listOf(msg("甲乙")))
        val idxA = act.tailInjection.indexOf("内容甲")
        val idxB = act.tailInjection.indexOf("内容乙")
        assertTrue(idxA in 0 until idxB) // 同位置组内 order 大者更靠下
    }

    // ===== 缓存布局 =====

    /** 核心缓存契约：同一书 + 不同对话输入 → staticHead 必须逐字节一致。 */
    @Test
    fun staticHeadIsStableAcrossDifferentInputs() {
        val b = book(
            entry(constant = true, content = "宪法条目一", order = 1),
            entry(constant = true, content = "宪法条目二", order = 2),
        )
        val a = LorebookEngine.activate(listOf(b), config, listOf(msg("今天聊点别的")))
        val c = LorebookEngine.activate(listOf(b), config, listOf(msg("完全不同的话题 星球大战")))
        assertEquals(a.staticHead, c.staticHead)
        assertTrue(a.staticHead.isNotEmpty())
    }

    @Test
    fun buildTailMessageCarriesTailText() {
        val act = LorebookActivation(staticHead = "", tailInjection = "动态内容", activatedCount = 1, estimatedTokens = 4)
        val m = LorebookEngine.buildTailMessage(act)
        assertEquals("system", m?.role)
        assertEquals("动态内容", m?.content)
        assertNull(LorebookEngine.buildTailMessage(LorebookActivation("", "", 0, 0)))
    }

    // ===== 作用域 =====

    @Test
    fun scopeFilteringAllPassesEverywhere() {
        val b = book(entry(keys = listOf("星"))).copy(scopeType = LorebookScopeType.ALL)
        assertTrue(b.matchesScope(characterId = "c1", groupConversationId = null))
        assertTrue(b.matchesScope(characterId = null, groupConversationId = "9"))
    }

    @Test
    fun scopeCharacterOnlyBindsListedCharacters() {
        val b = book(entry(keys = listOf("星"))).copy(
            scopeType = LorebookScopeType.CHARACTER, scopeIds = listOf("char-a"),
        )
        assertTrue(b.matchesScope(characterId = "char-a", groupConversationId = null))
        assertFalse(b.matchesScope(characterId = "char-b", groupConversationId = null))
        assertFalse(b.matchesScope(characterId = null, groupConversationId = "9"))
    }

    @Test
    fun scopeGroupOnlyBindsListedGroups() {
        val b = book(entry(keys = listOf("星"))).copy(
            scopeType = LorebookScopeType.GROUP, scopeIds = listOf("42"),
        )
        assertTrue(b.matchesScope(characterId = null, groupConversationId = "42"))
        assertFalse(b.matchesScope(characterId = null, groupConversationId = "43"))
        assertFalse(b.matchesScope(characterId = "char-a", groupConversationId = null))
    }
}
