package com.rhodesisland.terminal.llm.template

import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.metrics.InferenceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThinkingOutputClassifier] 单元测试（Task 2）。
 *
 * 覆盖：Qwen/DeepSeek 风格完整思考段、无标签模型、不完整 reasoning（有 `<think>` 无 `</think>` 截断）、
 * 空 EOS、max-token 空、think-only、whitespace-only、UTF-8 跨批边界（`</th` + `ink>` 分片）、
 * THINKING_DISABLE_NOT_EFFECTIVE、ENABLED/DISABLED/IGNORED_BY_TEMPLATE、后端失败阶段区分、
 * TEMPLATE_SUPPRESSED_OUTPUT 启发式。
 */
class ThinkingOutputClassifierTest {

    private fun classifier(
        requested: Boolean = false,
        capability: ThinkingTemplateCapability = ThinkingTemplateCapability.UNKNOWN,
    ) = ThinkingOutputClassifier(requested, capability)

    private fun feed(c: ThinkingOutputClassifier, vararg chunks: String) {
        chunks.forEach { c.append(it) }
    }

    // ===== 观察（增量标签/正文/字节） =====

    @Test
    fun qwenStyleCompleteSegment_observesTagsBodyAndBytes() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>让我思考一下\n", "</think>", "答案是 42")

        assertTrue(c.sawThinkOpen)
        assertTrue(c.sawThinkClose)
        // 流：<think>(0-6) 让我思考一下(7-12) \n(13) </think>(14-21) 答案是 42(22-27)
        assertEquals(22, c.firstBodyOffset!!)
        // raw = 7 + 6*3 + 1 + 8 + (3+3+3+1+1+1) = 46；body = "答案是 42" UTF-8 = 12
        assertEquals(46L, c.rawBytes)
        assertEquals(12L, c.bodyBytes)
    }

    @Test
    fun noTagModel_wholeStreamIsBody() {
        val c = classifier(requested = false, capability = ThinkingTemplateCapability.UNSUPPORTED)
        feed(c, "你好，我是本地助手")

        assertFalse(c.sawThinkOpen)
        assertFalse(c.sawThinkClose)
        assertEquals(0, c.firstBodyOffset!!)
        assertEquals(c.rawBytes, c.bodyBytes)
    }

    @Test
    fun noTagModel_bodyOffsetSkipsLeadingWhitespace() {
        val c = classifier()
        feed(c, "  \n", "你好")
        assertEquals(3, c.firstBodyOffset!!)
    }

    @Test
    fun thinkOpenTagSplitAcrossBatches_isDetected() {
        val c = classifier()
        feed(c, "<th", "ink>推理</think>正文")
        assertTrue(c.sawThinkOpen)
        assertTrue(c.sawThinkClose)
    }

    @Test
    fun closeTagSplitAcrossBatches_bodyBoundaryCorrect() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        // </th + ink> 分片到达：闭标签仍须完整捕获，正文起点从分片后继续。
        feed(c, "<think>推理", "</th", "ink>答案")
        assertTrue(c.sawThinkClose)
        // 流：<think>(0-6) 推理(7-8) </think>(9-16) 答案(17-18)
        assertEquals(17, c.firstBodyOffset!!)
        // raw = 7 + 6 + 4 + 4 + 6 = 27：<think>7 + 推理6 + </th 4 + ink> 4 + 答案6
        //（闭标签跨批分片为 </th + ink> 各 4 字节，合计 8 字节，非单个 8 字节 </think>）
        assertEquals(27L, c.rawBytes)
        assertEquals(6L, c.bodyBytes)   // "答案" = 2 * 3
    }

    @Test
    fun thinkSectionThenWhitespaceBody_tracksNoBodyChar() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>推理</think>", "  \n", "　")
        assertTrue(c.sawThinkClose)
        assertNull(c.firstBodyOffset)
        // 正文仅有空白：bodyBytes 仍计数（正文段全部字节），raw 含全部。
        assertEquals("  \n　".toByteArray(Charsets.UTF_8).size.toLong(), c.bodyBytes)
    }

    // ===== EmptyResponseClass =====

    @Test
    fun normalBody_isNone() {
        val c = classifier()
        feed(c, "好的")
        assertEquals(EmptyResponseClass.NONE, c.finish(CompletionReason.EOS, 5).emptyResponseClass)
    }

    @Test
    fun emptyEos_isEosEmpty() {
        val c = classifier(requested = false)
        assertEquals(EmptyResponseClass.EOS_EMPTY, c.finish(CompletionReason.EOS, 0).emptyResponseClass)
    }

    @Test
    fun maxTokensEmpty_isMaxTokensEmpty() {
        val c = classifier()
        assertEquals(
            EmptyResponseClass.MAX_TOKENS_EMPTY,
            c.finish(CompletionReason.MAX_TOKENS, 0).emptyResponseClass,
        )
    }

    @Test
    fun thinkOnly_completeSegmentWithoutBody_isThinkOnly() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>推理过程</think>")
        assertEquals(EmptyResponseClass.THINK_ONLY, c.finish(CompletionReason.EOS, 15).emptyResponseClass)
    }

    @Test
    fun incompleteReasoning_truncated_isThinkOnly() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>", "正在深入思考")
        assertTrue(c.sawThinkOpen)
        assertFalse(c.sawThinkClose)
        assertNull(c.firstBodyOffset)
        assertEquals(0L, c.bodyBytes)
        assertEquals(EmptyResponseClass.THINK_ONLY, c.finish(CompletionReason.MAX_TOKENS, 20).emptyResponseClass)
    }

    @Test
    fun thinkThenWhitespaceBody_prefersThinkOnly() {
        // 有思考段但正文仅空白：THINK_ONLY（思考了但没作答）优先于 WHITESPACE_ONLY。
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>推理</think>", "  \n")
        assertEquals(EmptyResponseClass.THINK_ONLY, c.finish(CompletionReason.EOS, 10).emptyResponseClass)
    }

    @Test
    fun whitespaceOnly_isWhitespaceOnly() {
        val c = classifier()
        feed(c, "  \n\t", "　  ")
        assertNull(c.firstBodyOffset)
        assertTrue(c.rawBytes > 0L)
        assertEquals(EmptyResponseClass.WHITESPACE_ONLY, c.finish(CompletionReason.EOS, 3).emptyResponseClass)
    }

    @Test
    fun cancelledTimeoutThermal_areClassifiedByReason() {
        assertEquals(
            EmptyResponseClass.CANCELLED,
            classifier().finish(CompletionReason.USER_CANCEL, 0).emptyResponseClass,
        )
        // POLICY_TRUNCATION 与用户取消同属请求级提前终止。
        assertEquals(
            EmptyResponseClass.CANCELLED,
            classifier().finish(CompletionReason.POLICY_TRUNCATION, 0).emptyResponseClass,
        )
        assertEquals(
            EmptyResponseClass.TIMEOUT,
            classifier().finish(CompletionReason.TIMEOUT, 0).emptyResponseClass,
        )
        assertEquals(
            EmptyResponseClass.THERMAL_STOP,
            classifier().finish(CompletionReason.THERMAL_STOP, 0).emptyResponseClass,
        )
    }

    @Test
    fun backendFailure_distinguishesPrefillAndDecode() {
        assertEquals(
            EmptyResponseClass.PREFILL_FAILURE,
            classifier().finish(CompletionReason.BACKEND_FAILURE, 0).emptyResponseClass,
        )
        assertEquals(
            EmptyResponseClass.DECODE_FAILURE,
            classifier().finish(CompletionReason.BACKEND_FAILURE, 5).emptyResponseClass,
        )
    }

    @Test
    fun backendFailure_errorStageTakesPrecedenceOverTokenHeuristic() {
        // M-2：native errorStage（PREFILL/DECODE）优先于 generatedTokens 近似——
        // 有 token 的 PREFILL 仍归 PREFILL_FAILURE；零 token 的 DECODE 仍归 DECODE_FAILURE。
        assertEquals(
            EmptyResponseClass.PREFILL_FAILURE,
            classifier()
                .finish(CompletionReason.BACKEND_FAILURE, 5, InferenceStage.PREFILL.name)
                .emptyResponseClass,
        )
        assertEquals(
            EmptyResponseClass.DECODE_FAILURE,
            classifier()
                .finish(CompletionReason.BACKEND_FAILURE, 0, InferenceStage.DECODE.name)
                .emptyResponseClass,
        )
    }

    @Test
    fun templateSuppressedOutput_heuristic() {
        // 请求思考 + 模板含分支 + EOS 零输出：疑似模板渲染吞掉输出。
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        assertEquals(
            EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT,
            c.finish(CompletionReason.EOS, 0).emptyResponseClass,
        )
        // 对照：未请求思考时 EOS 零输出为普通 EOS_EMPTY。
        val c2 = classifier(requested = false, capability = ThinkingTemplateCapability.SUPPORTED)
        assertEquals(
            EmptyResponseClass.EOS_EMPTY,
            c2.finish(CompletionReason.EOS, 0).emptyResponseClass,
        )
    }

    // ===== ThinkingEffect =====

    @Test
    fun disableNotEffective_completeReasoningDespiteOff() {
        // 硬性要求（Task 2 Step 4）：requested=false 仍出现完整思考段。
        val c = classifier(requested = false, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>推理</think>", "好的")
        assertEquals(
            ThinkingEffect.THINKING_DISABLE_NOT_EFFECTIVE,
            c.finish(CompletionReason.EOS, 12).thinkingEffect,
        )
    }

    @Test
    fun enabled_whenRequestedAndCompleteReasoningObserved() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "<think>推理</think>", "好的")
        assertEquals(ThinkingEffect.ENABLED, c.finish(CompletionReason.EOS, 12).thinkingEffect)
    }

    @Test
    fun disabled_whenOffNoReasoningAndNormalBody() {
        val c = classifier(requested = false, capability = ThinkingTemplateCapability.UNSUPPORTED)
        feed(c, "你好，我是助手")
        val r = c.finish(CompletionReason.EOS, 8)
        assertEquals(ThinkingEffect.DISABLED, r.thinkingEffect)
        assertEquals(EmptyResponseClass.NONE, r.emptyResponseClass)
    }

    @Test
    fun ignoredByTemplate_whenRequestedButTemplateUnsupported() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.UNSUPPORTED)
        feed(c, "你好呀")
        val r = c.finish(CompletionReason.EOS, 5)
        assertEquals(ThinkingEffect.IGNORED_BY_TEMPLATE, r.thinkingEffect)
        assertEquals(EmptyResponseClass.NONE, r.emptyResponseClass)
    }

    @Test
    fun unknown_whenRequestedSupportedButNoReasoningAndNormalBody() {
        // 模板支持分支但未见完整思考段且有正文：开关是否生效无法归因（如 MNN 注入失败）。
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        feed(c, "直接回答")
        assertEquals(ThinkingEffect.UNKNOWN, c.finish(CompletionReason.EOS, 6).thinkingEffect)
    }

    @Test
    fun unknown_whenEmptyOutputCannotBeAttributed() {
        val c = classifier(requested = true, capability = ThinkingTemplateCapability.SUPPORTED)
        val r = c.finish(CompletionReason.EOS, 0)
        assertEquals(ThinkingEffect.UNKNOWN, r.thinkingEffect)
        assertEquals(EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT, r.emptyResponseClass)
    }

    @Test
    fun emptyDeltaIsNoop() {
        val c = classifier()
        c.append("")
        assertEquals(0L, c.rawBytes)
        assertEquals(0L, c.bodyBytes)
        assertNull(c.firstBodyOffset)
    }
}
