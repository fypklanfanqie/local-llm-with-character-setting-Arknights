package com.rhodesisland.terminal.conversationexport

const val EXPORT_MESSAGE_GAP_PX = 14

// ===== 头像 + 气泡占宽（渲染器与布局共用，保证换行/高度一致）=====
/** 导出图左右留白（gutter）。 */
const val EXPORT_GUTTER = 48
/** 头像圆直径。 */
const val EXPORT_AVATAR_SIZE = 56
/** 头像与气泡间距。 */
const val EXPORT_AVATAR_GAP = 12
/** 气泡内边距（左右各）。 */
const val EXPORT_BUBBLE_PADDING = 20
/** 气泡占宽（角色/用户对称）：右缘 2/3 宽 - 左 gutter - 头像 - 间距，整体更居中。 */
const val EXPORT_BUBBLE_WIDTH = EXPORT_IMAGE_WIDTH_PX * 2 / 3 - EXPORT_GUTTER - EXPORT_AVATAR_SIZE - EXPORT_AVATAR_GAP
/** 气泡内文本换行宽度。 */
const val EXPORT_BUBBLE_CONTENT_WIDTH = EXPORT_BUBBLE_WIDTH - EXPORT_BUBBLE_PADDING * 2
/** 气泡头像（圆形）垂直中心相对消息顶部的偏移：对齐元信息行。 */
const val EXPORT_AVATAR_CENTER_Y = 44

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
    private const val CONTENT_WIDTH = EXPORT_BUBBLE_CONTENT_WIDTH
    private const val HEADER_HEIGHT = 180
    private const val MESSAGE_PADDING = EXPORT_BUBBLE_PADDING
    private const val META_LINE_HEIGHT = 32
    private const val BODY_LINE_HEIGHT = 42
    private const val ATTACHMENT_LINE_HEIGHT = 38

    fun plan(document: ConversationExportDocument, mode: ConversationImageMode): ImageRenderPlan {
        val totalHeight = HEADER_HEIGHT + document.messages.sumOf { message -> messageHeight(message) + EXPORT_MESSAGE_GAP_PX } + 48
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

    internal fun wrap(text: String, width: Int, textSize: Float): List<String> {
        // 以 1.2em 估算每个字符：略保守于 CJK 宽字，保证 Canvas 实绘不会比布局更宽而裁切。
        val charactersPerLine = (width / (textSize * 1.2f)).toInt().coerceAtLeast(1)
        return buildList {
            text.split('\n').forEach { paragraph ->
                if (paragraph.isEmpty()) {
                    add("")
                } else {
                    paragraph.chunked(charactersPerLine).forEach(::add)
                }
            }
        }
    }

    internal fun textLineCount(text: String, width: Int, textSize: Float): Int =
        wrap(text, width, textSize).size

    private fun estimatePageCount(document: ConversationExportDocument): Int {
        var pages = 1
        var used = HEADER_HEIGHT
        document.messages.forEach { message ->
            val height = messageHeight(message)
            if (used + height > EXPORT_PAGE_HEIGHT_PX && used > HEADER_HEIGHT) {
                pages++
                used = HEADER_HEIGHT
            }
            used += height + EXPORT_MESSAGE_GAP_PX
        }
        return pages
    }
}
