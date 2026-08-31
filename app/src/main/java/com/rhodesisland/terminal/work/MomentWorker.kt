package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatProviderType
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.random.Random

/**
 * 自动发圈 Worker：由 [MomentScheduler] 的 PeriodicWork 每 15 分钟驱动。检查
 * `moment_next_fire_at`，到点则轮换选一个已选角色，经 [MomentGenerationCoordinator]
 * 生成一条朋友圈（LLM 文案 + 生图）落库，并写回下一个目标时间。
 *
 * 与 GreetingWorker 同构（周期保活 + 门控 + 时段检查），差异：
 * - 不发通知（用户打开朋友圈页即可见）；
 * - 无每日配额，节奏由用户可调间隔（小时）决定；
 * - 生图 API 未配置/失败时由协调器降级纯文字，仍算成功投递。
 * 所有路径都以 success 返回（靠周期保活，不靠 reschedule 自延续）。
 */
class MomentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** 门控设置读取重试次数（冷启动 DataStore I/O 阻塞是暂时性的）。 */
        private const val GATING_READ_ATTEMPTS = 3
        private const val GATING_READ_RETRY_MS = 2_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as RhodesApp).container
        val settings = container.settingsRepository

        // 1. 读门控状态（带重试）；读不到 -> 本周期跳过，等下个周期（保活语义）
        val state = readGatingState(settings) ?: return Result.success()
        if (!state.enabled) return Result.success()
        if (settings.getActiveProviderNow() != ChatProviderType.CLOUD) return Result.success()
        if (state.charIds.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < AppConfig.Moment.HOUR_START || hour >= AppConfig.Moment.HOUR_END) {
            // 非时段：把 next_fire_at 推到下一个 HOUR_START
            withTimeoutOrNullCompat { settings.setMomentNextFireAt(MomentScheduler.nextWindowStart(now)) }
            return Result.success()
        }

        // 2. 到点检查
        val nextFire = settings.getMomentNextFireAtNow()
        if (nextFire <= 0L) {
            // 首次启用尚未初始化：补算一个目标时间（当前 + 间隔 ± 抖动），本周期不投递
            withTimeoutOrNullCompat {
                settings.setMomentNextFireAt(MomentScheduler.computeNextFireAt(now, state.intervalHours))
            }
            return Result.success()
        }
        if (now < nextFire) return Result.success()

        // 3. 到点：轮换选角色并生成
        val charId = pickCharacter(settings, state.charIds)
        val delivered = try {
            container.momentGenerationCoordinator.generateAndPost(charId, imageCount = 1)
            true
        } catch (e: Exception) {
            false
        }

        // 4. 写回下一次目标时间 + 上次角色
        val nextDelay = if (delivered) {
            MomentScheduler.computeNextFireAt(now, state.intervalHours)
        } else {
            // 失败退避 45 分钟，避免每周期都失败空转（与 Greeting 一致）
            now + AppConfig.Greeting.RETRY_DELAY_MS
        }
        withTimeoutOrNullCompat {
            settings.setMomentNextFireAt(nextDelay)
            if (delivered) settings.setMomentLastCharId(charId)
        }
        return Result.success()
    }

    private data class GatingState(
        val enabled: Boolean,
        val charIds: Set<String>,
        val intervalHours: Int,
    )

    private suspend fun readGatingState(settings: com.rhodesisland.terminal.data.repository.SettingsRepository): GatingState? {
        repeat(GATING_READ_ATTEMPTS) { attempt ->
            val config = runCatching { settings.getMomentAutoConfigNow() }.getOrNull()
            if (config != null) {
                return GatingState(config.enabled, config.characterIds, config.intervalHours)
            }
            if (attempt < GATING_READ_ATTEMPTS - 1) delay(GATING_READ_RETRY_MS)
        }
        return null
    }

    /** 严格轮询（与 GreetingWorker.pickCharacter 语义一致）。 */
    private suspend fun pickCharacter(
        settings: com.rhodesisland.terminal.data.repository.SettingsRepository,
        charIds: Set<String>,
    ): String {
        if (charIds.size == 1) return charIds.first()
        val sorted = charIds.sorted()
        val last = settings.getMomentLastCharIdNow()
        val idx = last?.let { sorted.indexOf(it).takeIf { i -> i >= 0 } }
        return if (idx != null) sorted[(idx + 1) % sorted.size]
        else sorted[Random.nextInt(sorted.size)]
    }

    private suspend fun withTimeoutOrNullCompat(block: suspend () -> Unit) {
        kotlinx.coroutines.withTimeoutOrNull(WRITE_TIMEOUT_MS) { runCatching { block() } }
    }
}
