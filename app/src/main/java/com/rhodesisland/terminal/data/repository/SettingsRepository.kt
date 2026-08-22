package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.LocalInferenceSettings
import com.rhodesisland.terminal.data.local.SettingsStore
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.GroupChatConfig
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.UserProfileConfig
import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.ThemeMode
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsEngine
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置仓库
 * 封装 SettingsStore，提供同步获取当前值的便捷方法
 */
class SettingsRepository(private val store: SettingsStore) {

    /** 主题模式（默认跟随系统）。 */
    val themeMode: Flow<ThemeMode> = store.themeMode

    val apiConfig: Flow<ApiConfig> = store.apiConfig
    /** Seedance 视频生成配置（聚合快照）。 */
    val seedanceConfig: Flow<SeedanceConfig> = store.seedanceConfig
    val ttsConfig: Flow<TtsConfig> = store.ttsConfig
    val ttsLanguage: Flow<TtsLanguage> = store.ttsLanguage
    val ttsVolume: Flow<Int> = store.ttsVolume
    val ttsVoiceMap: Flow<Map<String, VoicePair>> = store.ttsVoiceMap
    /** 朗读引擎（system=手机自带，默认；cloud=云端火山豆包）。 */
    val ttsEngine: Flow<TtsEngine> = store.ttsEngine
    /** 系统引擎声音模板。 */
    val ttsSystemTemplate: Flow<SystemVoiceTemplate> = store.ttsSystemTemplate
    /** 自动朗读角色每次完整回复（默认关闭）。 */
    val ttsAutoRead: Flow<Boolean> = store.ttsAutoRead
    val activeCharacter: Flow<String> = store.activeCharacter
    val customCharacters: Flow<List<Character>> = store.customCharacters
    val volume: Flow<Int> = store.volume
    val musicFavorites: Flow<Set<String>> = store.musicFavorites
    val musicRepeatMode: Flow<Int> = store.musicRepeatMode
    /** 随机播放开关（音乐页）。 */
    val musicShuffle: Flow<Boolean> = store.musicShuffle
    val activeProvider: Flow<ChatProviderType> = store.activeProvider
    val activeLocalModelId: Flow<String?> = store.activeLocalModelId
    val llmContextLen: Flow<Int> = store.llmContextLen
    val llmThreads: Flow<Int> = store.llmThreads
    val llmTemperature: Flow<Float> = store.llmTemperature
    val llmMaxTokens: Flow<Int> = store.llmMaxTokens
    val llmBackend: Flow<BackendPreference> = store.llmBackend
    /** 推理性能模式（默认 BALANCED）。 */
    val llmPerformanceMode: Flow<InferencePerformanceMode> = store.llmPerformanceMode
    /** 本地推理设置不可变快照（Task 6）：一次读取全部本地 LLM 参数。 */
    val localInferenceSettings: Flow<LocalInferenceSettings> = store.localInferenceSettings
    /** legacy：CPU 提频开关（Task 6 起不再权威，高级诊断视图仍可改）。 */
    val llmCpuBoost: Flow<Boolean> = store.llmCpuBoost
    /** legacy：CPU lookahead 投机解码开关（默认关，Task 6 起不再权威）。仅 MNN CPU 后端生效。 */
    val llmLookahead: Flow<Boolean> = store.llmLookahead
    /** 深度思考模式开关（本地 + 云端通用）。 */
    val deepThinking: Flow<Boolean> = store.deepThinking
    /** 本地思考档位（默认 AUTO，仅本地生效）；云端不读取。 */
    val localThinkingLevel: Flow<LocalThinkingLevel> = store.localThinkingLevel
    /** 性能浮窗液态玻璃开关（默认开）。 */
    val liquidGlass: Flow<Boolean> = store.liquidGlass
    /** 推理参数是否相对上次成功加载已变更（供设置页展示"将自动重载"横幅）*/
    val llmConfigChanged: Flow<Boolean> = store.llmConfigChanged

    // ===== 角色问候（角色主动消息，仅云端可用）=====
    /** 角色问候开关。 */
    val greetingEnabled: Flow<Boolean> = store.greetingEnabled
    /** 主动发消息的角色 id 集合（可多选）。 */
    val greetingCharacterIds: Flow<Set<String>> = store.greetingCharacterIds
    /** 每天主动消息条数。 */
    val greetingDailyCount: Flow<Int> = store.greetingDailyCount
    /** 当日配额（日期 -> 已发条数）。 */
    val greetingQuota: Flow<Pair<String, Int>> = store.greetingQuota
    /** 上次发问候的角色 id（跨天也连续轮询）。 */
    val greetingLastCharId: Flow<String?> = store.greetingLastCharId
    /** 下一次问候投递目标时间（epoch ms；0 = 尚未初始化）。 */
    val greetingNextFireAt: Flow<Long> = store.greetingNextFireAt

    // ===== 群聊（仅云端可用）=====
    /** 群聊配置聚合快照（开关/成员/自动聊天）。 */
    val groupChatConfig: Flow<GroupChatConfig> = store.groupChatConfig
    /** 每日自动聊天轮次上限。 */
    val groupDailyRounds: Flow<Int> = store.groupDailyRounds
    /** 当日轮次配额（日期 -> 已执行轮次）。 */
    val groupQuota: Flow<Pair<String, Int>> = store.groupQuota
    /** 上次发言的成员 id（跨天连续轮询）。 */
    val groupLastSpeakerId: Flow<String?> = store.groupLastSpeakerId
    /** 已执行轮次累计计数。 */
    val groupRoundCounter: Flow<Long> = store.groupRoundCounter
    /** 用户最近一次群聊发言时间（epoch ms）。 */
    val groupLastUserMessageAt: Flow<Long> = store.groupLastUserMessageAt
    /** 下一次自动聊天触发目标时间（epoch ms；0 = 尚未初始化）。 */
    val groupNextFireAt: Flow<Long> = store.groupNextFireAt

    // ===== 博士档案（我的形象）=====
    /** 博士档案聚合（头像路径/人设/关系）。 */
    val userProfile: Flow<UserProfileConfig> = store.userProfile

    suspend fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)

    suspend fun setApiConfig(config: ApiConfig) = store.setApiConfig(config)
    /** 每服务商独立配置表（key = 预设 id 或 "custom"）。 */
    val providerApiConfigs: Flow<Map<String, ApiConfig>> = store.providerApiConfigs
    suspend fun getProviderApiConfigNow(key: String): ApiConfig? =
        dataStoreFirstOrNull(providerApiConfigs.map { it[key] })
    suspend fun setProviderApiConfig(key: String, config: ApiConfig) = store.setProviderApiConfig(key, config)
    suspend fun setSeedanceConfig(config: SeedanceConfig) = store.setSeedanceConfig(config)
    suspend fun setTtsConfig(config: TtsConfig) = store.setTtsConfig(config)
    suspend fun setTtsLanguage(lang: TtsLanguage) = store.setTtsLanguage(lang)
    suspend fun setTtsVolume(vol: Int) = store.setTtsVolume(vol)
    suspend fun setTtsVoiceMap(map: Map<String, VoicePair>) = store.setTtsVoiceMap(map)
    suspend fun setTtsEngine(engine: TtsEngine) = store.setTtsEngine(engine)
    suspend fun setTtsSystemTemplate(template: SystemVoiceTemplate) = store.setTtsSystemTemplate(template)
    suspend fun setTtsAutoRead(enabled: Boolean) = store.setTtsAutoRead(enabled)
    suspend fun setActiveCharacter(id: String) = store.setActiveCharacter(id)
    val activeConversations: Flow<Map<String, Long>> = store.activeConversations
    suspend fun setActiveConversation(characterId: String, conversationId: Long) =
        store.setActiveConversation(characterId, conversationId)
    suspend fun clearActiveConversation(characterId: String) = store.clearActiveConversation(characterId)
    suspend fun clearAllActiveConversations() = store.clearAllActiveConversations()
    suspend fun getActiveConversationNow(characterId: String): Long? =
        dataStoreFirstOrNull(activeConversations.map { it[characterId] })
    suspend fun setCustomCharacters(list: List<Character>) = store.setCustomCharacters(list)
    suspend fun updateCustomCharacters(transform: (List<Character>) -> List<Character>) =
        store.updateCustomCharacters(transform)
    suspend fun setVolume(vol: Int) = store.setVolume(vol)
    suspend fun toggleMusicFavorite(key: String) = store.toggleMusicFavorite(key)
    suspend fun setMusicRepeatMode(mode: Int) = store.setMusicRepeatMode(mode)
    suspend fun setMusicShuffle(enabled: Boolean) = store.setMusicShuffle(enabled)
    suspend fun setActiveProvider(type: ChatProviderType) = store.setActiveProvider(type)
    suspend fun setActiveLocalModelId(id: String?) = store.setActiveLocalModelId(id)
    suspend fun setLlmParams(
        contextLen: Int? = null,
        threads: Int? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
    ) = store.setLlmParams(contextLen, threads, temperature, maxTokens)

    suspend fun setLlmBackend(preference: BackendPreference) = store.setLlmBackend(preference)

    suspend fun setLlmPerformanceMode(mode: InferencePerformanceMode) =
        store.setLlmPerformanceMode(mode)

    /** 同步读取本地推理设置快照；DataStore I/O 被拦截/异常时超时回退不可变默认快照。 */
    suspend fun getLocalInferenceSettingsNow(timeoutMs: Long = DATASTORE_TIMEOUT_MS): LocalInferenceSettings =
        runCatching { withTimeoutOrNull(timeoutMs) { localInferenceSettings.first() } }
            .getOrNull() ?: LocalInferenceSettings()

    suspend fun setLlmCpuBoost(enabled: Boolean) = store.setLlmCpuBoost(enabled)

    suspend fun setLlmLookahead(enabled: Boolean) = store.setLlmLookahead(enabled)

    suspend fun setDeepThinking(enabled: Boolean) = store.setDeepThinking(enabled)

    suspend fun setLocalThinkingLevel(level: LocalThinkingLevel) = store.setLocalThinkingLevel(level)

    suspend fun setLiquidGlass(enabled: Boolean) = store.setLiquidGlass(enabled)

    suspend fun setGreetingEnabled(enabled: Boolean) = store.setGreetingEnabled(enabled)
    suspend fun setGreetingCharacterIds(ids: Set<String>) = store.setGreetingCharacterIds(ids)
    suspend fun setGreetingDailyCount(count: Int) = store.setGreetingDailyCount(count)
    suspend fun setGreetingQuota(date: String, count: Int) = store.setGreetingQuota(date, count)
    suspend fun setGreetingLastCharId(id: String?) = store.setGreetingLastCharId(id)
    suspend fun setGreetingNextFireAt(epochMs: Long) = store.setGreetingNextFireAt(epochMs)

    suspend fun setGroupChatConfig(config: GroupChatConfig) = store.setGroupChatConfig(config)
    suspend fun updateGroupChatConfig(transform: (GroupChatConfig) -> GroupChatConfig) =
        store.updateGroupChatConfig(transform)
    suspend fun setGroupDailyRounds(count: Int) = store.setGroupDailyRounds(count)
    suspend fun setGroupQuota(date: String, count: Int) = store.setGroupQuota(date, count)
    suspend fun setGroupLastSpeakerId(id: String?) = store.setGroupLastSpeakerId(id)
    suspend fun setGroupRoundCounter(counter: Long) = store.setGroupRoundCounter(counter)
    suspend fun setGroupLastUserMessageAt(epochMs: Long) = store.setGroupLastUserMessageAt(epochMs)
    suspend fun setGroupNextFireAt(epochMs: Long) = store.setGroupNextFireAt(epochMs)

    suspend fun setUserProfileConfig(config: UserProfileConfig) = store.setUserProfileConfig(config)

    /** 一次成功推理后写回本次生效的用户配置，使 [llmConfigChanged] 归 false */
    suspend fun acknowledgeLlmConfig(
        threads: Int, contextLen: Int, backend: BackendPreference, lookahead: Boolean, temperature: Float,
    ) = store.acknowledgeLlmConfig(threads, contextLen, backend, lookahead, temperature)

    /** 最近一次成功加载实际应用的 plan 配置哈希（Task 7）。 */
    val llmLastConfigHash: Flow<String?> = store.llmLastConfigHash

    suspend fun setLlmLastConfigHash(hash: String?) = store.setLlmLastConfigHash(hash)

    /** 同步获取当前 API 配置（5s 超时/异常返回默认配置）。
     *  国产 ROM（MIUI/EMUI/ColorOS）的电池优化可能拦截 DataStore 文件 I/O 导致 .first() 永久挂起；
     *  文件损坏则抛 CorruptionException。dataStoreFirst 双兜底保证 UI 不卡死、不崩溃。 */
    suspend fun getApiConfigNow(): ApiConfig = dataStoreFirst(
        apiConfig, ApiConfig(baseUrl = "", apiKey = "", model = ""),
    )

    suspend fun getTtsConfigNow(): TtsConfig = dataStoreFirst(
        ttsConfig, TtsConfig(apiKey = "", appId = "", accessKey = ""),
    )

    /** 同步获取当前 Seedance 配置（超时/异常回退默认配置，保证国产 ROM 文件 I/O 被拦截时 UI 不卡死）。 */
    suspend fun getSeedanceConfigNow(): SeedanceConfig = dataStoreFirst(seedanceConfig, SeedanceConfig())

    suspend fun getTtsLanguageNow(): TtsLanguage = dataStoreFirst(ttsLanguage, TtsLanguage.ZH)

    suspend fun getTtsEngineNow(): TtsEngine = dataStoreFirst(ttsEngine, TtsEngine.DEFAULT)

    suspend fun getTtsSystemTemplateNow(): SystemVoiceTemplate =
        dataStoreFirst(ttsSystemTemplate, SystemVoiceTemplate.DEFAULT_TEMPLATE)

    suspend fun getTtsAutoReadNow(): Boolean = dataStoreFirst(ttsAutoRead, false)

    suspend fun getTtsVoiceMapNow(): Map<String, VoicePair> = dataStoreFirst(ttsVoiceMap, emptyMap())

    suspend fun getActiveProviderNow(): ChatProviderType = dataStoreFirst(activeProvider, ChatProviderType.CLOUD)

    suspend fun getActiveLocalModelIdNow(): String? = dataStoreFirstOrNull(activeLocalModelId)
        // 超时/异常返回 null（无模型），上游 LocalChatProvider 会抛出「未选择模型」

    suspend fun getDeepThinkingNow(): Boolean = dataStoreFirst(deepThinking, false)

    /** 同步获取活跃角色（5s 超时/异常回退默认角色），供 CharacterRepository 使用 */
    suspend fun getActiveCharacterNow(): String =
        dataStoreFirst(activeCharacter, Characters.DEFAULT_CHARACTER_ID)

    /** 同步获取自定义角色（5s 超时/异常返回空列表，等同无自定义角色） */
    suspend fun getCustomCharactersNow(): List<Character> = dataStoreFirst(customCharacters, emptyList())

    // ===== 角色问候同步读取（供 GreetingWorker 用）=====
    /** 已开启?（读不到/异常返回 null 而非 false——Worker 据此区分「明确关闭」与「暂时读不到」）。 */
    suspend fun getGreetingEnabledOrNull(): Boolean? = dataStoreFirstOrNull(greetingEnabled)

    suspend fun getGreetingEnabledNow(): Boolean = getGreetingEnabledOrNull() ?: false

    /** 已选角色集合?（读不到/异常返回 null 而非空集，避免 Worker 误判「未选角色」）。 */
    suspend fun getGreetingCharacterIdsOrNull(): Set<String>? = dataStoreFirstOrNull(greetingCharacterIds)

    suspend fun getGreetingCharacterIdsNow(): Set<String> = getGreetingCharacterIdsOrNull() ?: emptySet()

    suspend fun getGreetingDailyCountNow(): Int =
        dataStoreFirst(greetingDailyCount, AppConfig.Greeting.DEFAULT_DAILY_COUNT)

    suspend fun getGreetingQuotaNow(): Pair<String, Int> = dataStoreFirst(greetingQuota, "" to 0)

    /** 上次发问候的角色 id（读不到/异常返回 null，Worker 退化为随机起点）。 */
    suspend fun getLastGreetingCharIdNow(): String? = dataStoreFirstOrNull(greetingLastCharId)

    /** 下一次问候投递目标时间（epoch ms；0 = 尚未初始化，超时/异常回退 0）。 */
    suspend fun getGreetingNextFireAtNow(): Long = dataStoreFirst(greetingNextFireAt, 0L)

    // ===== 群聊同步读取（供 GroupChatWorker / GroupChatScheduler 用）=====
    /** 群聊配置?（读不到/异常返回 null 而非默认值，Worker 据此区分「关闭」与「暂时读不到」）。 */
    suspend fun getGroupChatConfigOrNull(): GroupChatConfig? = dataStoreFirstOrNull(groupChatConfig)

    suspend fun getGroupChatConfigNow(): GroupChatConfig = getGroupChatConfigOrNull() ?: GroupChatConfig()

    suspend fun getGroupDailyRoundsNow(): Int =
        dataStoreFirst(groupDailyRounds, AppConfig.GroupChat.DEFAULT_DAILY_ROUNDS)

    suspend fun getGroupQuotaNow(): Pair<String, Int> = dataStoreFirst(groupQuota, "" to 0)

    suspend fun getGroupLastSpeakerIdNow(): String? = dataStoreFirstOrNull(groupLastSpeakerId)

    suspend fun getGroupRoundCounterNow(): Long = dataStoreFirst(groupRoundCounter, 0L)

    suspend fun getGroupLastUserMessageAtNow(): Long = dataStoreFirst(groupLastUserMessageAt, 0L)

    suspend fun getGroupNextFireAtNow(): Long = dataStoreFirst(groupNextFireAt, 0L)

    /** 博士档案（超时/异常回退默认空档案）。 */
    suspend fun getUserProfileNow(): UserProfileConfig = dataStoreFirst(userProfile, UserProfileConfig())

    companion object {
        /** DataStore .first() 超时阈值（ms）。国产 ROM 文件 I/O 被拦截时避免永久挂起。 */
        private const val DATASTORE_TIMEOUT_MS = 5000L

        /**
         * DataStore 单值读取统一入口（超时 + 异常双兜底）。
         *
         * 背景（OPPO/vivo 启动闪退排查）：withTimeoutOrNull 只把「挂起」转 fallback——
         * DataStore 文件损坏抛 CorruptionException/IOException 会原样穿透，启动协程读到即崩。
         * runCatching 把「抛异常」（文件损坏等）也归入 fallback；corruptionHandler 已在
         * store 层兜住大部分损坏场景，此处是第二道防线。
         */
        private suspend fun <T> dataStoreFirst(flow: Flow<T>, fallback: T): T =
            runCatching { withTimeoutOrNull(DATASTORE_TIMEOUT_MS) { flow.first() } }.getOrNull() ?: fallback

        /** 同 [dataStoreFirst]，但读不到/异常都返回 null（供需区分「未设置」的调用方）。 */
        private suspend fun <T : Any> dataStoreFirstOrNull(flow: Flow<T?>): T? =
            runCatching { withTimeoutOrNull(DATASTORE_TIMEOUT_MS) { flow.first() } }.getOrNull()
    }
}
