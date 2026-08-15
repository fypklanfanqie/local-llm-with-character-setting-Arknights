package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * 本地推理 prompt 窗口规划结果（Task 5）。
 *
 * [Success] 保留 system prompt、最近最大的完整 user/assistant 轮次，以及最新 user 消息；
 * [AdmissionFailure] 表示连 mandatory 消息都无法放入，不会静默裁剪最新用户输入。
 */
sealed class PromptWindowResult {
    data class Success(val plan: PromptWindowPlan) : PromptWindowResult()

    data class AdmissionFailure(
        val reason: PromptAdmissionFailureReason,
        val requiredInputTokens: Int,
        val availableInputTokens: Int,
        val message: String,
        val plan: PromptWindowPlan? = null,
    ) : PromptWindowResult()
}

enum class PromptAdmissionFailureReason {
    MISSING_SYSTEM_PROMPT,
    MISSING_LATEST_USER,
    SYSTEM_PROMPT_TOO_LARGE,
    LATEST_USER_TOO_LARGE,
}

/** 传给本地 MNN 的精确消息窗口。文本不摘要、不重写。 */
data class PromptWindowPlan(
    val messages: List<ChatMessage>,
    val estimatedInputTokens: Int,
    val reservedOutputTokens: Int,
    val anchorChanged: Boolean,
    val downgradeReason: String?,
    /** system + 被保留的最早 user 指纹；首轮以最新 user 为锚，便于下一轮前缀扩展保持稳定。 */
    val anchor: String,
)

/** 类型化准入异常：UI 可展示明确错误，不能把最新用户消息静默截断。 */
class PromptAdmissionException(
    val failure: PromptWindowResult.AdmissionFailure,
) : IllegalArgumentException(failure.message)

/**
 * 保守 prompt 窗口规划器。
 *
 * 预算：`admittedContextTokens - reservedOutputTokens - templateReserveTokens`。
 * 历史只按完整 user/assistant 二元轮次加入，从最近向旧选择最大后缀。最新 user 与 system 是 mandatory。
 */
class PromptWindowPlanner {

    fun plan(
        messages: List<ChatMessage>,
        admittedContextTokens: Int,
        requestedOutputTokens: Int,
        templateReserveTokens: Int = DEFAULT_TEMPLATE_RESERVE_TOKENS,
        previousAnchor: String? = null,
        /** 可选的历史实测 token 数，key 为 [messages] 原始索引；无值时走保守估算。 */
        knownMessageTokenCounts: Map<Int, Int> = emptyMap(),
    ): PromptWindowResult {
        require(admittedContextTokens > 0) { "admittedContextTokens must be > 0" }
        require(requestedOutputTokens > 0) { "requestedOutputTokens must be > 0" }
        require(templateReserveTokens >= 0) { "templateReserveTokens must be >= 0" }

        val systemIndex = messages.indexOfFirst { it.role == ROLE_SYSTEM }
        if (systemIndex < 0) {
            return admissionFailure(
                PromptAdmissionFailureReason.MISSING_SYSTEM_PROMPT,
                required = 0,
                available = admittedContextTokens - templateReserveTokens,
                message = "本地推理缺少 system prompt",
            )
        }
        val latestUserIndex = messages.indexOfLast { it.role == ROLE_USER }
        if (latestUserIndex < 0 || latestUserIndex <= systemIndex) {
            return admissionFailure(
                PromptAdmissionFailureReason.MISSING_LATEST_USER,
                required = 0,
                available = admittedContextTokens - templateReserveTokens,
                message = "本地推理缺少最新用户消息",
            )
        }

        val system = messages[systemIndex]
        val latestUser = messages[latestUserIndex]
        val systemTokens = messageTokens(system, systemIndex, knownMessageTokenCounts)
        val latestUserTokens = messageTokens(latestUser, latestUserIndex, knownMessageTokenCounts)
        val mandatoryTokens = systemTokens + latestUserTokens
        val contextAfterTemplate = (admittedContextTokens - templateReserveTokens).coerceAtLeast(0)
        val maxOutputAfterMandatory = contextAfterTemplate - mandatoryTokens
        if (maxOutputAfterMandatory < MIN_OUTPUT_RESERVE_TOKENS) {
            val systemTooLarge = systemTokens + MIN_OUTPUT_RESERVE_TOKENS > contextAfterTemplate
            return admissionFailure(
                reason = if (systemTooLarge) PromptAdmissionFailureReason.SYSTEM_PROMPT_TOO_LARGE
                    else PromptAdmissionFailureReason.LATEST_USER_TOO_LARGE,
                required = mandatoryTokens,
                available = (contextAfterTemplate - MIN_OUTPUT_RESERVE_TOKENS).coerceAtLeast(0),
                message = if (systemTooLarge) {
                    "角色 system prompt 过长，无法在当前上下文中保留；请提高上下文长度或缩短人设"
                } else {
                    "最新消息过长，无法在当前上下文中保留完整内容；请缩短消息或提高上下文长度"
                },
            )
        }

        // requested 是上限；显式 Unlimited 仍受本轮实际 context 限制，不能让输出 reserve 挤掉 mandatory 输入。
        val outputReserve = requestedOutputTokens.coerceAtMost(maxOutputAfterMandatory)
        val inputBudget = contextAfterTemplate - outputReserve

        val completeTurns = completeHistoricalTurns(
            messages = messages,
            fromExclusive = systemIndex,
            toExclusive = latestUserIndex,
        )
        val selectedReverse = ArrayList<IndexedTurn>()
        var inputTokens = mandatoryTokens
        for (turn in completeTurns.asReversed()) {
            val turnTokens = messageTokens(turn.user.message, turn.user.index, knownMessageTokenCounts) +
                messageTokens(turn.assistant.message, turn.assistant.index, knownMessageTokenCounts)
            if (inputTokens + turnTokens > inputBudget) break
            selectedReverse += turn
            inputTokens += turnTokens
        }
        val selected = selectedReverse.asReversed()
        val plannedMessages = buildList {
            add(system)
            for (turn in selected) {
                add(turn.user.message)
                add(turn.assistant.message)
            }
            add(latestUser)
        }

        val anchor = anchorFor(system, selected.firstOrNull()?.user?.message ?: latestUser)
        val anchorChanged = previousAnchor != null && previousAnchor != anchor
        val droppedTurns = completeTurns.size - selected.size
        val downgradeReason = when {
            anchorChanged -> "history_anchor_changed"
            droppedTurns > 0 -> "history_trimmed_to_fit_context"
            outputReserve < requestedOutputTokens -> "output_reserve_capped_by_context"
            else -> null
        }
        return PromptWindowResult.Success(
            PromptWindowPlan(
                messages = plannedMessages,
                estimatedInputTokens = inputTokens,
                reservedOutputTokens = outputReserve,
                anchorChanged = anchorChanged,
                downgradeReason = downgradeReason,
                anchor = anchor,
            )
        )
    }

    private fun completeHistoricalTurns(
        messages: List<ChatMessage>,
        fromExclusive: Int,
        toExclusive: Int,
    ): List<IndexedTurn> {
        val turns = ArrayList<IndexedTurn>()
        var pendingUser: IndexedMessage? = null
        for (index in (fromExclusive + 1) until toExclusive) {
            val message = messages[index]
            when (message.role) {
                ROLE_USER -> pendingUser = IndexedMessage(index, message)
                ROLE_ASSISTANT -> {
                    val user = pendingUser
                    if (user != null) {
                        turns += IndexedTurn(user, IndexedMessage(index, message))
                        pendingUser = null
                    }
                    // 无 pending user 的 assistant 是孤儿，明确忽略。
                }
            }
        }
        return turns
    }

    private fun messageTokens(
        message: ChatMessage,
        index: Int,
        known: Map<Int, Int>,
    ): Int = (known[index]?.takeIf { it > 0 }
        ?: estimateTextTokens(message.modelContent ?: message.content)) + MESSAGE_TEMPLATE_OVERHEAD_TOKENS

    private fun admissionFailure(
        reason: PromptAdmissionFailureReason,
        required: Int,
        available: Int,
        message: String,
    ) = PromptWindowResult.AdmissionFailure(
        reason = reason,
        requiredInputTokens = required,
        availableInputTokens = available,
        message = message,
    )

    private data class IndexedMessage(val index: Int, val message: ChatMessage)
    private data class IndexedTurn(val user: IndexedMessage, val assistant: IndexedMessage)

    companion object {
        const val DEFAULT_TEMPLATE_RESERVE_TOKENS = 32
        const val MIN_OUTPUT_RESERVE_TOKENS = 16
        private const val MESSAGE_TEMPLATE_OVERHEAD_TOKENS = 4
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /**
         * 无 tokenizer 时的保守估算：CJK/emoji/非 ASCII 每 code point 计 1 token；ASCII 每 3 字符
         * 向上取整（代码/标识符比普通英文更碎）。比常见 BPE 略保守，同时不切 UTF-16 surrogate pair。
         */
        fun estimateTextTokens(text: String): Int {
            if (text.isEmpty()) return 0
            var ascii = 0
            var nonAscii = 0
            var offset = 0
            while (offset < text.length) {
                val cp = Character.codePointAt(text, offset)
                if (cp <= 0x7f) ascii++ else nonAscii++
                offset += Character.charCount(cp)
            }
            return ceil(ascii / 3.0).toInt() + nonAscii
        }

        private fun anchorFor(system: ChatMessage, firstUser: ChatMessage): String {
            val raw = buildString {
                append(system.role).append('\u0000').append(system.content)
                append('\u0001').append(firstUser.role).append('\u0000')
                append(firstUser.modelContent ?: firstUser.content)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/** Task 6 可按性能模式覆盖的生成安全策略；Task 5 先提供默认模式映射。 */
data class GenerationSafetyPolicy(
    val maxTokens: Int,
    val stallTimeoutMs: Long,
    val wallClockTimeoutMs: Long,
) {
    init {
        require(maxTokens > 0)
        require(stallTimeoutMs > 0L)
        require(wallClockTimeoutMs > 0L)
    }

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 90_000L
        const val BALANCED_WALL_CLOCK_TIMEOUT_MS = 15 * 60_000L
        const val MAXIMUM_SPEED_WALL_CLOCK_TIMEOUT_MS = 10 * 60_000L

        fun forMode(mode: InferencePerformanceMode, maxTokens: Int): GenerationSafetyPolicy = when (mode) {
            InferencePerformanceMode.BALANCED -> GenerationSafetyPolicy(
                maxTokens = maxTokens,
                stallTimeoutMs = DEFAULT_STALL_TIMEOUT_MS,
                wallClockTimeoutMs = BALANCED_WALL_CLOCK_TIMEOUT_MS,
            )
            InferencePerformanceMode.MAXIMUM_SPEED -> GenerationSafetyPolicy(
                maxTokens = maxTokens,
                stallTimeoutMs = DEFAULT_STALL_TIMEOUT_MS,
                wallClockTimeoutMs = MAXIMUM_SPEED_WALL_CLOCK_TIMEOUT_MS,
            )
        }
    }
}

/** 请求级终止状态：首个明确原因胜出，后端回退不能清空或覆盖。 */
class GenerationRequestStopState {
    private val stopReason = AtomicReference<CompletionReason?>(null)

    fun beginRequest() {
        stopReason.set(null)
    }

    fun requestStop(reason: CompletionReason) {
        stopReason.compareAndSet(null, reason)
    }

    fun reason(): CompletionReason? = stopReason.get()

    fun canTryNextBackend(): Boolean = stopReason.get() == null
}

/** 整次 BackendManager.generate 的共享控制面：终止原因、累计 token、wall/stall deadline 均跨 fallback。 */
class GenerationExecutionControl(
    policy: GenerationSafetyPolicy,
    startedElapsedMs: Long,
) {
    private val stopState = GenerationRequestStopState().also { it.beginRequest() }
    private val guard = GenerationProgressGuard(policy, startedElapsedMs)

    fun requestStop(reason: CompletionReason) = stopState.requestStop(reason)

    fun reason(): CompletionReason? = stopState.reason()

    fun canTryNextBackend(): Boolean = stopState.canTryNextBackend()

    fun onProgress(generationId: String, generatedTokens: Int, progressElapsedMs: Long) {
        guard.onProgress(generationId, generatedTokens, progressElapsedMs)
        guard.completionReason(progressElapsedMs)?.let(stopState::requestStop)
    }

    fun completionReason(nowElapsedMs: Long): CompletionReason? {
        val reason = stopState.reason() ?: guard.completionReason(nowElapsedMs)
        if (reason != null) stopState.requestStop(reason)
        return reason
    }

    fun remainingTokens(): Int = guard.remainingTokens()

    fun generatedTokens(): Int = guard.generatedTokens()
}

/**
 * 请求级进度 guard。一个实例覆盖整条后端回退链：各 attempt 以 generationId 分桶，token 数求和，
 * 壁钟起点永不因 fallback 重置；进度时间取实际回调时间而非 watchdog 轮询时间。
 */
class GenerationProgressGuard(
    private val policy: GenerationSafetyPolicy,
    private val startedElapsedMs: Long,
) {
    private val attemptTokenCounts = mutableMapOf<String, Int>()
    private var lastProgressElapsedMs: Long = startedElapsedMs

    /** 兼容纯逻辑/单 attempt 调用。 */
    fun onProgress(generatedTokens: Int, nowElapsedMs: Long) =
        onProgress("default", generatedTokens, nowElapsedMs)

    @Synchronized
    fun onProgress(generationId: String, generatedTokens: Int, progressElapsedMs: Long) {
        val previous = attemptTokenCounts[generationId] ?: 0
        if (generatedTokens > previous) {
            attemptTokenCounts[generationId] = generatedTokens
            lastProgressElapsedMs = maxOf(lastProgressElapsedMs, progressElapsedMs)
        }
    }

    @Synchronized
    fun generatedTokens(): Int = attemptTokenCounts.values.sum()

    @Synchronized
    fun remainingTokens(): Int = (policy.maxTokens - attemptTokenCounts.values.sum()).coerceAtLeast(0)

    @Synchronized
    fun completionReason(nowElapsedMs: Long): CompletionReason? = when {
        attemptTokenCounts.values.sum() >= policy.maxTokens -> CompletionReason.MAX_TOKENS
        nowElapsedMs - startedElapsedMs >= policy.wallClockTimeoutMs -> CompletionReason.TIMEOUT
        nowElapsedMs - lastProgressElapsedMs >= policy.stallTimeoutMs -> CompletionReason.TIMEOUT
        else -> null
    }
}
