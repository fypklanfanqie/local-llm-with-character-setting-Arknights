package com.rhodesisland.terminal.config

/**
 * 应用全局配置
 */
object AppConfig {
    // ===== TTS =====
    /** 火山引擎语音合成 HTTP V3 Chunked 官方直连地址。 */
    const val TTS_DIRECT_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    /** 声音复刻 2.0 固定资源，用户只需填写训练成功的 speaker_id。 */
    const val TTS_VOICE_CLONE_RESOURCE_ID = "seed-icl-2.0"

    // ===== 云端 AI 默认配置 =====
    const val DEFAULT_API_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"

    // ===== 资源 CDN（立绘/语音/BGM 公网地址）=====
    // 由于 Android 无微信云存储，需将 assets 上传至公网 CDN
    // 留空则使用本地 assets 回退
    const val ASSET_CDN_BASE = ""

    // ===== TTS =====
    const val TTS_DEFAULT_VOLUME = 85

    // ===== 本地 LLM 默认参数 =====
    object LLM {
        // 4096（非 2048）：现代小模型（Qwen2.5 / Gemma2 / Llama3.2 / SmolLM2 等）均支持 ≥8192，
        // 4096 通用安全且让长对话少丢上下文。设置页可调到 32768（受模型实际支持限制）。
        const val DEFAULT_CONTEXT_LEN = 4096
        const val DEFAULT_THREADS = 4
        // 0.8（非 0.9）：小模型角色扮演在高温下易「上头」，从单角色回复滑向编造多角色剧本并无限生成
        // （配合 LocalChatProvider 的 system prompt 输出规范 + onToken 剧本标记截断兜底）。0.8 保留角色
        // 语气多样性的同时显著降低跑偏概率。
        const val DEFAULT_TEMPERATURE = 0.8f
        // 缺省输出上限 2048：只影响 DataStore 中尚无 llm_max_tokens 键的新安装/未设置用户。
        // 已存储值不迁移、不覆盖；65536 仍保留为设置页显式高级选项「不限」（native 硬循环边界）。
        const val MAX_TOKENS_UNLIMITED = 65536
        const val DEFAULT_MAX_TOKENS = 2048
        const val DEFAULT_TOP_P = 0.9f
        // 1.2（非 1.1）：小模型无重复惩罚时会逐字复读角色卡循环；mixed_samplers 现已含 "penalty"
        // 生效（见 mnn_jni.cpp set_config）。1.1 偏弱压不住结构性复读，1.2 在 max_penalty=10 内安全。
        const val DEFAULT_REPEAT_PENALTY = 1.2f
    }

    // ===== 聊天历史 =====
    // 每个会话（conversation）最多保留的消息条数；超出按时间修剪最旧消息。
    const val MAX_HISTORY_PER_CONVERSATION = 100
    // 单次请求交给 provider 规划的历史候选上限。云端发送最近 N 条；本地由 PromptWindowPlanner
    // 在候选中保留 system + 最近完整 user/assistant 轮次，预留输出/模板空间，不再依赖模型静默左截断。
    // 云端历史经 PromptWindowAnchor.anchoredWindow 按块锚定截断（step=20），避免逐条滑动破坏前缀缓存。
    const val MAX_CONTEXT_MESSAGES = 100

    // ===== 角色问候（角色主动消息）=====
    // 开启后，所选角色会在白天随机时间主动给用户发消息（早安/晚安/关心/开话题）。
    // 仅云端 AI 模式可用：消息由 DirectLlmClient.chatOnce 生成，符合角色人设。
    object Greeting {
        // 用户可设置的每天主动消息条数范围与默认值
        const val DEFAULT_DAILY_COUNT = 3
        const val MIN_DAILY_COUNT = 1
        const val MAX_DAILY_COUNT = 10
        // 仅在此时段内触发（避免深夜打扰）：08:00–23:00
        const val HOUR_START = 8
        const val HOUR_END = 23
        // 生成主动消息时带入的最近历史条数（让消息能衔接正在聊的话题）
        const val CONTEXT_MESSAGES = 6
        // PeriodicWork 周期（分钟）。WorkManager 最短允许 15 分钟。
        // 用周期性 Worker 取代脆弱的自延续链：错失一次下个周期仍会触发，链条不会因进程被杀而永久断裂。
        const val HEARTBEAT_INTERVAL_MIN = 15L
        // 单次生成超时（秒）
        const val GENERATE_TIMEOUT_MS = 60_000L
        // 云 API 失败后重排的间隔（毫秒），避免 WorkManager retry 风暴
        const val RETRY_DELAY_MS = 45 * 60 * 1000L
    }

    // ===== 群聊（多人角色同群聊天，仅云端可用）=====
    // 勾选具体角色组群；用户可参与对话；空闲时角色自动互聊并主动提问（走通知）。
    // 后台驱动复用「角色问候」的 PeriodicWork + next_fire_at + 精确闹钟 + 开机重挂套件。
    object GroupChat {
        // 群成员上限（二轮：4 -> 10；10 人 × 300 字人设 ≈ 3K 字符 system，云端可承受）
        const val MAX_MEMBERS = 10
        // 用户回合单轮最多答复的成员数（随机 1..此值；@ 指定的成员必定回复并占用名额）
        const val MAX_REPLIES_PER_USER_MESSAGE = 4
        // 每位成员人设写入 system prompt 时的截断长度（字符）
        const val PERSONA_MAX_CHARS = 300
        // 群聊生成时带回的最近历史条数
        const val MAX_CONTEXT_MESSAGES = 40
        // 自动聊天的触发时段（避免深夜打扰）：09:00–22:00
        const val HOUR_START = 9
        const val HOUR_END = 22
        // 每日自动聊天「轮次」默认值（每一轮 = 2 条成员互聊，或 1 条主动提问）
        const val DEFAULT_DAILY_ROUNDS = 8
        const val MIN_DAILY_ROUNDS = 1
        const val MAX_DAILY_ROUNDS = 20
        // 每 N 轮中第 3 轮改为「向用户提问」轮（其余为成员互聊轮）
        const val ASK_USER_EVERY_N_ROUNDS = 3
        // 每个互聊轮由几名成员依次回应（= 该轮云端调用次数）
        const val DISCUSS_REPLIES_PER_ROUND = 2
        // 用户刚发过消息后的冷却时间（毫秒）：冷却期内不触发自动聊天，避免打断用户交互
        const val AFTER_USER_COOLDOWN_MS = 60 * 60 * 1000L
        // PeriodicWork 周期（分钟），与问候一致
        const val HEARTBEAT_INTERVAL_MIN = 15L
        // 单次生成超时（秒）
        const val GENERATE_TIMEOUT_MS = 60_000L
        // 云 API 失败后重排的间隔（毫秒）
        const val RETRY_DELAY_MS = 45 * 60 * 1000L
    }

    // ===== 世界书（SillyTavern 风格 Lorebook）=====
    object Lorebook {
        /** 绿灯触发条目折入请求尾部时的参考块标题（GroupChatPromptBuilder 尾块 / 本地 user 并入共用）。 */
        const val REFERENCE_HEADER = "[设定参考]"
    }
}
