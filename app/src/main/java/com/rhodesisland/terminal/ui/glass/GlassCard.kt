package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.ui.theme.GlassShapes

/**
 * 通用毛玻璃卡片容器。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    liquid: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = if (liquid) Modifier.liquidGlass(shape) else Modifier.frostedGlass(shape)
    Box(
        modifier = modifier.then(glass).padding(contentPadding),
        content = content,
    )
}
