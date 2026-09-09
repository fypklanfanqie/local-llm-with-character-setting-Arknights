package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 03GuideB：使用指南测验（GuideQuiz）
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 *
 * 题目/选项写在数据层（QUIZ_QUESTIONS），因此在渲染处包 t(question.question) / t(option)。
 * 每题 note 只作源码注释、永不展示，标 l10n:ignore 不入词典。
 */
internal val En03GuideBEntries: List<Pair<String, String>> = listOf(

    // ===== 测验界面 =====
    "🏆 嘉豪认证考试" to "🏆 Jiahao Certification Exam",
    "第 {0} / {1} 题" to "Question {0} / {1}",
    "选定后立即锁定，无法修改" to "Locks immediately after selection and cannot be changed",
    "🎉 恭喜你" to "🎉 Congratulations",
    "恭喜你，你确实有几把刷子，凭此弹窗的截图可以找我，有奖励！" to
        "Congratulations — you clearly know your stuff. Screenshot this dialog and come find me for a reward!",
    "收下奖励" to "Claim the reward",
    "💔 很遗憾" to "💔 Unfortunately",
    "很遗憾嘉豪你没有通过测试，本软件不再为你提供任何服务" to
        "Sorry Jiahao, you failed the test. This app will no longer serve you.",
    "我知道了" to "I understand",

    // ===== 第 1 题：KV cache 估算 =====
    "本应用估算本地模型运行内存时，KV cache 部分的计算方式是？" to
        "How does this app estimate the KV cache portion of local model memory usage?",
    "参数量(十亿) × 每参数字节数 × 批大小" to "Parameters (billions) × bytes per parameter × batch size",
    "fp16(2字节) × 2 × 层数(layerCount) × 上下文长度 × KV头数 × 头维度" to
        "fp16 (2 bytes) × 2 × layerCount × context length × KV heads × head dim",
    "上下文长度 × hidden_size × 4字节 × 注意力头总数" to
        "Context length × hidden_size × 4 bytes × total attention heads",
    "层数 × 上下文长度 × 词表大小 × 2字节" to "Layers × context length × vocabulary size × 2 bytes",

    // ===== 第 2 题：Lookahead 投机解码 =====
    "Lookahead 投机解码在什么情况下才真正参与推理？" to
        "Under what condition does Lookahead speculative decoding actually take part in inference?",
    "仅当解析出的组合命中 CPU_OPTIMIZED 变体的认证组合（profile resolver 门禁通过）" to
        "Only when the resolved combination matches a certified CPU_OPTIMIZED variant (profile resolver gate passes)",
    "打开后端设为 OpenCL GPU 并选最高速度性能模式即生效" to
        "Turning on the OpenCL GPU backend and selecting the highest-speed performance mode is enough",
    "QNN NPU 后端的专属加速能力" to "An exclusive acceleration feature of the QNN NPU backend",
    "AUTO 后端在任何设备上都会自动启用" to "The AUTO backend enables it automatically on any device",

    // ===== 第 3 题：Anthropic 协议判定 =====
    "应用判定某个自定义接口走 Anthropic /v1/messages + x-api-key 协议的依据是？" to
        "What makes the app treat a custom endpoint as using the Anthropic /v1/messages + x-api-key protocol?",
    "请求头中已经带了 x-api-key" to "The request already carries an x-api-key header",
    "Base URL 的域名字段精确等于 api.anthropic.com" to
        "The Base URL's domain is exactly api.anthropic.com",
    "所填模型名以 claude 开头" to "The model name starts with claude",
    "URL 包含 anthropic/claude 字样，或路径以 /v1/messages 结尾" to
        "The URL contains anthropic/claude, or the path ends with /v1/messages",

    // ===== 第 4 题：思考过程字段 =====
    "云端 SSE 直连时，DeepSeek / Qwen 系模型的思考过程取自哪个字段？" to
        "When connecting to cloud SSE directly, which field carries the reasoning of DeepSeek / Qwen models?",
    "delta.content 里原始 <think> 标签的解析结果" to
        "The parsed raw <think> tags inside delta.content",
    "choices[0].delta.reasoning_content（部分端点为 reasoning），随后被包装成 <think> 展示" to
        "choices[0].delta.reasoning_content (reasoning on some endpoints), later wrapped as <think> for display",
    "message.reasoning_summary" to "message.reasoning_summary",
    "usage 里的 completion_tokens_details" to "completion_tokens_details in usage",

    // ===== 第 5 题：免费通道 Key 存放位置 =====
    "「免费对话」通道的真实 API Key 实际存放在哪里？" to
        "Where is the real API key of the \"Free chat\" channel actually stored?",
    "编译期写入 APK 的 BuildConfig 常量里" to "In BuildConfig constants compiled into the APK",
    "随应用内置的 assets 配置文件分发" to "In a config file shipped inside the app's assets",
    "Cloudflare Worker 的加密环境变量中，对话经 Worker 代理由服务端注入" to
        "In the Cloudflare Worker's encrypted environment variables; the server injects it while proxying the conversation",
    "Room 数据库的一张加密表里，首次联网时下载" to
        "In an encrypted Room table, downloaded on first connection",

    // ===== 第 6 题：问候投递条件（负向题）=====
    "角色主动问候真正投递的必要条件，不包括以下哪项？" to
        "Which of the following is NOT a requirement for a proactive character greeting to be delivered?",
    "15 分钟周期 Work 到期且 next_fire_at 门控通过" to
        "A 15-minute periodic Work fires and the next_fire_at gate passes",
    "处于白天时段（08:00–23:00）" to "It is within daytime hours (08:00–23:00)",
    "当日配额未耗尽（默认每天 3 条，可调 1–10）" to
        "The daily quota is not used up (3 per day by default, adjustable 1–10)",
    "设备必须连接 Wi-Fi 网络" to "The device must be connected to Wi-Fi",

    // ===== 第 7 题：群聊回复人数上限 =====
    "群聊中用户发出一条消息，最多由几名成员回复？" to
        "In a group chat, how many members can reply to a single user message at most?",
    "2 名（与自动互聊每轮人数相同）" to "2 members (same as the per-round count for auto chat)",
    "3 名（群成员数的三分之一）" to "3 members (one third of the group)",
    "4 名（@ 指定的成员必答并占用名额）" to
        "4 members (members mentioned with @ must answer and take up a slot)",
    "所有群成员依次作答" to "All group members answer in turn",

    // ===== 第 8 题：Seedance 中转站接口 =====
    "Seedance 视频生成在「中转站」模式下，应用自动调用的是哪组接口？" to
        "In relay-station mode for Seedance video generation, which set of endpoints does the app call automatically?",
    "/v1/media/generate 与 /v1/media/status" to "/v1/media/generate and /v1/media/status",
    "官方 /api/v3 下的任务创建/查询接口" to "The official /api/v3 task create/query endpoints",
    "/v1/video/create 与 /v1/video/poll" to "/v1/video/create and /v1/video/poll",
    "复用 /chat/completions 并附加 video 模态参数" to
        "Reuse /chat/completions with an extra video modality parameter",

    // ===== 第 9 题：世界书「蓝灯」状态 =====
    "世界书条目处于「蓝灯」状态意味着什么？" to
        "What does a lorebook entry in the \"blue light\" state mean?",
    "条目已被禁用但仍占用 token 预算" to "The entry is disabled but still consumes token budget",
    "只有主关键词和次级关键词同时命中才注入" to
        "It is injected only when both primary and secondary keywords match",
    "常驻条目：无需关键词，每次请求都注入 system prompt" to
        "A constant entry: no keywords needed, injected into the system prompt on every request",
    "仅在递归扫描第二遍时才会被注入" to
        "It is injected only on the second pass of recursive scanning",

    // ===== 第 10 题：DataStore 损坏处理 =====
    "偏好设置数据文件损坏（如国产 ROM 半写）时，应用的行为是？" to
        "What does the app do when the preferences file is corrupted (e.g. half-written on a domestic ROM)?",
    "弹窗引导用户导出日志手动修复" to
        "Show a dialog guiding the user to export logs and repair it manually",
    "逐段尝试恢复仍可读的键值对" to "Try to recover the still-readable key-value pairs piece by piece",
    "回退读取 Room 数据库里的一份备份" to "Fall back to a backup inside the Room database",
    "经 ReplaceFileCorruptionHandler 删除损坏文件并以默认值重建" to
        "Delete the corrupted file via ReplaceFileCorruptionHandler and rebuild it with defaults",

)
