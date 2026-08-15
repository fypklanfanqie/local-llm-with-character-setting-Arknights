package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalDynamicAccent

data class GlassNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * 浮动胶囊毛玻璃底栏：选中项以玻璃药丸高亮。
 *
 * 当 [LocalDynamicAccent] 非空（通讯页可见）时，选中项图标/文字/高亮药丸与底栏玻璃底色
 * 均改为跟随当前立绘的主题色；其它情况下回退到默认紫罗兰主色。
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    currentRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalDynamicAccent.current
    val selectedColor = accent ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.frostedGlass(GlassShapes.bar, tint = accent?.copy(alpha = 0.08f), shadowElevation = 14.dp)) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { item ->
                    val selected = item.route == currentRoute
                    Column(
                        modifier = Modifier
                            .size(width = 64.dp, height = 48.dp)
                            .clip(GlassShapes.chip)
                            .then(
                                if (selected) Modifier.frostedGlass(
                                    GlassShapes.chip,
                                    tint = accent?.copy(alpha = 0.20f),
                                    shadowElevation = 0.dp,
                                ) else Modifier
                            )
                            .clickable { onSelect(item.route) },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
