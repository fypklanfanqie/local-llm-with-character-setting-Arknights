package com.rhodesisland.terminal.ui.settings

import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.benchmark.BenchmarkScenarioResult
import com.rhodesisland.terminal.llm.benchmark.BenchmarkSummary
import com.rhodesisland.terminal.llm.benchmark.CertifiedInferenceOptions
import com.rhodesisland.terminal.llm.benchmark.InferenceBenchmarkScenario
import com.rhodesisland.terminal.llm.metrics.InferenceTurnRecord
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.profile.DowngradeReason
import com.rhodesisland.terminal.llm.template.ThinkingEffect
import com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import com.rhodesisland.terminal.provider.local.LocalChatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断摘要纯逻辑测试（Task 7 Step 2/5）。
 *
 * 覆盖 [templateCapabilityText]（Step 5：UNKNOWN/UNSUPPORTED 不得声称「思考已关闭」）、
 * [thinkingStatusText]（请求/实际与模板能力合并展示口径）、[downgradeReasonText]、
 * [certificationStatusText] 与 [diagnosticRows]（记录 -> 摘要行的纯映射）。
 * 全部为纯函数，不触 Android 运行时。
 */
class BackendDiagnosticsTextTest {

    // ===== 思考档位文案 =====

    @Test
    fun autoLevelIsMarkedRecommended() {
        assertTrue(thinkingLevelTitle(LocalThinkingLevel.AUTO).contains("推荐"))
        assertTrue(thinkingLevelTitle(LocalThinkingLevel.AUTO).contains("自动"))
    }

    @Test
    fun allThinkingLevelsHaveDistinctDescriptions() {
        val descs = LocalThinkingLevel.entries.map { thinkingLevelDesc(it) }
        assertEquals(4, descs.distinct().size)
        // 文案只描述策略，不承诺精确时长，也不暗示强制截断。
        LocalThinkingLevel.entries.forEach {
            val d = thinkingLevelDesc(it)
            assertTrue(d.isNotBlank())
            assertTrue(!d.contains("强制"))
            assertTrue(!d.contains("自动停止"))
        }
    }

    @Test
    fun autoDescriptionMentionsAdaptiveComplexity() {
        val d = thinkingLevelDesc(LocalThinkingLevel.AUTO)
        assertTrue(d.contains("按问题复杂度"))
        assertTrue(!d.contains("强制"))
    }

    // ===== 思考档位策略行（Task 5）=====

    @Test
    fun autoPolicyShowsRouteToEffectiveWithComplexity() {
        val rows = thinkingPolicyRows(
            ThinkingPolicyTelemetry(
                requestedLevel = "auto",
                effectiveLevel = "medium",
                complexity = "STANDARD",
                controlMode = "PROMPT_FALLBACK",
                targetMinMs = 8_000L,
                targetMaxMs = 15_000L,
                checkpointBudget = 4,
                generationMode = ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT,
                nativeBudgetCapability = "UNVERIFIED",
            ),
        )
        assertEquals(2, rows.size)
        val level = rows.first { it.label == "思考档位" }
        assertTrue(level.value.contains("自动"))
        assertTrue(level.value.contains("中"))
        assertTrue(level.value.contains("标准"))
        val target = rows.first { it.label == "思考策略" }
        assertTrue(target.value.contains("约 8–15 秒"))
        assertTrue(target.value.contains("4 个核验点"))
        assertTrue(target.value.contains("提示策略"))
        assertTrue(target.value.contains("单次生成"))
        assertTrue(target.value.contains("共享最大生成长度"))
        assertTrue(!target.value.contains("硬上限"))
        assertTrue(!target.value.contains("tokens"))
    }

    @Test
    fun manualPolicyShowsSingleLevelWithoutComplexity() {
        val rows = thinkingPolicyRows(
            ThinkingPolicyTelemetry(
                requestedLevel = "long",
                effectiveLevel = "long",
                complexity = null,
                controlMode = "PROMPT_FALLBACK",
                targetMinMs = 20_000L,
                targetMaxMs = 45_000L,
                checkpointBudget = 8,
                generationMode = ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT,
                nativeBudgetCapability = "UNVERIFIED",
            ),
        )
        val level = rows.first { it.label == "思考档位" }
        assertEquals("长", level.value)
        val strategy = rows.first { it.label == "思考策略" }
        assertTrue(strategy.value.contains("单次生成"))
        assertTrue(strategy.value.contains("共享最大生成长度"))
        assertTrue(strategy.value.contains("提示策略"))
        assertTrue(!strategy.value.contains("硬上限"))
        assertTrue(!strategy.value.contains("tokens"))
    }

    @Test
    fun nullPolicyYieldsNoRows() {
        assertTrue(thinkingPolicyRows(null).isEmpty())
    }

    // ===== 模板能力文案（Step 5）=====

    @Test
    fun templateCapabilityUnsupportedStatesTheSwitchIsIneffectiveNotThatThinkingIsOff() {
        // 明确不支持：只说开关无效，不声称「思考已关闭」。
        assertTrue(templateCapabilityText(ThinkingTemplateCapability.UNSUPPORTED).contains("开关无效"))
        assertTrue(!templateCapabilityText(ThinkingTemplateCapability.UNSUPPORTED).contains("已关闭"))
    }

    @Test
    fun templateCapabilityUnknownDoesNotClaimSwitchDisabled() {
        // 信息不足：不得声称「思考已关闭」，文案提示开关可能无效。
        val text = templateCapabilityText(ThinkingTemplateCapability.UNKNOWN)
        assertTrue(text.contains("未知"))
        assertTrue(text.contains("可能无效"))
        assertTrue(!text.contains("已关闭"))
    }

    @Test
    fun templateCapabilitySupportedAndNullAreDistinct() {
        assertTrue(templateCapabilityText(ThinkingTemplateCapability.SUPPORTED).contains("可生效"))
        assertTrue(templateCapabilityText(null).contains("未选择模型"))
    }

    // ===== 思考状态合并文案（Step 2）=====

    @Test
    fun requestedWithUnsupportedTemplateSaysSwitchIneffective() {
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = null,
            templateCapability = ThinkingTemplateCapability.UNSUPPORTED,
        )
        assertTrue(text.contains("模板不支持"))
        assertTrue(text.contains("开关无效"))
    }

    @Test
    fun requestedWithUnknownTemplateSaysPossiblyIneffective() {
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = null,
            templateCapability = ThinkingTemplateCapability.UNKNOWN,
        )
        assertTrue(text.contains("模板能力未知"))
        assertTrue(text.contains("可能无效"))
    }

    @Test
    fun requestedWithSupportedTemplateAndEnabledEffectSaysEffective() {
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = ThinkingEffect.ENABLED.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("已生效"))
    }

    @Test
    fun disableNotEffectiveIsSurfaced() {
        // 未请求但出现完整思考段：硬性要求口径「关闭未生效」。
        val text = thinkingStatusText(
            thinkingRequested = false,
            thinkingEffective = ThinkingEffect.THINKING_DISABLE_NOT_EFFECTIVE.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("关闭未生效"))
    }

    @Test
    fun disableRequestedAndEffectiveSaysClosed() {
        val text = thinkingStatusText(
            thinkingRequested = false,
            thinkingEffective = ThinkingEffect.DISABLED.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("已生效"))
    }

    @Test
    fun disableRequestedWithUnknownEffectDoesNotClaimEffective() {
        // Task 7 review M-3：请求关闭但效果 UNKNOWN（截断/失败/空响应生成）：不得声称「已生效」。
        val text = thinkingStatusText(
            thinkingRequested = false,
            thinkingEffective = ThinkingEffect.UNKNOWN.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("未能确认生效"))
        assertTrue(!text.contains("已生效"))
    }

    @Test
    fun requestedWithoutEvidenceDoesNotClaimEffective() {
        // 请求开启但无 ENABLED 证据（如生成被截断）：不得声称「已生效」。
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = null,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("未确认生效"))
    }

    // ===== 降级原因文案 =====

    @Test
    fun knownDowngradeReasonsMapToChinese() {
        assertEquals("GPU 空输出回退 CPU", downgradeReasonText("EMPTY_GPU_OUTPUT_FALLBACK"))
        assertEquals("lookahead 未认证（未启用）", downgradeReasonText(DowngradeReason.LOOKAHEAD_UNCERTIFIED.name))
        assertEquals("OpenCL 健康异常（未入链）", downgradeReasonText(DowngradeReason.OPENCL_UNHEALTHY.name))
        assertEquals("标准构建不含 QNN（解析为 CPU）", downgradeReasonText(DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD.name))
        assertEquals("当前模型 ≤7B，AUTO 用 CPU（GPU 仅 >7B 启用）", downgradeReasonText(DowngradeReason.AUTO_MODEL_AT_OR_BELOW_7B_CPU.name))
        assertEquals("模型参数未知，AUTO 默认 CPU", downgradeReasonText(DowngradeReason.AUTO_MODEL_PARAMETERS_UNKNOWN_CPU.name))
        assertEquals("GPU 加载失败，回退 CPU", downgradeReasonText(DowngradeReason.GPU_LOAD_FALLBACK.name))
        assertEquals("GPU 生成异常，回退 CPU", downgradeReasonText(DowngradeReason.GPU_GENERATION_FALLBACK.name))
        assertEquals("思考超过档位预算，已截断并直接作答", downgradeReasonText(LocalChatProvider.THINKING_BUDGET_TRUNCATED))
    }

    @Test
    fun unknownDowngradeReasonIsKeptVerbatim() {
        // 未知原因原样保留，不猜测也不崩溃。
        assertEquals("SOME_FUTURE_REASON", downgradeReasonText("SOME_FUTURE_REASON"))
    }

    // ===== 模型大小策略文案与默认链（Task 15）=====

    @Test
    fun autoSubtitleIsModelAware() {
        assertTrue(autoSubtitle(AutoBackendModelClass.GPU_ELIGIBLE, true).contains("GPU 优先"))
        assertTrue(autoSubtitle(AutoBackendModelClass.GPU_ELIGIBLE, false).contains("GPU 未就绪"))
        assertTrue(autoSubtitle(AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, true).contains("≤7B"))
        assertTrue(autoSubtitle(AutoBackendModelClass.CPU_UNKNOWN_PARAMETERS, true).contains("参数未知"))
    }

    @Test
    fun previewFallbackChainRespectsModelSizeGate() {
        // AUTO：仅 >7B 且 GPU 就绪时呈 GPU→CPU；小/未知模型恒 CPU。
        assertEquals(
            listOf(BackendType.MNN_GPU, BackendType.MNN_CPU),
            previewFallbackChain(BackendPreference.AUTO, AutoBackendModelClass.GPU_ELIGIBLE, gpuReady = true),
        )
        assertEquals(
            listOf(BackendType.MNN_CPU),
            previewFallbackChain(BackendPreference.AUTO, AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, gpuReady = true),
        )
        assertEquals(
            listOf(BackendType.MNN_CPU),
            previewFallbackChain(BackendPreference.AUTO, AutoBackendModelClass.CPU_UNKNOWN_PARAMETERS, gpuReady = true),
        )
        // 显式 GPU 不受大小门槛限制。
        assertEquals(
            listOf(BackendType.MNN_GPU, BackendType.MNN_CPU),
            previewFallbackChain(BackendPreference.MNN_GPU, AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, gpuReady = true),
        )
    }

    // ===== 认证状态文案 =====

    private fun cert(lookahead: Boolean = false, step: Int = 1) = CertifiedInferenceOptions(
        deviceFingerprint = "d",
        modelFingerprint = "m",
        variant = "CPU_OPTIMIZED",
        nativeBuildId = "b",
        mnnCommit = "c",
        lookahead = lookahead,
        decodeStepTokens = step,
    )

    @Test
    fun unCertifiedStatesBothGatedFeaturesAreOff() {
        val text = certificationStatusText(null)
        assertTrue(text.contains("未认证"))
        assertTrue(text.contains("lookahead"))
        assertTrue(text.contains("步进"))
    }

    @Test
    fun certifiedLookaheadOnlyMentionsLookahead() {
        val text = certificationStatusText(cert(lookahead = true, step = 1))
        assertTrue(text.contains("lookahead"))
        assertTrue(!text.contains("步进"))
    }

    @Test
    fun certifiedStepMentionsStep() {
        assertTrue(certificationStatusText(cert(lookahead = false, step = 2)).contains("步进 2"))
        assertTrue(certificationStatusText(cert(lookahead = true, step = 2)).contains("lookahead + 多 token 步进 2"))
    }

    // ===== 记录 -> 诊断行映射 =====

    private fun record(
        thinkingRequested: Boolean? = true,
        thinkingEffective: String? = ThinkingEffect.ENABLED.name,
        templateCapability: String? = ThinkingTemplateCapability.SUPPORTED.name,
        backend: BackendType? = BackendType.MNN_CPU,
        attemptTrace: List<String> = listOf("CPU_OPTIMIZED"),
        downgradeReasons: List<String> = emptyList(),
        prefillMs: Long? = 123L,
        decodeMs: Long? = 456L,
        ttftMs: Long? = 78L,
        decodeTps: Float? = 12.5f,
        kvReuse: Boolean? = true,
    ) = InferenceTurnRecord(
        generationId = "g1",
        requestedMode = InferencePerformanceMode.BALANCED,
        effectiveMode = InferencePerformanceMode.BALANCED,
        backend = backend,
        startedElapsedMs = 0L,
        endedElapsedMs = 1000L,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        ttftMs = ttftMs,
        decodeTps = decodeTps,
        kvReuse = kvReuse,
        attemptTrace = attemptTrace,
        downgradeReasons = downgradeReasons,
        thinkingRequested = thinkingRequested,
        templateCapability = templateCapability,
        thinkingEffective = thinkingEffective,
    )

    @Test
    fun nullRecordYieldsNoRows() {
        assertTrue(diagnosticRows(null, ThinkingTemplateCapability.SUPPORTED).isEmpty())
    }

    @Test
    fun recordYieldsThinkingBackendAndTimingRows() {
        val rows = diagnosticRows(record(), ThinkingTemplateCapability.SUPPORTED)
        val labels = rows.map { it.label }
        assertTrue(labels.contains("深度思考"))
        assertTrue(labels.contains("实际后端"))
        assertTrue(labels.contains("阶段计时"))
        val thinking = rows.first { it.label == "深度思考" }.value
        assertTrue(thinking.contains("已生效"))
        val backend = rows.first { it.label == "实际后端" }.value
        assertTrue(backend.contains("MNN CPU"))
        assertTrue(backend.contains("CPU_OPTIMIZED"))
        val timings = rows.first { it.label == "阶段计时" }.value
        assertTrue(timings.contains("prefill 123ms"))
        assertTrue(timings.contains("decode 456ms"))
        assertTrue(timings.contains("TTFT 78ms"))
        assertTrue(timings.contains("12.5 tok/s"))
        assertTrue(timings.contains("KV 复用"))
    }

    @Test
    fun fallbackReasonRowRenderedWhenDowngradesPresent() {
        val rows = diagnosticRows(
            record(downgradeReasons = listOf("EMPTY_GPU_OUTPUT_FALLBACK", DowngradeReason.LOOKAHEAD_UNCERTIFIED.name)),
            ThinkingTemplateCapability.SUPPORTED,
        )
        val fallback = rows.firstOrNull { it.label == "回退/降级" }
        assertTrue("应存在回退/降级行", fallback != null)
        assertTrue(fallback!!.value.contains("GPU 空输出回退 CPU"))
        assertTrue(fallback.value.contains("lookahead 未认证"))
    }

    @Test
    fun timingsRowOmittedWhenAllTimingsNull() {
        val rows = diagnosticRows(
            record(prefillMs = null, decodeMs = null, ttftMs = null, decodeTps = null, kvReuse = null),
            ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(rows.none { it.label == "阶段计时" })
    }

    @Test
    fun contextDowngradeRowShownWhenAdmissionReducedContext() {
        // Task 15：内存准入把 context 仅本次降级 -> 显示「配置值 → 实际值（仅本次）」。
        val rows = diagnosticRows(
            record().copy(
                configuredContextTokens = 8192,
                actualContextTokens = 4096,
                downgradeReasons = listOf(DowngradeReason.MEMORY.name),
            ),
            ThinkingTemplateCapability.SUPPORTED,
        )
        val ctx = rows.firstOrNull { it.label == "上下文" }
        assertTrue("应存在上下文降级行", ctx != null)
        assertTrue(ctx!!.value.contains("8192 → 4096"))
        assertTrue(ctx.value.contains("仅本次"))
        assertTrue(ctx.value.contains("未修改设置"))
        val fallback = rows.firstOrNull { it.label == "回退/降级" }
        assertTrue(fallback!!.value.contains("内存受限"))
    }

    @Test
    fun noContextRowWhenNotDowngraded() {
        val rows = diagnosticRows(record(), ThinkingTemplateCapability.SUPPORTED)
        assertTrue(rows.none { it.label == "上下文" })
    }

    // ===== CPU/GPU prefill 对比摘要（Task 15/16）=====

    private fun prefillResult(
        prefillTps: Float?,
        ttftMs: Float?,
        decodeTps: Float?,
        backendCounts: Map<String, Int>,
    ) = BenchmarkScenarioResult(
        scenario = InferenceBenchmarkScenario.LONG_PREFILL,
        deviceFingerprint = "d",
        configFingerprint = "c",
        summary = BenchmarkSummary(
            medianPrefillTps = prefillTps,
            medianTtftMs = ttftMs,
            medianDecodeTps = decodeTps,
        ),
        recordedSampleCount = 5,
        warmupSampleCount = 1,
        coolRun = true,
        actualBackendCounts = backendCounts,
    )

    @Test
    fun prefillComparisonTextShowsBothSidesAndActualBackends() {
        val text = prefillComparisonText(
            prefillResult(100f, 800f, 10f, mapOf("MNN_CPU" to 5)),
            prefillResult(200f, 500f, 10f, mapOf("MNN_GPU" to 3, "MNN_CPU" to 2)),
        )
        assertTrue(text.contains("CPU:"))
        assertTrue(text.contains("GPU:"))
        assertTrue(text.contains("prefill 100.0 tok/s"))
        assertTrue(text.contains("TTFT 800ms"))
        // GPU 目标混入 CPU fallback 时如实标出，不冒充 GPU 性能。
        assertTrue(text.contains("MNN_GPU=3"))
        assertTrue(text.contains("MNN_CPU=2"))
    }
}
