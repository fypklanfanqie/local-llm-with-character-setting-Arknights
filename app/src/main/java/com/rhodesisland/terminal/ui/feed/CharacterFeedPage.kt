package com.rhodesisland.terminal.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.ui.characters.CharacterPortrait
import com.rhodesisland.terminal.ui.glass.GlassButton
import com.rhodesisland.terminal.ui.glass.GlassButtonStyle
import com.rhodesisland.terminal.ui.glass.monogramGradient
import kotlin.math.abs

// 背景图可配置参数：避免魔法数，方便后续根据角色立绘比例微调
private const val BG_SCALE = 1.35f
private const val BG_OFFSET_RATIO = 0.05f

/**
 * 抖音式竖滑卡片流的单页：全屏立绘背景（分层视差）+ 左下信息区（含随机问好气泡）
 * + 右侧动作图标 rail。页面居中时 settle 弹性回弹、图标逐个弹出。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CharacterFeedPage(
    character: Character,
    isActive: Boolean,
    imageUrl: String,
    pagerState: PagerState,
    pageIndex: Int,
    settled: Boolean,
    bottomBarHeight: Dp,
    onChat: () -> Unit,
    onPersona: () -> Unit,
    onAffinity: () -> Unit,
    onVoice: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    // settle 弹性回弹：0.96 → 1f，弹簧会过冲再落回，制造「落定」动感
    val settleScale = remember { Animatable(0.96f) }
    LaunchedEffect(settled) {
        if (settled) {
            settleScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        } else {
            settleScale.snapTo(0.96f)
        }
    }

    // 整页裁剪：背景层有 1.35x 视差缩放（BG_SCALE）会越出本页边界，导致相邻页的立绘
    // 从屏幕底部溢入当前页。裁剪到本页边界后，只有当前角色的立绘铺满全屏，不再露出上/下一张。
    Box(Modifier.fillMaxSize().clipToBounds()) {
        // 整页容器：偏移驱动缩放 + 轻微旋转
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = pageOffset(pagerState, pageIndex)
                    val pageScale = 1f - 0.08f * abs(offset).coerceAtMost(1f)
                    scaleX = pageScale * settleScale.value
                    scaleY = pageScale * settleScale.value
                    rotationZ = 0.5f * offset.coerceIn(-1f, 1f)
                },
        ) {
            // 背景层：视差最快；放大 + 轻微下移，让立绘人物主体居中偏上，
            // 同时保证画面铺满到底部 dock 栏背后，避免露出浅色底。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val offset = pageOffset(pagerState, pageIndex)
                        scaleX = BG_SCALE
                        scaleY = BG_SCALE
                        translationY = offset * -60f + size.height * BG_OFFSET_RATIO
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onChat,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.BottomCenter,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(monogramGradient(character.name))),
                    )
                }
                // 上/下双端 scrim：底部更暗更宽，覆盖 dock 区域，保证文字可读
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Black.copy(alpha = 0.45f),
                                    0.35f to Color.Transparent,
                                    0.60f to Color.Transparent,
                                    1.0f to Color.Black.copy(alpha = 0.80f),
                                ),
                            ),
                        ),
                )
            }

            // 无立绘时：居中大号 monogram 头像（视差慢于背景）
            if (imageUrl.isBlank()) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.62f)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            val offset = pageOffset(pagerState, pageIndex)
                            translationY = offset * -28f
                        },
                ) {
                    CharacterPortrait(
                        imageUrl = "",
                        name = character.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                    )
                }
            }

            // 左下信息区：视差更慢，增强纵深
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 22.dp, end = 22.dp, bottom = 96.dp + bottomBarHeight)
                    .graphicsLayer {
                        val offset = pageOffset(pagerState, pageIndex)
                        translationY = offset * -14f
                    },
            ) {
                GreetingBubble(character = character, visible = settled)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = character.name,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(10.dp))
                        ActiveBadgePill()
                    }
                }
                if (character.role.isNotBlank()) {
                    Text(
                        text = character.role,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
                if (character.code.isNotBlank()) {
                    Text(
                        text = character.code,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                // 底部操作按钮：主按钮突出，人设辅助；移除与主按钮重复的「对话」
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassButton(
                        onClick = onChat,
                        style = GlassButtonStyle.Primary,
                        modifier = Modifier.weight(1.5f),
                        horizontalPadding = 8.dp,
                        verticalPadding = 14.dp,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始对话", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    }
                    GlassButton(
                        onClick = onPersona,
                        style = GlassButtonStyle.Glass,
                        modifier = Modifier.weight(1f),
                        horizontalPadding = 8.dp,
                        verticalPadding = 14.dp,
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("人设", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    GlassButton(
                        onClick = onAffinity,
                        style = GlassButtonStyle.Glass,
                        modifier = Modifier.weight(1f),
                        horizontalPadding = 8.dp,
                        verticalPadding = 14.dp,
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("好感", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }

            // 右侧动作图标 rail：视差最慢
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .graphicsLayer {
                        val offset = pageOffset(pagerState, pageIndex)
                        translationY = offset * -8f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                onVoice?.let { voice ->
                    RailItem(
                        index = 0,
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        label = "语音",
                        visible = settled,
                        onClick = voice,
                    )
                }
                onDelete?.let { delete ->
                    RailItem(
                        index = 1,
                        icon = Icons.Outlined.Delete,
                        label = "删除",
                        danger = true,
                        visible = settled,
                        onClick = delete,
                    )
                }
            }
        }
    }
}
