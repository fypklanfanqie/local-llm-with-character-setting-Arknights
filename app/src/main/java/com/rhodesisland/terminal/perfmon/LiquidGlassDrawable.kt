package com.rhodesisland.terminal.perfmon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 液态玻璃 Drawable —— 工业风，边缘折射环 + 半透明玻璃本体。
 *
 * 窗口级 backdrop blur（[PerformanceOverlayManager] 负责 `FLAG_BLUR_BEHIND` + `setBlurBehindRadius`）
 * 模糊浮窗背后内容 → 着色底半透明让模糊透出 → 上层叠加折射边缘。
 *
 * 绘制栈（4 层）：
 *  ① 着色底：半透明深色渐变。backdropBlur 生效时较透（0.55）让模糊透出，否则较深（0.85）保可读。
 *  ② 玻璃本体：极淡半透明白色 fill（#28FFFFFF，~16%），frost 感。
 *  ③ 边缘折射内阴影环：粗 Stroke（~8dp）clip 在圆角内，形成沿边缘的软暗环 —— 模拟玻璃厚度在边缘吸收光线。
 *  ④ 边缘折射外描边：竖向渐变 Stroke，顶部亮冷白（~70%）→ 底部完全透明。
 *     物理：光从顶部进入、在边缘弯曲折射，到底部因厚度吸收几乎无出射光。纯冷白，无多色/RGB。
 *
 * 无动画、无虹彩、无 AGSL RuntimeShader。
 *
 * @param cornerRadiusPx 圆角半径
 * @param strokePx 边缘折射描边宽度
 */
class LiquidGlassDrawable(
    private val cornerRadiusPx: Float,
    private val strokePx: Float,
) : Drawable() {

    // === ① 着色底：半透明深色渐变，backdropBlur 生效时 alpha 降低让窗口模糊透出 ===
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 窗口级 backdrop blur 是否生效（true 时底色 alpha 0.55 让模糊透出，false 时 0.85 保可读）。 */
    var backdropBlurActive: Boolean = false
        set(value) {
            if (field != value) { field = value; rebuildTint(); invalidateSelf() }
        }

    // === ② 玻璃本体：极淡半透明白色 ===
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#28FFFFFF") // ~16% white
    }

    // === ③ 边缘折射内阴影环：粗 Stroke 模拟玻璃厚度 ===
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 宽度约为描边的 5 倍，形成软暗环
    }

    // === ④ 边缘折射外描边：非对称竖向渐变 ===
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }

    // === 几何 ===
    private val rect = RectF()
    private val edgePath = Path()

    init {
        rebuildTint()
    }

    private fun rebuildTint() {
        val baseAlpha = if (backdropBlurActive) 0x8C else 0xD9 // 0.55 / 0.85
        tintPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(
                Color.argb(baseAlpha, 0x18, 0x22, 0x3E),
                Color.argb((baseAlpha * 1.2f).toInt().coerceAtMost(255), 0x0C, 0x12, 0x24),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        edgePath.reset()
        edgePath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        // ③ 内阴影环宽度
        innerShadowPaint.strokeWidth = strokePx * 5f
        innerShadowPaint.shader = LinearGradient(
            0f, rect.top,
            0f, rect.bottom,
            intArrayOf(
                Color.parseColor("#00FFFFFF"), // 顶：几乎透明（光明处，阴影弱）
                Color.parseColor("#08FFFFFF"), // 25%：极微白（过渡）
                Color.parseColor("#0D000000"), // 50%：微暗（玻璃厚度开始吸收）
                Color.parseColor("#14000000"), // 75%：较暗
                Color.parseColor("#18000000"), // 底：最暗（完全吸收，阴影最强）
            ),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1.0f),
            Shader.TileMode.CLAMP,
        )

        // ④ 边缘折射外描边：竖向非对称渐变
        edgePaint.shader = LinearGradient(
            0f, rect.top,
            0f, rect.bottom,
            intArrayOf(
                Color.parseColor("#B3FAFBFF"), // 顶 70% 冷白：light entering
                Color.parseColor("#66FAFBFF"), // 25% 40% 冷白
                Color.parseColor("#1AF0F5FF"), // 50% 10% 冷青白
                Color.parseColor("#00FFFFFF"), // 75% 透明
                Color.parseColor("#00FFFFFF"), // 底 透明
            ),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1.0f),
            Shader.TileMode.CLAMP,
        )

        rebuildTint()
    }

    override fun draw(canvas: Canvas) {
        // ① 着色底
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, tintPaint)

        // ② 玻璃本体
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, bodyPaint)

        // ③ 边缘折射内阴影环 —— clip 到圆角内，只留内侧一半
        canvas.save()
        canvas.clipPath(edgePath)
        canvas.drawPath(edgePath, innerShadowPaint)
        canvas.restore()

        // ④ 边缘折射外描边
        canvas.drawPath(edgePath, edgePaint)
    }

    // 无动画
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Overrides framework-deprecated Drawable.getOpacity()", level = DeprecationLevel.WARNING)
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
