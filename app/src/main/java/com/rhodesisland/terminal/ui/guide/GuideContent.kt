package com.rhodesisland.terminal.ui.guide

/**
 * 使用指南内容层：分级枚举 / 结构化内容块 / 分类与话题数据 / 模糊搜索。
 *
 * 设计要点：
 * - 纯 Kotlin 数据（零 Compose 依赖），UI 渲染在 ui/settings/GuideDialog.kt；
 * - 每个话题 [GuideTopic] 携带 beginner / experienced 两套 [GuideBlock]，按用户水平二选一渲染；
 * - 搜索采用「别名关键词」方案（无拼音库）：query 对 标题 > 别名 > 分类名 做 contains 分层打分。
 * - 内容为孪生项目（本地 AI 聊天）同源移植版：全部使用通用措辞，不含任何特定 IP 世界观元素。
 */

/** 阅读水平：storageKey 与 DataStore guide_level 键对接；chipLabel 供话题页切换 chip 显示。 */
enum class GuideLevel(val storageKey: String, val chipLabel: String) {
    BEGINNER("BEGINNER", "🧭 小白版"),
    EXPERIENCED("EXPERIENCED", "🚀 熟练版");

    companion object {
        /** DataStore 原始串 -> 枚举；未知/未选返回 null（UI 侧兜底 BEGINNER）。 */
        fun fromStorageKey(key: String?): GuideLevel? =
            values().firstOrNull { it.storageKey == key }
    }
}

enum class GuideBlockType { PARAGRAPH, STEP_TITLE, STEP_TEXT, TIP, WARN }

/**
 * 内容块。[title] 仅 TIP 使用（TipRow 的粗体前缀）；其余类型只读 [text]。
 */
data class GuideBlock(
    val type: GuideBlockType,
    val title: String? = null,
    val text: String,
)

data class GuideTopic(
    val id: String,
    val categoryId: String,
    val emoji: String,
    val title: String,
    /** 搜索别名（含英文小写变体）；标题本身天然参与匹配，不必重复写入。 */
    val aliases: List<String> = emptyList(),
    val beginner: List<GuideBlock>,
    val experienced: List<GuideBlock>,
)

data class GuideCategory(
    val id: String,
    val emoji: String,
    val title: String,
    val subtitle: String,
)

// ===== 构造简写 =====
private fun para(text: String) = GuideBlock(GuideBlockType.PARAGRAPH, text = text)
private fun stepTitle(text: String) = GuideBlock(GuideBlockType.STEP_TITLE, text = text)
private fun stepText(text: String) = GuideBlock(GuideBlockType.STEP_TEXT, text = text)
private fun tip(title: String, text: String) = GuideBlock(GuideBlockType.TIP, title = title, text = text)
private fun warn(text: String) = GuideBlock(GuideBlockType.WARN, text = text)

// =====================================================================
// 分类（首页网格，顺序即展示顺序）
// =====================================================================

val GUIDE_CATEGORIES: List<GuideCategory> = listOf(
    GuideCategory("quick_start", "🚀", "快速上手", "简介 · 双引擎 · 第一次对话 · 签到"),
    GuideCategory("cloud_api", "☁️", "云端 AI 配置", "API Key · 免费对话 · 各家服务商"),
    GuideCategory("local_llm", "📱", "本地大模型", "下载 · 切换 · 内存占用"),
    GuideCategory("engine_tuning", "⚙️", "引擎与性能调优", "后端 · 参数 · 预热 · 浮窗"),
    GuideCategory("chat_features", "💬", "聊天功能", "会话 · 深度思考 · 图片文件 · 导出"),
    GuideCategory("tts", "🔊", "语音合成 TTS", "朗读 · 音色复刻 · 中日双语"),
    GuideCategory("lore", "📖", "世界观与世界书", "设定注入 · 关键词触发 · 导入导出"),
    GuideCategory("social", "👥", "角色 · 问候 · 群聊", "角色卡 · 好感度 · 主动消息 · 群聊"),
    GuideCategory("media", "🎵", "音乐 · 视频 · 外观", "在线音乐 · 视频 · 主题背景"),
)

fun guideCategoryOf(id: String): GuideCategory? = GUIDE_CATEGORIES.firstOrNull { it.id == id }

fun guideTopicOf(id: String): GuideTopic? = GUIDE_TOPICS.firstOrNull { it.id == id }

fun topicsOfCategory(categoryId: String): List<GuideTopic> =
    GUIDE_TOPICS.filter { it.categoryId == categoryId }

// =====================================================================
// 话题（9 分类 45 项，覆盖全部用户可见功能）
// =====================================================================

val GUIDE_TOPICS: List<GuideTopic> = listOf(

    // ---------------- 1. 快速上手 ----------------
    GuideTopic(
        id = "intro", categoryId = "quick_start", emoji = "🌟",
        title = "应用简介与核心玩法",
        aliases = listOf("简介", "介绍", "是什么", "入门", "about", "玩法"),
        beginner = listOf(
            para("本应用是一款 AI 角色扮演聊天应用。内置数百位预设角色（另可自建专属角色），可以像即时通讯软件一样和她们聊天、送礼物、拉群聊，还能让角色开口说话、生成对话短视频。"),
            tip("五个页面", "屏幕底部有 5 个入口：聊天（对话）、角色（管理角色）、音乐、模型（本地 AI）、设置。所有功能都从这里出发。"),
            tip("从哪开始", "打开「聊天」选择一位角色即可开始对话；对话记录按角色独立保存。"),
        ),
        experienced = listOf(
            para("基于 LLM 的角色扮演应用：云端走 OpenAI 兼容 /chat/completions（SSE 流式直连），本地走 MNN 端侧推理；预设角色库 + 自定义角色卡（JSON 导入导出）。"),
            tip("架构速览", "数据层 DataStore（偏好）+ Room（会话/视频/好感度）；UI 为 Compose 单 Activity + 嵌套导航；后台调度驱动主动问候与群聊。"),
        ),
    ),
    GuideTopic(
        id = "dual_engine", categoryId = "quick_start", emoji = "☁️",
        title = "云端 / 本地双引擎是什么",
        aliases = listOf("双引擎", "云端", "本地", "切换", "离线", "engine", "sse", "mnn", "联网"),
        beginner = listOf(
            para("聊天有两种方式。「云端」像点外卖——由网上的 AI 服务帮你回答，效果好、不吃手机性能，但要联网、部分服务要花钱买 Key；「本地」像自己做饭——AI 直接装在你手机里，不联网也能聊，但速度取决于手机性能。"),
            stepTitle("怎么切换"),
            stepText("打开任意聊天，顶部有「☁ 云端 / 📱 本地」开关，点一下即可切换；两边共用同一份聊天记录。"),
            warn("本地模式要先在「模型」页下载模型文件；另外群聊和角色主动问候只在云端可用。"),
        ),
        experienced = listOf(
            para("云端：OkHttp/Retrofit 直连 OpenAI 兼容端点，SSE 长连接（readTimeout/callTimeout=0），逐 token 回调；Anthropic 端点自动切换 /v1/messages + x-api-key 协议。"),
            para("本地：MNN 端侧推理，后端链 CPU / OpenCL GPU / QNN NPU 按偏好与健康记录回退；流式批处理回调，推理期间有前台保活服务与性能浮窗。"),
            tip("记录共享", "两种引擎读写同一 Room 会话库，provider 切换无缝衔接。"),
        ),
    ),
    GuideTopic(
        id = "first_chat", categoryId = "quick_start", emoji = "💬",
        title = "三步开启第一场对话",
        aliases = listOf("开始聊天", "第一次", "新手", "怎么聊", "hello", "快速开始"),
        beginner = listOf(
            stepTitle("第 1 步：让 AI 能回答你"),
            stepText("最省事的做法：设置 → LLM API 配置 → 模型商选「免费对话」→ 点「保存 API 设置」。不用注册、不用填任何 Key。"),
            stepTitle("第 2 步：找到想聊的角色"),
            stepText("底部切到「角色」页或聊天页的角色入口，挑选一位喜欢的角色。"),
            stepTitle("第 3 步：开始对话"),
            stepText("进入聊天直接发消息；欢迎页还有推荐话题，点一下就能发送。"),
        ),
        experienced = listOf(
            para("配置任一 provider（「免费对话」零配置）→ 选定目标角色 → 开始对话；欢迎态提供推荐话题直发。"),
            tip("进阶", "想要更强模型参照「云端 AI 配置」分类接入 DeepSeek 等；想要完全离线参照「本地大模型」分类。"),
        ),
    ),
    GuideTopic(
        id = "checkin", categoryId = "quick_start", emoji = "🪙",
        title = "每日签到与金币",
        aliases = listOf("签到", "金币", "商店", "礼物", "coin", "checkin", "钱包"),
        beginner = listOf(
            para("每天第一次打开应用会弹出签到提醒，领 10,000 金币。金币用来在商店采购礼物，送给角色能涨好感度。"),
            stepTitle("入口"),
            stepText("角色页右上角「签到」进入每日供应与商店；好感度档案页也能进。"),
            tip("送礼物", "聊天输入框旁的 🎁 按钮可以把库存里的礼物送给角色，她会当场道谢，好感度上涨。"),
            tip("自制礼物", "商店里可以「新建礼物」：起名、写描述、定价，做一份独一无二的礼物。"),
        ),
        experienced = listOf(
            para("好感度经济系统：签到入账 10,000 金币/日（冷启动每日提醒弹窗一次）；礼物购买扣减钱包、赠送入账本并触发 AI 道谢回复落库。"),
            tip("好感增益", "礼物按价格档位提供不同好感增量；收礼记录沉淀在关系档案的礼物墙。"),
        ),
    ),

    // ---------------- 2. 云端 AI 配置 ----------------
    GuideTopic(
        id = "deepseek", categoryId = "cloud_api", emoji = "🐋",
        title = "DeepSeek 接入（推荐）",
        aliases = listOf("deepseek", "深度求索", "ds", "官方api"),
        beginner = listOf(
            para("DeepSeek 是国内可以直接使用的 AI 服务，价格便宜效果好，推荐作为第一个接入的服务商。"),
            stepTitle("步骤"),
            stepText("1. 浏览器访问 platform.deepseek.com 注册账号。"),
            stepText("2. 在「API Keys」页面创建一个 Key（sk- 开头的一串字符），复制下来。"),
            stepText("3. 回到应用：设置 → LLM API 配置 → 模型商选「DeepSeek」→ 粘贴 Key → 选模型 → 点「保存 API 设置」。"),
            tip("选哪个模型", "V4-Flash 又快又便宜，日常够用；追求更好效果选 V4-Pro。"),
        ),
        experienced = listOf(
            para("platform.deepseek.com 创建 Key → 预设 baseUrl https://api.deepseek.com；V4-Flash 支持思考/非思考双模式与长上下文。"),
            tip("配置记忆", "每个供应商的 baseUrl/model/key 独立记忆，来回切换互不串 Key。"),
        ),
    ),
    GuideTopic(
        id = "other_providers", categoryId = "cloud_api", emoji = "🌐",
        title = "OpenAI / 通义千问 / 智谱 GLM",
        aliases = listOf("openai", "通义", "千问", "智谱", "glm", "qwen", "gpt", "阿里", "百炼"),
        beginner = listOf(
            para("除了 DeepSeek，还内置多家服务商的快捷接入。流程都一样：官网注册 → 创建 Key → 回应用里选对应模型商粘贴保存。"),
            stepTitle("OpenAI"),
            stepText("platform.openai.com 创建 Key 并充值 → 模型商选「OpenAI」。需要国外网络环境。"),
            stepTitle("通义千问（阿里百炼）"),
            stepText("bailian.console.aliyun.com 开通服务获取 Key → 模型商选「通义千问」。"),
            stepTitle("智谱 GLM"),
            stepText("open.bigmodel.cn 获取 Key → 模型商选「智谱 GLM」。"),
        ),
        experienced = listOf(
            para("均为 OpenAI 兼容协议预设（PRESET_PROVIDERS）；baseUrl 与模型清单预置，切换供应商时各自配置独立恢复，互不污染。"),
        ),
    ),
    GuideTopic(
        id = "custom_api", categoryId = "cloud_api", emoji = "🔧",
        title = "自定义接口（OpenAI 兼容）",
        aliases = listOf("自定义", "baseurl", "中转", "openai兼容", "oneapi", "newapi", "claude", "anthropic", "api地址", "硅基流动", "月之暗面"),
        beginner = listOf(
            para("如果你手里是别家的 Key（中转站、聚合平台等），只要它兼容 OpenAI 格式就都能用。"),
            stepTitle("步骤"),
            stepText("1. 设置 → LLM API 配置 → 模型商选「自定义」。"),
            stepText("2. 填 API BASE URL（服务商给的接口地址，一般以 /v1 结尾）。"),
            stepText("3. 填 MODEL（模型名）与 API KEY → 点「保存 API 设置」。"),
            tip("Claude 用户", "地址里带 anthropic/claude 字样、或以 /v1/messages 结尾时，会自动按 Claude 的协议连接，直接填即可，无需额外设置。"),
        ),
        experienced = listOf(
            para("任意 OpenAI Chat Completions 兼容端点均可。协议自动识别：URL 含 anthropic/claude 或路径以 /v1/messages 结尾 → /v1/messages + x-api-key；否则 /chat/completions + Bearer。Base URL 自动归一化（尾缀 scheme 残渣/空白）；也接受完整 endpoint 直填。"),
        ),
    ),
    GuideTopic(
        id = "free_chat", categoryId = "cloud_api", emoji = "🆓",
        title = "免费对话怎么用",
        aliases = listOf("免费", "free", "白嫖", "不要钱", "硅基流动", "siliconflow", "免费对话", "零成本"),
        beginner = listOf(
            para("「免费对话」是应用内置的免费通道：不用注册、不用填任何 Key，选上就能聊！"),
            stepTitle("使用方法"),
            stepText("设置 → LLM API 配置 → 模型商选「免费对话」→ 直接点「保存 API 设置」（Key 一栏完全不用管）。"),
            warn("免费通道是大家共享的：人多时回复偏慢、偶发报错，稍等重试即可。此服务需要国外网络环境访问。"),
            tip("两个免费模型", "Qwen2.5-7B（通用闲聊）与 DeepSeek-R1-7B（会深度思考），在「模型」下拉里切换。"),
        ),
        experienced = listOf(
            para("SiliconFlow 免费共享 7B（Qwen/Qwen2.5-7B-Instruct 与 DeepSeek-R1-Distill-Qwen-7B），经 Cloudflare Worker 代理转发；真实 Key 存于 Worker 加密环境变量由服务端注入，App 端 requiresApiKey=false 允许空 Key 落库与请求。"),
        ),
    ),
    GuideTopic(
        id = "api_key_safety", categoryId = "cloud_api", emoji = "🔑",
        title = "API Key 安全与保存",
        aliases = listOf("key", "密钥", "安全", "泄露", "保存", "sk-", "凭据"),
        beginner = listOf(
            para("Key 就是你的付费通行证，泄露出去别人就能消耗你的额度。"),
            tip("只存本机", "Key 只保存在你自己手机的本地设置里，不会上传给任何第三方服务器（免费对话甚至完全不需要 Key）。"),
            tip("眼睛图标", "Key 输入框右侧的 👁 图标可显示/隐藏明文，粘贴后点一下确认没有粘错。"),
            warn("不要把 Key 发到群里或截图给别人。万一泄露，去服务商网站删除旧的重建一个，回应用里替换保存。"),
        ),
        experienced = listOf(
            para("保存走原子双写：更新活跃 api_config 三键 + 写入该供应商记忆槽；密码框遮蔽显示。"),
        ),
    ),

    // ---------------- 3. 本地大模型 ----------------
    GuideTopic(
        id = "model_list", categoryId = "local_llm", emoji = "🗂️",
        title = "内置模型一览",
        aliases = listOf("模型列表", "有哪些模型", "qwen", "llama", "gemma", "推荐", "14个", "选模型", "多大"),
        beginner = listOf(
            para("「模型」页内置了 14 个可以直接下载的模型，带 ⭐ 的是推荐入门款。"),
            tip("怎么选", "名字里的数字越小（0.8B、1.5B）越快越省内存、但更笨；越大（7B、8B）越聪明、但更慢更占内存。新手建议从 2B / 4B 开始。"),
            tip("会思考的模型", "名字带 DeepSeek-R1 的模型会先「想一想」再回答，配合聊天页 🧠 深度思考开关能看到思考过程。"),
            warn("本地模型暂不支持识别图片；模型文件几百 MB 到几 GB，建议用 Wi-Fi 下载。"),
        ),
        experienced = listOf(
            para("MNN 格式共 14 个：Qwen 系列（0.8B~35B）、DeepSeek-R1 蒸馏系、Llama、Gemma 等；「模型」页可刷新拉取列表。"),
            tip("版本标识", "模型卡显示名称 / 推荐星标 / 体积 / 版本号；下载完成后才可「切换使用」。"),
        ),
    ),
    GuideTopic(
        id = "download_resume", categoryId = "local_llm", emoji = "⬇️",
        title = "下载与断点续传",
        aliases = listOf("下载", "断点续传", "暂停", "继续", "下载失败", "重试"),
        beginner = listOf(
            para("点模型卡的「下载」开始下载，进度条实时显示；中途可暂停，下次接着下，不会从头再来。"),
            tip("失败怎么办", "网络波动导致失败时会出现「重试」按钮，点击继续即可，已下载的部分不会浪费。"),
            tip("下载太慢", "模型托管在公共源，高峰期偏慢属正常；换 Wi-Fi 或错峰再试。"),
        ),
        experienced = listOf(
            para("分块下载 + 断点续传；完成后做完整性校验；各失败阶段给出对应重试入口。模型文件不占用「存储管理」统计口径（后者仅业务数据）。"),
        ),
    ),
    GuideTopic(
        id = "load_switch", categoryId = "local_llm", emoji = "✅",
        title = "加载与切换使用",
        aliases = listOf("加载", "切换使用", "删除模型", "换模型", "首次加载", "很慢"),
        beginner = listOf(
            para("下载完成后点「切换使用」即装载该模型；回聊天页把顶部切到「📱 本地」就开始离线对话。"),
            tip("换模型", "再下载另一个模型点「切换使用」即可换人；不用的模型点「删除」清出空间。"),
            warn("首次加载大模型要等一会（手机正在把它读进内存）；内存紧张的手机加载大模型可能失败，换小一号的模型即可。"),
        ),
        experienced = listOf(
            para("切换写入 active model 标记；switchProvider(LOCAL) 时按推理引擎设置快照加载；生成期间有前台保活通知。"),
        ),
    ),
    GuideTopic(
        id = "memory_usage", categoryId = "local_llm", emoji = "🧮",
        title = "内存占用怎么看",
        aliases = listOf("内存", "占用", "kv", "cache", "kv cache", "多大内存", "oom", "爆内存", "估算"),
        beginner = listOf(
            para("想知道一个模型要吃多少内存？到 设置 → 推理引擎设置 里拖动「上下文长度」，页面会实时显示内存估算，照着留余量即可。"),
            tip("一句话原则", "大约等于：模型体积 + 几百 MB 到 1GB 的额外开销。手机剩余可用内存比这个数大就基本稳。"),
            warn("加载时闪退或报错多半是内存不足——换更小的模型，或调小上下文长度。"),
        ),
        experienced = listOf(
            para("KV cache 按 fp16（2 字节/元素）估算：bytes = contextTokens × layerCount × 2 × kvHeads × headDim × bytesPerElement（GQA 按 numKeyValueHeads 折减，维度未知回退 full-hidden 近似与保守密度兜底）。实现在 LlmMemoryEstimator。"),
        ),
    ),

    // ---------------- 4. 引擎与性能调优 ----------------
    GuideTopic(
        id = "backends", categoryId = "engine_tuning", emoji = "⚙️",
        title = "四种推理后端（AUTO/CPU/GPU/NPU）",
        aliases = listOf("后端", "cpu", "gpu", "npu", "opencl", "qnn", "hexagon", "骁龙", "自动", "backend"),
        beginner = listOf(
            para("本地推理可以选择让手机哪个部件干活：自动（推荐）/ 强制 CPU / 强制 GPU / 强制 NPU。不了解就保持「自动」。"),
            tip("自动是什么", "优先尝试 GPU 提速，条件不满足就退回 CPU 保证可用，全程无需操心。"),
            tip("NPU 是什么", "手机里专门跑 AI 的芯片单元（通常是较新的骁龙机型才有）。强制 NPU 需要解锁设备驱动，属于高级玩法。"),
            warn("改完设置不用重启应用——下次发送消息时自动重载生效。"),
        ),
        experienced = listOf(
            para("AUTO 仅对总参数量严格 >7B 的模型探测/启用 GPU（小模型不为注定 CPU 的推理等待探测）；OpenCL GPU 走健康探测与冷却机制（BackendHealthCoordinator 维护健康记录，异常后端移出回退链）；QNN NPU 基于 Hexagon（需骁龙 + 解锁）。完整回退链预览见推理引擎设置页底部说明区。"),
        ),
    ),
    GuideTopic(
        id = "perf_lookahead", categoryId = "engine_tuning", emoji = "🏎️",
        title = "性能模式与投机解码",
        aliases = listOf("性能模式", "最高速度", "lookahead", "投机解码", "投机采样", "加速", "平衡", "跑分"),
        beginner = listOf(
            para("「推理性能模式」有两档：综合平衡（默认，推荐）与最高速度。日常用平衡即可，追求响应速度可切最高速度。"),
            warn("「Lookahead 投机解码」是实验性加速开关：必须先用页面里的「运行基准并认证」跑完测试、且证明在你这台手机上确实更快之后，打开它才真正生效；否则即使打开也不会起作用。"),
        ),
        experienced = listOf(
            para("Profile resolver 统一裁决性能模式与候选加速项。Lookahead 仅当 device+model+CPU_OPTIMIZED 变体组合命中认证记录（InferenceCertificationStore）时启用；未认证候选恒回落 lookahead=false。"),
            tip("基准闭环", "「运行基准并认证」跑 FIXED_DECODE 对比（预热轮 + 记录轮）→ 判定 promotion → 认证落盘后旧开关才被放行。"),
        ),
    ),
    GuideTopic(
        id = "threads_context", categoryId = "engine_tuning", emoji = "🎛️",
        title = "CPU 线程数与上下文长度",
        aliases = listOf("线程", "上下文", "记忆长度", "context", "threads", "参数", "生成长度", "max_tokens"),
        beginner = listOf(
            para("这两项决定本地 AI「算得多快」与「记性多好」。默认值已经比较合理，不确定就别动。"),
            tip("线程数", "一般设成手机的大核数量（常见为 4）即可，盲目调大反而发热降频变慢。"),
            tip("上下文长度", "相当于 AI 的短期记忆容量，范围 512–8192；调大记得住更多对话但更吃内存。"),
            tip("最大生成长度", "单次回复的字数上限，可在设置页调整。"),
            warn("修改后下次发消息时自动重载生效，页面顶部会显示提示横幅。"),
        ),
        experienced = listOf(
            para("默认 contextLen=4096 / threads=4 / temperature=0.8（AppConfig.LLM）。KV cache 随 contextLen 线性增长（见「内存占用怎么看」）；llm_config_changed 横幅提示重载，成功推理后 acknowledge 写回 last_applied 快照消除横幅。"),
        ),
    ),
    GuideTopic(
        id = "gpu_preheat", categoryId = "engine_tuning", emoji = "🔥",
        title = "GPU 完整预热",
        aliases = listOf("预热", "preheat", "首字延迟", "第一次慢", "gpu慢"),
        beginner = listOf(
            para("用 GPU 时是不是感觉第一句话特别慢？到 推理引擎设置 里手动点一次「GPU 完整预热」，之后首次回复会明显变快。"),
            warn("只有大于 7B 的大模型才需要预热（小模型按钮不可用）；预热过程手机发热几分钟属正常现象。"),
        ),
        experienced = listOf(
            para("GpuPreheatCoordinator 手动触发（空闲期策略只做轻量 OpenCL 探测、绝不自动加载模型）：加载当前 >7B 模型执行一次极短 GPU 生成，预热 OpenCL 图/内核/缓存，降低首字延迟（TTFT）；预热后无条件释放，不影响聊天记录与已保存设置。"),
        ),
    ),
    GuideTopic(
        id = "perf_overlay", categoryId = "engine_tuning", emoji = "📊",
        title = "性能浮窗",
        aliases = listOf("浮窗", "性能", "tok/s", "速率", "温度", "监控", "fps"),
        beginner = listOf(
            para("本地模式下聊天页有一个小浮窗，实时显示 AI 出字速度、手机温度、内存占用等信息。纯展示、不影响聊天，可以拖到顺手的位置。"),
            tip("外观切换", "设置里的「性能浮窗液态玻璃」开关：开=模糊玻璃质感（Android 12+），关=普通深色面板。老手机建议关。"),
        ),
        experienced = listOf(
            para("PerformanceCollector 500ms 周期采集：token 速率、CPU 大核频率、GPU/NPU 状态、温度、内存等指标；液态玻璃渲染走 RenderEffect 模糊路径，关闭后为纯色面板。"),
        ),
    ),

    // ---------------- 5. 聊天功能 ----------------
    GuideTopic(
        id = "conversations", categoryId = "chat_features", emoji = "🗨️",
        title = "会话管理",
        aliases = listOf("会话", "对话记录", "新建对话", "重命名", "删除消息", "多个会话", "history"),
        beginner = listOf(
            para("和每个角色的聊天都可以开多个「会话」，就像同一个人有多个聊天窗口。点聊天顶部的 💬 图标打开会话抽屉。"),
            tip("新建 / 切换", "抽屉里「新建对话」开新话题；点任意历史会话即可切回去，记录都在。"),
            tip("整理", "会话可以重命名、删除；单条消息也能删除。导出见「对话导出」篇。"),
        ),
        experienced = listOf(
            para("会话按角色隔离（active_conversations 映射当前会话）；单会话历史上限按配置修剪最旧；PromptWindowPlanner 在候选内保 system + 最近完整轮次。"),
        ),
    ),
    GuideTopic(
        id = "export_chat", categoryId = "chat_features", emoji = "📤",
        title = "对话导出（TXT / 图片）",
        aliases = listOf("导出", "备份", "截图", "长图", "txt", "导出记录", "保存聊天"),
        beginner = listOf(
            para("想把聊天记录保存或分享？打开会话抽屉 →「导出记录」→ 选格式："),
            tip("TXT", "完整文字记录，方便备份与搜索。"),
            tip("图片", "把聊天渲染成漂亮的对话截图：超长会话自动分页多张图，短会话可拼一张长图。"),
        ),
        experienced = listOf(
            para("conversationexport 管线：ConversationTextExporter 全量 TXT / ConversationImageRenderer 分页渲染 PNG（Canvas 绘制，超限自动分页防 OOM）。"),
        ),
    ),
    GuideTopic(
        id = "deep_thinking", categoryId = "chat_features", emoji = "🧠",
        title = "深度思考开关",
        aliases = listOf("思考", "推理", "深度思考", "think", "r1", "思维链", "cot"),
        beginner = listOf(
            para("点聊天顶部 🧠 图标开启「深度思考」：AI 回答前会先展示一段「思考过程」，然后再给正式答案；思考段自动折叠，点一下可展开查看。"),
            tip("什么时候开", "数学、逻辑、复杂问题更适合；日常闲聊关掉回复更快。开了也没关系——「自动」档位遇到简单问题会自动跳过思考。"),
            warn("不是所有模型都会思考：免费的 DeepSeek-R1-7B、云端的 Qwen / DeepSeek 系列支持较好。"),
        ),
        experienced = listOf(
            para("云端解析 delta.reasoning_content / reasoning 字段包装 <think> 流式段；本地 MNN enable_thinking 且受 LocalThinkingLevel 档位（AUTO/短/中/长）控制。AUTO 走 when-to-think 路由：平凡/简单输入整轮跳过思考，STANDARD→短档、COMPLEX→中档；软提示为「思考模板」微指令 + 思考段字节硬预算截断收束。"),
        ),
    ),
    GuideTopic(
        id = "image_chat", categoryId = "chat_features", emoji = "🖼️",
        title = "上传图片对话",
        aliases = listOf("图片", "发图", "看图", "多模态", "photo", "识图", "拍照"),
        beginner = listOf(
            para("聊天输入框点 ➕ 可以添加图片（一次最多 3 张），让 AI「看图说话」。"),
            warn("需要支持看图的模型：云端多模态模型可以；免费通道与本地模型目前看不了图片。"),
        ),
        experienced = listOf(
            para("图片 base64 进 vision content parts；能否携带由活跃模型的模态能力决定，本地 MNN 无 vision。输入栏预览可逐张移除。"),
        ),
    ),
    GuideTopic(
        id = "pdf_files", categoryId = "chat_features", emoji = "📄",
        title = "PDF 与文本文件",
        aliases = listOf("文件", "pdf", "文档", "word", "上传文件", "附件", "read"),
        beginner = listOf(
            para("输入框 📎 可以发文件给 AI 读：PDF 自动提取文字（前 6 页），txt / md 等纯文本直接读取。"),
            warn("Word / PPT 等 Office 文档请先转成 PDF 再发；PDF 超长时只读前 6 页。"),
        ),
        experienced = listOf(
            para("逐页渲染提取（页数上限），base64 流式编码防大文件 OOM；Office 族不受支持需预转 PDF。"),
        ),
    ),
    GuideTopic(
        id = "code_math", categoryId = "chat_features", emoji = "🧪",
        title = "代码高亮与数学公式",
        aliases = listOf("代码", "公式", "数学", "latex", "高亮", "代码块", "复制代码"),
        beginner = listOf(
            para("AI 回复里的代码会自动变成彩色代码块（可一键复制，超长自动折叠）；数学公式也会正确排版显示。"),
            tip("公式怎么写", "让 AI 用一对符号把公式包起来即可：行内公式用单个美元符包裹、独立公式块用双美元符包裹（例如「请用 LaTeX 输出这段公式的行内形式」）。"),
        ),
        experienced = listOf(
            para("代码块语法高亮 + 复制；LaTeX 渲染行内 $...$ 与块级 $$...$$（上下标/分数/根号常用子集）。"),
        ),
    ),

    // ---------------- 6. 语音合成 TTS ----------------
    GuideTopic(
        id = "tts_setup", categoryId = "tts", emoji = "🔊",
        title = "开通语音朗读（两种方式）",
        aliases = listOf("tts", "语音合成", "豆包", "火山", "朗读", "配音", "speaker", "声音", "念出来"),
        beginner = listOf(
            para("想让角色开口说话？两种方式任选："),
            stepTitle("方式一：手机自带语音（零成本，推荐先试）"),
            stepText("设置 → 语音合成(TTS) → 朗读引擎选「手机系统语音」→ 挑个声音模板 → 保存。离线免费，立刻能用。"),
            stepTitle("方式二：云端火山豆包（更自然好听）"),
            stepText("注册火山引擎 → 开通「语音合成」服务拿 API Key → 设置里朗读引擎选「云端（火山豆包）」→ 粘贴 Key → 保存。"),
            tip("先试听", "设置里有「试听」按钮，配好后点一下就知道效果。"),
        ),
        experienced = listOf(
            para("云端直连火山 openspeech V3 unidirectional chunked 端点（X-Api-Key 鉴权）；声音复刻资源 seed-icl-2.0 已预置，无需 AppID / AccessKey / ResourceID。系统引擎经 TextToSpeech 模板匹配、无匹配语音回落默认（语速音调仍生效）。"),
        ),
    ),
    GuideTopic(
        id = "voice_clone", categoryId = "tts", emoji = "🎭",
        title = "声音复刻与角色音色映射",
        aliases = listOf("音色", "复刻", "克隆", "speaker_id", "s_xxx", "角色声音", "专属声音"),
        beginner = listOf(
            para("想让每个角色用自己的专属声音说话？在火山引擎「声音复刻」用一段录音训练出音色，会得到一串 S_xxx 编号，回来填给对应角色即可。"),
            stepTitle("配置位置"),
            stepText("设置 → 角色双语音色（speaker_id）：每个角色有中文、日文两格；没填的角色使用上方「默认音色」。"),
            tip("日语注意", "想让角色说日语，需要为她填日文音色；只填中文音色时日语朗读会退回默认音色。"),
        ),
        experienced = listOf(
            para("VoicePair(zh/ja speaker_id) 按角色映射；解析顺序：角色当前语言 speaker_id → TtsConfig.defaultVoiceId；JA 缺失同样回落默认。试听按当前引擎与语言取样。"),
        ),
    ),
    GuideTopic(
        id = "bilingual", categoryId = "tts", emoji = "🌏",
        title = "中日双语模式",
        aliases = listOf("日语", "中文", "语言切换", "双语", "字幕", "日本語", "ja"),
        beginner = listOf(
            para("聊天顶部有个圆形语言按钮（中 / 日），点一下切换。日语模式下角色会用日语回复并朗读，屏幕底部还显示中日对照字幕，方便一边看翻译一边听。"),
            warn("日语模式需要云端 AI；本地模型说不好日语。"),
        ),
        experienced = listOf(
            para("JA 模式：正文生成后经 chatOnce 非流式翻译再合成；字幕条同步展示原文与译文。语言选择全局持久化。"),
        ),
    ),
    GuideTopic(
        id = "auto_read", categoryId = "tts", emoji = "📢",
        title = "自动朗读与音量",
        aliases = listOf("自动朗读", "系统语音", "离线朗读", "音量", "auto read"),
        beginner = listOf(
            para("「自动朗读新回复」打开后（设置 → 语音合成），AI 每次回复完成自动读出来，不用再手动点 ▶；仍可随时点消息旁的朗读按钮手动控制。"),
            tip("音量", "朗读音量跟随手机媒体音量，用侧面音量键调节即可。"),
            tip("停止朗读", "朗读中再点一次 ▶ 即停止；开始播放视频时会自动暂停朗读避免抢声。"),
        ),
        experienced = listOf(
            para("tts_auto_read 持久化开关；播放器队列化 utterance，Seedance 播放控制器抢占时挂起当前朗读。"),
        ),
    ),

    // ---------------- 7. 世界观与世界书 ----------------
    GuideTopic(
        id = "worldview", categoryId = "lore", emoji = "🌍",
        title = "世界观设定（绑定注入）",
        aliases = listOf("世界观", "设定注入", "worldview", "背景设定", "人际设定"),
        beginner = listOf(
            para("「世界观」是你写给 AI 看的背景设定，比如「这个故事发生在魔法学院」「我和她是同学」。每条设定只对你指定的那个聊天生效。"),
            stepTitle("怎么建"),
            stepText("设置 → 世界观设定 → 添加：起名、写正文、选择给谁用（某角色的私聊或某群）→ 保存。"),
            tip("多条叠加", "同一个聊天可以添加多条世界观，按顺序依次生效。"),
        ),
        experienced = listOf(
            para("WorldviewConfig 一一绑定目标（CHARACTER / GROUP + targetId），buildWorldviewDirective 注入 system prompt；同目标多条按序拼接。目标悬空显式告警，不静默丢弃。"),
        ),
    ),
    GuideTopic(
        id = "lorebook_import", categoryId = "lore", emoji = "📚",
        title = "世界书是什么 & 导入导出",
        aliases = listOf("世界书", "lorebook", "lore", "酒馆", "sillytavern", "st导入", "导入json", "世界书导入"),
        beginner = listOf(
            para("「世界书」是一堆背景资料的合集：平时藏着不占地方，一旦聊天里提到相关关键词，对应资料才被塞给 AI 参考。适合存放大量世界观细节。"),
            stepTitle("导入现成的"),
            stepText("设置 → 世界书 → 「导入 .json」：SillyTavern（酒馆）格式的世界书文件直接兼容。"),
            stepTitle("自己建"),
            stepText("「新建」命名一本书 → 点进去 → 「添加条目」写关键词与内容；每本书、每条目都有独立开关。"),
            tip("导出分享", "书详情页可导出 SillyTavern 兼容 JSON，和酒馆玩家互通。"),
        ),
        experienced = listOf(
            para("LorebookJson 双向 ST 兼容；ST 格式不含本应用的绑定路由，导入一律落 ALL 作用域。书级 scopeType（ALL / CHARACTER / GROUP + scopeIds）决定参与哪些聊天匹配（matchesScope）。"),
        ),
    ),
    GuideTopic(
        id = "keyword_trigger", categoryId = "lore", emoji = "🔍",
        title = "关键词触发与次级关键词",
        aliases = listOf("关键词", "触发", "次级关键词", "绿灯", "扫描", "匹配", "trigger"),
        beginner = listOf(
            para("每个条目写几个「主关键词」（顿号或逗号隔开）：聊天最近几句里出现关键词，这条资料就被激活注入。"),
            tip("次级关键词", "可选的进阶条件：要求「同时出现」「出现任意一个」「不全出现」「都不出现」等组合逻辑，精确控制生效时机。"),
            tip("扫描范围", "默认检查最近 2 条消息；可在 设置 → 世界书 → 全局参数 调大扫描深度。"),
        ),
        experienced = listOf(
            para("LorebookEntry.keys 主匹配 + secondaryKeys 五种 LorebookSecondaryLogic 组合语义；scanDepth 全局默认 2、条目 scanDepthOverride 可覆盖（≤0 视为继承）；caseSensitive / matchWholeWords 精细控制匹配行为。"),
        ),
    ),
    GuideTopic(
        id = "insert_position", categoryId = "lore", emoji = "💡",
        title = "插入位置与常驻蓝灯",
        aliases = listOf("蓝灯", "绿灯", "常驻", "插入位置", "深度", "顺序", "constant", "at_depth"),
        beginner = listOf(
            para("条目激活后插到哪里、对 AI 影响多强，都可以调："),
            tip("蓝灯 = 常驻", "条目的「常驻条目」开关（酒馆里叫蓝灯）：打开后不再需要关键词，每次对话必定注入。核心世界观设定用它。"),
            tip("插入位置", "可放在角色设定的前面 / 后面，或插进最近聊天记录中间的指定位置（@深度，数值越小越靠近最新消息、影响越强）。"),
            tip("插入顺序", "order 数值越大排得越靠下、话语权越强；预算不够裁剪时也是高 order 优先保留。"),
        ),
        experienced = listOf(
            para("LorebookInsertPosition：BEFORE_CHAR / AFTER_CHAR / AT_DEPTH(depth)；constant=true 免关键词必注入；order 控制装配次序与预算裁剪优先级；probability 1-100 触发概率门。"),
        ),
    ),
    GuideTopic(
        id = "budget_recursion", categoryId = "lore", emoji = "📏",
        title = "Token 预算与递归扫描",
        aliases = listOf("预算", "token", "递归", "recursion", "扫描深度", "超预算", "注入太多"),
        beginner = listOf(
            para("设置 → 世界书 → 全局参数里有两个总闸："),
            tip("Token 预算上限", "所有被激活条目的内容加起来最多塞多少给 AI（默认 1024），防止把对话撑爆。"),
            tip("递归扫描", "打开后，A 条目内容里出现的新关键词还能继续激活 B 条目（最多连环 3 层）。设定联动复杂时有用，一般保持关闭。"),
        ),
        experienced = listOf(
            para("budgetCapTokens ≤0 视为不限——本地默认 context 4096 下危险，PromptWindowPlanner 有 SYSTEM_PROMPT_TOO_LARGE 兜底；recursiveScanning 最多 3 轮；条目级 preventRecursion / excludeRecursion 分别阻止自身外链扩散与被递归轮激活。"),
        ),
    ),

    // ---------------- 8. 角色 · 问候 · 群聊 ----------------
    GuideTopic(
        id = "character_library", categoryId = "social", emoji = "👥",
        title = "角色库与搜索",
        aliases = listOf("角色", "人设", "角色库", "换角色", "persona", "角色搜索", "立绘", "头像"),
        beginner = listOf(
            para("应用内置大量预设角色，每人有独立性格、说话方式与立绘；也支持自建角色。「角色」页网格浏览，顶部搜索框支持按名称等条件查找。"),
            tip("查看人设", "角色卡上的「查看人设」可阅读完整人格设定正文。"),
            tip("点谁聊谁", "角色页点任意角色卡片，即刻把她设为当前对象并直达聊天。"),
        ),
        experienced = listOf(
            para("预设角色序列 + 自定义角色合并展示；filterCharacters 多字段 contains（忽略大小写）同函数复用于角色页与世界观点选器。"),
        ),
    ),
    GuideTopic(
        id = "custom_character", categoryId = "social", emoji = "✏️",
        title = "新建 / 导入自定义角色",
        aliases = listOf("自定义角色", "新建角色", "导入角色", "导出角色", "角色卡", "做角色"),
        beginner = listOf(
            para("想聊自己创造的角色？角色页 →「新建」：起名、传一张立绘、写一段「人格设定」（她是谁、什么性格、怎么说话），保存后立刻出现在列表里。"),
            tip("分享角色", "「导出」把你做的角色复制成 JSON 发给别人；「导入」粘贴 JSON 收下对方的角色。"),
            tip("随时改", "自定义角色卡片上有编辑 / 删除按钮，随时调整人设。"),
        ),
        experienced = listOf(
            para("Character(systemPrompt 必填) JSON 序列化导入导出；自定义角色立绘经 CharacterImageStore 本地存储。"),
        ),
    ),
    GuideTopic(
        id = "affinity", categoryId = "social", emoji = "💝",
        title = "好感度 · 礼物 · 剧情事件",
        aliases = listOf("好感度", "关系", "礼物墙", "特殊邂逅", "羁绊", "事件", "解锁剧情", "affinity"),
        beginner = listOf(
            para("送礼物、常聊天都能涨「好感度」（满值 200）。好感越高解锁越多："),
            tip("关系档案", "角色页点好感进度进入档案页：进度条、收礼记录（礼物墙）、随好感解锁的剧情入口。"),
            tip("剧情事件", "达到好感门槛解锁专属剧情对话，类似游戏里的个人线；自定义角色也可以自己编写各阶段剧情。"),
            tip("我的形象", "设置 → 我的形象：上传你的头像、写下你是谁、与她什么关系——AI 在单聊、群聊、主动消息里都会记住这些设定。"),
        ),
        experienced = listOf(
            para("好感经济为 Room 体系（钱包 / 账本 / 礼物档案，MAX_AFFINITY=200）；AFFINITY_EVENT_THRESHOLDS 阈值解锁阶段事件，场景 prompt 开启事件会话（离线保底目录）；UserProfileConfig(persona/relationship/avatar) 注入三类会话上下文。"),
        ),
    ),
    GuideTopic(
        id = "greeting", categoryId = "social", emoji = "🔔",
        title = "角色主动问候",
        aliases = listOf("主动问候", "主动消息", "问候", "通知", "greeting", "主动找你", "早安"),
        beginner = listOf(
            para("想让角色主动来找你？设置 → 角色问候：打开开关、勾选哪些角色可以主动发消息、设定每天最多几条（默认 3 条）。之后白天时间里她们会像真人一样随机找你说话，并推送通知。"),
            tip("先测试", "点「测试主动问候（10 秒后）」马上体验一次，测试不计入每日条数。"),
            warn("仅云端 AI 可用；需要允许通知权限，收不到时按页面提示去系统设置开启。"),
            tip("收不到排查", "多数情况是国产手机杀了后台：按「后台保活三件套」篇设置一遍即可解决。"),
        ),
        experienced = listOf(
            para("GreetingScheduler 15min PeriodicWork 心跳 + next_fire_at 门控 + 精确闹钟兜底 + BootReceiver 重挂；投递时段 08:00–23:00；每日配额默认 3（1–10）；多角色严格轮询（greeting_last_char_id 的下一个）；Android 14 生成期前台化通知；测试路径跳过门控不计配额。"),
        ),
    ),
    GuideTopic(
        id = "survival", categoryId = "social", emoji = "🔋",
        title = "后台保活三件套",
        aliases = listOf("保活", "后台", "杀后台", "自启动", "电池优化", "闹钟权限", "收不到通知", "耗电优化"),
        beginner = listOf(
            para("国产手机为省电喜欢「杀后台」，会导致主动问候 / 群聊收不到。设置里备好三个跳转按钮，逐一点击去系统里放行："),
            stepTitle("三步设置"),
            stepText("1. 🔋 电池优化白名单：把本应用设为「不允许优化 / 无限制」。"),
            stepText("2. 📱 厂商自启动管理：允许自启动、关联启动、后台活动。"),
            stepText("3. ⏰ 精确闹钟授权：允许设定提醒。"),
            tip("还不行", "把应用锁在最近任务卡片上（下拉加锁），并在系统通知设置里允许横幅与铃声。"),
        ),
        experienced = listOf(
            para("BackgroundSurvivalHelper 跳转各 ROM 页面（电池优化白名单 / 厂商自启管理 / SCHEDULE_EXACT_ALARM 授权）；POST_NOTIFICATIONS 运行时权限在开启问候 / 群聊时即时请求（Tiramisu+）。"),
        ),
    ),
    GuideTopic(
        id = "group_play", categoryId = "social", emoji = "👾",
        title = "群聊玩法",
        aliases = listOf("群聊", "拉群", "多人", "group", "多角色", "at", "提人", "自动聊天", "群"),
        beginner = listOf(
            para("可以拉多个角色建一个群一起聊！新建群聊：选封面、起群名、勾选成员。"),
            tip("@提人", "输入 @ 可以点名某个角色回答，她一定回；其他成员也可能随机搭话（一条消息最多 4 名成员回复）。"),
            tip("自动聊天", "设置 → 群聊 → 打开「空闲自动聊天」：你不说话时群里成员也会自己唠起来，偶尔还会向你提问；每天轮次可调。"),
            warn("群聊仅云端 AI 可用；正盯着群聊页面时通知不会打扰你。"),
        ),
        experienced = listOf(
            para("GroupChatWorker：discuss 轮 DISCUSS_REPLIES_PER_ROUND=2 名成员接力互聊；每 ASK_USER_EVERY_N_ROUNDS(3) 轮改为 ask-user 轮向用户提问。用户回合 MAX_REPLIES_PER_USER_MESSAGE=4（@ 指定必答占额 + 随机补齐）；成员上限 10、人设截断、上下文限量；时段 09:00–22:00、用户发言后冷却；前台抑制通知。"),
        ),
    ),

    // ---------------- 9. 音乐 · 视频 · 外观 ----------------
    GuideTopic(
        id = "netease", categoryId = "media", emoji = "🎵",
        title = "在线音乐搜索播放",
        aliases = listOf("音乐", "网易云", "搜歌", "在线音乐", "bgm", "music", "听歌"),
        beginner = listOf(
            para("音乐页顶部搜索框可以在线搜歌：结果里点「添加」收进播放列表，随时播放。"),
            tip("全局播放", "音乐在后台持续播放，聊天、刷角色都不打断；角色视频出声时会自动让路暂停。"),
        ),
        experienced = listOf(
            para("网易云接口搜索取外链 → ExoPlayer 流式播放；播放列表持久化（MUSIC_PLAYLIST，按 key 去重追加在线曲目）；与 Seedance 播放互斥降音（音频焦点协调）。"),
        ),
    ),
    GuideTopic(
        id = "local_music", categoryId = "media", emoji = "📥",
        title = "导入本地音乐与收藏",
        aliases = listOf("本地音乐", "导入", "下载的歌", "mp3", "flac", "收藏", "喜欢"),
        beginner = listOf(
            para("音乐页「导入本地音乐」可从手机里选歌（支持多选），导入后保存在应用内，完全离线可听。"),
            tip("收藏夹", "点歌曲旁的 ♡ 收藏；用收藏过滤开关只看你喜欢的歌。"),
        ),
        experienced = listOf(
            para("SAF 多选 audio/* 拷贝至内部存储；favorites 为 stringSet 持久化；重复模式顺序 / 列表循环 / 单曲循环 + 独立 shuffle 开关。"),
        ),
    ),
    GuideTopic(
        id = "player_controls", categoryId = "media", emoji = "🎼",
        title = "播放控制与歌词",
        aliases = listOf("歌词", "进度", "循环", "随机播放", "音量", "播放控制", "lrc"),
        beginner = listOf(
            para("播放器支持：随机 / 上一曲 / 播放暂停 / 下一曲 / 循环模式切换、进度条拖动、音量滑杆。"),
            tip("歌词", "有歌词的歌会逐行高亮自动滚动，当前句突出显示；本地导入的歌若无歌词则显示「暂无歌词」。"),
        ),
        experienced = listOf(
            para("LRC 解析 + 当前行滚动定位（LrcParser）；repeatMode int 持久化（顺序 / 列表循环 / 单曲循环）。"),
        ),
    ),
    GuideTopic(
        id = "seedance_video", categoryId = "media", emoji = "🎬",
        title = "对话视频与邂逅",
        aliases = listOf("视频", "seedance", "生成视频", "自动视频", "邂逅", "对话视频", "短片"),
        beginner = listOf(
            para("想看角色「动起来」？聊天顶栏点 📹 开启自动视频：之后 AI 每次回复都会自动生成一段对应短视频插在消息里（需先配置服务）。"),
            stepTitle("配置"),
            stepText("设置 → Seedance 对话视频：填 API Key（官方方舟，或支持媒体协议的中转站）→ 点「测试连接」→ 保存。"),
            tip("看所有视频", "「邂逅」页面：生成过的视频按时间排成竖滑流，可全屏播放、保存到手机。"),
            tip("失败了", "视频卡上有状态说明，按提示操作即可；涉及扣费的重试会先弹确认。"),
            warn("仅云端 AI 模式可用；生成需要排队，等待几分钟属正常。"),
        ),
        experienced = listOf(
            para("双协议自适应：官方 base（含 /api/v3）任务接口；中转站媒体协议 POST /v1/media/generate + GET /v1/media/status（URL 特征自动识别）。SeedanceVideoState 状态机分阶段给出对应重试动作，Worker 后台推进；Fast 变体分辨率受限；水印仅官方渠道生效；语音固定开启。"),
        ),
    ),
    GuideTopic(
        id = "theme", categoryId = "media", emoji = "🎨",
        title = "主题模式切换",
        aliases = listOf("主题", "深色", "黑色", "夜间模式", "白色", "浅色", "dark mode"),
        beginner = listOf(
            para("设置 → 主题模式：跟随系统 / 浅色 / 深色三选一，立即生效。"),
            tip("玻璃质感", "整个应用是毛玻璃风格，背景为流动的极光渐变；深色模式下更沉稳护眼。"),
        ),
        experienced = listOf(
            para("ThemeMode(SYSTEM/LIGHT/DARK) 持久化，冷启动同步读取初始化 SystemBarStyle，避免系统栏图标错位闪烁。"),
        ),
    ),
    GuideTopic(
        id = "chat_background", categoryId = "media", emoji = "🖼️",
        title = "聊天背景与液态玻璃",
        aliases = listOf("背景", "聊天背景", "壁纸", "轮播", "液态玻璃", "glass"),
        beginner = listOf(
            para("设置 → 自定义背景图片：从相册挑选最多 20 张图片作为聊天背景轮播，定时自动切换，带淡入淡出效果。"),
            tip("性能浮窗外观", "设置 → 性能浮窗液态玻璃：开 = 炫酷磨砂玻璃，关 = 朴素深色面板（老手机建议关）。"),
        ),
        experienced = listOf(
            para("ChatBackgroundRepository：URI 拷贝至内部存储规避持久化权限问题；定时 Crossfade 轮播；liquid_glass_perf_overlay 控制 RenderEffect 模糊渲染路径（Android 12+）。"),
        ),
    ),
)

// =====================================================================
// 搜索（别名关键词方案：分层打分 contains 匹配，无拼音库依赖）
// =====================================================================

data class GuideSearchHit(
    val topic: GuideTopic,
    /** 命中分层：3=标题 > 2=别名 > 1=分类名。 */
    val score: Int,
    /** 实际命中的字段文本（供结果行副标展示「命中：xxx」）。 */
    val matchedIn: String,
)

/** 空查询时的推荐联想词（点击即填入搜索框触发搜索）。 */
val GUIDE_RECOMMENDED_QUERIES: List<String> = listOf("TTS", "免费", "本地模型", "世界书", "群聊", "NPU")

/**
 * 模糊搜索：query trim+lowercase 后对 标题 > 别名 > 分类名 做 contains 分层打分，
 * 同分按标题稳定排序。话题总量 ~45，内存即时过滤即可，无需 debounce。
 */
fun searchGuideTopics(query: String): List<GuideSearchHit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return GUIDE_TOPICS.mapNotNull { topic ->
        val hit = when {
            topic.title.lowercase().contains(q) -> 3 to topic.title
            else -> {
                val alias = topic.aliases.firstOrNull { it.lowercase().contains(q) }
                if (alias != null) {
                    2 to alias
                } else {
                    val cat = guideCategoryOf(topic.categoryId)
                    if (cat != null && cat.title.lowercase().contains(q)) 1 to cat.title else null
                }
            }
        } ?: return@mapNotNull null
        GuideSearchHit(topic, hit.first, hit.second)
    }.sortedWith(compareByDescending<GuideSearchHit> { it.score }.thenBy { it.topic.title })
}
