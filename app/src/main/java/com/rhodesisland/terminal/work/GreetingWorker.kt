package com.rhodesisland.terminal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.notification.AppLifecycleObserver
import com.rhodesisland.terminal.notification.GreetingNotificationManager
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 角色问候 Worker：在白天随机时间触发，调用云端 API 让所选角色主动发一条符合人设的消息，
 * 落库到该角色活跃会话，发类微信通知，并自调度下一次。
 *
 * 仅云端模式生效；本地模式 / 关闭时静默重排或终止链条。所有失败路径都以 success 返回
 * 并自行 reschedule，避免 WorkManager 指数退避 retry 风暴。
 *
 * 测试模式（[GreetingScheduler.KEY_TEST]）：用户在设置页点「测试」触发，10s 后执行，
 * 跳过开关/配额/时段门控，始终弹通知预览，不计配额、不重排。
 */
class GreetingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val thinkRegex = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    override suspend fun doWork(): Result {
        val container = (applicationContext as RhodesApp).container
        val settings = container.settingsRepository
        val context = applicationContext

        // 测试模式：10s 预览，独立于日常链
        if (inputData.getBoolean(GreetingScheduler.KEY_TEST, false)) {
            return runTestGreeting(container, settings, context)
        }

        // 1. 门控：未开启 -> 链终止（下次开 App 或重新开启时 ensureScheduled/reschedule 重启）
        if (!settings.getGreetingEnabledNow()) return Result.success()

        // 本地模式 -> 静默重排到次日早晨（保持链条存活，切回云端即恢复）
        if (settings.getActiveProviderNow() != ChatProviderType.CLOUD) {
            GreetingScheduler.reschedule(context, settings)
            return Result.success()
        }

        val charIds = settings.getGreetingCharacterIdsNow()
        if (charIds.isEmpty()) {
            // 未选角色 -> 重排等待用户选择
            GreetingScheduler.reschedule(context, settings)
            return Result.success()
        }
        val dailyCount = settings.getGreetingDailyCountNow()

        // 2. 每日配额（跨天自动重置）
        val (quotaDate, quotaCount) = settings.getGreetingQuotaNow()
        val today = dateFmt.format(Date())
        val used = if (quotaDate == today) quotaCount else 0
        if (used >= dailyCount) {
            GreetingScheduler.reschedule(context, settings) // -> 次日早晨
            return Result.success()
        }

        // 3. 时段：仅 HOUR_START..HOUR_END 触发，避免深夜打扰
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < AppConfig.Greeting.HOUR_START || hour >= AppConfig.Greeting.HOUR_END) {
            GreetingScheduler.reschedule(context, settings) // -> 下一个 HOUR_START
            return Result.success()
        }

        // 4. 随机选一个角色并投递
        val charId = charIds.elementAt(Random.nextInt(charIds.size))
        val delivered = deliverGreeting(container, settings, context, charId, alwaysNotify = false)

        if (delivered) {
            settings.setGreetingQuota(today, used + 1)
            GreetingScheduler.reschedule(context, settings)
        } else {
            // 生成失败：不计配额，稍后重试
            GreetingScheduler.scheduleNext(context, AppConfig.Greeting.RETRY_DELAY_MS)
        }
        return Result.success()
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
        val history = container.chatRepository.getHistory(convId)
            .takeLast(AppConfig.Greeting.CONTEXT_MESSAGES)

        val apiConfig = settings.getApiConfigNow()
        if (apiConfig.apiKey.isBlank()) return false

        val message = try {
            withTimeout(AppConfig.Greeting.GENERATE_TIMEOUT_MS) {
                generateGreeting(container.directLlmClient, apiConfig, char, history)
            }
        } catch (e: Exception) {
            return false
        }
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
    ): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeStr = "%02d:%02d".format(hour, minute)
        val period = when (hour) {
            in 5..7 -> "清晨"
            in 8..10 -> "上午"
            in 11..13 -> "中午"
            in 14..17 -> "下午"
            in 18..21 -> "傍晚"
            else -> "晚上"
        }
        val instruction = buildString {
            append("\n\n[系统附加指令] 现在请你主动给用户发一条消息。当前时间 ")
            append(timeStr).append("（").append(period).append("）。")
            append("\n要求：")
            append("\n- 完全符合你的人设、性格与说话风格")
            append("\n- 可以是打招呼（早安/晚安等）、问候关心、或主动开启一个话题")
            append("\n- 自然简短，像真人随手发的一条消息（1-3 句）")
            append("\n- 只输出消息内容本身，不要加角色名前缀、引号或任何解释")
        }

        val messages = buildList {
            add(ChatMessageDto(role = "system", content = JsonPrimitive(char.systemPrompt + instruction)))
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
