package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.llm.backend.BackendPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "rhodes_settings")

/**
 * 设置存储（DataStore）
 * 对应小程序 utils/storage.js
 * 已删除付费相关字段（credits / ad_unit_id / openid）
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        // API
        val API_BASE = stringPreferencesKey("api_base")
        val API_KEY = stringPreferencesKey("api_key")
        val API_MODEL = stringPreferencesKey("api_model")

        // TTS
        val TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val TTS_VOLUME = intPreferencesKey("tts_volume")
        val TTS_VOICE_MAP = stringPreferencesKey("tts_voice_map")  // JSON: Map<characterId, VoicePair>

        // 角色
        val ACTIVE_CHARACTER = stringPreferencesKey("active_character")
        val CUSTOM_CHARACTERS = stringPreferencesKey("custom_characters")  // JSON: List<Character>

        // 会话：角色 -> 当前活跃会话 id
        val ACTIVE_CONVERSATIONS = stringPreferencesKey("active_conversations")  // JSON: Map<String, Long>

        // 音量
        val VOLUME = intPreferencesKey("volume")

        // 音乐
        val MUSIC_FAVORITES = stringSetPreferencesKey("music_favorites")
        val MUSIC_REPEAT_MODE = intPreferencesKey("music_repeat_mode")

        // ★ 本地 AI
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")  // CLOUD / LOCAL
        val ACTIVE_LOCAL_MODEL = stringPreferencesKey("active_local_model")
        val LLM_CONTEXT_LEN = intPreferencesKey("llm_context_len")
        val LLM_THREADS = intPreferencesKey("llm_threads")
        val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        // 推理后端偏好：AUTO / MNN_CPU / MNN_GPU / MNN_NPU（见 BackendPreference）
        val LLM_BACKEND = stringPreferencesKey("llm_backend")
        // CPU 推理提频开关：PerformanceHintManager hint session + SustainedPerformanceMode + 高线程优先级
        val LLM_CPU_BOOST = booleanPreferencesKey("llm_cpu_boost")
        // CPU lookahead 投机解码开关（n-gram，无需 draft 模型）；仅 MNN CPU 后端生效，改值需重载模型
        val LLM_LOOKAHEAD = booleanPreferencesKey("llm_lookahead")
        // 深度思考模式开关（本地 + 云端通用）：控制推理过程是否生成与展示
        val DEEP_THINKING = booleanPreferencesKey("deep_thinking")
        // 性能监控浮窗液态玻璃效果开关（默认开）：backdrop blur + 镜面高光 + 旋转虹彩光晕；关闭则用普通深色面板
        val LIQUID_GLASS = booleanPreferencesKey("liquid_glass_perf_overlay")

        // 通讯界面自定义背景：是否启用 + 内部存储图片绝对路径列表（JSON List<String>，有序）。
        // 所选相册图复制到 filesDir/chat_backgrounds/，仅存路径，不依赖 SAF 持久权限。
        val CHAT_BG_ENABLED = booleanPreferencesKey("chat_bg_enabled")
        val CHAT_BG_PATHS = stringPreferencesKey("chat_bg_paths")

        // ===== 配置变更检测（移植自 iFeng 的 hasConfigChanged/acknowledgeConfigChange）=====
        // 记录"上次成功加载模型时所用的"线程/上下文/后端/lookahead。当前值 != last_applied 即视为已变更，
        // 设置页据此展示"下次发送将自动重载"横幅；LocalChatProvider 在 generate 成功后 acknowledge 写回。
        // 持久化（而非纯内存）：冷启动时 last_applied 与当前默认值对齐 -> 无横幅，避免每次冷启都误报。
        val LLM_LAST_THREADS = intPreferencesKey("llm_last_threads")
        val LLM_LAST_CONTEXT_LEN = intPreferencesKey("llm_last_context_len")
        val LLM_LAST_BACKEND = stringPreferencesKey("llm_last_backend")
        val LLM_LAST_LOOKAHEAD = booleanPreferencesKey("llm_last_lookahead")
        val LLM_LAST_TEMPERATURE = floatPreferencesKey("llm_last_temperature")
    }

    // ===== API Config =====
    val apiConfig: Flow<ApiConfig> = context.settingsDataStore.data.map { p ->
        ApiConfig(
            baseUrl = p[Keys.API_BASE] ?: AppConfig.DEFAULT_API_BASE,
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.API_MODEL] ?: AppConfig.DEFAULT_MODEL,
        )
    }

    suspend fun setApiConfig(config: ApiConfig) {
        context.settingsDataStore.edit { p ->
            p[Keys.API_BASE] = config.baseUrl
            p[Keys.API_KEY] = config.apiKey
            p[Keys.API_MODEL] = config.model
        }
    }

    // ===== TTS Config =====
    val ttsConfig: Flow<TtsConfig> = context.settingsDataStore.data.map { p ->
        TtsConfig(
            apiKey = p[Keys.TTS_API_KEY] ?: "",
        )
    }

    suspend fun setTtsConfig(config: TtsConfig) {
        context.settingsDataStore.edit { p ->
            p[Keys.TTS_API_KEY] = config.apiKey
        }
    }

    val ttsLanguage: Flow<TtsLanguage> = context.settingsDataStore.data.map { p ->
        TtsLanguage.fromCode(p[Keys.TTS_LANGUAGE] ?: "zh")
    }

    suspend fun setTtsLanguage(lang: TtsLanguage) {
        context.settingsDataStore.edit { it[Keys.TTS_LANGUAGE] = lang.code }
    }

    val ttsVolume: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.TTS_VOLUME] ?: AppConfig.TTS_DEFAULT_VOLUME
    }

    suspend fun setTtsVolume(vol: Int) {
        context.settingsDataStore.edit { it[Keys.TTS_VOLUME] = vol }
    }

    // ===== 角色音色映射 =====
    private val voiceJson = Json { ignoreUnknownKeys = true; isLenient = true }

    val ttsVoiceMap: Flow<Map<String, VoicePair>> = context.settingsDataStore.data.map { p ->
        val raw = p[Keys.TTS_VOICE_MAP] ?: ""
        if (raw.isBlank()) emptyMap()
        else try {
            voiceJson.decodeFromString<Map<String, VoicePair>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun setTtsVoiceMap(map: Map<String, VoicePair>) {
        context.settingsDataStore.edit { it[Keys.TTS_VOICE_MAP] = voiceJson.encodeToString(map) }
    }

    // ===== 角色 =====
    val activeCharacter: Flow<String> = context.settingsDataStore.data.map { p ->
        p[Keys.ACTIVE_CHARACTER] ?: Characters.DEFAULT_CHARACTER_ID
    }

    suspend fun setActiveCharacter(id: String) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_CHARACTER] = id }
    }

    // ===== 会话：每角色当前活跃会话 =====
    // 存 Map<characterId, conversationId>，切换角色时恢复该角色上次活跃的会话。
    val activeConversations: Flow<Map<String, Long>> = context.settingsDataStore.data.map { p ->
        val raw = p[Keys.ACTIVE_CONVERSATIONS] ?: ""
        if (raw.isBlank()) emptyMap()
        else runCatching { voiceJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun setActiveConversation(characterId: String, conversationId: Long) {
        context.settingsDataStore.edit { p ->
            val raw = p[Keys.ACTIVE_CONVERSATIONS] ?: ""
            val current: Map<String, Long> = if (raw.isBlank()) emptyMap()
            else runCatching { voiceJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
            p[Keys.ACTIVE_CONVERSATIONS] = voiceJson.encodeToString(current + (characterId to conversationId))
        }
    }

    /** 删除会话后清理指向它的活跃记录（让角色回到「无活跃会话」态，由 ViewModel 重建） */
    suspend fun clearActiveConversation(characterId: String) {
        context.settingsDataStore.edit { p ->
            val raw = p[Keys.ACTIVE_CONVERSATIONS] ?: ""
            val current: Map<String, Long> = if (raw.isBlank()) emptyMap()
            else runCatching { voiceJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
            p[Keys.ACTIVE_CONVERSATIONS] = voiceJson.encodeToString(current - characterId)
        }
    }

    // ===== 自定义角色 =====
    val customCharacters: Flow<List<Character>> = context.settingsDataStore.data.map { p ->
        val raw = p[Keys.CUSTOM_CHARACTERS] ?: ""
        if (raw.isBlank()) emptyList()
        else try {
            voiceJson.decodeFromString<List<Character>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun setCustomCharacters(list: List<Character>) {
        context.settingsDataStore.edit { it[Keys.CUSTOM_CHARACTERS] = voiceJson.encodeToString(list) }
    }

    /**
     * 原子地更新自定义角色：读-改-写整个流程在单个 DataStore edit 事务内完成。
     * 旧的 addCustom/removeCustom/importCustom 是「customCharacters.first() 读 -> 改 -> setCustomCharacters 写」，
     * 两次并发调用会发生 lost update（A、B 都读到 [x]，A 写 [x,y]、B 写 [x,z]，最终丢失 y）。
     * 改为在 edit 闭包内读取当前值并写入新值，由 DataStore 保证原子性。
     */
    suspend fun updateCustomCharacters(transform: (List<Character>) -> List<Character>) {
        context.settingsDataStore.edit { p ->
            val raw = p[Keys.CUSTOM_CHARACTERS] ?: ""
            val current: List<Character> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<Character>>(raw) }.getOrDefault(emptyList())
            p[Keys.CUSTOM_CHARACTERS] = voiceJson.encodeToString(transform(current))
        }
    }

    // ===== 音量 =====
    val volume: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.VOLUME] ?: 60
    }

    suspend fun setVolume(vol: Int) {
        context.settingsDataStore.edit { it[Keys.VOLUME] = vol }
    }

    // ===== 音乐 =====
    val musicFavorites: Flow<Set<String>> = context.settingsDataStore.data.map { p ->
        p[Keys.MUSIC_FAVORITES] ?: emptySet()
    }

    suspend fun toggleMusicFavorite(key: String): Boolean {
        var added = false
        context.settingsDataStore.edit { p ->
            val current = p[Keys.MUSIC_FAVORITES] ?: emptySet()
            if (key in current) {
                p[Keys.MUSIC_FAVORITES] = current - key
                added = false
            } else {
                p[Keys.MUSIC_FAVORITES] = current + key
                added = true
            }
        }
        return added
    }

    val musicRepeatMode: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.MUSIC_REPEAT_MODE] ?: 0
    }

    suspend fun setMusicRepeatMode(mode: Int) {
        context.settingsDataStore.edit { it[Keys.MUSIC_REPEAT_MODE] = mode }
    }

    // ===== 本地 AI =====
    val activeProvider: Flow<ChatProviderType> = context.settingsDataStore.data.map { p ->
        val v = p[Keys.ACTIVE_PROVIDER] ?: "CLOUD"
        if (v == "LOCAL") ChatProviderType.LOCAL else ChatProviderType.CLOUD
    }

    suspend fun setActiveProvider(type: ChatProviderType) {
        context.settingsDataStore.edit { it[Keys.ACTIVE_PROVIDER] = type.name }
    }

    val activeLocalModelId: Flow<String?> = context.settingsDataStore.data.map { p ->
        p[Keys.ACTIVE_LOCAL_MODEL]
    }

    suspend fun setActiveLocalModelId(id: String?) {
        context.settingsDataStore.edit { p ->
            if (id == null) p.remove(Keys.ACTIVE_LOCAL_MODEL)
            else p[Keys.ACTIVE_LOCAL_MODEL] = id
        }
    }

    val llmContextLen: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_CONTEXT_LEN] ?: AppConfig.LLM.DEFAULT_CONTEXT_LEN
    }

    val llmThreads: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_THREADS] ?: AppConfig.LLM.DEFAULT_THREADS
    }

    val llmTemperature: Flow<Float> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_TEMPERATURE] ?: AppConfig.LLM.DEFAULT_TEMPERATURE
    }

    val llmMaxTokens: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_MAX_TOKENS] ?: AppConfig.LLM.DEFAULT_MAX_TOKENS
    }

    /** 推理后端偏好（默认 AUTO）*/
    val llmBackend: Flow<BackendPreference> = context.settingsDataStore.data.map { p ->
        BackendPreference.fromKey(p[Keys.LLM_BACKEND])
    }

    suspend fun setLlmBackend(preference: BackendPreference) {
        context.settingsDataStore.edit { it[Keys.LLM_BACKEND] = preference.storageKey }
    }

    /** CPU 推理提频开关（默认开）。非 root 下用系统提频机制把大核频率尽量推高，详见 CpuBoostController。 */
    val llmCpuBoost: Flow<Boolean> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_CPU_BOOST] ?: true
    }

    suspend fun setLlmCpuBoost(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LLM_CPU_BOOST] = enabled }
    }

    /** CPU lookahead 投机解码开关（默认关）。n-gram 投机解码无需 draft 模型，仅 MNN CPU 后端生效，改值触发下次重载。
     *  默认关：首轮无 n-gram 历史时 draft 全 miss，每步多跑 draft_predict_length 个前向却只产 1 token，
     *  在慢模型上反而数倍拖慢首条回复；多轮重复/代码类文本再开可获 1.5–3× 提速。 */
    val llmLookahead: Flow<Boolean> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_LOOKAHEAD] ?: false
    }

    suspend fun setLlmLookahead(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LLM_LOOKAHEAD] = enabled }
    }

    // ===== 深度思考模式（本地 + 云端通用）=====
    /** 深度思考开关（默认关）。开启时展示推理过程并（对支持的供应商）请求思考。 */
    val deepThinking: Flow<Boolean> = context.settingsDataStore.data.map { p ->
        p[Keys.DEEP_THINKING] ?: false
    }

    suspend fun setDeepThinking(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DEEP_THINKING] = enabled }
    }

    // ===== 性能浮窗液态玻璃 =====
    /** 性能浮窗液态玻璃开关（默认开）。开启：背景模糊 + 镜面高光 + 旋转虹彩光晕；关闭：普通深色面板。背景模糊需 Android 12+。 */
    val liquidGlass: Flow<Boolean> = context.settingsDataStore.data.map { p ->
        p[Keys.LIQUID_GLASS] ?: true
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LIQUID_GLASS] = enabled }
    }

    // ===== 通讯界面自定义背景 =====
    /** 自定义背景开关（默认关）。开启且路径列表非空 -> 轮播自定义图片；否则回退内置 PRTS 背景轮播。 */
    val chatBgEnabled: Flow<Boolean> = context.settingsDataStore.data.map { p ->
        p[Keys.CHAT_BG_ENABLED] ?: false
    }

    suspend fun setChatBgEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CHAT_BG_ENABLED] = enabled }
    }

    /** 自定义背景图片路径列表（有序）。 */
    val chatBgPaths: Flow<List<String>> = context.settingsDataStore.data.map { p ->
        val raw = p[Keys.CHAT_BG_PATHS] ?: ""
        if (raw.isBlank()) emptyList()
        else runCatching { voiceJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setChatBgPaths(paths: List<String>) {
        context.settingsDataStore.edit { it[Keys.CHAT_BG_PATHS] = voiceJson.encodeToString(paths) }
    }

    /**
     * 原子更新自定义背景路径列表（读-改-写在单个 DataStore edit 事务内），避免并发 lost update。
     * 20 张上限由 [com.rhodesisland.terminal.data.repository.ChatBackgroundRepository] 在 addUris 处强制。
     */
    suspend fun updateChatBgPaths(transform: (List<String>) -> List<String>) {
        context.settingsDataStore.edit { p ->
            val raw = p[Keys.CHAT_BG_PATHS] ?: ""
            val current: List<String> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            val next = transform(current)
            p[Keys.CHAT_BG_PATHS] = voiceJson.encodeToString(next)
        }
    }

    // ===== 配置变更检测（移植自 iFeng SettingsManager.hasConfigChanged / acknowledgeConfigChange）=====

    /** 上次成功加载模型时生效的线程数（冷启动默认与当前默认值对齐，避免误报变更）*/
    val lastAppliedThreads: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_LAST_THREADS] ?: AppConfig.LLM.DEFAULT_THREADS
    }

    /** 上次成功加载模型时生效的上下文长度 */
    val lastAppliedContextLen: Flow<Int> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_LAST_CONTEXT_LEN] ?: AppConfig.LLM.DEFAULT_CONTEXT_LEN
    }

    /** 上次成功加载模型时生效的后端偏好 */
    val lastAppliedBackend: Flow<BackendPreference> = context.settingsDataStore.data.map { p ->
        BackendPreference.fromKey(p[Keys.LLM_LAST_BACKEND])
    }

    /** 上次成功加载模型时生效的 lookahead 开关（默认关，与 [llmLookahead] 对齐，避免新装误报变更）*/
    val lastAppliedLookahead: Flow<Boolean> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_LAST_LOOKAHEAD] ?: false
    }

    /** 上次成功加载模型时生效的采样温度（MNN 采样器在 load 时构建，温度改值须重载）*/
    val lastAppliedTemperature: Flow<Float> = context.settingsDataStore.data.map { p ->
        p[Keys.LLM_LAST_TEMPERATURE] ?: AppConfig.LLM.DEFAULT_TEMPERATURE
    }

    /** 配置快照（线程/上下文/后端/lookahead/温度），供 [llmConfigChanged] 比对 */
    private data class LlmCfg(val threads: Int, val contextLen: Int, val backend: BackendPreference, val lookahead: Boolean, val temperature: Float)

    /**
     * 推理参数是否相对上次成功加载已变更（线程 / 上下文 / 后端偏好 / lookahead / 温度 任一不同即为 true）。
     * 供设置页展示"下次发送消息将自动重载"横幅。注意：此处比对的是用户设定值
     *（[llmThreads]），非 BackendManager 实际使用的 effective threads--后者由大核数/温控
     * 再折算，与本 UI 无关；横幅只回答"用户是否改了设置"。lookahead 仅 CPU 后端生效，但切换
     * 仍需重载模型才生效，故一并纳入比对。温度同理：MNN 采样器在 load() 构建，改温度须重载。
     */
    val llmConfigChanged: Flow<Boolean> = run {
        val current = combine(llmThreads, llmContextLen, llmBackend, llmLookahead, llmTemperature) { t, c, b, l, tp -> LlmCfg(t, c, b, l, tp) }
        val applied = combine(lastAppliedThreads, lastAppliedContextLen, lastAppliedBackend, lastAppliedLookahead, lastAppliedTemperature) { t, c, b, l, tp -> LlmCfg(t, c, b, l, tp) }
        combine(current, applied) { cur, app -> cur != app }
    }

    /**
     * 在一次成功推理后把"本次生效的"用户配置写回 last_applied，使 [llmConfigChanged] 归 false。
     * 传用户设定值（[com.rhodesisland.terminal.data.repository.SettingsRepository.llmThreads] 对应的值），
     * 非 effective threads。单次 edit 事务保证原子性。
     */
    suspend fun acknowledgeLlmConfig(threads: Int, contextLen: Int, backend: BackendPreference, lookahead: Boolean, temperature: Float) {
        context.settingsDataStore.edit { p ->
            p[Keys.LLM_LAST_THREADS] = threads
            p[Keys.LLM_LAST_CONTEXT_LEN] = contextLen
            p[Keys.LLM_LAST_BACKEND] = backend.storageKey
            p[Keys.LLM_LAST_LOOKAHEAD] = lookahead
            p[Keys.LLM_LAST_TEMPERATURE] = temperature
        }
    }

    suspend fun setLlmParams(
        contextLen: Int? = null,
        threads: Int? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
    ) {
        context.settingsDataStore.edit { p ->
            contextLen?.let { p[Keys.LLM_CONTEXT_LEN] = it }
            threads?.let { p[Keys.LLM_THREADS] = it }
            temperature?.let { p[Keys.LLM_TEMPERATURE] = it }
            maxTokens?.let { p[Keys.LLM_MAX_TOKENS] = it }
        }
    }
}
