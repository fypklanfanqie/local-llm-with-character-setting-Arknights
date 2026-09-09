package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 09System：后台任务/通知/Provider/工具与错误提示
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En09SystemEntries: List<Pair<String, String>> = listOf(

    // ===== 异常 → 用户文案（util/UserErrorMessage）=====
    "云端 API Key 无效或未授权" to "Cloud API key is invalid or unauthorized",
    "云端服务暂时繁忙，请稍后重试" to "The cloud service is busy; please try again later",
    "云端服务暂时不可用，请稍后重试" to "The cloud service is temporarily unavailable; please try again later",
    "云端请求失败，请检查配置后重试" to "Cloud request failed; check your configuration and try again",
    "网络连接失败，请检查网络后重试" to "Network connection failed; check your network and try again",
    "云端未返回有效内容，请稍后重试" to "The cloud returned no valid content; please try again later",
    "设备可用内存不足，请关闭其他应用或改用更小模型" to
        "Not enough free memory; close other apps or switch to a smaller model",
    "对话内容过长，请缩短消息后重试" to "The conversation is too long; shorten your message and try again",
    "请先在模型管理页下载并选择本地模型" to
        "Download and select a local model on the Model manager page first",
    "本地模型文件未找到，请重新下载模型" to "Local model file not found; download the model again",
    "本地模型校验失败，请重新下载模型" to "Local model verification failed; download the model again",
    "本地推理后端暂不可用，请稍后重试" to
        "The local inference backend is temporarily unavailable; please try again later",
    "操作失败，请稍后重试" to "Operation failed; please try again later",

    // ===== Seedance 视频错误 =====
    "视频内容未通过审核，请修改描述后重试" to
        "The video content failed moderation; adjust the description and try again",
    "视频服务额度不足或已达上限" to "The video service quota is insufficient or has been reached",
    "Seedance API Key 无效或未授权" to "Seedance API key is invalid or unauthorized",
    "视频生成参数不合法，请检查设置" to "Invalid video generation parameters; check your settings",
    "Seedance 服务地址或路径不正确" to "The Seedance service address or path is incorrect",
    "视频模型或任务不存在" to "The video model or task does not exist",
    "视频模型尚未开通" to "The video model is not enabled yet",
    "视频服务暂时繁忙，请稍后重试" to "The video service is busy; please try again later",
    "网络异常，暂时无法确认视频任务状态" to
        "Network error; the video task status cannot be confirmed right now",
    "视频生成失败，请稍后重试" to "Video generation failed; please try again later",

    // ===== 通知（notification）=====
    "角色主动发来的消息提醒" to "Notifications for messages a character sends proactively",
    "问候生成" to "Greeting generation",
    "生成角色主动消息时的保活通知" to "Keep-alive notification while generating a proactive character message",
    "正在生成角色消息…" to "Generating a character message…",
    "群聊成员主动发言与提问提醒" to "Alerts when group members speak up or ask questions",
    "群聊生成" to "Group chat generation",
    "生成群聊发言时的保活通知" to "Keep-alive notification while generating group messages",
    "群聊成员正在聊天…" to "Group members are chatting…",

    // ===== 前台服务（service）=====
    "本地 AI" to "Local AI",
    "本地 AI 推理中…" to "Local AI is inferring…",
    "正在使用 {0} 生成回复" to "Generating a reply with {0}",
    "本地推理" to "Local inference",
    "本地 AI 推理进行时的保活通知" to "Keep-alive notification while local AI inference runs",

    // ===== 性能浮窗（perfmon）=====
    "⚡ 性能监控" to "⚡ Performance monitor",
    "🚀 Token 速率" to "🚀 Token rate",
    "📈 大核频率" to "📈 Big-core frequency",
    "🌡️ 温度" to "🌡️ Temperature",
    "💾 内存" to "💾 Memory",
    "等待推理..." to "Waiting for inference...",
    "引擎: {0}" to "Engine: {0}",
    "CPU 推理" to "CPU inference",

    // ===== 相对时间 / 存储用量（util）=====
    "刚刚" to "Just now",
    "{0}分钟前" to "{0} min ago",
    "{0}小时前" to "{0} h ago",
    "昨天 {0}:{1}" to "Yesterday {0}:{1}",
    "图片与临时缓存" to "Images and temp cache",
    "Coil 图片缓存、网络缓存等临时文件，可随时清除" to
        "Temporary files such as Coil image and network caches; safe to clear anytime",
    "已生成并下载到本地的视频文件与任务快照" to
        "Generated videos downloaded to this device plus task snapshots",
    "从相册导入的聊天背景图片" to "Chat background images imported from your gallery",
    "自定义角色立绘" to "Custom character illustrations",
    "自定义角色从相册导入的立绘图片" to
        "Illustrations imported from your gallery for custom characters",
    "聊天记录（数据库）" to "Chat history (database)",
    "全部单聊/群聊消息与 Seedance 任务记录" to
        "All direct and group messages plus Seedance task records",

    // ===== ROM 名称（util/RomDetector）=====
    "MIUI（小米）" to "MIUI (Xiaomi)",
    "HyperOS（小米）" to "HyperOS (Xiaomi)",
    "EMUI（华为）" to "EMUI (Huawei)",
    "HarmonyOS（华为）" to "HarmonyOS (Huawei)",
    "MagicOS（荣耀）" to "MagicOS (Honor)",
    "ColorOS（OPPO/一加/realme）" to "ColorOS (OPPO/OnePlus/realme)",
    "Flyme（魅族）" to "Flyme (Meizu)",
    "One UI（三星）" to "One UI (Samsung)",
    "标准 Android" to "Standard Android",

    // ===== 语音合成（tts）=====
    "请先填写该角色当前语言的 speaker_id" to
        "Fill in the speaker_id for this character's current language first",
    "语音服务返回为空" to "The voice service returned nothing",
    "语音服务请求失败，请检查配置后重试" to
        "Voice service request failed; check your configuration and try again",
    "语音服务返回格式不受支持" to "The voice service returned an unsupported format",
    "语音服务返回格式异常" to "The voice service returned a malformed response",
    "语音数据异常" to "The voice data is invalid",
    "语音服务暂时无法合成，请稍后重试" to
        "The voice service can't synthesize right now; please try again later",
    "语音服务未返回音频，请稍后重试" to
        "The voice service returned no audio; please try again later",
    "系统语音引擎启动失败：请到系统设置 → 更多设置 → 无障碍 → 文字转语音（TTS）输出中，确认已安装并选中一个语音引擎（如小爱同学/讯飞），必要时先下载语音数据" to
        "Failed to start the system speech engine: go to Settings → More settings → Accessibility → Text-to-speech output, make sure a speech engine is installed and selected (e.g. XiaoAi / iFlytek), and download voice data first if needed",
    "手机系统语音不支持日语，请切换中文，或在设置中改用云端引擎" to
        "The phone's system speech does not support Japanese; switch to Chinese or use the cloud engine in Settings",

    // ===== 后台任务 / 本地 Provider =====
    "生成的朋友圈文案为空" to "The generated Moments caption is empty",
    "(本地模型未生成回复)" to "(The local model produced no reply)",

)
