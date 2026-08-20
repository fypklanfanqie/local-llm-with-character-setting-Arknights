package com.rhodesisland.terminal.conversationexport

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val EXPORT_IMAGE_WIDTH_PX = 1080
const val EXPORT_PAGE_HEIGHT_PX = 1920
const val EXPORT_LONG_IMAGE_MAX_HEIGHT_PX = 16_384

enum class ConversationExportFormat { TEXT, IMAGE }

enum class ConversationImageMode { PAGINATED, LONG_IMAGE }

data class ConversationExportDocument(
    val title: String,
    val ownerName: String,
    val createdAt: Long,
    val exportedAt: Long,
    val messages: List<ConversationExportMessage>,
)

data class ConversationExportMessage(
    val timestamp: Long,
    val senderName: String,
    val content: String,
    val attachments: List<String> = emptyList(),
    /** 发送者头像源（内置=assets 相对路径，自定义/用户=file 绝对路径；空=无头像画 monogram）。 */
    val avatarPath: String = "",
)

fun suggestedExportBaseName(ownerName: String, title: String, exportedAt: Long): String {
    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(exportedAt))
    return "罗德岛通讯记录_${sanitizeExportName(ownerName, "未知角色")}_${sanitizeExportName(title, "未命名会话")}_$time"
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
