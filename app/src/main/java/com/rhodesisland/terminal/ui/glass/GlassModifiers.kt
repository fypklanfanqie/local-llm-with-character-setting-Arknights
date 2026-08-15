package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalGlassTokens
import kotlin.math.roundToInt

/**
 * 毛玻璃叠层：真实背景模糊（采样 [BackdropState] 已预模糊的极光背板）+ 半透明主体填充 + 顶部高光渐变
 * + 内缘描边 + 彩色弥散阴影，裁剪到 [shape]。
 *
 * 当 [LocalBackdropState] 无效、位图未就绪或处于不同窗口（Dialog/Sheet）时，自动回退到纯半透明叠层
 * （[fillBrush] 优先，否则 [tint] ?: tokens.surfaceTint），全 API 24+ 可用、性能稳定。
 *
 * @param blurRadius 语义化模糊半径（背板为全局预模糊，此参数暂作视觉意图标注，供后续按面板差异化时使用）。
 * @param fillBrush 液态玻璃的渐变填充（如输入胶囊 / 欢迎药丸的「液体充盈」渐变）。
 */
fun Modifier.frostedGlass(
    shape: Shape = GlassShapes.card,
    tint: Color? = null,
    highlight: Boolean = true,
    shadowElevation: Dp = 10.dp,
    borderWidth: Dp = 1.dp,
    blurRadius: Dp = 22.dp,
    fillBrush: Brush? = null,
): Modifier = composed {
    val tokens = LocalGlassTokens.current
    val fill = tint ?: tokens.surfaceTint
    val backdrop = LocalBackdropState.current
    val ok = backdrop != null && backdrop.isSameWindow(LocalView.current)
    var bounds by remember { mutableStateOf<Rect?>(null) }
    this
        .shadow(
            shadowElevation,
            shape,
            clip = true,
            ambientColor = tokens.ambientShadow,
            spotColor = tokens.spotShadow,
        )
        .clip(shape)
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .drawWithContent {
            val bmp = backdrop?.bitmap
            var drewBackdrop = false
            if (ok && bmp != null && bmp.width > 0 && bounds != null) {
                val b = bounds!!
                val s = backdrop.downscale
                // 面板在根坐标系下的区域 → 映射到背板位图，并裁剪到位图范围内（面板滚动出屏时安全）。
                val cL = (b.left / s).coerceIn(0f, bmp.width.toFloat())
                val cT = (b.top / s).coerceIn(0f, bmp.height.toFloat())
                val cR = (b.right / s).coerceIn(0f, bmp.width.toFloat())
                val cB = (b.bottom / s).coerceIn(0f, bmp.height.toFloat())
                if (cR - cL > 0f && cB - cT > 0f) {
                    drawImage(
                        image = bmp,
                        srcOffset = IntOffset(cL.roundToInt(), cT.roundToInt()),
                        srcSize = IntSize((cR - cL).roundToInt(), (cB - cT).roundToInt()),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                    drewBackdrop = true
                }
            }
            if (drewBackdrop) {
                // 白霜 veil：让背板颜色透出时仍保持朦胧与通透。
                drawRect(fill)
            } else if (fillBrush != null) {
                // 背板不可用回退：液态渐变填充（如输入胶囊的液体充盈）。
                drawRect(fillBrush)
            } else {
                drawRect(fill)
            }
            drawContent()
            if (highlight) {
                drawRect(
                    Brush.verticalGradient(
                        0f to tokens.sheen,
                        0.45f to Color.Transparent,
                        startY = 0f,
                        endY = size.height,
                    ),
                )
            }
        }
        .border(
            borderWidth,
            Brush.verticalGradient(
                0f to tokens.borderHighlight,
                0.5f to Color.Transparent,
                1f to tokens.borderShadow.copy(alpha = tokens.borderShadow.alpha * 0.6f),
            ),
            shape,
        )
}

/**
 * 液态玻璃：在 [frostedGlass] 基础上叠加更强的顶部高光环，
 * 用于英雄级表面（吸顶栏、正在播放卡、主操作按钮容器、输入胶囊）。
 */
fun Modifier.liquidGlass(
    shape: Shape = GlassShapes.card,
    tint: Color? = null,
    shadowElevation: Dp = 14.dp,
    blurRadius: Dp = 24.dp,
    fillBrush: Brush? = null,
): Modifier = composed {
    val tokens = LocalGlassTokens.current
    frostedGlass(
        shape = shape,
        tint = tint,
        highlight = true,
        shadowElevation = shadowElevation,
        blurRadius = blurRadius,
        fillBrush = fillBrush,
    ).border(
        1.dp,
        Brush.verticalGradient(
            0f to tokens.borderHighlight.copy(alpha = (tokens.borderHighlight.alpha * 1.6f).coerceAtMost(1f)),
            0.18f to Color.Transparent,
        ),
        shape,
    )
}

/** 仅描边的轻量玻璃边框。 */
fun Modifier.glassBorder(
    shape: Shape = GlassShapes.card,
    width: Dp = 1.dp,
): Modifier = composed {
    val tokens = LocalGlassTokens.current
    this.border(
        width,
        Brush.verticalGradient(
            0f to tokens.borderHighlight,
            1f to tokens.borderShadow.copy(alpha = tokens.borderShadow.alpha * 0.5f),
        ),
        shape,
    )
}
