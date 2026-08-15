package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalDynamicAccent
import com.rhodesisland.terminal.util.lightenedOnImage
import com.rhodesisland.terminal.util.readableForeground

enum class GlassButtonStyle { Primary, Glass, Tinted }

/**
 * 玻璃按钮。
 * - [GlassButtonStyle.Primary]：实心主按钮（通讯页跟随立绘主题色，其它页面为冰蓝主色）
 * - [GlassButtonStyle.Glass]：透明毛玻璃按钮（通讯页透出立绘主题色）
 * - [GlassButtonStyle.Tinted]：主色低透明度填充（iOS tinted 风）
 *
 * 当 [LocalDynamicAccent] 非空（通讯页可见）时，Primary 填充、Glass 内容与玻璃底色
 * 均改为跟随当前立绘的主题色；其它情况下回退到 Material colorScheme 默认配色。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GlassButtonStyle = GlassButtonStyle.Primary,
    shape: Shape = GlassShapes.button,
    horizontalPadding: Dp = 24.dp,
    verticalPadding: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = LocalDynamicAccent.current
    val interaction = remember { MutableInteractionSource() }
    val containerColor = when (style) {
        GlassButtonStyle.Primary -> accent ?: scheme.primary
        GlassButtonStyle.Tinted -> (accent ?: scheme.primary).copy(alpha = 0.16f)
        GlassButtonStyle.Glass -> Color.Transparent
    }
    val contentColor = when (style) {
        GlassButtonStyle.Primary -> if (accent != null) accent.readableForeground() else scheme.onPrimary
        GlassButtonStyle.Tinted -> accent ?: scheme.primary
        GlassButtonStyle.Glass -> accent?.lightenedOnImage() ?: scheme.onSurface
    }
    val surface = if (style == GlassButtonStyle.Glass) {
        Modifier.frostedGlass(
            shape,
            tint = accent?.copy(alpha = 0.12f),
            shadowElevation = 4.dp,
        )
    } else {
        Modifier.background(containerColor, shape)
    }
    Row(
        modifier = modifier
            .clip(shape)
            .then(surface)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
