package com.rhodesisland.terminal.conversationexport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConversationImageRenderer {
    private const val BACKGROUND = 0xFF07111F.toInt()
    private const val GOLD = 0xFFD7B76A.toInt()
    private const val SURFACE = 0xFF12233A.toInt()
    private const val USER_BUBBLE = 0xFF3B2D13.toInt()
    private const val TEXT = 0xFFF1EEE7.toInt()
    private const val MUTED = 0xFFA9B3C2.toInt()
    private const val INSET = 48
    private const val HEADER_HEIGHT = 180
    private const val MESSAGE_PADDING = 20
    private const val BODY_TEXT_SIZE = 30f
    private const val BODY_LINE_HEIGHT = 42
    private const val META_LINE_HEIGHT = 32
    private const val ATTACHMENT_LINE_HEIGHT = 38

    suspend fun render(plan: ImageRenderPlan): List<ByteArray> = withContext(Dispatchers.Default) {
        when (plan.mode) {
            ConversationImageMode.LONG_IMAGE -> listOf(renderPage(plan.document, plan.document.messages, plan.totalHeight, 1, 1))
            ConversationImageMode.PAGINATED -> renderPages(plan.document)
        }
    }

    private fun renderPages(document: ConversationExportDocument): List<ByteArray> {
        val pages = mutableListOf<List<ConversationExportMessage>>()
        val page = mutableListOf<ConversationExportMessage>()
        var used = HEADER_HEIGHT
        document.messages.forEach { message ->
            val height = ConversationImageLayout.messageHeight(message)
            if (height > EXPORT_PAGE_HEIGHT_PX - HEADER_HEIGHT) {
                message.splitForPage(EXPORT_PAGE_HEIGHT_PX - HEADER_HEIGHT).forEach { part ->
                    if (used + ConversationImageLayout.messageHeight(part) > EXPORT_PAGE_HEIGHT_PX && page.isNotEmpty()) {
                        pages += page.toList()
                        page.clear()
                        used = HEADER_HEIGHT
                    }
                    page += part
                    used += ConversationImageLayout.messageHeight(part)
                }
            } else {
                if (page.isNotEmpty() && used + height > EXPORT_PAGE_HEIGHT_PX) {
                    pages += page.toList()
                    page.clear()
                    used = HEADER_HEIGHT
                }
                page += message
                used += height
            }
        }
        if (page.isNotEmpty()) pages += page.toList()
        return pages.mapIndexed { index, messages ->
            renderPage(document, messages, EXPORT_PAGE_HEIGHT_PX, index + 1, pages.size)
        }
    }

    private fun renderPage(
        document: ConversationExportDocument,
        messages: List<ConversationExportMessage>,
        height: Int,
        pageNumber: Int,
        pageCount: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(EXPORT_IMAGE_WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(BACKGROUND)
            drawHeader(canvas, document, pageNumber, pageCount)
            var y = HEADER_HEIGHT
            messages.forEach { message ->
                y = drawMessage(canvas, message, y)
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawHeader(canvas: Canvas, document: ConversationExportDocument, pageNumber: Int, pageCount: Int) {
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD; textSize = 42f; isFakeBoldText = true }
        val subtitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 24f }
        canvas.drawText("罗德岛通讯记录", INSET.toFloat(), 62f, title)
        canvas.drawText("${document.ownerName} · ${document.title}", INSET.toFloat(), 102f, subtitle)
        canvas.drawText("第 $pageNumber / $pageCount 页", INSET.toFloat(), 138f, subtitle)
        canvas.drawRect(INSET.toFloat(), 156f, (EXPORT_IMAGE_WIDTH_PX - INSET).toFloat(), 160f, Paint().apply { color = GOLD })
    }

    private fun drawMessage(canvas: Canvas, message: ConversationExportMessage, top: Int): Int {
        val lines = ConversationImageLayout.wrap(message.content.ifBlank { "（无文本内容）" }, EXPORT_IMAGE_WIDTH_PX - INSET * 2 - MESSAGE_PADDING * 2, BODY_TEXT_SIZE)
        val height = MESSAGE_PADDING * 2 + META_LINE_HEIGHT + lines.size * BODY_LINE_HEIGHT + message.attachments.size * ATTACHMENT_LINE_HEIGHT + 18
        val isUser = message.senderName == "博士" || message.senderName == "用户"
        val left = if (isUser) EXPORT_IMAGE_WIDTH_PX / 4 else INSET
        val right = if (isUser) EXPORT_IMAGE_WIDTH_PX - INSET else EXPORT_IMAGE_WIDTH_PX * 3 / 4
        val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (isUser) USER_BUBBLE else SURFACE }
        canvas.drawRoundRect(left.toFloat(), top.toFloat(), right.toFloat(), (top + height).toFloat(), 22f, 22f, bubble)

        val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD; textSize = 22f; isFakeBoldText = true }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = BODY_TEXT_SIZE }
        val attachment = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 22f }
        var y = top + MESSAGE_PADDING + 24
        canvas.drawText("${message.senderName} · ${formatTime(message.timestamp)}", (left + MESSAGE_PADDING).toFloat(), y.toFloat(), meta)
        y += META_LINE_HEIGHT
        lines.forEach { line ->
            canvas.drawText(line, (left + MESSAGE_PADDING).toFloat(), y.toFloat(), body)
            y += BODY_LINE_HEIGHT
        }
        message.attachments.forEach { label ->
            canvas.drawText(label, (left + MESSAGE_PADDING).toFloat(), y.toFloat(), attachment)
            y += ATTACHMENT_LINE_HEIGHT
        }
        return top + height + 14
    }

    private fun ConversationExportMessage.splitForPage(maxHeight: Int): List<ConversationExportMessage> {
        val maxLines = ((maxHeight - MESSAGE_PADDING * 2 - META_LINE_HEIGHT - 18) / BODY_LINE_HEIGHT).coerceAtLeast(1)
        val lines = ConversationImageLayout.wrap(content.ifBlank { "（无文本内容）" }, EXPORT_IMAGE_WIDTH_PX - INSET * 2 - MESSAGE_PADDING * 2, BODY_TEXT_SIZE)
        return lines.chunked(maxLines).mapIndexed { index, part ->
            copy(
                content = part.joinToString("\n"),
                attachments = if (index == 0) attachments else emptyList(),
            )
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}
