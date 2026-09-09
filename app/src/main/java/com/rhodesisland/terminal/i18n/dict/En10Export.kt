package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 10Export：会话导出
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En10ExportEntries: List<Pair<String, String>> = listOf(

    // ===== 导出文档（TXT / PNG）=====
    "罗德岛通讯记录" to "Rhodes Island Log",
    "角色：{0}" to "Character: {0}",
    "会话：{0}" to "Conversation: {0}",
    "导出时间：{0}" to "Exported: {0}",
    "第 {0} / {1} 页" to "Page {0} / {1}",
    "未知角色" to "Unknown character",
    "未命名会话" to "Untitled conversation",
    "未知发言人" to "Unknown speaker",
    "（无文本内容）" to "(No text content)",
    "博士" to "Doctor",
    "助手" to "Assistant",
    "群聊成员" to "Group member",
    "用户" to "User",
    "图片附件（{0} 张）" to "{0} image attachment(s)",
    "附件：{0}" to "Attachment: {0}",
    "未命名附件" to "Untitled attachment",

    // ===== 导出错误 =====
    "该会话已不存在" to "This conversation no longer exists",
    "没有可导出的已保存消息" to "No saved messages to export",
    "无法访问所选目录" to "Cannot access the selected folder",
    "无法在所选目录创建图片文件" to "Cannot create the image file in the selected folder",
    "无法写入图片文件" to "Cannot write the image file",
    "无法写入所选文件" to "Cannot write to the selected file",
    "当前对话过长，无法安全生成单张长图；请改用“自动分页多张图”或 TXT（预计高度 {0}px）" to
        "This conversation is too long to render as a single image; use \"auto-paginated images\" or TXT instead (estimated height {0}px)",

    // ===== 语音合成 / 播放（manager）=====
    "没有可朗读的文本" to "Nothing to read aloud",
    "播放失败，请检查音频资源或网络" to "Playback failed. Check the audio source or your network.",
    "播放列表为空" to "The playlist is empty",
    "音频资源缺失：{0} 暂无可用音频源，请在音乐页导入本地文件或检查网络" to
        "Audio unavailable: no playable source for {0}. Import a local file on the Music page or check your network.",
    "音频加载失败：{0}" to "Failed to load audio: {0}",

)
