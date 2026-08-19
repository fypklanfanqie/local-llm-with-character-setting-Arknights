package com.rhodesisland.terminal.conversationexport

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConversationTextExporter {

    fun render(document: ConversationExportDocument): String = buildString {
        appendLine("罗德岛通讯记录")
        appendLine("角色：${document.ownerName.ifBlank { "未知角色" }}")
        appendLine("会话：${document.title.ifBlank { "未命名会话" }}")
        appendLine("导出时间：${formatTimestamp(document.exportedAt)}")
        appendLine()

        document.messages.forEachIndexed { index, message ->
            appendLine("[${formatTimestamp(message.timestamp)}] ${message.senderName.ifBlank { "未知发言人" }}")
            appendLine(message.content.ifBlank { "（无文本内容）" })
            message.attachments.forEach(::appendLine)
            if (index != document.messages.lastIndex) appendLine()
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}
