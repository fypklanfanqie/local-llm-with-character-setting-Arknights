package com.rhodesisland.terminal.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.ui.theme.GlassShapes

/**
 * 可折叠玻璃分组卡（设置页手风琴）：点击头部整行展开 / 收起内容。
 *
 * - 头部 = 标题（+ 可选摘要）+ 可选 [headerExtra]（如分区总开关）+ 右侧 chevron，
 *   随展开态平滑旋转；整行可点、触达高度 ≥48dp、带涟漪
 * - [initiallyExpanded] 默认 false（全收起）；[key] 提供则跨组合生命周期持久化（rememberSaveable(key)），
 *   否则仅会话内保留、离开页面重置
 * - [keepContent] true 时折叠仅隐藏不销毁内容组合（animateContentSize 方案），保住未保存的
 *   表单草稿态（API/TTS 等输入分区用）；false 用 AnimatedVisibility，收起时子内容离开组合树，
 *   长页组合成本随之下降
 */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    key: String? = null,
    keepContent: Boolean = false,
    headerExtra: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by if (key != null) {
        rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    } else {
        rememberSaveable { mutableStateOf(initiallyExpanded) }
    }
    // chevron：收起朝下，展开旋转 180° 朝上
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "collapsibleChevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // 玻璃卡整体：头部 + 可折叠体共用一张卡（与 GlassListSection 同语言）
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .frostedGlass(GlassShapes.large, shadowElevation = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = scheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (summary != null) {
                        Text(
                            summary,
                            color = scheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (headerExtra != null) {
                    headerExtra()
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起$title" else "展开$title",
                    tint = scheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
            if (keepContent) {
                // 折叠仅隐藏不销毁组合，保住表单草稿态（animateContentSize 平滑过渡）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = tween(durationMillis = 250)),
                ) {
                    if (expanded) {
                        Column(Modifier.fillMaxWidth()) {
                            content()
                        }
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
                    exit = shrinkVertically(animationSpec = tween(250)) + fadeOut(animationSpec = tween(250)),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        content()
                    }
                }
            }
        }
    }
}
