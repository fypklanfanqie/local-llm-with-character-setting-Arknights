package com.rhodesisland.terminal.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.isFreeProxyBaseUrl
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.WorldviewTargetType
import com.rhodesisland.terminal.data.model.matchesScope
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.llm.LorebookEngine
import com.rhodesisland.terminal.notification.AppLifecycleObserver
import com.rhodesisland.terminal.notification.GreetingNotificationManager
import com.rhodesisland.terminal.util.PromptWindowAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 角色问候 Worker：由 [GreetingScheduler] 的 PeriodicWork 每 15 分钟驱动一次。检查投递目标时间
 * `next_fire_at`，到点则调用云端 API 让所选角色主动发一条符合人设的消息，落库到该角色活跃会话，
 * 发类微信通知，并写回下一个目标时间。
 *
 * 由**周期性工作**驱动而非自延续链：错失一次下个周期仍会触发，链条不会因进程被杀而永久断裂
 * （修复「退出 App 后收不到问候 / 只有一个角色发过一次」）。仅云端模式生效；本地/关闭/未选角色/
 * 不在时段/配额满时静默跳过。所有路径都以 success 返回（靠周期保活，不靠 reschedule 自延续），
 * 避免 WorkManager 指数退避 retry 风暴。
 *
 * 测试模式（[GreetingScheduler.KEY_TEST]）：用户在设置页点「测试」触发，10s 后执行，
 * 跳过开关/配额/时段门控，始终弹通知预览，不计配额、不更新目标时间。
 */
class GreetingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val thinkRegex = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    companion object {
        /** 门控设置读取重试次数（每次含 DataStore 5s 超时）。冷启动 I/O 阻塞是暂时性的，重试通常可成功。 */
        private const val GATING_READ_ATTEMPTS = 3
        /** 门控读取重试间隔（ms）。 */
        private const val GATING_READ_RETRY_MS = 2_000L
        /** 设置写入（配额/上次角色/目标时间）超时（ms），防止 DataStore 阻塞拖垮 Worker。 */
        private const val WRITE_TIMEOUT_MS = 5_000L

        /**
         * 问候生成前台化信息：低优先级 ongoing 通知 + 前台类型。
         * API 35+ 用 specialUse（Android 15 起 dataSync 有 6h/24h 超时，生成时长不可控）；
         * API 34 用 dataSync；<34 无类型概念两参构造。
         */
        fun buildGreetingForegroundInfo(context: Context): ForegroundInfo {
            val notification = GreetingNotificationManager.buildProgressNotification(context)
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                    ForegroundInfo(
                        GreetingNotificationManager.PROGRESS_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    ForegroundInfo(
                        GreetingNotificationManager.PROGRESS_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                else ->
                    ForegroundInfo(GreetingNotificationManager.PROGRESS_NOTIFICATION_ID, notification)
            }
        }

        /** 取一个 PARTIAL_WAKE_LOCK 保 60s 生成期间 CPU 唤醒；超时兜底防泄漏。失败返回 null。 */
        fun acquireGreetingWakeLock(context: Context): PowerManager.WakeLock? = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()

        fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }

        private const val WAKE_LOCK_TAG = "rhodes:greeting"
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L // 略大于 GENERATE_TIMEOUT_MS(60s) 兜底
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as RhodesApp).container
        val settings = container.settingsRepository
        val context = applicationContext

        // 测试模式：10s 预览，独立于日常周期链
        if (inputData.getBoolean(GreetingScheduler.KEY_TEST, false)) {
            return runTestGreeting(container, settings, context)
        }

        val today = dateFmt.format(Date())

        // 1. 一次性读取门控状态（带重试，区分「已关闭」与「读不到」）。
        //    读不到 -> 直接 success：本 Worker 由 PeriodicWork 周期驱动，下个周期还会触发，
        //    天然保活，无需自延续 reschedule。这正是修复「退出 App 后链条断裂」的关键--
        //    周期性工作错失一次下轮仍会跑，不依赖「发一条->排下一条」的自延续（自延续错失一次即永久断裂）。
        val state = readGatingState(settings, today)
        if (state == null) return Result.success() // 读不到设置：等下个周期再读，保活
        if (!state.enabled) return Result.success() // 明确关闭 -> 由 ensureScheduled/reschedule cancel

        // 本地模式 -> 静默等待（周期工作继续跑，切回云端即恢复）
        if (settings.getActiveProviderNow() != ChatProviderType.CLOUD) return Result.success()
        if (state.charIds.isEmpty()) return Result.success() // 未选角色 -> 等用户选择

        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val remaining = state.dailyCount - state.used // 今日剩余配额（state.used 已按 today 跨天重置）

        // 2. 不在时段 / 配额已满：把 next_fire_at 推到下一个 HOUR_START（即次日早晨），本周期不投递。
        //    computeNextDelay 对「不在时段 / remaining<=0」返回到下一个 HOUR_START 的延迟。
        if (hour < AppConfig.Greeting.HOUR_START || hour >= AppConfig.Greeting.HOUR_END || remaining <= 0) {
            val nextFire = GreetingScheduler.computeNextFireAt(now, remaining)
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGreetingNextFireAt(nextFire) }
            GreetingAlarmScheduler.armNext(context, nextFire)
            return Result.success()
        }

        // 3. 在时段内且有配额：检查是否到了该投递的目标时间（next_fire_at）。
        val nextFire = settings.getGreetingNextFireAtNow()
        if (nextFire <= 0L) {
            // 首次启用尚未初始化：补算一个随机目标时间，本周期不投递，等下个周期再发
            // （避免开启后立刻发，应等下一个随机时刻）。
            val initFire = GreetingScheduler.computeNextFireAt(now, remaining)
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGreetingNextFireAt(initFire) }
            GreetingAlarmScheduler.armNext(context, initFire)
            return Result.success()
        }
        if (now < nextFire) return Result.success() // 还没到点，等下个周期

        // 4. 到点了：轮换选一个角色并投递（避免连续挑同一角色，让多角色都有机会）
        val charId = pickCharacter(settings, state.charIds)
        val delivered = deliverGreeting(container, settings, context, charId, alwaysNotify = false)

        // 5. 写回下一次目标时间 + 配额 + 上次角色（超时包裹，防 DataStore 写入阻塞拖垮 Worker）。
        //    成功 -> 按剩余配额算下个随机时刻；失败 -> 退避 RETRY_DELAY_MS，避免每周期都失败重试。
        val newRemaining = (remaining - 1).coerceAtLeast(0)
        val nextDelay = if (delivered) GreetingScheduler.computeNextDelay(now, newRemaining)
                        else AppConfig.Greeting.RETRY_DELAY_MS
        val nextFireAt = now + nextDelay
        withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            settings.setGreetingNextFireAt(nextFireAt)
            if (delivered) {
                settings.setGreetingQuota(today, state.used + 1)
                settings.setGreetingLastCharId(charId)
            }
        }
        GreetingAlarmScheduler.armNext(context, nextFireAt)
        return Result.success()
    }

    /** 门控快照：一次性承载 Worker 决策所需的全部设置。 */
    private data class GatingState(
        val enabled: Boolean,
        val charIds: Set<String>,
        val dailyCount: Int,
        val used: Int, // 今日已发（跨天已归零）
    )

    /**
     * 读取门控状态，带重试以应对冷启动时 DataStore 文件 I/O 暂时性阻塞（国产 ROM 常见）。
     * 关键字段（开关/角色集）多次重试仍读不到 -> 返回 null，调用方本周期跳过、等下个周期再读，
     * **切勿**误判为「已关闭」--PeriodicWork 会保活，无需自延续。
     */
    private suspend fun readGatingState(settings: SettingsRepository, today: String): GatingState? {
        repeat(GATING_READ_ATTEMPTS) { attempt ->
            val enabled = settings.getGreetingEnabledOrNull()
            val charIds = settings.getGreetingCharacterIdsOrNull()
            if (enabled != null && charIds != null) {
                val dailyCount = settings.getGreetingDailyCountNow()
                val (date, count) = settings.getGreetingQuotaNow()
                val used = if (date == today) count else 0
                return GatingState(enabled, charIds, dailyCount, used)
            }
            if (attempt < GATING_READ_ATTEMPTS - 1) delay(GATING_READ_RETRY_MS)
        }
        return null
    }

    /**
     * 从已选角色中挑一个发问候。多于一个角色时**严格轮询**：取上次问候角色在排序表中的下一个
     * （循环），保证所选每个角色都被轮流投递、不会长期冷落某一个（修复「选了多个却只有当前
     * 干员在发」）。读不到上次记录或上次角色已不在选择集时退化为随机起点，随后继续轮询。
     * 配合 doWork 发完后写回 lastCharId，跨天也连续轮询（次日从上次的下一个接续）。
     */
    private suspend fun pickCharacter(settings: SettingsRepository, charIds: Set<String>): String {
        if (charIds.size == 1) return charIds.first()
        val sorted = charIds.sorted() // 固定排序，保证轮询顺序稳定
        val last = settings.getLastGreetingCharIdNow()
        val idx = last?.let { sorted.indexOf(it).takeIf { i -> i >= 0 } }
        return if (idx != null) sorted[(idx + 1) % sorted.size]
        else sorted[Random.nextInt(sorted.size)]
    }

    /** 测试模式：仍需云端；从已选角色随机挑一个（无则用当前活跃角色），始终弹通知预览。 */
    private suspend fun runTestGreeting(
        container: AppContainer,
        settings: SettingsRepository,
        context: Context,
    ): Result {
        if (settings.getActiveProviderNow() != ChatProviderType.CLOUD) return Result.success()
        val charIds = settings.getGreetingCharacterIdsNow()
        val charId = if (charIds.isNotEmpty()) {
            charIds.elementAt(Random.nextInt(charIds.size))
        } else {
            settings.getActiveCharacterNow()
        }
        deliverGreeting(container, settings, context, charId, alwaysNotify = true)
        return Result.success()
    }

    /**
     * 生成并投递一条主动消息：解析/创建活跃会话 -> 取最近历史 -> 云端生成 -> 落库 -> 通知。
     * @return true 已成功投递；false 表示角色不存在 / 无 API Key / 生成失败 / 内容为空。
     */
    private suspend fun deliverGreeting(
        container: AppContainer,
        settings: SettingsRepository,
        context: Context,
        charId: String,
        alwaysNotify: Boolean,
    ): Boolean {
        val char = container.characterRepository.getNow(charId) ?: return false
        val convId = resolveActiveConversation(settings, container, charId)
        // 锚定截断：cap=6 须显式小步长（默认 20 会被 coerce 到 6 造成保留数震荡）；
        // 统一走锚定是为防未来调大 CONTEXT_MESSAGES 后退化回逐条滑动窗口、破坏前缀缓存。
        val history = PromptWindowAnchor.anchoredWindow(
            container.chatRepository.getHistory(convId), AppConfig.Greeting.CONTEXT_MESSAGES, step = 2,
        )

        val apiConfig = settings.getApiConfigNow()
        // 内置免费服务商（Cloudflare 代理）无需客户端 key；其余服务商必须配置
        if (apiConfig.apiKey.isBlank() && !isFreeProxyBaseUrl(apiConfig.baseUrl)) return false

        // 前台化 + WakeLock：保护最长 60s 的云端生成不被国产 ROM 冻结/杀进程。
        // setForeground 失败（如通知权限被拒）则降级为仅持锁 + 周期保活，不阻断投递。
        val wakeLock = acquireGreetingWakeLock(context)
        try {
            setForeground(buildGreetingForegroundInfo(context))
        } catch (ce: CancellationException) {
            releaseWakeLock(wakeLock)
            throw ce
        } catch (e: Exception) {
            android.util.Log.w("GreetingWorker", "setForeground 降级（仅持锁）: ${e.message}")
        }
        val message: String? = try {
            withTimeout(AppConfig.Greeting.GENERATE_TIMEOUT_MS) {
                // 博士档案（人设/关系）一并注入主动问候的 system
                val userDirective = settings.getUserProfileNow().toDirectiveText()
                generateGreeting(container.directLlmClient, apiConfig, char, history, userDirective, settings)
            }
        } catch (e: Exception) {
            null
        } finally {
            releaseWakeLock(wakeLock)
        }
        if (message == null) return false
        if (message.isBlank()) return false

        // 落库为 assistant 消息（Room Flow 会推送到前台正在看该会话的 UI -> 实时冒泡）
        container.chatRepository.addMessage(
            charId, convId, ChatMessage(role = "assistant", content = message),
        )
        container.conversationRepository.touch(convId)

        // 通知：前台且正停留在该角色时抑制（消息已实时冒泡，类微信不重复弹）；测试模式始终弹
        val viewingThisChar = AppLifecycleObserver.isForeground &&
            settings.getActiveCharacterNow() == charId
        if (alwaysNotify || !viewingThisChar) {
            GreetingNotificationManager.notify(context, charId, convId, char.name, message)
        }
        return true
    }

    /** 解析角色的活跃会话；不存在则新建并记为活跃。 */
    private suspend fun resolveActiveConversation(
        settings: SettingsRepository,
        container: AppContainer,
        charId: String,
    ): Long {
        val existing = settings.getActiveConversationNow(charId)
        if (existing != null && container.conversationRepository.getById(existing) != null) {
            return existing
        }
        val newId = container.conversationRepository.create(charId)
        settings.setActiveConversation(charId, newId)
        return newId
    }

    /** 调用云端 API 生成一条符合人设、贴合时段的主动消息。 */
    private suspend fun generateGreeting(
        client: com.rhodesisland.terminal.data.remote.DirectLlmClient,
        apiConfig: com.rhodesisland.terminal.data.model.ApiConfig,
        char: com.rhodesisland.terminal.data.model.Character,
        history: List<ChatMessage>,
        userDirective: String,
        settings: SettingsRepository,
    ): String {
        // 时间上下文只注入低基数时段词（不含 HH:mm）：分钟级时间会让 system prompt
        // 每次不同，云端 prompt 前缀缓存无法复用；时段词同时段内字节稳定（见 GreetingPromptBuilder）。
        val instruction = GreetingPromptBuilder.buildTimeDirective(
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        )

        // 自定义世界观（绑定到该角色的私聊）注入
        val worldviewDirective = settings.worldviewDirectiveFor(WorldviewTargetType.CHARACTER, char.id)

        // 世界书激活：按作用域过滤（ALL 或 CHARACTER 绑定本角色）。问候没有「最新用户消息紧跟」
        // 的对话语境，动态尾直接拼在同一 system 末尾（单次生成，无缓存连续性诉求）
        val lorebookDirective = run {
            val cfg = settings.getLorebookConfigNow()
            if (!cfg.masterEnabled) ""
            else {
                val act = LorebookEngine.activate(
                    books = settings.getLorebooksNow().filter {
                        it.enabled && it.matchesScope(characterId = char.id, groupConversationId = null)
                    },
                    config = cfg,
                    scanMessages = history.takeLast(50),
                )
                if (act.isEmpty) "" else act.staticHead + act.tailInjection
            }
        }
        val systemContent = buildString {
            append(char.systemPrompt)
            append(worldviewDirective)
            append(lorebookDirective)
            append(userDirective)
            append(instruction)
        }
        val messages = buildList {
            add(ChatMessageDto(role = "system", content = JsonPrimitive(systemContent)))
            history.forEach { m ->
                if (m.content.isBlank()) return@forEach
                // 剥离 <think> 段（深度思考模式下云端回复会带），避免把推理过程当历史喂回
                val clean = thinkRegex.replace(m.content, "").trim()
                if (clean.isEmpty()) return@forEach
                add(ChatMessageDto(role = m.role, content = JsonPrimitive(clean)))
            }
        }

        return client.chatOnce(apiConfig.baseUrl, apiConfig.apiKey, apiConfig.model, messages)
            .trim()
            .removeSurrounding("\"")
    }
}
