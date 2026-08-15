package com.rhodesisland.terminal.video

/**
 * Seedance 流水线自动重试策略（纯 JVM，无 Android 依赖）。
 *
 * 统一回答「这一次瞬时失败是否继续自动重试、退避多久」：
 * - [automaticRetryCount] 为已完成的自动重试次数（0 基，持久化在
 *   [com.rhodesisland.terminal.data.model.SeedanceVideo.automaticRetryCount]）；
 * - 达到 [maxAutomaticRetries] 后返回 null，调用方转永久失败态（FAILED_*）等待用户；
 * - 退避为指数级（base * 2^attempt）封顶 [maxBackoffMillis]；
 * - 若上游给出 Retry-After（毫秒），优先采用并封顶 [maxBackoffMillis]。
 *
 * 注意：POST 创建任务的歧义失败（AMBIGUOUS_TRANSPORT）**绝不**走本策略自动重试，
 * 由协调器直接置 FAILED_SUBMISSION/AMBIGUOUS_POST + requiresCostConfirmation。
 */
class SeedanceRetryPolicy(
    private val maxAutomaticRetries: Int = DEFAULT_MAX_AUTOMATIC_RETRIES,
    private val baseBackoffMillis: Long = DEFAULT_BASE_BACKOFF_MILLIS,
    private val maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
) {

    /**
     * 计算第 [automaticRetryCount] 次（0 基）自动重试的退避延迟。
     * 返回 null 表示重试次数耗尽，应转永久失败态。
     */
    fun retryDelayMillis(automaticRetryCount: Int, retryAfterMillis: Long? = null): Long? {
        if (automaticRetryCount >= maxAutomaticRetries) return null
        if (retryAfterMillis != null && retryAfterMillis > 0) {
            return minOf(retryAfterMillis, MAX_RETRY_AFTER_MILLIS)
        }
        val exponent = automaticRetryCount.coerceAtMost(30)
        val factor = 1L shl exponent
        return (baseBackoffMillis * factor).coerceAtMost(maxBackoffMillis)
    }

    companion object {
        /** 自动重试次数上限。 */
        const val DEFAULT_MAX_AUTOMATIC_RETRIES = 5

        /** 指数退避基数。 */
        const val DEFAULT_BASE_BACKOFF_MILLIS = 15_000L

        /** 退避封顶。 */
        const val DEFAULT_MAX_BACKOFF_MILLIS = 5 * 60_000L

        /** Retry-After 头封顶：服务端指令最长采纳 10 分钟。 */
        const val MAX_RETRY_AFTER_MILLIS = 10 * 60_000L

        /** 排队/生成中任务的下一次轮询间隔。 */
        const val POLL_INTERVAL_MILLIS = 10_000L
    }
}
