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
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.GroupChatConfig
import com.rhodesisland.terminal.data.model.WorldviewTargetType
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.notification.AppLifecycleObserver
import com.rhodesisland.terminal.notification.GroupChatNotificationManager
import com.rhodesisland.terminal.ui.groupchat.GroupChatPromptBuilder
import com.rhodesisland.terminal.ui.groupchat.GroupScreenTracker
import com.rhodesisland.terminal.ui.groupchat.GroupSpeakerPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 群聊自动聊天 Worker（仅云端可用）：由 [GroupChatScheduler] 的 PeriodicWork 每 15 分钟驱动。
 *
 * 到点执行一轮：discuss 轮让 [AppConfig.GroupChat.DISCUSS_REPLIES_PER_ROUND] 名成员依次互聊，
 * 或 ask-user 轮让一名成员主动向用户提问；都落库到群聊会话、发类微信通知（观看群聊时抑制）。
 * 与 [GreetingWorker] 同构：周期驱动保活、`next_fire_at` 门控、非云端/关闭时静默 success。
 */
class GroupChatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    companion object {
        private const val GATING_READ_ATTEMPTS = 3
        private const val GATING_READ_RETRY_MS = 2_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val WAKE_LOCK_TAG = "rhodes:groupchat"
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L

        /**
         * 群聊生成前台化信息：低优先级 ongoing 通知 + 前台类型。
         * API 35+ 用 specialUse（Android 15 起 dataSync 有 6h/24h 超时，生成时长不可控）；
         * API 34 用 dataSync；<34 无类型概念两参构造。
         */
        fun buildGroupForegroundInfo(context: Context): ForegroundInfo {
            val notification = GroupChatNotificationManager.buildProgressNotification(context)
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                    ForegroundInfo(
                        GroupChatNotificationManager.PROGRESS_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    ForegroundInfo(
                        GroupChatNotificationManager.PROGRESS_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                else ->
                    ForegroundInfo(GroupChatNotificationManager.PROGRESS_NOTIFICATION_ID, notification)
            }
        }

        fun acquireGroupWakeLock(context: Context): PowerManager.WakeLock? = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()

        fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as RhodesApp).container
        val settings = container.settingsRepository
        val context = applicationContext

        if (inputData.getBoolean(GroupChatScheduler.KEY_TEST, false)) {
            return runTestRound(container, settings, context)
        }

        val today = dateFmt.format(Date())

        val config = readGatingState(settings)
        if (config == null) return Result.success()
        if (!config.enabled || !config.autoChat) return Result.success()
        if (settings.getActiveProviderNow() != ChatProviderType.CLOUD) return Result.success()
        // 多群聊：目标 = 最近活跃的群；无群静默等待（PeriodicWork 保活）
        if (container.groupChatRepository.listGroups().isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val dailyRounds = settings.getGroupDailyRoundsNow()
        val (date, used) = settings.getGroupQuotaNow()
        val usedToday = if (date == today) used else 0
        val remaining = (dailyRounds - usedToday).coerceAtLeast(0)

        // 不在时段 / 配额满：推到下一个 HOUR_START。
        if (hour < AppConfig.GroupChat.HOUR_START || hour >= AppConfig.GroupChat.HOUR_END || remaining <= 0) {
            val nextFire = GroupChatScheduler.computeNextFireAt(now, remaining)
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGroupNextFireAt(nextFire) }
            GroupChatAlarmScheduler.armNext(context, nextFire)
            return Result.success()
        }

        val nextFire = settings.getGroupNextFireAtNow()
        if (nextFire <= 0L) {
            val initFire = GroupChatScheduler.computeNextFireAt(now, remaining)
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGroupNextFireAt(initFire) }
            GroupChatAlarmScheduler.armNext(context, initFire)
            return Result.success()
        }
        if (now < nextFire) return Result.success()

        // 用户冷却闸：用户刚发过消息，推迟本轮避免打断。
        val lastUserAt = settings.getGroupLastUserMessageAtNow()
        if (lastUserAt > 0L && now - lastUserAt < AppConfig.GroupChat.AFTER_USER_COOLDOWN_MS) {
            val cooldownUntil = (lastUserAt + AppConfig.GroupChat.AFTER_USER_COOLDOWN_MS)
                .coerceAtLeast(now + 60_000L)
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGroupNextFireAt(cooldownUntil) }
            GroupChatAlarmScheduler.armNext(context, cooldownUntil)
            return Result.success()
        }

        // 到点：执行一轮。
        val counter = settings.getGroupRoundCounterNow()
        val isAskUser = GroupChatScheduler.isAskUserRound(counter)
        val ok = runRound(container, settings, context, isAskUser)

        val newRemaining = (remaining - 1).coerceAtLeast(0)
        val nextDelay = if (ok) GroupChatScheduler.computeNextDelay(now, newRemaining)
        else AppConfig.GroupChat.RETRY_DELAY_MS
        val nextFireAt = now + nextDelay
        withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            settings.setGroupNextFireAt(nextFireAt)
            if (ok) {
                settings.setGroupQuota(today, usedToday + 1)
                settings.setGroupRoundCounter(counter + 1)
            }
        }
        GroupChatAlarmScheduler.armNext(context, nextFireAt)
        return Result.success()
    }

    private suspend fun readGatingState(settings: SettingsRepository): GroupChatConfig? {
        repeat(GATING_READ_ATTEMPTS) { attempt ->
            settings.getGroupChatConfigOrNull()?.let { return it }
            if (attempt < GATING_READ_ATTEMPTS - 1) delay(GATING_READ_RETRY_MS)
        }
        return null
    }

    private suspend fun runTestRound(
        container: AppContainer,
        settings: SettingsRepository,
        context: Context,
    ): Result {
        if (settings.getActiveProviderNow() != ChatProviderType.CLOUD) return Result.success()
        runRound(container, settings, context, isAskUser = true, alwaysNotify = true)
        return Result.success()
    }

    /**
     * 执行一轮自动聊天（目标 = 最近活跃的群及其成员）。
     * @return true 至少成功生成并落库了一条成员发言。
     */
    private suspend fun runRound(
        container: AppContainer,
        settings: SettingsRepository,
        context: Context,
        isAskUser: Boolean,
        alwaysNotify: Boolean = false,
    ): Boolean {
        val target = container.groupChatRepository.listGroups().firstOrNull() ?: return false
        val convId = target.id
        val members = target.memberIds
            .take(AppConfig.GroupChat.MAX_MEMBERS)
            .mapNotNull { container.characterRepository.getNow(it) }
        if (members.isEmpty()) return false

        val apiConfig = settings.getApiConfigNow()
        if (apiConfig.apiKey.isBlank() && !isFreeProxyBaseUrl(apiConfig.baseUrl)) return false

        val history0 = container.chatRepository.getHistory(convId)

        // 博士档案（人设/关系）注入每一条自动发言的 system
        val profile = settings.getUserProfileNow()
        // 自定义世界观（绑定到该群聊）注入：一次解析，本轮全部发言共用
        val worldviewDirective =
            settings.worldviewDirectiveFor(WorldviewTargetType.GROUP, convId.toString())

        val wakeLock = acquireGroupWakeLock(context)
        try {
            setForeground(buildGroupForegroundInfo(context))
        } catch (ce: CancellationException) {
            releaseWakeLock(wakeLock)
            throw ce
        } catch (e: Exception) {
            android.util.Log.w("GroupChatWorker", "setForeground 降级（仅持锁）: ${e.message}")
        }

        return try {
            var ok = false
            if (isAskUser) {
                val speaker = GroupSpeakerPicker.pick(members.map { it.id }.toSet(), settings.getGroupLastSpeakerIdNow())
                val char = speaker?.let { id -> members.firstOrNull { it.id == id } }
                if (char != null) {
                    val content = generateGroupMessage(
                        container.directLlmClient, apiConfig, members, char, history0,
                        askUser = true, userPersona = profile.persona, userRelationship = profile.relationship,
                        worldviewDirective = worldviewDirective,
                    )
                    if (!content.isNullOrBlank()) {
                        container.groupChatRepository.sendMemberMessage(convId, char.id, content)
                        withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGroupLastSpeakerId(char.id) }
                        if (alwaysNotify || !isViewingGroup()) {
                            GroupChatNotificationManager.notify(context, convId, char.name, content)
                        }
                        ok = true
                    }
                }
            } else {
                // discuss 轮：DISCUSS_REPLIES_PER_ROUND 名成员依次互聊。
                val memberById = members.associateBy { it.id }
                var lastId = settings.getGroupLastSpeakerIdNow()
                var history = history0
                var lastChar: Character? = null
                var lastContent: String? = null
                repeat(AppConfig.GroupChat.DISCUSS_REPLIES_PER_ROUND) {
                    val speakerId = GroupSpeakerPicker.pick(memberById.keys, lastId)
                    val char = speakerId?.let { memberById[it] }
                    if (char == null) return@repeat
                    val content = generateGroupMessage(
                        container.directLlmClient, apiConfig, members, char, history,
                        askUser = false, userPersona = profile.persona, userRelationship = profile.relationship,
                        worldviewDirective = worldviewDirective,
                    )
                    if (content.isNullOrBlank()) return@repeat
                    container.groupChatRepository.sendMemberMessage(convId, char.id, content)
                    withTimeoutOrNull(WRITE_TIMEOUT_MS) { settings.setGroupLastSpeakerId(char.id) }
                    lastId = char.id
                    lastChar = char
                    lastContent = content
                    history = history + ChatMessage(role = "assistant", content = content, characterId = char.id)
                    ok = true
                }
                // discuss 轮也提示一次（不打断式地让用户知道群在聊）
                val c = lastChar
                val content = lastContent
                if (c != null && content != null && (alwaysNotify || !isViewingGroup())) {
                    GroupChatNotificationManager.notify(context, convId, c.name, content)
                }
            }
            ok
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    /** 用户是否正在前台观看群聊界面（是则抑制通知，消息已实时冒泡）。 */
    private fun isViewingGroup(): Boolean = AppLifecycleObserver.isForeground && GroupScreenTracker.isVisible

    private suspend fun generateGroupMessage(
        client: DirectLlmClient,
        apiConfig: ApiConfig,
        members: List<Character>,
        speaker: Character,
        history: List<ChatMessage>,
        askUser: Boolean,
        userPersona: String?,
        userRelationship: String?,
        worldviewDirective: String = "",
    ): String? {
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members, speaker, history, askUser,
            userPersona = userPersona,
            userRelationship = userRelationship,
            worldviewDirective = worldviewDirective,
        ).map { ChatMessageDto(it.role, JsonPrimitive(it.content)) }
        return try {
            withTimeout(AppConfig.GroupChat.GENERATE_TIMEOUT_MS) {
                client.chatOnce(apiConfig.baseUrl, apiConfig.apiKey, apiConfig.model, messages)
                    .let { GroupChatPromptBuilder.stripSpeakerPrefix(it, members.map { c -> c.name }) }
            }.takeIf { it.isNotBlank() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null
        }
    }
}