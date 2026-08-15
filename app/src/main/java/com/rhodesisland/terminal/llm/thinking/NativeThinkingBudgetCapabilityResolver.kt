package com.rhodesisland.terminal.llm.thinking

/**
 * 原生思考预算能力的保守判定结果。
 *
 * - [VERIFIED]：存在完整、可复现的适配器契约 + 运行时能力声明 + 指纹 + 真机认证证据，
 *   才允许把档位映射为原生 token budget / effort 参数。
 * - [UNVERIFIED]：任何证据缺失或仅有关键词/开关证据。此时必须回退 [ThinkingControlMode.PROMPT_FALLBACK]。
 */
enum class NativeThinkingBudgetCapability {
    VERIFIED,
    UNVERIFIED,
}

/**
 * 原生思考预算能力证据（五个维度缺一不可）。
 *
 * 只有同时满足以下条件才构成 VERIFIED 证据：
 * - [adapterVersion]：应用端存在版本化适配器，明确知道配置键、类型、单位和作用范围（不能动态猜测键）；
 * - [runtimeCapabilityId]：native 握手声明精确能力（如未来的 `thinking_budget_tokens_v1`），
 *   不能用宽泛的 `summary_v2`/`thinking` 代替；
 * - [modelFingerprint]：模型模板/元数据与适配器契约匹配（模板字符串扫描只是候选证据）；
 * - [nativeBuildId]：native 构建身份（认证键分量之一）；
 * - [certificationId]：真机 A/B 认证记录（同一 device+model+native 组合、多档位下 reasoning 长度/
 *   时长出现稳定显著差异且两档都能产出最终答案）。
 */
data class NativeThinkingBudgetEvidence(
    val adapterVersion: String,
    val runtimeCapabilityId: String,
    val modelFingerprint: String,
    val nativeBuildId: String,
    val certificationId: String,
)

/**
 * 原生思考预算能力门禁（首期只回 UNVERIFIED）。
 *
 * 当前 MNN 调用链只验证了 `enable_thinking` 开关（[com.rhodesisland.terminal.llm.template.
 * ThinkingTemplateCapabilityResolver] 只判定模板是否存在该分支）；没有任何证据证明运行时支持
 * 独立的 thinking token budget / reasoning effort。因此首期不注册任何 adapter/capability ID，
 * 生产路径统一 [UNVERIFIED]，防止制造「名字不同、行为相同」的伪 SHORT/MEDIUM/LONG。
 *
 * 未来扩展流程（勿在首期实现）：
 * 1. 在 native 与 pinned MNN 上验证具体配置键/语义；
 * 2. 定义版本化 adapter 并注册 [resolve] 的匹配逻辑；
 * 3. 真机 A/B 认证（多档位差异 + 两档均产最终答案）落盘；
 * 4. 升级 JNI 契约与认证键后再启用。
 */
class NativeThinkingBudgetCapabilityResolver {

    /** 首期未注册任何已认证适配器：任何证据（含 null）都判 UNVERIFIED。 */
    fun resolve(evidence: NativeThinkingBudgetEvidence?): NativeThinkingBudgetCapability =
        NativeThinkingBudgetCapability.UNVERIFIED
}
