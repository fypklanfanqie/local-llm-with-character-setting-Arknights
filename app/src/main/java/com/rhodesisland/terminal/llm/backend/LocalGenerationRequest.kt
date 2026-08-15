package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.GenerationExecutionControl
import com.rhodesisland.terminal.llm.profile.ResolvedInferencePlan
import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry

/**
 * 单阶段本地生成的请求值对象（Task 2）。
 *
 * 把一次生成所需的全部参数收敛为不可变快照，交由 [LocalGenerationRunner] 执行。生产实现是
 * [BackendManager] 的 adapter（[BackendManager.asLocalGenerationRunner]）；测试用 fake runner
 * 只记录调用，不实例化 Android Context / 真实 native。
 *
 * 单阶段语义：思考与正文在同一次 generate 中共享 [maxTokens]（= [ResolvedInferencePlan.maxOutputTokens]），
 * 不再有「阶段 1 思考硬上限 + 阶段 2 直接作答」的两阶段控制流，也不追加「直接给出最终答案」补答指令。
 *
 * 放于 `llm.backend` 包（与 [BackendManager] 同包）：使 [LocalGenerationRunner] 的实现方
 * [BackendManager] 与请求值对象同包，避免 `llm.backend -> provider.local` 的反向依赖边。
 */
internal data class LocalGenerationRequest(
    val modelPath: String,
    val messages: List<ChatMessage>,
    /** 本次生成的总输出上限：恒为 [ResolvedInferencePlan.maxOutputTokens]，思考与正文共享。 */
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val repeatPenalty: Float,
    val enableThinking: Boolean,
    val downgradeReasons: List<String>,
    val resolvedPlan: ResolvedInferencePlan,
    val thinkingRequested: Boolean,
    val templateCapability: String,
    val thinkingClassifier: ThinkingOutputClassifier,
    val thinkingPolicy: ThinkingPolicyTelemetry?,
    val outputPolicy: GenerationOutputPolicy,
    val decodeStepTokens: Int,
)

/**
 * 单阶段生成 runner（Task 2 seam）：一次调用 = 思考与正文共享总上限的一次推理。
 *
 * 生产默认实现转发到 [BackendManager.generate] 的既有回退/attempt 执行语义
 * （见 [BackendManager.asLocalGenerationRunner]）；测试注入 fake 只记录调用。
 *
 * 用普通 [interface] 而非 `fun interface`：抽象方法为 [suspend] 时，Kotlin 对 suspend `fun interface`
 * 的 SAM 转换支持不可靠，实现方（[BackendManager.asLocalGenerationRunner]）与测试均用显式 object。
 */
internal interface LocalGenerationRunner {
    suspend fun generate(
        request: LocalGenerationRequest,
        executionControl: GenerationExecutionControl,
        onToken: (String) -> Boolean,
    ): BackendManager.GenerationResult
}
