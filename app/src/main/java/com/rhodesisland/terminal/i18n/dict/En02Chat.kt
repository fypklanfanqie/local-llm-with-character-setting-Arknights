package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 02Chat：聊天页
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En02ChatEntries: List<Pair<String, String>> = listOf(

    // ===== 欢迎页 / 顶栏 =====
    "未选择角色" to "No character selected",
    "去角色页选择一位，开始对话吧" to "Pick a character on the Characters page to start chatting",
    "开始和 {0} 对话吧" to "Start chatting with {0}",
    "和我打个招呼" to "Say hello to me",
    "今天过得怎么样" to "How was your day?",
    "讲个故事给我听" to "Tell me a story",
    "展开操作栏" to "Expand controls",
    "收起操作栏" to "Collapse controls",
    "☁ 云端" to "☁ Cloud",
    "云端" to "Cloud",
    "本地" to "Local",
    "会话记录" to "Conversations",
    "语音语言：{0}" to "Voice language: {0}",
    "语音语言已切换至{0}" to "Voice language switched to {0}",
    "中文" to "Chinese",
    "日本語" to "Japanese",
    "自动视频" to "Auto video",
    "自动视频：仅云端可用" to "Auto video: cloud only",
    "自动视频仅云端可用" to "Auto video is available on cloud only",
    "特殊邂逅中不可生成视频" to "Video generation is unavailable during a special encounter",

    // ===== 消息气泡 / 操作胶囊 =====
    "我" to "Me",
    "复制" to "Copy",
    "已复制" to "Copied",
    "✓ 已复制" to "✓ Copied",
    "朗读" to "Read aloud",
    "折叠" to "Collapse",
    "展开" to "Expand",
    "思考中…" to "Thinking…",
    "思考过程" to "Thinking process",
    "已停止（已保留部分输出）" to "Stopped (partial output kept)",
    "已停止（尚未生成最终答案）" to "Stopped (no final answer generated)",

    // ===== 输入栏 =====
    "移除" to "Remove",
    "赠送礼物" to "Send a gift",
    "添加图片" to "Add image",
    "添加文件" to "Add file",
    "输入消息…" to "Type a message…",
    "正在停止" to "Stopping",
    "停止生成" to "Stop generating",

    // ===== 会话抽屉 =====
    "对话记录" to "Conversation history",
    "导出记录" to "Export",
    "导出中…" to "Exporting…",
    "新建对话" to "New conversation",
    "新对话" to "New conversation",
    "暂无对话，点右上角「新建对话」开始" to "No conversations yet. Tap “New conversation” in the top right to start",
    "重命名对话" to "Rename conversation",
    "删除对话" to "Delete conversation",
    "确定删除「{0}」？该对话的全部消息将被清除。" to "Delete “{0}”? All messages in this conversation will be erased.",
    "确定" to "OK",
    "重命名" to "Rename",

    // ===== 全屏播放 =====
    "关闭全屏" to "Exit full screen",

    // ===== Toast / 导出反馈 =====
    "已赠送 {0}，好感度 +{1}" to "Gifted {0}, affinity +{1}",
    "该礼物库存不足" to "Not enough of this gift in stock",
    "礼物已不存在" to "This gift no longer exists",
    "聊天记录已导出" to "Chat log exported",
    "聊天记录图片已导出" to "Chat log image exported",
    "已导出 {0} 张聊天记录图片" to "Exported {0} chat log images",
    "导出失败：{0}" to "Export failed: {0}",
    "图片生成失败：{0}" to "Image generation failed: {0}",
    "视频已保存到所选位置" to "Video saved to the selected location",
    "视频已保存到相册 Movies/RhodesIslandTerminal" to "Video saved to gallery Movies/RhodesIslandTerminal",
    "保存失败：{0}" to "Save failed: {0}",
    "视频文件尚未就绪" to "The video file is not ready yet",

    // ===== 导出对话框 =====
    "选择要导出的对话" to "Select a conversation to export",
    " · 当前对话" to " · Current conversation",
    "选择导出格式" to "Select export format",
    "TXT（完整记录）" to "TXT (full transcript)",
    "图片（PNG）" to "Image (PNG)",
    "选择图片导出方式" to "Select image export mode",
    "自动分页多张图" to "Auto-paginated multiple images",
    "一张超长图" to "One extra-long image",
    "超长会话建议使用分页模式，避免生成失败。" to "For very long conversations, use paginated mode to avoid generation failures.",

    // ===== ViewModel 错误 / 状态 =====
    "角色数据加载失败：{0}" to "Failed to load character data: {0}",
    "会话数据加载失败：{0}" to "Failed to load conversation data: {0}",
    "聊天记录加载失败：{0}" to "Failed to load chat history: {0}",
    "会话列表加载失败：{0}" to "Failed to load conversation list: {0}",
    "Provider 切换失败：{0}" to "Failed to switch provider: {0}",
    "特殊邂逅的回忆会永久保存，无法删除" to "Special encounter memories are kept permanently and cannot be deleted",
    "未配置 Seedance API Key，请先到「设置」中配置后再开启自动视频" to "Seedance API key is not configured. Set it in Settings before enabling auto video",
    "该角色未设置立绘图片，请先到角色页配置后再开启自动视频" to "This character has no illustration image. Configure one on the character page before enabling auto video",
    "特殊邂逅中仅支持云端对话" to "Only cloud chat is supported during a special encounter",
    "会话尚未就绪，请稍候再试" to "The conversation is not ready yet. Please try again shortly",
    "角色不存在" to "Character not found",
    "生成中…" to "Generating…",
    "已停止" to "Stopped",
    "已停止（保留部分输出）" to "Stopped (partial output retained)",
    "生成超时" to "Generation timed out",
    "达到生成上限" to "Generation limit reached",
    "完成: {0} tokens" to "Done: {0} tokens",
    "出错: {0}" to "Error: {0}",
    "当前模型不支持图片识别，请切换多模态模型（如 GPT-4o / Qwen-VL）" to "The current model cannot read images. Switch to a multimodal model (e.g. GPT-4o / Qwen-VL)",
    "礼物已送出，感谢回复生成失败：{0}" to "The gift was sent, but the thank-you reply failed: {0}",
    "TTS 失败：{0}" to "TTS failed: {0}",
    "日语翻译需要配置云端对话 API Key" to "Japanese translation requires a cloud chat API key",
    "日语翻译失败，请检查云端对话 API 配置后重试" to "Japanese translation failed. Check the cloud chat API configuration and try again",

)
