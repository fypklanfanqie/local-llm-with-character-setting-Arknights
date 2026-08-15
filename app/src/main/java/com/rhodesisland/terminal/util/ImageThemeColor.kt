package com.rhodesisland.terminal.util

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 从立绘图片提取「主题色」：以饱和度加权的色相主方向为基准（彩色区域比灰/黑/白区域贡献更大），
 * 再把饱和度 / 明度收敛到中高区间，得到稳定、可读、贴合立绘的强调色。
 *
 * 适用于通讯页按钮与 dock 栏的主题化着色；无立绘 / 提取失败时返回 null（回退默认配色）。
 */
fun Bitmap.extractThemeColor(): Color? {
    if (width <= 0 || height <= 0) return null
    // 网格采样，避免大图全量遍历；~48x48 采样点已足够稳定。
    val step = max(1, max(width, height) / 48)
    var sinH = 0.0
    var cosH = 0.0
    var satSum = 0.0
    var lightSum = 0.0
    var weightSum = 0.0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val px = getPixel(x, y)
            val alpha = (px ushr 24) and 0xFF
            if (alpha > 40) { // 跳过透明像素（透明部分多为无效底）
                val r = ((px ushr 16) and 0xFF) / 255f
                val g = ((px ushr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                val (h, s, l) = rgbToHsl(r, g, b)
                val w = s.toDouble() // 饱和度加权：彩色区域主导主题色
                sinH += sin(h * 2.0 * PI) * w
                cosH += cos(h * 2.0 * PI) * w
                satSum += s * w
                lightSum += l * w
                weightSum += w
            }
            x += step
        }
        y += step
    }
    if (weightSum < 1e-6) return null
    val hue = ((atan2(sinH, cosH) / (2.0 * PI)) + 1.0) % 1.0 // 归一化到 [0,1)
    val sat = (satSum / weightSum).toFloat().coerceIn(0.45f, 0.8f)
    val light = (lightSum / weightSum).toFloat().coerceIn(0.38f, 0.55f)
    val (r, g, b) = hslToRgb(hue.toFloat(), sat, light)
    return Color(r, g, b)
}

/**
 * 加载图片并提取主题色；空 URL / 加载失败返回 null。内部已切 IO 调度。
 * Coil 解码到小尺寸（~96px），速度快且对内存友好。
 */
suspend fun loadThemeColor(context: Context, url: String): Color? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    val drawable = runCatching {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .size(96, 96)
                .allowHardware(false)
                .build(),
        ).drawable
    }.getOrNull() ?: return@withContext null
    val bitmap = runCatching { drawable.toBitmap(96, 96, Bitmap.Config.ARGB_8888) }.getOrNull()
        ?: return@withContext null
    bitmap.extractThemeColor()
}

/** 依据背景明暗返回可读前景色（黑 / 白）。 */
fun Color.readableForeground(): Color =
    if (luminance() > 0.5f) Color.Black else Color.White

/** 玻璃按钮内容用「亮化主题色」：向白色混合，保证在深色立绘 scrim 上可读。 */
fun Color.lightenedOnImage(amount: Float = 0.55f): Color = lerp(this, Color.White, amount)

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val cMax = max(r, g)
    val max = max(cMax, b)
    val cMin = min(r, g)
    val min = min(cMin, b)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    var h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    h /= 6f
    return Triple(h, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
    val c = (1f - abs(2f * l - 1f)) * s
    val hh = ((h % 1f + 1f) % 1f) * 6f
    val x = c * (1f - abs(hh % 2f - 1f))
    val (r1, g1, b1) = when {
        hh < 1f -> Triple(c, x, 0f)
        hh < 2f -> Triple(x, c, 0f)
        hh < 3f -> Triple(0f, c, x)
        hh < 4f -> Triple(0f, x, c)
        hh < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Triple(r1 + m, g1 + m, b1 + m)
}
