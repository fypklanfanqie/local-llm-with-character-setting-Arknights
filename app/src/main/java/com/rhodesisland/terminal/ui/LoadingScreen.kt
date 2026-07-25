package com.rhodesisland.terminal.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.ui.theme.PrtsColors
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * PRTS 启动 Loading 画面
 * 迁移自网页版 #loading-screen：
 *  - 深色全屏底 + 四角几何引导线
 *  - 中央亚克力面板（切角），标签 "RHODES ISLAND // BOOT"（金色 + RGB 抖动）
 *  - 4dp 金色渐变进度条（随机增量推进到 100%）
 *  - "LOADING - NN% ......" 计数
 *
 * 进度自行驱动，到 100% 后停留约 450ms 再回调 [onFinished]。
 */
@Composable
fun LoadingScreen(onFinished: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var p = 0f
        while (p < 100f) {
            delay(180)
            // 模拟网页版：每次随机增量 6~28，逼近 100
            p = (p + Random.nextFloat() * 22f + 6f).coerceAtMost(100f)
            progress = p
        }
        delay(450)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrtsColors.BgPrimary)
            .drawCornerGuides(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = PrtsColors.AcrylicBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, PrtsColors.AcrylicBorder),
            shape = CutCornerShape(8.dp),
            modifier = Modifier.width(300.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BootLabel(text = "RHODES ISLAND // BOOT")
                Spacer(Modifier.height(20.dp))

                // 进度条轨道
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CutCornerShape(2.dp))
                        .background(PrtsColors.Border),
                ) {
                    // 金色渐变填充
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(CutCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(PrtsColors.GoldDim, PrtsColors.Gold, PrtsColors.GoldBright),
                                ),
                            ),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "LOADING - ${progress.toInt()}% ......",
                    color = PrtsColors.TextDim,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * "RHODES ISLAND // BOOT" 标签：金色 + RGB 抖动（迁移自网页 .loading-label 的 text-glitch）。
 * 三层文字叠放：金色主层 + 金色右偏 + 蓝色左偏，并用无限动画做轻微水平抖动。
 */
@Composable
private fun BootLabel(text: String) {
    val transition = rememberInfiniteTransition(label = "glitch")
    val jitter by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "jitter",
    )
    // 0->1 周期内大部分时间停在 0，仅在末尾 ~15% 做一次抖动（x/y 反向，对应网页 translate(-1px,1px)）
    val dx = when {
        jitter > 0.86f && jitter < 0.92f -> -1f
        jitter >= 0.92f && jitter < 0.97f -> 1f
        else -> 0f
    }
    val dy = -dx

    Box(modifier = Modifier.width(260.dp), contentAlignment = Alignment.Center) {
        // 蓝色左偏层
        Text(
            text = text,
            color = PrtsColors.AccentBlue.copy(alpha = 0.18f),
            fontSize = 12.sp,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.offset(x = (-2).dp + dx.dp, y = dy.dp),
        )
        // 金色右偏层
        Text(
            text = text,
            color = PrtsColors.Gold.copy(alpha = 0.22f),
            fontSize = 12.sp,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.offset(x = 2.dp + dx.dp, y = dy.dp),
        )
        // 金色主层
        Text(
            text = text,
            color = PrtsColors.Gold,
            fontSize = 12.sp,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 四角几何引导线（迁移自网页 .geo-guides）。
 * 在屏幕四角绘制 L 形金色细线，作为 PRTS 终端的入场动效装饰。
 */
private fun Modifier.drawCornerGuides(): Modifier = drawBehind {
    val len = 28.dp.toPx()
    val inset = 18.dp.toPx()
    val color = PrtsColors.Gold.copy(alpha = 0.28f)
    val strokeWidth = 1.dp.toPx()

    // 左上
    drawLine(color, Offset(inset, inset), Offset(inset + len, inset), strokeWidth = strokeWidth)
    drawLine(color, Offset(inset, inset), Offset(inset, inset + len), strokeWidth = strokeWidth)
    // 右上
    drawLine(color, Offset(size.width - inset - len, inset), Offset(size.width - inset, inset), strokeWidth = strokeWidth)
    drawLine(color, Offset(size.width - inset, inset), Offset(size.width - inset, inset + len), strokeWidth = strokeWidth)
    // 左下
    drawLine(color, Offset(inset, size.height - inset), Offset(inset + len, size.height - inset), strokeWidth = strokeWidth)
    drawLine(color, Offset(inset, size.height - inset - len), Offset(inset, size.height - inset), strokeWidth = strokeWidth)
    // 右下
    drawLine(color, Offset(size.width - inset - len, size.height - inset), Offset(size.width - inset, size.height - inset), strokeWidth = strokeWidth)
    drawLine(color, Offset(size.width - inset, size.height - inset - len), Offset(size.width - inset, size.height - inset), strokeWidth = strokeWidth)
}
