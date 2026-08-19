package com.rhodesisland.terminal.conversationexport

class LongImageTooTallException(val height: Int) : IllegalStateException(
    "当前对话过长，无法安全生成单张长图；请改用“自动分页多张图”或 TXT（预计高度 ${height}px）",
)

data class ImageRenderPlan(
    val document: ConversationExportDocument,
    val mode: ConversationImageMode,
    val pageCount: Int,
    val totalHeight: Int,
)

object ConversationImageLayout {
    private const val CONTENT_WIDTH = EXPORT_IMAGE_WIDTH_PX - 96
    private const val HEADER_HEIGHT = 180
    private const val MESSAGE_PADDING = 40
    private const val META_LINE_HEIGHT = 32
    private const val BODY_LINE_HEIGHT = 42
    private const val ATTACHMENT_LINE_HEIGHT = 38

    fun plan(document: ConversationExportDocument, mode: ConversationImageMode): ImageRenderPlan {
        val totalHeight = HEADER_HEIGHT + document.messages.sumOf(::messageHeight) + 48
        if (mode == ConversationImageMode.LONG_IMAGE && totalHeight > EXPORT_LONG_IMAGE_MAX_HEIGHT_PX) {
            throw LongImageTooTallException(totalHeight)
        }
        val pageCount = if (mode == ConversationImageMode.PAGINATED) {
            estimatePageCount(document)
        } else {
            1
        }
        return ImageRenderPlan(document, mode, pageCount, totalHeight)
    }

    internal fun messageHeight(message: ConversationExportMessage): Int {
        val bodyLines = textLineCount(message.content.ifBlank { "（无文本内容）" }, CONTENT_WIDTH, 30f).coerceAtLeast(1)
        return MESSAGE_PADDING * 2 + META_LINE_HEIGHT + bodyLines * BODY_LINE_HEIGHT +
            message.attachments.size * ATTACHMENT_LINE_HEIGHT + 18
    }

    internal fun textLineCount(text: String, width: Int, textSize: Float): Int {
        val averageCharWidth = (textSize * 0.92f).coerceAtLeast(1f)
        val charactersPerLine = (width / averageCharWidth).toInt().coerceAtLeast(1)
        return text.split('\n').sumOf { paragraph ->
            if (paragraph.isEmpty()) 1 else (paragraph.length + charactersPerLine - 1) / charactersPerLine
        }
    }

    private fun estimatePageCount(document: ConversationExportDocument): Int {
        var pages = 1
        var used = HEADER_HEIGHT
        document.messages.forEach { message ->
            val height = messageHeight(message)
            if (used + height > EXPORT_PAGE_HEIGHT_PX && used > HEADER_HEIGHT) {
                pages++
                used = HEADER_HEIGHT
            }
            used += height
        }
        return pages
    }
}
