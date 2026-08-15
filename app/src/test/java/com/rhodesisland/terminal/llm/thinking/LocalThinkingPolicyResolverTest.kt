package com.rhodesisland.terminal.llm.thinking

import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalThinkingPolicyResolver] 纯逻辑测试：复杂度分类、档位解析、软收束提示语义。
 * 全部为纯 Kotlin，不触 Android 运行时。
 */
class LocalThinkingPolicyResolverTest {

    private val resolver = LocalThinkingPolicyResolver()

    /** 无强结构的多句普通问题（≥120 字，0 分但不够短）-> 保守 STANDARD。 */
    private val standardQuestion =
        "我最近在学习概率论，想了解一下贝叶斯定理大概是怎么回事。它和条件概率有什么关系？" +
            "能不能给我一个生活化的例子来说明？另外我还想问问在医疗诊断、垃圾邮件过滤和信用评分这几个场景里，" +
            "贝叶斯定理分别是怎样应用的，有没有什么常见的误区、需要注意的地方，以及学习它的推荐路径是怎样的？"

    // ===== 复杂度分类 =====

    @Test
    fun shortDailyQuestionIsSimple() {
        assertEquals(QuestionComplexity.SIMPLE, resolver.classify("今天天气怎么样？"))
        assertEquals(QuestionComplexity.SIMPLE, resolver.classify("帮我翻译成英文"))
    }

    @Test
    fun generalMultiSentenceQuestionIsStandard() {
        // 长于 120 字、多句、无强结构：0 分但不够短，保守 STANDARD。
        assertEquals(QuestionComplexity.STANDARD, resolver.classify(standardQuestion))
    }

    @Test
    fun longCodeWithFourSubtasksIsComplex() {
        val code = """
            请实现一个内存受限的 LRU 缓存，要求支持 get 和 put，复杂度 O(1)。
            ```python
            class LRUCache:
                def __init__(self, capacity):
                    self.capacity = capacity
                    self.cache = {}
                    self.order = collections.OrderedDict()

                def get(self, key):
                    if key not in self.cache:
                        return -1
                    self.order.move_to_end(key)
                    return self.cache[key]

                def put(self, key, value):
                    if key in self.cache:
                        self.order.move_to_end(key)
                    elif len(self.cache) >= self.capacity:
                        oldest = next(iter(self.order))
                        self.order.popitem(last=False)
                        del self.cache[oldest]
                    self.cache[key] = value
                    self.order[key] = None
            ```
            验收点：
            1. 写 5 个单元测试覆盖命中、未命中、容量淘汰和 put 更新。
            2. 分析极端情况下内存与时间的权衡，给出结论。
            3. 对比 OrderedDict 与手写双向链表的差异。
            4. 说明为什么不能直接用标准字典。
            5. 给出至少两种改进方案并比较。
            6. 写一份简短的设计说明文档。
            7. 说明线程安全性并给出加固建议。
        """.trimIndent()
        assertEquals(QuestionComplexity.COMPLEX, resolver.classify(code))
    }

    @Test
    fun keywordAloneCannotMakeComplex() {
        // 「复杂」不是关键词列表成员，短问法不会被关键词误判为深任务。
        val text = "这是一道复杂问题吗？"
        assertNotEquals(QuestionComplexity.COMPLEX, resolver.classify(text))
    }

    // ===== 档位解析 =====

    @Test
    fun disabledReturnsNull() {
        assertNull(resolver.resolve(false, LocalThinkingLevel.AUTO, "你好", false))
        assertNull(resolver.resolve(false, LocalThinkingLevel.LONG, "你好", false))
    }

    @Test
    fun autoRoutesByComplexity() {
        val simple = resolver.resolve(true, LocalThinkingLevel.AUTO, "今天天气怎么样", false)!!
        assertEquals(LocalThinkingLevel.SHORT, simple.effectiveLevel)
        assertEquals(QuestionComplexity.SIMPLE, simple.complexity)

        val standard = resolver.resolve(true, LocalThinkingLevel.AUTO, standardQuestion, false)!!
        assertEquals(LocalThinkingLevel.MEDIUM, standard.effectiveLevel)

        val complex = resolver.resolve(true, LocalThinkingLevel.AUTO, longTaskText(), false)!!
        assertEquals(LocalThinkingLevel.LONG, complex.effectiveLevel)
        assertEquals(QuestionComplexity.COMPLEX, complex.complexity)
    }

    @Test
    fun manualLevelsProduceDistinctPlans() {
        val short = resolver.resolve(true, LocalThinkingLevel.SHORT, "", false)!!
        val medium = resolver.resolve(true, LocalThinkingLevel.MEDIUM, "", false)!!
        val long = resolver.resolve(true, LocalThinkingLevel.LONG, "", false)!!

        assertEquals(LocalThinkingLevel.SHORT, short.effectiveLevel)
        assertEquals(LocalThinkingLevel.MEDIUM, medium.effectiveLevel)
        assertEquals(LocalThinkingLevel.LONG, long.effectiveLevel)
        assertNull(short.complexity)

        // 三个档位的目标范围与提示必须不同（不能是名字不同、行为相同的伪档位）。
        assertNotEquals(short.targetMaxMs, medium.targetMaxMs)
        assertNotEquals(medium.targetMaxMs, long.targetMaxMs)
        assertNotEquals(short.systemInstruction, medium.systemInstruction)
        assertNotEquals(medium.systemInstruction, long.systemInstruction)
        assertTrue(long.checkpointBudget > medium.checkpointBudget)
        assertTrue(medium.checkpointBudget > short.checkpointBudget)
    }

    @Test
    fun allLevelsOnlyChangeSoftGuidance() {
        val allPlans = LocalThinkingLevel.entries.map { level ->
            resolver.resolve(true, level, standardQuestion, nativeBudgetAvailable = false)!!
        }

        // 手动档 SHORT/MEDIUM/LONG 软提示两两不同（描述生效思考节奏）。
        val manualPlans = allPlans.filter { it.requestedLevel != LocalThinkingLevel.AUTO }
        assertEquals(3, manualPlans.map { it.systemInstruction }.distinct().size)
        // AUTO 按复杂度路由后，提示与其解析出的生效档位一致（AUTO+标准题 -> MEDIUM）。
        val autoPlan = allPlans.first { it.requestedLevel == LocalThinkingLevel.AUTO }
        assertEquals(
            manualPlans.first { it.requestedLevel == LocalThinkingLevel.MEDIUM }.systemInstruction,
            autoPlan.systemInstruction,
        )
        allPlans.forEach { plan ->
            assertEquals(ThinkingControlMode.PROMPT_FALLBACK, plan.controlMode)
            assertTrue(plan.systemInstruction.contains("不改变最终回答"))
            assertFalse(plan.systemInstruction.contains("token"))
            assertFalse(plan.systemInstruction.contains("硬上限"))
            assertFalse(plan.systemInstruction.contains("第二阶段"))
        }
    }

    @Test
    fun autoStandardTargetFallsWithin5To15Seconds() {
        val standard = resolver.resolve(true, LocalThinkingLevel.AUTO, standardQuestion, false)!!
        // 普通问题目标范围：5–15 秒（调优目标，不是硬 SLA）。
        assertTrue(standard.targetMinMs >= 5_000L)
        assertTrue(standard.targetMaxMs <= 15_000L)
        assertTrue(standard.targetMinMs < standard.targetMaxMs)
    }

    // ===== 提示语义 =====

    @Test
    fun instructionConstrainsOnlyThinkingAndPreservesFinalAnswer() {
        val plan = resolver.resolve(true, LocalThinkingLevel.AUTO, "你好", false)!!
        val instr = plan.systemInstruction

        // 只约束思考阶段。
        assertTrue(instr.contains("只约束你的内部思考过程"))
        assertTrue(instr.contains("不改变最终回答的格式、篇幅或内容要求"))
        // 先做必要核验。
        assertTrue(instr.contains("必要") && instr.contains("核验"))
        // 软预算后收束到最终答案。
        assertTrue(instr.contains("停止扩展更多旁支") && instr.contains("最终答案"))
        // 不得省略用户要求的最终答案。
        assertTrue(instr.contains("不得因思考被缩短而省略或简化重要结论"))
        // 不得包含虚假能力。
        assertFalse(instr.contains("强制停止"))
        assertFalse(instr.contains("精确"))
        assertFalse(instr.contains("token"))
    }

    // ===== 手动档范围差异 =====

    @Test
    fun longHasWidestBudgetAndMostCheckpoints() {
        val long = resolver.resolve(true, LocalThinkingLevel.LONG, "", false)!!
        assertTrue(long.targetMaxMs >= long.targetMinMs)
        assertTrue(long.checkpointBudget >= 6)
    }

    // ===== Task 17：思考段字节硬预算 =====

    @Test
    fun thinkingBudgetScalesWithLevel() {
        val short = resolver.resolve(true, LocalThinkingLevel.SHORT, "", false)!!
        val medium = resolver.resolve(true, LocalThinkingLevel.MEDIUM, "", false)!!
        val long = resolver.resolve(true, LocalThinkingLevel.LONG, "", false)!!

        assertTrue(short.thinkingBudgetBytes < medium.thinkingBudgetBytes)
        assertTrue(medium.thinkingBudgetBytes < long.thinkingBudgetBytes)
        // 预算必须远小于默认总 maxTokens（2048 token ≈ 8KB 中文），给正文留足空间。
        assertTrue(long.thinkingBudgetBytes < 8192L)
        assertTrue(long.thinkingBudgetBytes > 0L)
    }

    @Test
    fun autoRoutesBudgetByComplexity() {
        val simple = resolver.resolve(true, LocalThinkingLevel.AUTO, "今天天气怎么样", false)!!
        val standard = resolver.resolve(true, LocalThinkingLevel.AUTO, standardQuestion, false)!!
        val complex = resolver.resolve(true, LocalThinkingLevel.AUTO, longTaskText(), false)!!

        assertEquals(LocalThinkingLevel.SHORT, simple.effectiveLevel)
        assertEquals(LocalThinkingLevel.MEDIUM, standard.effectiveLevel)
        assertEquals(LocalThinkingLevel.LONG, complex.effectiveLevel)
        assertTrue(simple.thinkingBudgetBytes < standard.thinkingBudgetBytes)
        assertTrue(standard.thinkingBudgetBytes < complex.thinkingBudgetBytes)
    }

    // ===== shouldTruncateThinking（思考段超预算判定）=====

    private fun classifier() = ThinkingOutputClassifier(
        thinkingRequested = true,
        templateCapability = ThinkingTemplateCapability.SUPPORTED,
    )

    @Test
    fun truncatesOnlyWhileThinkingSegmentOpen() {
        val c = classifier()
        c.append("<think>")
        c.append("这个问题需要分析一二三四五六七八九十".repeat(20))
        assertTrue(c.sawThinkOpen)
        assertFalse(c.sawThinkClose)
        assertTrue("思考段未闭合且超预算应截断", shouldTruncateThinking(c, budgetBytes = 100))
    }

    @Test
    fun neverTruncatesAfterThinkClosed() {
        val c = classifier()
        c.append("<think>很短的思考</think>正文内容")
        assertTrue(c.sawThinkClose)
        assertFalse("思考已闭合（正文阶段）不应再截断", shouldTruncateThinking(c, budgetBytes = 1))
    }

    @Test
    fun neverTruncatesWithinBudget() {
        val c = classifier()
        c.append("<think>短</think>")
        assertFalse("预算内不应截断", shouldTruncateThinking(c, budgetBytes = 1_000_000L))
    }

    @Test
    fun budgetMeasuredInUtf8Bytes() {
        val c = classifier()
        c.append("<think>")
        c.append("一二三")
        // rawBytes = "<think>"(7) + 3×3 字节 = 16；思考未闭合 bodyBytes=0。
        assertEquals(16L, c.rawBytes)
        assertTrue(shouldTruncateThinking(c, budgetBytes = 15))
        assertFalse(shouldTruncateThinking(c, budgetBytes = 16))
    }

    @Test
    fun noThinkingSegmentNeverTruncates() {
        val c = classifier()
        c.append("普通回答，没有思考标签")
        assertFalse(c.sawThinkOpen)
        assertFalse("无思考段不应触发思考截断", shouldTruncateThinking(c, budgetBytes = 1))
    }

    private fun longTaskText(): String = """
        请设计并实现一个高并发下的分布式限流组件，并给出完整方案：
        ```kotlin
        class TokenBucket(
            private val rate: Double,
            private val capacity: Double,
        ) {
            @Volatile private var tokens = capacity
            @Volatile private var lastRefillNs = System.nanoTime()

            @Synchronized
            fun acquire(permits: Int): Boolean {
                refill()
                if (tokens >= permits) {
                    tokens -= permits
                    return true
                }
                return false
            }

            private fun refill() {
                val now = System.nanoTime()
                tokens = minOf(
                    capacity,
                    tokens + (now - lastRefillNs) / 1e9 * rate,
                )
                lastRefillNs = now
            }
        }
        ```
        具体要求与验收点：
        1. 说明令牌桶与漏桶的区别，给出选型结论。
        2. 讨论多实例部署时如何协调（本地 vs 分布式），给出推荐方案。
        3. 分析滑动窗口计数与令牌桶在突发流量下的差异。
        4. 设计 6 个覆盖临界条件的测试用例。
        5. 讨论时钟回拨和锁竞争对吞吐的影响，给出加固方案。
        6. 给出限流在网关层的部署拓扑与降级策略。
        7. 总结该方案在生产环境的风险与监控指标。
    """.trimIndent()
}
