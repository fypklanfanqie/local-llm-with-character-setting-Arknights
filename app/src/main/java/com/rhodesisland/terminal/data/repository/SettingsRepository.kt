package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.SettingsStore
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.llm.backend.BackendPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 设置仓库
 * 封装 SettingsStore，提供同步获取当前值的便捷方法
 */
class SettingsRepository(private val store: SettingsStore) {

    val apiConfig: Flow<ApiConfig> = store.apiConfig
    val ttsConfig: Flow<TtsConfig> = store.ttsConfig
    val ttsLanguage: Flow<TtsLanguage> = store.ttsLanguage
    val ttsVolume: Flow<Int> = store.ttsVolume
    val ttsVoiceMap: Flow<Map<String, VoicePair>> = store.ttsVoiceMap
    val activeCharacter: Flow<String> = store.activeCharacter
    val customCharacters: Flow<List<Character>> = store.customCharacters
    val volume: Flow<Int> = store.volume
    val musicFavorites: Flow<Set<String>> = store.musicFavorites
    val musicRepeatMode: Flow<Int> = store.musicRepeatMode
    val activeProvider: Flow<ChatProviderType> = store.activeProvider
    val activeLocalModelId: Flow<String?> = store.activeLocalModelId
    val llmContextLen: Flow<Int> = store.llmContextLen
    val llmThreads: Flow<Int> = store.llmThreads
    val llmTemperature: Flow<Float> = store.llmTemperature
    val llmMaxTokens: Flow<Int> = store.llmMaxTokens
    val llmBackend: Flow<BackendPreference> = store.llmBackend
    val llmCpuBoost: Flow<Boolean> = store.llmCpuBoost
    /** CPU lookahead 投机解码开关（默认关）。仅 MNN CPU 后端生效，改值需重载模型。 */
    val llmLookahead: Flow<Boolean> = store.llmLookahead
    /** 深度思考模式开关（本地 + 云端通用）。 */
    val deepThinking: Flow<Boolean> = store.deepThinking
    /** 性能浮窗液态玻璃开关（默认开）。 */
    val liquidGlass: Flow<Boolean> = store.liquidGlass
    /** 推理参数是否相对上次成功加载已变更（供设置页展示"将自动重载"横幅）*/
    val llmConfigChanged: Flow<Boolean> = store.llmConfigChanged

    suspend fun setApiConfig(config: ApiConfig) = store.setApiConfig(config)
    suspend fun setTtsConfig(config: TtsConfig) = store.setTtsConfig(config)
    suspend fun setTtsLanguage(lang: TtsLanguage) = store.setTtsLanguage(lang)
    suspend fun setTtsVolume(vol: Int) = store.setTtsVolume(vol)
    suspend fun setTtsVoiceMap(map: Map<String, VoicePair>) = store.setTtsVoiceMap(map)
    suspend fun setActiveCharacter(id: String) = store.setActiveCharacter(id)
    val activeConversations: Flow<Map<String, Long>> = store.activeConversations
    suspend fun setActiveConversation(characterId: String, conversationId: Long) =
        store.setActiveConversation(characterId, conversationId)
    suspend fun clearActiveConversation(characterId: String) = store.clearActiveConversation(characterId)
    suspend fun getActiveConversationNow(characterId: String): Long? =
        activeConversations.first()[characterId]
    suspend fun setCustomCharacters(list: List<Character>) = store.setCustomCharacters(list)
    suspend fun updateCustomCharacters(transform: (List<Character>) -> List<Character>) =
        store.updateCustomCharacters(transform)
    suspend fun setVolume(vol: Int) = store.setVolume(vol)
    suspend fun toggleMusicFavorite(key: String) = store.toggleMusicFavorite(key)
    suspend fun setMusicRepeatMode(mode: Int) = store.setMusicRepeatMode(mode)
    suspend fun setActiveProvider(type: ChatProviderType) = store.setActiveProvider(type)
    suspend fun setActiveLocalModelId(id: String?) = store.setActiveLocalModelId(id)
    suspend fun setLlmParams(
        contextLen: Int? = null,
        threads: Int? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
    ) = store.setLlmParams(contextLen, threads, temperature, maxTokens)

    suspend fun setLlmBackend(preference: BackendPreference) = store.setLlmBackend(preference)

    suspend fun setLlmCpuBoost(enabled: Boolean) = store.setLlmCpuBoost(enabled)

    suspend fun setLlmLookahead(enabled: Boolean) = store.setLlmLookahead(enabled)

    suspend fun setDeepThinking(enabled: Boolean) = store.setDeepThinking(enabled)

    suspend fun setLiquidGlass(enabled: Boolean) = store.setLiquidGlass(enabled)

    /** 一次成功推理后写回本次生效的用户配置，使 [llmConfigChanged] 归 false */
    suspend fun acknowledgeLlmConfig(
        threads: Int, contextLen: Int, backend: BackendPreference, lookahead: Boolean, temperature: Float,
    ) = store.acknowledgeLlmConfig(threads, contextLen, backend, lookahead, temperature)

    /** 同步获取当前 API 配置（阻塞读取 Flow 首值） */
    suspend fun getApiConfigNow(): ApiConfig = apiConfig.first()

    suspend fun getTtsConfigNow(): TtsConfig = ttsConfig.first()

    suspend fun getTtsLanguageNow(): TtsLanguage = ttsLanguage.first()

    suspend fun getTtsVoiceMapNow(): Map<String, VoicePair> = ttsVoiceMap.first()

    suspend fun getActiveProviderNow(): ChatProviderType = activeProvider.first()

    suspend fun getActiveLocalModelIdNow(): String? = activeLocalModelId.first()

    suspend fun getDeepThinkingNow(): Boolean = deepThinking.first()
}
