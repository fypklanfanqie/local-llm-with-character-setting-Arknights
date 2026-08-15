package com.rhodesisland.terminal.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.ui.glass.frostedGlass
import kotlinx.coroutines.delay

/**
 * 计算第 [page] 页相对视口中心的偏移（单位：视口倍数）。
 *
 * foundation 1.6.8 没有 `PagerScope.currentPageOffsetFraction`（1.7+ 才有），
 * 因此用无参的 `PagerState.currentPageOffsetFraction` 手动合成：
 *   offset = 0 → 该页正居中央；|offset| 越大 → 越远离中央。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun pageOffset(pagerState: PagerState, page: Int): Float =
    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

/**
 * 刷卡「随机问好」气泡：页面停在角色（settle）时，从角色问候池随机取一句（避免连续重复），
 * spring 弹性入场、3.2s 后淡出。
 */
@Composable
internal fun GreetingBubble(character: Character, visible: Boolean, modifier: Modifier = Modifier) {
    val pool = remember(character.id) {
        character.greetings.ifEmpty { Characters.GENERIC_GREETINGS }
    }
    var lastIndex by remember(character.id) { mutableStateOf<Int?>(null) }
    var current by remember(character.id) { mutableStateOf<String?>(null) }
    var shown by remember(character.id) { mutableStateOf(false) }

    LaunchedEffect(visible, character.id) {
        if (!visible) {
            shown = false
            return@LaunchedEffect
        }
        delay(260) // 让右侧图标 rail 先弹出，气泡随后登场
        // 随机取一句；若池大于 1 且抽到上一句，则顺移到下一条，保证不连续重复
        var idx = pool.indices.random()
        if (pool.size > 1 && idx == lastIndex) {
            idx = (idx + 1) % pool.size
        }
        lastIndex = idx
        current = pool[idx]
        shown = true
        delay(3200)
        shown = false
    }

    // 注意：animScale/animAlpha 命名避免遮蔽 GraphicsLayerScope 的 scaleX/alpha 属性
    val animScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.5f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "greetingScale",
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(180),
        label = "greetingAlpha",
    )

    val text = current
    if (text != null && animAlpha > 0.01f) {
        Box(
            modifier = modifier
                .widthIn(max = 260.dp)
                .frostedGlass(
                    shape = RoundedCornerShape(16.dp),
                    tint = Color.White.copy(alpha = 0.62f),
                    shadowElevation = 4.dp,
                    blurRadius = 18.dp,
                )
                .graphicsLayer {
                    scaleX = animScale
                    scaleY = animScale
                    alpha = animAlpha
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * 抖音风右侧动作图标：圆形毛玻璃底 + 图标 + 文字。
 * settle 时按 [index] 依次弹性弹出（staggered spring pop-in），按下时弹性缩放按压。
 */
@Composable
internal fun RailItem(
    index: Int,
    icon: ImageVector,
    label: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // staggered pop-in：页面停留时依次弹入
    val pop = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(index * 45L)
            pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        } else {
            pop.snapTo(0f)
        }
    }

    // 弹性按压：按下缩到 0.85，抬起弹簧回弹（会过冲）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (pressed) press.snapTo(0.85f)
        else press.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(if (big) 58.dp else 44.dp)
                .graphicsLayer {
                    scaleX = pop.value * press.value
                    scaleY = pop.value * press.value
                    alpha = pop.value
                }
                .then(
                    if (danger) Modifier.frostedGlass(
                        CircleShape,
                        tint = scheme.error.copy(alpha = 0.16f),
                        shadowElevation = 0.dp,
                    )
                    else Modifier.frostedGlass(
                        CircleShape,
                        tint = Color.White.copy(alpha = 0.22f),
                        shadowElevation = 2.dp,
                    )
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (danger) scheme.error else Color.White,
                modifier = Modifier.size(if (big) 26.dp else 20.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

/** 当前使用中角色的提示胶囊（右侧 rail / 左下按钮旁）。 */
@Composable
internal fun ActiveBadgePill() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(scheme.primary.copy(alpha = 0.9f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "使用中",
            color = scheme.onPrimary,
            fontSize = 9.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}
