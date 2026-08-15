package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rhodesisland.terminal.ui.theme.LocalDarkTheme

/**
 * 液态玻璃的背景画布：淡紫 / 冰蓝 / 粉光斑在底色上缓慢漂移，为半透明玻璃面提供可透出的色彩。
 *
 * 相位由 [GlassBackdrop] 的 [LocalMeshPhase] 唯一动画源下发，保证与低分辨率模糊背板（[LocalBackdropState]）
 * 同相位；光斑配色/几何与背板共享 [meshSpec]/[meshBlobPositions]，视觉严格一致。
 */
@Composable
fun MeshBackground(modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val spec = meshSpec(dark)
    // 组合期捕获稳定 State 引用（current 是 @Composable getter，只能在组合上下文读）；
    // draw 阶段再读 `.value`：相位变化只失效绘制、不触发重组。
    val phaseState = LocalMeshPhase.current

    Canvas(modifier) {
        val phase = phaseState?.value ?: 0f
        val w = size.width
        val h = size.height
        drawRect(spec.base)
        val positions = meshBlobPositions(phase, w, h)
        positions.forEachIndexed { i, (cx, cy, radius) ->
            val blob = spec.blobs[i]
            drawBlob(blob.color.copy(alpha = blob.alpha), Offset(cx, cy), radius)
        }
    }
}

private fun DrawScope.drawBlob(color: Color, center: Offset, radius: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius,
            tileMode = TileMode.Clamp,
        ),
    )
}
