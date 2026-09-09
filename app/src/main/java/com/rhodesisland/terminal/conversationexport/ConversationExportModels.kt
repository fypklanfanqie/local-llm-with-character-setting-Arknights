package com.rhodesisland.terminal.conversationexport

import com.rhodesisland.terminal.i18n.L10nRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val EXPORT_IMAGE_WIDTH_PX = 1080
const val EXPORT_PAGE_HEIGHT_PX = 1920
/**
 * 长图最大高度（px）。16384 ≈ 1080×16384×4 ≈ 67MB ARGB_8888 主 Bitmap，
 * 叠加背景/头像/PNG 输出缓冲已贴近低内存设备 largeHeap 上限——超限直接引导分页导出。
 */
const val EXPORT_LONG_IMAGE_MAX_HEIGHT_PX = 16_384

enum class ConversationExportFormat { TEXT, IMAGE }

enum class ConversationImageMode { PAGINATED, LONG_IMAGE }

data class ConversationExportDocument(
    val title: String,
    val ownerName: String,
    val createdAt: Long,
    val exportedAt: Long,
    val messages: List<ConversationExportMessage>,
    /** 聊天背景源（自定义=绝对路径，内置=assets 路径；空=无背景用纯色底）。 */
    val backgroundPath: String = "",
)

data class ConversationExportMessage(
    val timestamp: Long,
    val senderName: String,
    val content: String,
    val attachments: List<String> = emptyList(),
    /** 发送者头像源（内置=assets 相对路径，自定义/用户=file 绝对路径；空=无头像画 monogram）。 */
    val avatarPath: String = "",
    /**
     * 是否是用户（博士）发言：决定气泡左右与头像位置。
     *
     * 用显式布尔而不是比较 [senderName]：界面语言切换后 senderName 会被翻译
     * （博士 → Doctor），拿中文名比较会失效。
     */
    val isUser: Boolean = false,
)

fun suggestedExportBaseName(ownerName: String, title: String, exportedAt: Long): String {
    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(exportedAt))
    // 先取翻译再拼接：不在字符串模板里嵌套 t()（否则跨语言语序无法调整，覆盖度脚本也看不见）
    val logName = L10nRuntime.t("罗德岛通讯记录")
    val owner = sanitizeExportName(ownerName, L10nRuntime.t("未知角色"))
    val docTitle = sanitizeExportName(title, L10nRuntime.t("未命名会话"))
    return "${logName}_${owner}_${docTitle}_$time"
}

private fun sanitizeExportName(value: String, fallback: String): String {
    val sanitized = value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .replace(Regex("\\s+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    return sanitized.ifBlank { fallback }
}
