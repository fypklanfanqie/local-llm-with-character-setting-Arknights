package com.rhodesisland.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.ui.glass.MeshBackground
import com.rhodesisland.terminal.ui.glass.frostedGlass
import kotlinx.coroutines.delay

/**
 * 启动加载画面：极简玻璃风。
 *  - 动态渐变网格底
 *  - 中央 app logo（渐变圆角方块）+ 应用名
 *  - 玻璃进度条（紫罗兰渐变）+ 百分比
 * 进度自行驱动，到 100% 后停留约 450ms 再回调 [onFinished]。
 */
@Composable
fun LoadingScreen(onFinished: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var p = 0f
        while (p < 100f) {
            delay(180)
            p = (p + (kotlin.random.Random.nextFloat() * 22f + 6f)).coerceAtMost(100f)
            progress = p
        }
        delay(450)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MeshBackground(Modifier.fillMaxSize())

        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .fillMaxWidth(0.78f)
                .clip(RoundedCornerShape(28.dp))
                .frostedGlass(RoundedCornerShape(28.dp), shadowElevation = 12.dp)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // App logo
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(scheme.primary, scheme.secondary))),
                contentAlignment = Alignment.Center,
            ) {
                Text("C", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Chat by your side",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "AI 角色扮演聊天",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(24.dp))

            // 进度条轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(scheme.primary, scheme.secondary))),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "${progress.toInt()}%",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
