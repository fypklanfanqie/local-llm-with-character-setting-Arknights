package com.rhodesisland.terminal.conversationexport

import com.rhodesisland.terminal.i18n.L10nRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConversationTextExporter {

    fun render(document: ConversationExportDocument): String = buildString {
        appendLine(L10nRuntime.t("罗德岛通讯记录"))
        appendLine(L10nRuntime.format("角色：{0}", document.ownerName.ifBlank { L10nRuntime.t("未知角色") }))
        appendLine(L10nRuntime.format("会话：{0}", document.title.ifBlank { L10nRuntime.t("未命名会话") }))
        appendLine(L10nRuntime.format("导出时间：{0}", formatTimestamp(document.exportedAt)))
        appendLine()

        document.messages.forEachIndexed { index, message ->
            appendLine(
                "[${formatTimestamp(message.timestamp)}] " +
                    message.senderName.ifBlank { L10nRuntime.t("未知发言人") },
            )
            appendLine(message.content.ifBlank { L10nRuntime.t("（无文本内容）") })
            message.attachments.forEach(::appendLine)
            if (index != document.messages.lastIndex) appendLine()
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}
