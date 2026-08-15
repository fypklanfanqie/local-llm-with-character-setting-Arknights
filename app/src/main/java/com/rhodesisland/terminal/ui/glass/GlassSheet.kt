package com.rhodesisland.terminal.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rhodesisland.terminal.ui.theme.GlassShapes

/**
 * 毛玻璃底部抽屉：透明容器 + frostedGlass 内容面 + 自定义拖拽指示器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = Color.Transparent,
        shape = GlassShapes.sheet,
        dragHandle = { GlassDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .frostedGlass(
                    GlassShapes.sheet,
                    // 内容型抽屉默认近实底（95% 不透明度），避免弹窗窗口内无毛玻璃背板回退成高透明平涂、文字难读。
                    tint = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GlassDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 10.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {}
}
