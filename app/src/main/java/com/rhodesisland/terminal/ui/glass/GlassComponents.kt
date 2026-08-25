package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.ui.theme.GlassShapes
import kotlin.math.abs

/** 渐变配色盘：monogram 头像 / 用户头像按 seed 取一组，保证同一角色稳定。 */
private val MONOGRAM_GRADIENTS = listOf(
    listOf(Color(0xFFFF6B9D), Color(0xFFC44CE0)),
    listOf(Color(0xFF5B8DEF), Color(0xFF7C5CFF)),
    listOf(Color(0xFF34C7BE), Color(0xFF5B8DEF)),
    listOf(Color(0xFFFFB347), Color(0xFFFF6B6B)),
    listOf(Color(0xFF7C5CFF), Color(0xFFFF6B9D)),
    listOf(Color(0xFF0A84FF), Color(0xFF34C7BE)),
    listOf(Color(0xFFC44CE0), Color(0xFF7C5CFF)),
    listOf(Color(0xFF34C759), Color(0xFF5B8DEF)),
)

/** 由文本稳定推导一个渐变索引。 */
fun monogramSeed(text: String): Int = abs(text.hashCode())

/** 由文本稳定推导一组渐变色（供非圆形头像 / 立绘占位等使用）。 */
fun monogramGradient(text: String): List<Color> {
    val seed = monogramSeed(text)
    return MONOGRAM_GRADIENTS[(seed % MONOGRAM_GRADIENTS.size + MONOGRAM_GRADIENTS.size) % MONOGRAM_GRADIENTS.size]
}

/**
 * 字母 monogram 渐变头像：圆形 + 线性渐变 + 居中首字。
 * 用于用户头像、无立绘角色占位等。
 */
@Composable
fun MonogramAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    seed: Int = monogramSeed(text),
) {
    val gradient = MONOGRAM_GRADIENTS[(seed % MONOGRAM_GRADIENTS.size + MONOGRAM_GRADIENTS.size) % MONOGRAM_GRADIENTS.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.take(1),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

/**
 * 玻璃分段控件（iOS segmented 风）：选项列表 + 选中项主色高亮药丸。
 */
@Composable
fun <T> GlassSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .clip(GlassShapes.pill)
            .frostedGlass(GlassShapes.pill, shadowElevation = 2.dp)
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .clip(GlassShapes.pill)
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
                    .clickable { onSelect(value) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * iOS 大标题：左对齐大字 + 可选尾部动作槽。
 */
@Composable
fun GlassLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}
