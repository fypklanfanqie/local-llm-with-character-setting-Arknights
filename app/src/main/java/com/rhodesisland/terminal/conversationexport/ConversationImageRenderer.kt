package com.rhodesisland.terminal.conversationexport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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
    private const val AVATAR_RING = 0xFF3A5377.toInt()
    private const val HEADER_HEIGHT = 180
    private const val MESSAGE_PADDING = EXPORT_BUBBLE_PADDING
    private const val BODY_TEXT_SIZE = 30f
    private const val BODY_LINE_HEIGHT = 42
    private const val META_LINE_HEIGHT = 32
    private const val ATTACHMENT_LINE_HEIGHT = 38

    // ===== 气泡几何（与 ConversationImageLayout 共享常量，严格以中轴镜像、居中）=====
    /** 角色气泡：左缘 = gutter + 头像 + 间距；宽 = EXPORT_BUBBLE_WIDTH。 */
    private val charBubbleLeft = EXPORT_GUTTER + EXPORT_AVATAR_SIZE + EXPORT_AVATAR_GAP
    private val charBubbleRight = charBubbleLeft + EXPORT_BUBBLE_WIDTH
    /** 用户气泡：右缘 = 宽 - gutter - 头像 - 间距；宽 = EXPORT_BUBBLE_WIDTH（与角色气泡镜像对称）。 */
    private val userBubbleRight = EXPORT_IMAGE_WIDTH_PX - EXPORT_GUTTER - EXPORT_AVATAR_SIZE - EXPORT_AVATAR_GAP
    private val userBubbleLeft = userBubbleRight - EXPORT_BUBBLE_WIDTH

    suspend fun render(plan: ImageRenderPlan, context: Context): List<ByteArray> = withContext(Dispatchers.Default) {
        when (plan.mode) {
            ConversationImageMode.LONG_IMAGE ->
                listOf(renderPage(context, plan.document, plan.document.messages, plan.totalHeight, 1, 1))
            ConversationImageMode.PAGINATED -> renderPages(context, plan.document)
        }
    }

    private fun renderPages(context: Context, document: ConversationExportDocument): List<ByteArray> {
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
                    used += ConversationImageLayout.messageHeight(part) + EXPORT_MESSAGE_GAP_PX
                }
            } else {
                if (page.isNotEmpty() && used + height > EXPORT_PAGE_HEIGHT_PX) {
                    pages += page.toList()
                    page.clear()
                    used = HEADER_HEIGHT
                }
                page += message
                used += height + EXPORT_MESSAGE_GAP_PX
            }
        }
        if (page.isNotEmpty()) pages += page.toList()
        return pages.mapIndexed { index, messages ->
            renderPage(context, document, messages, EXPORT_PAGE_HEIGHT_PX, index + 1, pages.size)
        }
    }

    private fun renderPage(
        context: Context,
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
            // 头像按路径去重解码（1:1 会话只有角色+博士两张，群聊每成员一张）。
            val avatarCache = HashMap<String, Bitmap?>()
            var y = HEADER_HEIGHT
            messages.forEach { message ->
                y = drawMessage(canvas, context, message, y, avatarCache)
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
        // 头部整体居中：标题/副标题/页码按各自宽度水平居中，金线横贯居中区。
        canvas.drawText("罗德岛通讯记录", (EXPORT_IMAGE_WIDTH_PX - title.measureText("罗德岛通讯记录")) / 2f, 62f, title)
        val subtitleText = "${document.ownerName} · ${document.title}"
        canvas.drawText(subtitleText, (EXPORT_IMAGE_WIDTH_PX - subtitle.measureText(subtitleText)) / 2f, 102f, subtitle)
        val pageText = "第 $pageNumber / $pageCount 页"
        canvas.drawText(pageText, (EXPORT_IMAGE_WIDTH_PX - subtitle.measureText(pageText)) / 2f, 138f, subtitle)
        // 分隔线：从 gutter 到右侧 gutter（本身关于中轴对称），顶部间距保持与标题节奏一致。
        canvas.drawRect(
            EXPORT_GUTTER.toFloat(), 156f, (EXPORT_IMAGE_WIDTH_PX - EXPORT_GUTTER).toFloat(), 160f,
            Paint().apply { color = GOLD },
        )
    }

    private fun drawMessage(
        canvas: Canvas,
        context: Context,
        message: ConversationExportMessage,
        top: Int,
        avatarCache: MutableMap<String, Bitmap?>,
    ): Int {
        val lines = ConversationImageLayout.wrap(
            message.content.ifBlank { "（无文本内容）" },
            EXPORT_BUBBLE_CONTENT_WIDTH,
            BODY_TEXT_SIZE,
        )
        val height = MESSAGE_PADDING * 2 + META_LINE_HEIGHT + lines.size * BODY_LINE_HEIGHT +
            message.attachments.size * ATTACHMENT_LINE_HEIGHT + 18
        val isUser = message.senderName == "博士" || message.senderName == "用户"
        val left = if (isUser) userBubbleLeft else charBubbleLeft
        val right = if (isUser) userBubbleRight else charBubbleRight

        val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (isUser) USER_BUBBLE else SURFACE }
        canvas.drawRoundRect(left.toFloat(), top.toFloat(), right.toFloat(), (top + height).toFloat(), 22f, 22f, bubble)

        // 头像：角色在左、博士在右，垂直对齐元信息行。
        val avatarCenterX = if (isUser) {
            (EXPORT_IMAGE_WIDTH_PX - EXPORT_GUTTER - EXPORT_AVATAR_SIZE / 2).toFloat()
        } else {
            (EXPORT_GUTTER + EXPORT_AVATAR_SIZE / 2).toFloat()
        }
        drawAvatar(canvas, context, message, avatarCenterX, (top + EXPORT_AVATAR_CENTER_Y).toFloat(), avatarCache)

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

    /** 画圆形头像：有图裁圆，无图/加载失败画 monogram（姓名首字）。 */
    private fun drawAvatar(
        canvas: Canvas,
        context: Context,
        message: ConversationExportMessage,
        centerX: Float,
        centerY: Float,
        avatarCache: MutableMap<String, Bitmap?>,
    ) {
        val radius = EXPORT_AVATAR_SIZE / 2f
        // 外圈
        canvas.drawCircle(
            centerX, centerY, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AVATAR_RING; style = Paint.Style.STROKE; strokeWidth = 3f },
        )
        val avatar = avatarFor(context, message.avatarPath, avatarCache)
        if (avatar != null) {
            val path = Path().apply { addCircle(centerX, centerY, radius - 2f, Path.Direction.CW) }
            val save = canvas.save()
            canvas.clipPath(path)
            val dest = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            canvas.drawBitmap(
                avatar,
                Rect(0, 0, EXPORT_AVATAR_SIZE, EXPORT_AVATAR_SIZE),
                dest,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            canvas.restoreToCount(save)
        } else {
            canvas.drawCircle(
                centerX, centerY, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = monogramColor(message.senderName) },
            )
            val initial = message.senderName.take(1).ifBlank { "?" }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 26f; isFakeBoldText = true
            }
            val textWidth = paint.measureText(initial)
            val baseline = centerY - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(initial, centerX - textWidth / 2, baseline, paint)
        }
    }

    /** 按路径解码头像并缩放裁成正方形（assets 相对路径 / file:// / 绝对路径）。 */
    private fun avatarFor(
        context: Context,
        path: String,
        cache: MutableMap<String, Bitmap?>,
    ): Bitmap? {
        if (path.isBlank()) return null
        cache[path]?.let { return it }
        val decoded = runCatching {
            val input = when {
                path.startsWith("file://") -> FileInputStream(File(path.removePrefix("file://")))
                path.startsWith("/") -> FileInputStream(File(path))
                else -> context.assets.open(path)
            }
            input.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        val bmp = decoded?.let { raw ->
            val side = minOf(raw.width, raw.height)
            val srcX = (raw.width - side) / 2
            val srcY = (raw.height - side) / 2
            val square = Bitmap.createBitmap(raw, srcX, srcY, side, side)
            if (square !== raw) raw.recycle()
            Bitmap.createScaledBitmap(square, EXPORT_AVATAR_SIZE, EXPORT_AVATAR_SIZE, true)
        }
        cache[path] = bmp
        return bmp
    }

    private fun monogramColor(name: String): Int {
        val palette = intArrayOf(
            0xFF3A5377.toInt(), 0xFF6B4F8A.toInt(), 0xFF7A5A3A.toInt(),
            0xFF3A6B6B.toInt(), 0xFF6B3A4A.toInt(),
        )
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }

    private fun ConversationExportMessage.splitForPage(maxHeight: Int): List<ConversationExportMessage> {
        val maxLines = ((maxHeight - MESSAGE_PADDING * 2 - META_LINE_HEIGHT - 18) / BODY_LINE_HEIGHT).coerceAtLeast(1)
        val lines = ConversationImageLayout.wrap(content.ifBlank { "（无文本内容）" }, EXPORT_BUBBLE_CONTENT_WIDTH, BODY_TEXT_SIZE)
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
