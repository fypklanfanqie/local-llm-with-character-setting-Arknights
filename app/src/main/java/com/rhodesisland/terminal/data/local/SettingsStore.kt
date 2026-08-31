package com.rhodesisland.terminal.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.GroupChatConfig
import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookGlobalConfig
import com.rhodesisland.terminal.data.model.MomentAutoConfig
import com.rhodesisland.terminal.data.model.MomentImageGenConfig
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.UserProfileConfig
import com.rhodesisland.terminal.data.model.Worldview
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.ThemeMode
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsEngine
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.model.VoiceConfig
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.data.repository.BgmTrack
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// corruptionHandler：文件损坏（国产 ROM 强杀进程写中断）时重置为空偏好，绝不抛异常——
// 启动路径读取损坏文件曾直接闪退（见 DataStoreCorruption.kt 说明）。
private val Context.settingsDataStore by preferencesDataStore(
    name = "rhodes_settings",
    corruptionHandler = tolerantCorruptionHandler,
)

/**
 * 设置存储（DataStore）
 * 对应小程序 utils/storage.js
 * 已删除付费相关字段（credits / ad_unit_id / openid）
 */
class SettingsStore(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore,
) {

    private object Keys {
        // 主题模式
        val THEME_MODE = stringPreferencesKey("theme_mode")

        // API
        val API_BASE = stringPreferencesKey("api_base")
        val API_KEY = stringPreferencesKey("api_key")
        val API_MODEL = stringPreferencesKey("api_model")
        // 每服务商独立保存的 API 配置（key = 预设 id 或 "custom"），切换服务商互不串 key/model/baseUrl。
        val PROVIDER_API_CONFIGS = stringPreferencesKey("provider_api_configs")

        // TTS
        val TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val TTS_DEFAULT_VOICE_ID = stringPreferencesKey("tts_default_voice_id")
        val TTS_APP_ID = stringPreferencesKey("tts_app_id")
        val TTS_ACCESS_KEY = stringPreferencesKey("tts_access_key")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val TTS_VOLUME = intPreferencesKey("tts_volume")
        val TTS_VOICE_MAP = stringPreferencesKey("tts_voice_map")  // JSON: Map<characterId, VoicePair>
        val TTS_ENGINE = stringPreferencesKey("tts_engine")        // system（默认，手机自带）/ cloud（火山豆包）
        val TTS_SYSTEM_TEMPLATE = stringPreferencesKey("tts_system_template")  // 系统引擎声音模板
        /** 自动朗读角色完整回复（默认关闭，避免首次升级后突然播放声音）。 */
        val TTS_AUTO_READ = booleanPreferencesKey("tts_auto_read")

        // 角色
        val ACTIVE_CHARACTER = stringPreferencesKey("active_character")
        val CUSTOM_CHARACTERS = stringPreferencesKey("custom_characters")  // JSON: List<Character>
        // 自定义世界观（JSON: List<Worldview>，一条绑定一个目标）
        val WORLDVIEWS = stringPreferencesKey("worldviews")
        // 世界书（JSON: List<Lorebook>，作用域路由全局/多选角色/多选群聊）+ 全局参数快照
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val LOREBOOK_CONFIG = stringPreferencesKey("lorebook_config")

        // 会话：角色 -> 当前活跃会话 id
        val ACTIVE_CONVERSATIONS = stringPreferencesKey("active_conversations")  // JSON: Map<String, Long>

        // 音量
        val VOLUME = intPreferencesKey("volume")

        // 音乐
        val MUSIC_FAVORITES = stringSetPreferencesKey("music_favorites")
        val MUSIC_REPEAT_MODE = intPreferencesKey("music_repeat_mode")  // 0=顺序播放, 1=列表循环, 2=单曲循环
        val MUSIC_PLAYLIST = stringPreferencesKey("music_playlist")  // JSON: List<BgmTrack>（本地导入 + 已添加在线曲）
        val MUSIC_SHUFFLE = booleanPreferencesKey("music_shuffle")

        // ★ 本地 AI
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")  // CLOUD / LOCAL
        val ACTIVE_LOCAL_MODEL = stringPreferencesKey("active_local_model")
        val LLM_CONTEXT_LEN = intPreferencesKey(LocalInferenceSettings.CONTEXT_LEN_KEY)
        val LLM_THREADS = intPreferencesKey(LocalInferenceSettings.THREADS_KEY)
        val LLM_TEMPERATURE = floatPreferencesKey(LocalInferenceSettings.TEMPERATURE_KEY)
        val LLM_MAX_TOKENS = intPreferencesKey(LocalInferenceSettings.MAX_TOKENS_KEY)
        // 推理后端偏好：AUTO / MNN_CPU / MNN_GPU / MNN_NPU（见 BackendPreference）
        val LLM_BACKEND = stringPreferencesKey("llm_backend")
        // 推理性能模式：BALANCED / MAXIMUM_SPEED（Task 6）。键名单点定义见 LocalInferenceSettings。
        val LLM_PERFORMANCE_MODE = stringPreferencesKey(LocalInferenceSettings.PERFORMANCE_MODE_KEY)
        // CPU 推理提频开关（legacy，Task 6 起不再权威；由性能模式解析层接管，高级诊断视图仍可改）
        val LLM_CPU_BOOST = booleanPreferencesKey(LocalInferenceSettings.CPU_BOOST_KEY)
        // CPU lookahead 投机解码开关（legacy，Task 6 起不再权威）；仅 MNN CPU 后端生效，改值需重载模型
        val LLM_LOOKAHEAD = booleanPreferencesKey(LocalInferenceSettings.LOOKAHEAD_KEY)
        // 深度思考模式开关（本地 + 云端通用）：控制推理过程是否生成与展示
        val DEEP_THINKING = booleanPreferencesKey(LocalInferenceSettings.DEEP_THINKING_KEY)
        // 本地思考档位（默认 AUTO，仅本地生效）：开启深度思考后决定思考强度；云端不读取本键
        val THINKING_LEVEL = stringPreferencesKey(LocalInferenceSettings.THINKING_LEVEL_KEY)
        // 性能监控浮窗液态玻璃效果开关（默认开）：backdrop blur + 镜面高光 + 旋转虹彩光晕；关闭则用普通深色面板
        val LIQUID_GLASS = booleanPreferencesKey("liquid_glass_perf_overlay")
        // 聊天顶栏第二行控件（云端/本地 + 快捷开关）是否展开（默认开）
        val CHAT_TOPBAR_CONTROLS_EXPANDED = booleanPreferencesKey("chat_topbar_controls_expanded")

        // 滚动摘要上下文压缩（单聊云端）：用户可调折叠批量（每 N 条压缩一次，默认 50）
        val ROLLING_SUMMARY_FOLD_BATCH = intPreferencesKey("cloud_summary_fold_batch")
        // 云端生成参数（留空/未设置=不带字段、走模型商默认；参照大众版「生成参数」区语义）
        val CLOUD_TEMPERATURE = floatPreferencesKey("cloud_temperature")
        val CLOUD_MAX_TOKENS = intPreferencesKey("cloud_max_tokens")

        // 通讯界面自定义背景：是否启用 + 内部存储图片绝对路径列表（JSON List<String>，有序）。
        // 所选相册图复制到 filesDir/chat_backgrounds/，仅存路径，不依赖 SAF 持久权限。
        val CHAT_BG_ENABLED = booleanPreferencesKey("chat_bg_enabled")
        val CHAT_BG_PATHS = stringPreferencesKey("chat_bg_paths")

        // Seedance 对话视频（Task 3）：聚合配置。generateAudio 固定 true 无存储键；
        // fps/seed/camera 不支持故无键。API Key 仅落 DataStore，绝不写入 Room/日志/WorkData。
        val SEEDANCE_BASE_URL = stringPreferencesKey("seedance_base_url")
        val SEEDANCE_API_KEY = stringPreferencesKey("seedance_api_key")
        val SEEDANCE_MODEL_ID = stringPreferencesKey("seedance_model_id")
        val SEEDANCE_VARIANT = stringPreferencesKey("seedance_variant")
        val SEEDANCE_RESOLUTION = stringPreferencesKey("seedance_resolution")
        val SEEDANCE_RATIO = stringPreferencesKey("seedance_ratio")
        val SEEDANCE_DURATION = intPreferencesKey("seedance_duration")
        val SEEDANCE_WATERMARK = booleanPreferencesKey("seedance_watermark")
        val SEEDANCE_SCENE_PATH = stringPreferencesKey("seedance_scene_path")
        val SEEDANCE_SCENE_DESCRIPTION = stringPreferencesKey("seedance_scene_description")

        // 角色问候（角色主动消息）：仅云端可用
        val GREETING_ENABLED = booleanPreferencesKey("greeting_enabled")
        val GREETING_CHARACTER_IDS = stringSetPreferencesKey("greeting_character_ids")  // 可多选
        val GREETING_DAILY_COUNT = intPreferencesKey("greeting_daily_count")
        // 每日配额：当天已发条数，按日期(yyyy-MM-dd)重置
        val GREETING_QUOTA_DATE = stringPreferencesKey("greeting_quota_date")
        val GREETING_QUOTA_COUNT = intPreferencesKey("greeting_quota_count")
        // 上次发问候的角色 id（跨天也连续轮询，保证多个已选角色轮流被投递）
        val GREETING_LAST_CHAR_ID = stringPreferencesKey("greeting_last_char_id")
        // 下一次问候投递的绝对目标时间（epoch ms）；<=0 表示尚未初始化（首次启用后先算一个随机时刻）
        val GREETING_NEXT_FIRE_AT = longPreferencesKey("greeting_next_fire_at")

        // ===== 群聊（多人角色同群聊天，仅云端可用）=====
        val GROUP_CHAT_ENABLED = booleanPreferencesKey("group_chat_enabled")
        val GROUP_MEMBER_IDS = stringSetPreferencesKey("group_member_ids")  // 可多选
        val GROUP_AUTO_CHAT_ENABLED = booleanPreferencesKey("group_auto_chat_enabled")
        // 每日自动聊天「轮次」上限（1~20，每轮 = 2 条互聊 或 1 条主动提问）
        val GROUP_DAILY_ROUNDS = intPreferencesKey("group_daily_rounds")
        // 每日配额：当天已执行的轮次数，按日期(yyyy-MM-dd)重置
        val GROUP_QUOTA_DATE = stringPreferencesKey("group_quota_date")
        val GROUP_QUOTA_COUNT = intPreferencesKey("group_quota_count")
        // 上次发言的成员 id（跨天连续轮询）
        val GROUP_LAST_SPEAKER_ID = stringPreferencesKey("group_last_speaker_id")
        // 已执行轮次累计计数（决定 discuss 轮 / ask-user 轮轮换）
        val GROUP_ROUND_COUNTER = longPreferencesKey("group_round_counter")
        // 用户最近一次在群聊发消息的时间（epoch ms；0 = 从未）——自动聊天冷却闸依据
        val GROUP_LAST_USER_AT = longPreferencesKey("group_last_user_message_at")
        // 下一次自动聊天触发的绝对目标时间（epoch ms；<=0 = 尚未初始化）
        val GROUP_NEXT_FIRE_AT = longPreferencesKey("group_next_fire_at")

        // ===== 博士档案（我的形象）=====
        val USER_AVATAR_PATH = stringPreferencesKey("user_avatar_path")
        val USER_PERSONA = stringPreferencesKey("user_persona")
        val USER_RELATIONSHIP = stringPreferencesKey("user_relationship")

        // ===== 朋友圈（仿微信）=====
        // 生图 API（OpenAI 聊天格式兼容的中转站/官方端点；与主 LLM 配置分离）。API Key 仅落 DataStore。
        val MOMENT_IMAGEGEN_BASE_URL = stringPreferencesKey("moment_imagegen_base_url")
        val MOMENT_IMAGEGEN_API_KEY = stringPreferencesKey("moment_imagegen_api_key")
        val MOMENT_IMAGEGEN_MODEL = stringPreferencesKey("moment_imagegen_model")
        // 朋友圈封面图内部存储路径（空=默认渐变）
        val MOMENT_COVER_PATH = stringPreferencesKey("moment_cover_path")
        // 自动发圈：开关 + 间隔（小时）+ 参与角色集 + 下次触发时间 + 上次发帖角色（轮换）
        val MOMENT_AUTO_ENABLED = booleanPreferencesKey("moment_auto_enabled")
        val MOMENT_AUTO_INTERVAL_HOURS = intPreferencesKey("moment_auto_interval_hours")
        val MOMENT_AUTO_CHARACTER_IDS = stringSetPreferencesKey("moment_auto_character_ids")
        val MOMENT_NEXT_FIRE_AT = longPreferencesKey("moment_next_fire_at")
        val MOMENT_LAST_CHAR_ID = stringPreferencesKey("moment_last_char_id")

        // ===== 配置变更检测（移植自 iFeng 的 hasConfigChanged/acknowledgeConfigChange）=====
        // 记录"上次成功加载模型时所用的"线程/上下文/后端/lookahead。当前值 != last_applied 即视为已变更，
        // 设置页据此展示"下次发送将自动重载"横幅；LocalChatProvider 在 generate 成功后 acknowledge 写回。
        // 持久化（而非纯内存）：冷启动时 last_applied 与当前默认值对齐 -> 无横幅，避免每次冷启都误报。
        val LLM_LAST_THREADS = intPreferencesKey("llm_last_threads")
        val LLM_LAST_CONTEXT_LEN = intPreferencesKey("llm_last_context_len")
        val LLM_LAST_BACKEND = stringPreferencesKey("llm_last_backend")
        val LLM_LAST_LOOKAHEAD = booleanPreferencesKey("llm_last_lookahead")
        val LLM_LAST_TEMPERATURE = floatPreferencesKey("llm_last_temperature")
        // Task 7：最近一次成功加载实际应用的 plan loadConfigHash（唯一重载指纹）。
        val LLM_LAST_CONFIG_HASH = stringPreferencesKey("llm_last_config_hash")

        // ===== 使用指南 =====
        // 是否已完成首次阅读水平选择；true 后指南初始页不再弹水平选择。
        val GUIDE_SETUP_DONE = booleanPreferencesKey("guide_setup_done")
        // 当前阅读水平："BEGINNER" / "EXPERIENCED"；"" = 未选择（仅 setup_done=false 时合法）。
        val GUIDE_LEVEL = stringPreferencesKey("guide_level")
    }

    // ===== Theme Mode =====
    val themeMode: Flow<ThemeMode> = dataStore.data.map { p ->
        ThemeMode.fromKey(p[Keys.THEME_MODE])
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    // ===== API Config =====
    val apiConfig: Flow<ApiConfig> = dataStore.data.map { p ->
        ApiConfig(
            baseUrl = p[Keys.API_BASE] ?: AppConfig.DEFAULT_API_BASE,
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.API_MODEL] ?: AppConfig.DEFAULT_MODEL,
        )
    }

    suspend fun setApiConfig(config: ApiConfig) {
        dataStore.edit { p ->
            p[Keys.API_BASE] = config.baseUrl
            p[Keys.API_KEY] = config.apiKey
            p[Keys.API_MODEL] = config.model
        }
    }

    // ===== 每服务商 API 配置（切换服务商互不串 key/model/baseUrl）=====
    private val providerApiJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 每服务商独立配置表：key = 预设 id 或 "custom"。 */
    val providerApiConfigs: Flow<Map<String, ApiConfig>> = dataStore.data.map { p ->
        p[Keys.PROVIDER_API_CONFIGS]?.let { raw ->
            runCatching { providerApiJson.decodeFromString<Map<String, ApiConfig>>(raw) }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    /** 读取某服务商已保存的独立配置（无则 null）。 */
    suspend fun getProviderApiConfig(key: String): ApiConfig? =
        providerApiConfigs.first()[key]

    /** 写入某服务商的独立配置（原子读改写，避免并发丢更新）。 */
    suspend fun setProviderApiConfig(key: String, config: ApiConfig) {
        dataStore.edit { p ->
            val current = p[Keys.PROVIDER_API_CONFIGS]?.let { raw ->
                runCatching { providerApiJson.decodeFromString<Map<String, ApiConfig>>(raw) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            p[Keys.PROVIDER_API_CONFIGS] = providerApiJson.encodeToString(current + (key to config))
        }
    }

    // ===== Seedance 对话视频 =====
    /**
     * Seedance 视频生成配置聚合快照（Task 3）。单个 data.map 读取全部相关键。
     * 缺失键回退 [SeedanceConfig] 默认值（只读、不写回）；未知枚举存储键经 fromStorageKey 保守回落；
     * 时长越界回落默认 5 秒（4–15 与 com.rhodesisland.terminal.video.SEEDANCE_MIN/MAX_DURATION_SECONDS 对齐）。
     * generateAudio 固定 true，无存储键；无 fps/seed/camera 键。
     */
    val seedanceConfig: Flow<SeedanceConfig> = dataStore.data.map { p ->
        val storedDuration = p[Keys.SEEDANCE_DURATION] ?: 5
        SeedanceConfig(
            baseUrl = p[Keys.SEEDANCE_BASE_URL] ?: SeedanceConfig().baseUrl,
            apiKey = p[Keys.SEEDANCE_API_KEY] ?: "",
            relayModelId = p[Keys.SEEDANCE_MODEL_ID] ?: SeedanceConfig().relayModelId,
            variant = SeedanceModelVariant.fromStorageKey(p[Keys.SEEDANCE_VARIANT]),
            resolution = SeedanceResolution.fromStorageKey(p[Keys.SEEDANCE_RESOLUTION]),
            ratio = SeedanceRatio.fromStorageKey(p[Keys.SEEDANCE_RATIO]),
            durationSeconds = storedDuration.takeIf { it in 4..15 } ?: 5,
            watermark = p[Keys.SEEDANCE_WATERMARK] ?: false,
            backgroundImagePath = p[Keys.SEEDANCE_SCENE_PATH],
            sceneDescription = p[Keys.SEEDANCE_SCENE_DESCRIPTION] ?: "",
        )
    }

    /**
     * 一次原子写回全部 Seedance 配置键（单个 edit 事务，避免逐字段写回被并发覆盖——同
     * [updateCustomCharacters] 的 lost update 教训）。API Key 仅落 DataStore，绝不进入 Room/日志/WorkData。
     */
    suspend fun setSeedanceConfig(config: SeedanceConfig) {
        dataStore.edit { p ->
            p[Keys.SEEDANCE_BASE_URL] = config.baseUrl
            p[Keys.SEEDANCE_API_KEY] = config.apiKey
            p[Keys.SEEDANCE_MODEL_ID] = config.relayModelId
            p[Keys.SEEDANCE_VARIANT] = config.variant.storageKey
            p[Keys.SEEDANCE_RESOLUTION] = config.resolution.storageKey
            p[Keys.SEEDANCE_RATIO] = config.ratio.storageKey
            p[Keys.SEEDANCE_DURATION] = config.durationSeconds
            p[Keys.SEEDANCE_WATERMARK] = config.watermark
            if (config.backgroundImagePath == null) {
                p.remove(Keys.SEEDANCE_SCENE_PATH)
            } else {
                p[Keys.SEEDANCE_SCENE_PATH] = config.backgroundImagePath
            }
            p[Keys.SEEDANCE_SCENE_DESCRIPTION] = config.sceneDescription
        }
    }

    // ===== 朋友圈（仿微信）=====
    val momentImageGenConfig: Flow<MomentImageGenConfig> = dataStore.data.map { p ->
        MomentImageGenConfig(
            baseUrl = p[Keys.MOMENT_IMAGEGEN_BASE_URL] ?: "",
            apiKey = p[Keys.MOMENT_IMAGEGEN_API_KEY] ?: "",
            model = p[Keys.MOMENT_IMAGEGEN_MODEL] ?: "",
        )
    }

    /** 一次原子写回全部生图配置键（单个 edit 事务，防逐字段写回被并发覆盖）。 */
    suspend fun setMomentImageGenConfig(config: MomentImageGenConfig) {
        dataStore.edit { p ->
            p[Keys.MOMENT_IMAGEGEN_BASE_URL] = config.baseUrl.trim()
            p[Keys.MOMENT_IMAGEGEN_API_KEY] = config.apiKey
            p[Keys.MOMENT_IMAGEGEN_MODEL] = config.model.trim()
        }
    }

    val momentCoverPath: Flow<String> = dataStore.data.map { p -> p[Keys.MOMENT_COVER_PATH] ?: "" }

    suspend fun setMomentCoverPath(path: String?) {
        dataStore.edit { p ->
            if (path.isNullOrBlank()) p.remove(Keys.MOMENT_COVER_PATH) else p[Keys.MOMENT_COVER_PATH] = path
        }
    }

    val momentAutoConfig: Flow<MomentAutoConfig> = dataStore.data.map { p ->
        val storedInterval = p[Keys.MOMENT_AUTO_INTERVAL_HOURS] ?: AppConfig.Moment.DEFAULT_INTERVAL_HOURS
        MomentAutoConfig(
            enabled = p[Keys.MOMENT_AUTO_ENABLED] ?: false,
            intervalHours = storedInterval.takeIf { it in AppConfig.Moment.MIN_INTERVAL_HOURS..AppConfig.Moment.MAX_INTERVAL_HOURS }
                ?: AppConfig.Moment.DEFAULT_INTERVAL_HOURS,
            characterIds = p[Keys.MOMENT_AUTO_CHARACTER_IDS] ?: emptySet(),
        )
    }

    /** 一次原子写回自动发圈配置键。 */
    suspend fun setMomentAutoConfig(config: MomentAutoConfig) {
        dataStore.edit { p ->
            p[Keys.MOMENT_AUTO_ENABLED] = config.enabled
            p[Keys.MOMENT_AUTO_INTERVAL_HOURS] = config.intervalHours
            p[Keys.MOMENT_AUTO_CHARACTER_IDS] = config.characterIds
        }
    }

    val momentNextFireAt: Flow<Long> = dataStore.data.map { p -> p[Keys.MOMENT_NEXT_FIRE_AT] ?: 0L }

    suspend fun setMomentNextFireAt(epochMs: Long) {
        dataStore.edit { p ->
            if (epochMs <= 0L) p.remove(Keys.MOMENT_NEXT_FIRE_AT) else p[Keys.MOMENT_NEXT_FIRE_AT] = epochMs
        }
    }

    suspend fun getMomentNextFireAtNow(): Long =
        withTimeoutOrNull(5_000L) { dataStore.data.map { p -> p[Keys.MOMENT_NEXT_FIRE_AT] ?: 0L }.first() } ?: 0L

    val momentLastCharId: Flow<String?> = dataStore.data.map { p -> p[Keys.MOMENT_LAST_CHAR_ID] }

    suspend fun setMomentLastCharId(id: String?) {
        dataStore.edit { p ->
            if (id == null) p.remove(Keys.MOMENT_LAST_CHAR_ID) else p[Keys.MOMENT_LAST_CHAR_ID] = id
        }
    }

    suspend fun getMomentLastCharIdNow(): String? =
        withTimeoutOrNull(5_000L) { dataStore.data.map { p -> p[Keys.MOMENT_LAST_CHAR_ID] }.first() }

    suspend fun getMomentImageGenConfigNow(): MomentImageGenConfig =
        withTimeoutOrNull(5_000L) { momentImageGenConfig.first() } ?: MomentImageGenConfig.EMPTY

    suspend fun getMomentAutoConfigNow(): MomentAutoConfig =
        withTimeoutOrNull(5_000L) { momentAutoConfig.first() } ?: MomentAutoConfig()

    // ===== TTS Config =====
    val ttsConfig: Flow<TtsConfig> = dataStore.data.map { p ->
        TtsConfig(
            apiKey = p[Keys.TTS_API_KEY] ?: "",
            defaultVoiceId = p[Keys.TTS_DEFAULT_VOICE_ID] ?: "",
            appId = p[Keys.TTS_APP_ID] ?: "",
            accessKey = p[Keys.TTS_ACCESS_KEY] ?: "",
        )
    }

    suspend fun setTtsConfig(config: TtsConfig) {
        dataStore.edit { p ->
            p[Keys.TTS_API_KEY] = config.apiKey
            p[Keys.TTS_DEFAULT_VOICE_ID] = config.defaultVoiceId
            // 旧版默认音色仅为兼容读取保留；新版合成绝不使用此值回退。
        }
    }

    val ttsLanguage: Flow<TtsLanguage> = dataStore.data.map { p ->
        TtsLanguage.fromCode(p[Keys.TTS_LANGUAGE] ?: "zh")
    }

    suspend fun setTtsLanguage(lang: TtsLanguage) {
        dataStore.edit { it[Keys.TTS_LANGUAGE] = lang.code }
    }

    /** 朗读引擎（system=手机自带，默认；cloud=云端火山豆包）。 */
    val ttsEngine: Flow<TtsEngine> = dataStore.data.map { p ->
        TtsEngine.fromStorageKey(p[Keys.TTS_ENGINE])
    }

    suspend fun setTtsEngine(engine: TtsEngine) {
        dataStore.edit { it[Keys.TTS_ENGINE] = engine.storageKey }
    }

    /** 系统引擎声音模板。 */
    val ttsSystemTemplate: Flow<SystemVoiceTemplate> = dataStore.data.map { p ->
        SystemVoiceTemplate.fromStorageKey(p[Keys.TTS_SYSTEM_TEMPLATE])
    }

    suspend fun setTtsSystemTemplate(template: SystemVoiceTemplate) {
        dataStore.edit { it[Keys.TTS_SYSTEM_TEMPLATE] = template.storageKey }
    }

    /** 自动朗读角色每次完整回复（默认关闭）。 */
    val ttsAutoRead: Flow<Boolean> = dataStore.data.map { p -> p[Keys.TTS_AUTO_READ] ?: false }

    suspend fun setTtsAutoRead(enabled: Boolean) {
        dataStore.edit { it[Keys.TTS_AUTO_READ] = enabled }
    }

    val ttsVolume: Flow<Int> = dataStore.data.map { p ->
        p[Keys.TTS_VOLUME] ?: AppConfig.TTS_DEFAULT_VOLUME
    }

    suspend fun setTtsVolume(vol: Int) {
        dataStore.edit { it[Keys.TTS_VOLUME] = vol }
    }

    // ===== 角色音色映射 =====
    private val voiceJson = Json { ignoreUnknownKeys = true; isLenient = true }

    @kotlinx.serialization.Serializable
    private data class LegacyVoicePair(val zh: String = "", val ja: String = "")

    private fun decodeVoiceMap(raw: String): Map<String, VoicePair> = runCatching {
        voiceJson.decodeFromString<Map<String, VoicePair>>(raw)
    }.recoverCatching {
        voiceJson.decodeFromString<Map<String, LegacyVoicePair>>(raw).mapValues { (_, value) ->
            VoicePair(
                zh = VoiceConfig(voiceId = value.zh),
                ja = VoiceConfig(voiceId = value.ja),
            )
        }
    }.getOrDefault(emptyMap())

    val ttsVoiceMap: Flow<Map<String, VoicePair>> = dataStore.data.map { p ->
        val raw = p[Keys.TTS_VOICE_MAP] ?: ""
        if (raw.isBlank()) emptyMap() else decodeVoiceMap(raw)
    }

    suspend fun setTtsVoiceMap(map: Map<String, VoicePair>) {
        dataStore.edit { it[Keys.TTS_VOICE_MAP] = voiceJson.encodeToString(map) }
    }

    // ===== 角色 =====
    val activeCharacter: Flow<String> = dataStore.data.map { p ->
        p[Keys.ACTIVE_CHARACTER] ?: Characters.DEFAULT_CHARACTER_ID
    }

    suspend fun setActiveCharacter(id: String) {
        dataStore.edit { it[Keys.ACTIVE_CHARACTER] = id }
    }

    // ===== 会话：每角色当前活跃会话 =====
    // 存 Map<characterId, conversationId>，切换角色时恢复该角色上次活跃的会话。
    val activeConversations: Flow<Map<String, Long>> = dataStore.data.map { p ->
        val raw = p[Keys.ACTIVE_CONVERSATIONS] ?: ""
        if (raw.isBlank()) emptyMap()
        else runCatching { voiceJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun setActiveConversation(characterId: String, conversationId: Long) {
        dataStore.edit { p ->
            val raw = p[Keys.ACTIVE_CONVERSATIONS] ?: ""
            val current: Map<String, Long> = if (raw.isBlank()) emptyMap()
            else runCatching { voiceJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
            p[Keys.ACTIVE_CONVERSATIONS] = voiceJson.encodeToString(current + (characterId to conversationId))
        }
    }

    /** 删除会话后清理指向它的活跃记录（让角色回到「无活跃会话」态，由 ViewModel 重建） */
    suspend fun clearActiveConversation(characterId: String) {
        dataStore.edit { p ->
            val raw = p[Keys.ACTIVE_CONVERSATIONS] ?: ""
            val current: Map<String, Long> = if (raw.isBlank()) emptyMap()
            else runCatching { voiceJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
            p[Keys.ACTIVE_CONVERSATIONS] = voiceJson.encodeToString(current - characterId)
        }
    }

    /** 清空全部活跃会话记录（存储管理「清空聊天记录」后调用，避免指向已删会话）。 */
    suspend fun clearAllActiveConversations() {
        dataStore.edit { it.remove(Keys.ACTIVE_CONVERSATIONS) }
    }

    // ===== 自定义角色 =====
    val customCharacters: Flow<List<Character>> = dataStore.data.map { p ->
        val raw = p[Keys.CUSTOM_CHARACTERS] ?: ""
        if (raw.isBlank()) emptyList()
        else try {
            voiceJson.decodeFromString<List<Character>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun setCustomCharacters(list: List<Character>) {
        dataStore.edit { it[Keys.CUSTOM_CHARACTERS] = voiceJson.encodeToString(list) }
    }

    /**
     * 原子地更新自定义角色：读-改-写整个流程在单个 DataStore edit 事务内完成。
     * 旧的 addCustom/removeCustom/importCustom 是「customCharacters.first() 读 -> 改 -> setCustomCharacters 写」，
     * 两次并发调用会发生 lost update（A、B 都读到 [x]，A 写 [x,y]、B 写 [x,z]，最终丢失 y）。
     * 改为在 edit 闭包内读取当前值并写入新值，由 DataStore 保证原子性。
     */
    suspend fun updateCustomCharacters(transform: (List<Character>) -> List<Character>) {
        dataStore.edit { p ->
            val raw = p[Keys.CUSTOM_CHARACTERS] ?: ""
            val current: List<Character> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<Character>>(raw) }.getOrDefault(emptyList())
            p[Keys.CUSTOM_CHARACTERS] = voiceJson.encodeToString(transform(current))
        }
    }

    // ===== 自定义世界观 =====
    /** 世界观列表（JSON: List<Worldview>，容错空列表）。 */
    val worldviews: Flow<List<Worldview>> = dataStore.data.map { p ->
        val raw = p[Keys.WORLDVIEWS] ?: ""
        if (raw.isBlank()) emptyList()
        else try {
            voiceJson.decodeFromString<List<Worldview>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun setWorldviews(list: List<Worldview>) {
        dataStore.edit { it[Keys.WORLDVIEWS] = voiceJson.encodeToString(list) }
    }

    /**
     * 原子地更新世界观：读-改-写在单个 DataStore edit 事务内完成（仿 [updateCustomCharacters]）。
     * 一一对应约束由调用方在 transform 内保证（同目标 upsert）。
     */
    suspend fun updateWorldviews(transform: (List<Worldview>) -> List<Worldview>) {
        dataStore.edit { p ->
            val raw = p[Keys.WORLDVIEWS] ?: ""
            val current: List<Worldview> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<Worldview>>(raw) }.getOrDefault(emptyList())
            p[Keys.WORLDVIEWS] = voiceJson.encodeToString(transform(current))
        }
    }

    // ===== 世界书 =====
    /** 世界书列表（JSON: List<Lorebook>，容错空列表）。 */
    val lorebooks: Flow<List<Lorebook>> = dataStore.data.map { p ->
        val raw = p[Keys.LOREBOOKS] ?: ""
        if (raw.isBlank()) emptyList()
        else try {
            voiceJson.decodeFromString<List<Lorebook>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun setLorebooks(list: List<Lorebook>) {
        dataStore.edit { it[Keys.LOREBOOKS] = voiceJson.encodeToString(list) }
    }

    /**
     * 原子地更新世界书：读-改-写在单个 DataStore edit 事务内完成（仿 [updateWorldviews]）。
     * 多本书可绑同一目标、同 id upsert 语义由调用方在 transform 内保证。
     */
    suspend fun updateLorebooks(transform: (List<Lorebook>) -> List<Lorebook>) {
        dataStore.edit { p ->
            val raw = p[Keys.LOREBOOKS] ?: ""
            val current: List<Lorebook> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<Lorebook>>(raw) }.getOrDefault(emptyList())
            p[Keys.LOREBOOKS] = voiceJson.encodeToString(transform(current))
        }
    }

    /** 世界书全局参数快照（总开关/扫描深度/递归/token 预算；解析失败回落默认）。 */
    val lorebookConfig: Flow<LorebookGlobalConfig> = dataStore.data.map { p ->
        val raw = p[Keys.LOREBOOK_CONFIG] ?: ""
        if (raw.isBlank()) LorebookGlobalConfig()
        else runCatching { voiceJson.decodeFromString<LorebookGlobalConfig>(raw) }.getOrDefault(LorebookGlobalConfig())
    }

    /**
     * 原子更新世界书全局参数：读-改-写在单个 DataStore edit 事务内完成（仿 [updateLorebooks]）。
     */
    suspend fun updateLorebookConfig(transform: (LorebookGlobalConfig) -> LorebookGlobalConfig) {
        dataStore.edit { p ->
            val raw = p[Keys.LOREBOOK_CONFIG] ?: ""
            val current: LorebookGlobalConfig = if (raw.isBlank()) LorebookGlobalConfig()
            else runCatching { voiceJson.decodeFromString<LorebookGlobalConfig>(raw) }.getOrDefault(LorebookGlobalConfig())
            p[Keys.LOREBOOK_CONFIG] = voiceJson.encodeToString(transform(current))
        }
    }

    // ===== 音量 =====
    val volume: Flow<Int> = dataStore.data.map { p ->
        p[Keys.VOLUME] ?: 60
    }

    suspend fun setVolume(vol: Int) {
        dataStore.edit { it[Keys.VOLUME] = vol }
    }

    // ===== 音乐 =====
    val musicFavorites: Flow<Set<String>> = dataStore.data.map { p ->
        p[Keys.MUSIC_FAVORITES] ?: emptySet()
    }

    suspend fun toggleMusicFavorite(key: String): Boolean {
        var added = false
        dataStore.edit { p ->
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

    val musicRepeatMode: Flow<Int> = dataStore.data.map { p ->
        p[Keys.MUSIC_REPEAT_MODE] ?: 0
    }

    suspend fun setMusicRepeatMode(mode: Int) {
        dataStore.edit { it[Keys.MUSIC_REPEAT_MODE] = mode }
    }

    /** 播放列表（本地导入 + 用户添加的在线曲），JSON List<BgmTrack>。 */
    val musicPlaylist: Flow<List<BgmTrack>> = dataStore.data.map { p ->
        val raw = p[Keys.MUSIC_PLAYLIST] ?: ""
        if (raw.isBlank()) emptyList()
        else runCatching { voiceJson.decodeFromString<List<BgmTrack>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setMusicPlaylist(list: List<BgmTrack>) {
        dataStore.edit { it[Keys.MUSIC_PLAYLIST] = voiceJson.encodeToString(list) }
    }

    /**
     * 原子更新播放列表（读-改-写在单个 DataStore edit 事务内，避免并发 lost update）。
     * 上限由 [com.rhodesisland.terminal.data.repository.MusicLibraryRepository] 在导入处强制。
     */
    suspend fun updateMusicPlaylist(transform: (List<BgmTrack>) -> List<BgmTrack>) {
        dataStore.edit { p ->
            val raw = p[Keys.MUSIC_PLAYLIST] ?: ""
            val current: List<BgmTrack> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<BgmTrack>>(raw) }.getOrDefault(emptyList())
            p[Keys.MUSIC_PLAYLIST] = voiceJson.encodeToString(transform(current))
        }
    }

    /** 随机播放开关。 */
    val musicShuffle: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.MUSIC_SHUFFLE] ?: false
    }

    suspend fun setMusicShuffle(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_SHUFFLE] = enabled }
    }

    // ===== 本地 AI =====
    val activeProvider: Flow<ChatProviderType> = dataStore.data.map { p ->
        val v = p[Keys.ACTIVE_PROVIDER] ?: "CLOUD"
        if (v == "LOCAL") ChatProviderType.LOCAL else ChatProviderType.CLOUD
    }

    suspend fun setActiveProvider(type: ChatProviderType) {
        dataStore.edit { it[Keys.ACTIVE_PROVIDER] = type.name }
    }

    val activeLocalModelId: Flow<String?> = dataStore.data.map { p ->
        p[Keys.ACTIVE_LOCAL_MODEL]
    }

    suspend fun setActiveLocalModelId(id: String?) {
        dataStore.edit { p ->
            if (id == null) p.remove(Keys.ACTIVE_LOCAL_MODEL)
            else p[Keys.ACTIVE_LOCAL_MODEL] = id
        }
    }

    val llmContextLen: Flow<Int> = dataStore.data.map { p ->
        p[Keys.LLM_CONTEXT_LEN] ?: AppConfig.LLM.DEFAULT_CONTEXT_LEN
    }

    val llmThreads: Flow<Int> = dataStore.data.map { p ->
        p[Keys.LLM_THREADS] ?: AppConfig.LLM.DEFAULT_THREADS
    }

    val llmTemperature: Flow<Float> = dataStore.data.map { p ->
        p[Keys.LLM_TEMPERATURE] ?: AppConfig.LLM.DEFAULT_TEMPERATURE
    }

    /**
     * 缺少偏好键时才使用 2048 新默认；这里只读回退、不写回 DataStore，已有 4096/65536 等
     * 用户选择原样保留（Task 5 absent-preference-only 约束）。
     */
    val llmMaxTokens: Flow<Int> = dataStore.data.map { p ->
        p[Keys.LLM_MAX_TOKENS] ?: AppConfig.LLM.DEFAULT_MAX_TOKENS
    }

    // ===== 滚动摘要压缩节奏（单聊云端）=====

    /** 折叠批量（每 N 条原文压一次摘要）；读出时钳制到配置区间，脏数据不外泄。 */
    val rollingSummaryFoldBatch: Flow<Int> = dataStore.data.map { p ->
        (p[Keys.ROLLING_SUMMARY_FOLD_BATCH] ?: AppConfig.RollingSummary.DEFAULT_FOLD_BATCH)
            .coerceIn(AppConfig.RollingSummary.MIN_FOLD_BATCH, AppConfig.RollingSummary.MAX_FOLD_BATCH)
    }

    suspend fun setRollingSummaryFoldBatch(batch: Int) {
        dataStore.edit {
            it[Keys.ROLLING_SUMMARY_FOLD_BATCH] =
                batch.coerceIn(AppConfig.RollingSummary.MIN_FOLD_BATCH, AppConfig.RollingSummary.MAX_FOLD_BATCH)
        }
    }

    // ===== 云端生成参数（null=未自定义 → 请求体不发该字段，走模型商默认）=====

    val cloudTemperature: Flow<Float?> = dataStore.data.map { p ->
        p[Keys.CLOUD_TEMPERATURE]
    }

    suspend fun setCloudTemperature(value: Float?) {
        dataStore.edit {
            if (value == null) it.remove(Keys.CLOUD_TEMPERATURE) else it[Keys.CLOUD_TEMPERATURE] = value
        }
    }

    val cloudMaxTokens: Flow<Int?> = dataStore.data.map { p ->
        p[Keys.CLOUD_MAX_TOKENS]
    }

    suspend fun setCloudMaxTokens(value: Int?) {
        dataStore.edit {
            if (value == null) it.remove(Keys.CLOUD_MAX_TOKENS) else it[Keys.CLOUD_MAX_TOKENS] = value
        }
    }

    /** 推理后端偏好（默认 AUTO）*/
    val llmBackend: Flow<BackendPreference> = dataStore.data.map { p ->
        BackendPreference.fromKey(p[Keys.LLM_BACKEND])
    }

    suspend fun setLlmBackend(preference: BackendPreference) {
        dataStore.edit { it[Keys.LLM_BACKEND] = preference.storageKey }
    }

    /** 推理性能模式（默认 BALANCED）。Task 6 引入；具体模式行为由后续 resolved-plans 任务解析。 */
    val llmPerformanceMode: Flow<InferencePerformanceMode> = dataStore.data.map { p ->
        InferencePerformanceMode.fromStorageKey(p[Keys.LLM_PERFORMANCE_MODE])
    }

    suspend fun setLlmPerformanceMode(mode: InferencePerformanceMode) {
        dataStore.edit { it[Keys.LLM_PERFORMANCE_MODE] = mode.storageKey }
    }

    /**
     * 一次聚合本地推理设置的不可变快照（Task 6）。
     *
     * 单个 `data.map` 读取全部相关键，替换 provider 侧逐字段 `.first()` 的多次读取；
     * 缺失键一律回退默认值（只读、不写回）。
     */
    val localInferenceSettings: Flow<LocalInferenceSettings> = dataStore.data.map {
        LocalInferenceSettings.fromPreferences(it)
    }

    /** CPU 推理提频开关（legacy，默认开；Task 6 起不再权威，详见 [LocalInferenceSettings]）。 */
    val llmCpuBoost: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.LLM_CPU_BOOST] ?: true
    }

    suspend fun setLlmCpuBoost(enabled: Boolean) {
        dataStore.edit { it[Keys.LLM_CPU_BOOST] = enabled }
    }

    /** CPU lookahead 投机解码开关（legacy，默认关）。n-gram 投机解码无需 draft 模型，仅 MNN CPU 后端生效，改值触发下次重载。
     *  默认关：首轮无 n-gram 历史时 draft 全 miss，每步多跑 draft_predict_length 个前向却只产 1 token，
     *  在慢模型上反而数倍拖慢首条回复；多轮重复/代码类文本再开可获 1.5–3× 提速。 */
    val llmLookahead: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.LLM_LOOKAHEAD] ?: false
    }

    suspend fun setLlmLookahead(enabled: Boolean) {
        dataStore.edit { it[Keys.LLM_LOOKAHEAD] = enabled }
    }

    // ===== 深度思考模式（本地 + 云端通用）=====
    /** 深度思考开关（默认关）。开启时展示推理过程并（对支持的供应商）请求思考。 */
    val deepThinking: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.DEEP_THINKING] ?: false
    }

    suspend fun setDeepThinking(enabled: Boolean) {
        dataStore.edit { it[Keys.DEEP_THINKING] = enabled }
    }

    // ===== 本地思考档位（仅本地生效，默认 AUTO）=====
    /** 本地思考档位（默认 AUTO）。只影响本地生成策略，云端不读取。 */
    val localThinkingLevel: Flow<LocalThinkingLevel> = dataStore.data.map { p ->
        LocalThinkingLevel.fromStorageKey(p[Keys.THINKING_LEVEL])
    }

    suspend fun setLocalThinkingLevel(level: LocalThinkingLevel) {
        dataStore.edit { it[Keys.THINKING_LEVEL] = level.storageKey }
    }

    // ===== 性能浮窗液态玻璃 =====
    /** 性能浮窗液态玻璃开关（默认开）。开启：背景模糊 + 镜面高光 + 旋转虹彩光晕；关闭：普通深色面板。背景模糊需 Android 12+。 */
    val liquidGlass: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.LIQUID_GLASS] ?: true
    }

    // ===== 使用指南 =====
    /** 是否已完成首次阅读水平选择（一旦 true 不再重置）。 */
    val guideSetupDone: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.GUIDE_SETUP_DONE] ?: false
    }

    suspend fun setGuideSetupDone(done: Boolean) {
        dataStore.edit { it[Keys.GUIDE_SETUP_DONE] = done }
    }

    /** 阅读水平原始串："BEGINNER" / "EXPERIENCED"；未选择时为空串（UI 侧映射枚举并兜底 BEGINNER）。 */
    val guideLevel: Flow<String> = dataStore.data.map { p ->
        p[Keys.GUIDE_LEVEL] ?: ""
    }

    suspend fun setGuideLevel(level: String) {
        dataStore.edit { it[Keys.GUIDE_LEVEL] = level }
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        dataStore.edit { it[Keys.LIQUID_GLASS] = enabled }
    }

    // ===== 聊天顶栏第二行控件折叠 =====
    /** 聊天顶栏第二行（云端/本地 + 快捷开关）是否展开（默认开）。 */
    val chatTopBarExpanded: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.CHAT_TOPBAR_CONTROLS_EXPANDED] ?: true
    }

    suspend fun setChatTopBarExpanded(expanded: Boolean) {
        dataStore.edit { it[Keys.CHAT_TOPBAR_CONTROLS_EXPANDED] = expanded }
    }

    // ===== 通讯界面自定义背景 =====
    /** 自定义背景开关（默认关）。开启且路径列表非空 -> 轮播自定义图片；否则回退内置 PRTS 背景轮播。 */
    val chatBgEnabled: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.CHAT_BG_ENABLED] ?: false
    }

    suspend fun setChatBgEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CHAT_BG_ENABLED] = enabled }
    }

    /** 自定义背景图片路径列表（有序）。 */
    val chatBgPaths: Flow<List<String>> = dataStore.data.map { p ->
        val raw = p[Keys.CHAT_BG_PATHS] ?: ""
        if (raw.isBlank()) emptyList()
        else runCatching { voiceJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setChatBgPaths(paths: List<String>) {
        dataStore.edit { it[Keys.CHAT_BG_PATHS] = voiceJson.encodeToString(paths) }
    }

    /**
     * 原子更新自定义背景路径列表（读-改-写在单个 DataStore edit 事务内），避免并发 lost update。
     * 20 张上限由 [com.rhodesisland.terminal.data.repository.ChatBackgroundRepository] 在 addUris 处强制。
     */
    suspend fun updateChatBgPaths(transform: (List<String>) -> List<String>) {
        dataStore.edit { p ->
            val raw = p[Keys.CHAT_BG_PATHS] ?: ""
            val current: List<String> = if (raw.isBlank()) emptyList()
            else runCatching { voiceJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            val next = transform(current)
            p[Keys.CHAT_BG_PATHS] = voiceJson.encodeToString(next)
        }
    }

    // ===== 角色问候（角色主动消息）=====
    /** 角色问候开关（默认关）。开启后所选角色在白天随机时间主动发消息，仅云端 AI 可用。 */
    val greetingEnabled: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.GREETING_ENABLED] ?: false
    }

    suspend fun setGreetingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.GREETING_ENABLED] = enabled }
    }

    /** 主动发消息的角色 id 集合（可多选，默认空）。 */
    val greetingCharacterIds: Flow<Set<String>> = dataStore.data.map { p ->
        p[Keys.GREETING_CHARACTER_IDS] ?: emptySet()
    }

    suspend fun setGreetingCharacterIds(ids: Set<String>) {
        dataStore.edit { it[Keys.GREETING_CHARACTER_IDS] = ids }
    }

    /** 每天主动消息条数（默认 [AppConfig.Greeting.DEFAULT_DAILY_COUNT]）。 */
    val greetingDailyCount: Flow<Int> = dataStore.data.map { p ->
        p[Keys.GREETING_DAILY_COUNT] ?: AppConfig.Greeting.DEFAULT_DAILY_COUNT
    }

    suspend fun setGreetingDailyCount(count: Int) {
        dataStore.edit { it[Keys.GREETING_DAILY_COUNT] = count }
    }

    /** 当日配额：日期(yyyy-MM-dd) -> 已发条数。 */
    val greetingQuota: Flow<Pair<String, Int>> = dataStore.data.map { p ->
        (p[Keys.GREETING_QUOTA_DATE] ?: "") to (p[Keys.GREETING_QUOTA_COUNT] ?: 0)
    }

    /** 写回当日配额（Worker 每发一条自增、跨天重置时调用）。 */
    suspend fun setGreetingQuota(date: String, count: Int) {
        dataStore.edit {
            it[Keys.GREETING_QUOTA_DATE] = date
            it[Keys.GREETING_QUOTA_COUNT] = count
        }
    }

    /** 上次发问候的角色 id（null = 从未发过）。 */
    val greetingLastCharId: Flow<String?> = dataStore.data.map { p ->
        p[Keys.GREETING_LAST_CHAR_ID]
    }

    suspend fun setGreetingLastCharId(id: String?) {
        dataStore.edit {
            if (id == null) it.remove(Keys.GREETING_LAST_CHAR_ID)
            else it[Keys.GREETING_LAST_CHAR_ID] = id
        }
    }

    /** 下一次问候投递目标时间（epoch ms；0 = 尚未初始化）。 */
    val greetingNextFireAt: Flow<Long> = dataStore.data.map { p ->
        p[Keys.GREETING_NEXT_FIRE_AT] ?: 0L
    }

    suspend fun setGreetingNextFireAt(epochMs: Long) {
        dataStore.edit {
            if (epochMs <= 0L) it.remove(Keys.GREETING_NEXT_FIRE_AT)
            else it[Keys.GREETING_NEXT_FIRE_AT] = epochMs
        }
    }

    // ===== 群聊（仅云端可用）=====
    /**
     * 群聊配置聚合快照：单个 data.map 读取开关/成员/自动聊天三键。
     * 单独原子写回走 [setGroupChatConfig]；picker 点选（只改成员不覆盖开关）走 [updateGroupChatConfig]（同
     * [updateCustomCharacters] 的 single-edit lost-update 教训）。
     */
    val groupChatConfig: Flow<GroupChatConfig> = dataStore.data.map { p ->
        GroupChatConfig(
            enabled = p[Keys.GROUP_CHAT_ENABLED] ?: false,
            memberIds = p[Keys.GROUP_MEMBER_IDS] ?: emptySet(),
            autoChat = p[Keys.GROUP_AUTO_CHAT_ENABLED] ?: true,
        )
    }

    /** 一次原子写回群聊配置三键。 */
    suspend fun setGroupChatConfig(config: GroupChatConfig) {
        dataStore.edit { p ->
            p[Keys.GROUP_CHAT_ENABLED] = config.enabled
            p[Keys.GROUP_MEMBER_IDS] = config.memberIds
            p[Keys.GROUP_AUTO_CHAT_ENABLED] = config.autoChat
        }
    }

    /** 原子读-改-写群聊配置（成员 picker 点选用；不覆盖开关）。 */
    suspend fun updateGroupChatConfig(transform: (GroupChatConfig) -> GroupChatConfig) {
        dataStore.edit { p ->
            val current = GroupChatConfig(
                enabled = p[Keys.GROUP_CHAT_ENABLED] ?: false,
                memberIds = p[Keys.GROUP_MEMBER_IDS] ?: emptySet(),
                autoChat = p[Keys.GROUP_AUTO_CHAT_ENABLED] ?: true,
            )
            val next = transform(current)
            p[Keys.GROUP_CHAT_ENABLED] = next.enabled
            p[Keys.GROUP_MEMBER_IDS] = next.memberIds
            p[Keys.GROUP_AUTO_CHAT_ENABLED] = next.autoChat
        }
    }

    /** 每日自动聊天轮次上限（默认 [AppConfig.GroupChat.DEFAULT_DAILY_ROUNDS]）。 */
    val groupDailyRounds: Flow<Int> = dataStore.data.map { p ->
        p[Keys.GROUP_DAILY_ROUNDS] ?: AppConfig.GroupChat.DEFAULT_DAILY_ROUNDS
    }

    suspend fun setGroupDailyRounds(count: Int) {
        dataStore.edit { it[Keys.GROUP_DAILY_ROUNDS] = count }
    }

    /** 当日轮次配额：(日期 yyyy-MM-dd, 已执行轮次)。 */
    val groupQuota: Flow<Pair<String, Int>> = dataStore.data.map { p ->
        (p[Keys.GROUP_QUOTA_DATE] ?: "") to (p[Keys.GROUP_QUOTA_COUNT] ?: 0)
    }

    suspend fun setGroupQuota(date: String, count: Int) {
        dataStore.edit {
            it[Keys.GROUP_QUOTA_DATE] = date
            it[Keys.GROUP_QUOTA_COUNT] = count
        }
    }

    /** 上次发言的成员 id（null = 从未）。 */
    val groupLastSpeakerId: Flow<String?> = dataStore.data.map { p ->
        p[Keys.GROUP_LAST_SPEAKER_ID]
    }

    suspend fun setGroupLastSpeakerId(id: String?) {
        dataStore.edit {
            if (id == null) it.remove(Keys.GROUP_LAST_SPEAKER_ID)
            else it[Keys.GROUP_LAST_SPEAKER_ID] = id
        }
    }

    /** 已执行轮次累计计数（决定 discuss / ask-user 轮换）。 */
    val groupRoundCounter: Flow<Long> = dataStore.data.map { p ->
        p[Keys.GROUP_ROUND_COUNTER] ?: 0L
    }

    suspend fun setGroupRoundCounter(counter: Long) {
        dataStore.edit { it[Keys.GROUP_ROUND_COUNTER] = counter }
    }

    /** 用户最近一次在群聊发消息的时间（epoch ms；0 = 从未）。 */
    val groupLastUserMessageAt: Flow<Long> = dataStore.data.map { p ->
        p[Keys.GROUP_LAST_USER_AT] ?: 0L
    }

    suspend fun setGroupLastUserMessageAt(epochMs: Long) {
        dataStore.edit {
            if (epochMs <= 0L) it.remove(Keys.GROUP_LAST_USER_AT)
            else it[Keys.GROUP_LAST_USER_AT] = epochMs
        }
    }

    /** 下一次自动聊天触发目标时间（epoch ms；0 = 尚未初始化）。 */
    val groupNextFireAt: Flow<Long> = dataStore.data.map { p ->
        p[Keys.GROUP_NEXT_FIRE_AT] ?: 0L
    }

    suspend fun setGroupNextFireAt(epochMs: Long) {
        dataStore.edit {
            if (epochMs <= 0L) it.remove(Keys.GROUP_NEXT_FIRE_AT)
            else it[Keys.GROUP_NEXT_FIRE_AT] = epochMs
        }
    }

    // ===== 博士档案（我的形象）=====
    /** 博士档案聚合（头像路径/人设/关系）：单 map 读取，单次原子写回。 */
    val userProfile: Flow<UserProfileConfig> = dataStore.data.map { p ->
        UserProfileConfig(
            avatarPath = p[Keys.USER_AVATAR_PATH] ?: "",
            persona = p[Keys.USER_PERSONA] ?: "",
            relationship = p[Keys.USER_RELATIONSHIP] ?: "",
        )
    }

    suspend fun setUserProfileConfig(config: UserProfileConfig) {
        dataStore.edit { p ->
            if (config.avatarPath.isBlank()) p.remove(Keys.USER_AVATAR_PATH)
            else p[Keys.USER_AVATAR_PATH] = config.avatarPath
            p[Keys.USER_PERSONA] = config.persona
            p[Keys.USER_RELATIONSHIP] = config.relationship
        }
    }

    // ===== 配置变更检测（移植自 iFeng SettingsManager.hasConfigChanged / acknowledgeConfigChange）=====

    /** 上次成功加载模型时生效的线程数（冷启动默认与当前默认值对齐，避免误报变更）*/
    val lastAppliedThreads: Flow<Int> = dataStore.data.map { p ->
        p[Keys.LLM_LAST_THREADS] ?: AppConfig.LLM.DEFAULT_THREADS
    }

    /** 上次成功加载模型时生效的上下文长度 */
    val lastAppliedContextLen: Flow<Int> = dataStore.data.map { p ->
        p[Keys.LLM_LAST_CONTEXT_LEN] ?: AppConfig.LLM.DEFAULT_CONTEXT_LEN
    }

    /** 上次成功加载模型时生效的后端偏好 */
    val lastAppliedBackend: Flow<BackendPreference> = dataStore.data.map { p ->
        BackendPreference.fromKey(p[Keys.LLM_LAST_BACKEND])
    }

    /** 上次成功加载模型时生效的 lookahead 开关（默认关，与 [llmLookahead] 对齐，避免新装误报变更）*/
    val lastAppliedLookahead: Flow<Boolean> = dataStore.data.map { p ->
        p[Keys.LLM_LAST_LOOKAHEAD] ?: false
    }

    /** 上次成功加载模型时生效的采样温度（MNN 采样器在 load 时构建，温度改值须重载）*/
    val lastAppliedTemperature: Flow<Float> = dataStore.data.map { p ->
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
        dataStore.edit { p ->
            p[Keys.LLM_LAST_THREADS] = threads
            p[Keys.LLM_LAST_CONTEXT_LEN] = contextLen
            p[Keys.LLM_LAST_BACKEND] = backend.storageKey
            p[Keys.LLM_LAST_LOOKAHEAD] = lookahead
            p[Keys.LLM_LAST_TEMPERATURE] = temperature
        }
    }

    /** 最近一次成功加载实际应用的 plan 配置哈希（Task 7）；供诊断/后续健康记录。 */
    val llmLastConfigHash: Flow<String?> = dataStore.data.map { p ->
        p[Keys.LLM_LAST_CONFIG_HASH]
    }

    suspend fun setLlmLastConfigHash(hash: String?) {
        dataStore.edit { p ->
            if (hash != null) p[Keys.LLM_LAST_CONFIG_HASH] = hash else p.remove(Keys.LLM_LAST_CONFIG_HASH)
        }
    }

    suspend fun setLlmParams(
        contextLen: Int? = null,
        threads: Int? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
    ) {
        dataStore.edit { p ->
            contextLen?.let { p[Keys.LLM_CONTEXT_LEN] = it }
            threads?.let { p[Keys.LLM_THREADS] = it }
            temperature?.let { p[Keys.LLM_TEMPERATURE] = it }
            maxTokens?.let { p[Keys.LLM_MAX_TOKENS] = it }
        }
    }
}
