package com.rhodesisland.terminal.ui.affinity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.affinity.AFFINITY_EVENT_THRESHOLDS
import com.rhodesisland.terminal.affinity.SpecialEventLaunchResult
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.GiftHistory
import com.rhodesisland.terminal.data.model.SpecialEvent
import com.rhodesisland.terminal.ui.characters.CharacterPortrait
import com.rhodesisland.terminal.ui.affinity.AffinityGiftImage
import com.rhodesisland.terminal.ui.glass.GlassSheet
import com.rhodesisland.terminal.affinity.formatAffinity
import com.rhodesisland.terminal.affinity.nextAffinityHint
import kotlinx.coroutines.launch

@Composable
fun AffinityScreen(
    container: AppContainer,
    character: Character,
    imageUrl: String,
    onBack: () -> Unit,
    onOpenGifts: () -> Unit,
    onOpenEvents: () -> Unit,
) {
    val affinity by container.affinityRepository.observeAffinity(character.id).collectAsState(initial = com.rhodesisland.terminal.data.model.CharacterAffinity(character.id, 0f, 0L))
    val events by container.affinityRepository.observeSpecialEvents(character.id).collectAsState(initial = emptyList())
    val giftHistory by container.affinityRepository.observeGiftHistory(character.id).collectAsState(initial = emptyList())
    val unread = events.count { !it.isRead }

    AffinityArchivePage(
        title = "${character.name} · 关系档案",
        code = "RELATION FILE / ${character.code.ifBlank { character.id.uppercase() }}",
        onBack = onBack,
        scrollTag = AFFINITY_SCROLL_TAG,
    ) {
        item {
            AffinityHero(character, imageUrl)
        }
        item {
            AffinityMeterCard(affinity.value)
        }
        item {
            ArchiveSectionLabel("ARCHIVE INDEX", "关系记录")
        }
        item {
            ArchiveRouteCard(
                icon = Icons.Filled.CardGiftcard,
                code = "GIFT LOG",
                title = "礼物墙",
                subtitle = if (giftHistory.isEmpty()) "尚无收礼记录" else "已收 ${giftHistory.size} 件礼物 · 查看完整记录",
                onClick = onOpenGifts,
            )
        }
        item {
            ArchiveRouteCard(
                icon = Icons.Filled.Event,
                code = "EVENT ARCHIVE",
                title = "特殊邂逅",
                subtitle = if (unread > 0) "$unread 个新事件等待回忆" else "${events.size} / ${AFFINITY_EVENT_THRESHOLDS.size} 个阶段已解锁",
                hasBadge = unread > 0,
                onClick = onOpenEvents,
            )
        }
        if (giftHistory.isNotEmpty()) {
            item { ArchiveSectionLabel("LATEST ENTRY", "最近动态") }
            item { GiftHistoryCard(giftHistory.first()) }
        }
    }
}

@Composable
fun AffinityGiftsScreen(
    container: AppContainer,
    character: Character,
    onBack: () -> Unit,
) {
    val history by container.affinityRepository.observeGiftHistory(character.id).collectAsState(initial = emptyList())
    AffinityArchivePage(
        title = "${character.name} · 礼物墙",
        code = "GIFT LOG / ${character.code.ifBlank { character.id.uppercase() }}",
        onBack = onBack,
        scrollTag = "affinity_gifts_scroll",
    ) {
        item { ArchiveSectionLabel("PRESENT HISTORY", "收到的礼物") }
        if (history.isEmpty()) {
            item { EmptyArchiveCard("礼物墙为空", "在每日供应与商店采购礼物，再回到聊天中赠送给这名干员。") }
        } else {
            items(history.size, key = { history[it].id }) { index -> GiftHistoryCard(history[index]) }
        }
    }
}

@Composable
fun AffinityEventsScreen(
    container: AppContainer,
    character: Character,
    onBack: () -> Unit,
    onOpenEventConversation: () -> Unit,
) {
    val affinity by container.affinityRepository.observeAffinity(character.id).collectAsState(initial = com.rhodesisland.terminal.data.model.CharacterAffinity(character.id, 0f, 0L))
    val events by container.affinityRepository.observeSpecialEvents(character.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    AffinityArchivePage(
        title = "${character.name} · 特殊邂逅",
        code = "EVENT ARCHIVE / ${character.code.ifBlank { character.id.uppercase() }}",
        onBack = onBack,
        scrollTag = "affinity_events_scroll",
    ) {
        item { ArchiveSectionLabel("RELATION MILESTONES", "阶段记录") }
        items(AFFINITY_EVENT_THRESHOLDS.size) { index ->
            val threshold = AFFINITY_EVENT_THRESHOLDS[index]
            val event = events.firstOrNull { it.threshold == threshold }
            EventArchiveNode(
                threshold = threshold,
                event = event,
                enabled = affinity.value >= threshold,
                onOpen = {
                    scope.launch {
                        event?.let { container.specialEventConversationCoordinator.markRead(it.id) }
                        when (container.specialEventConversationCoordinator.launch(character.id, threshold)) {
                            is SpecialEventLaunchResult.Ready, is SpecialEventLaunchResult.Existing -> onOpenEventConversation()
                            SpecialEventLaunchResult.Missing -> Unit
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AffinityHero(character: Character, imageUrl: String) {
    Box(Modifier.fillMaxWidth().height(230.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))) {
        CharacterPortrait(imageUrl = imageUrl, name = character.name, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF07111F)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("RELATION FILE", color = archivePrimaryColor(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(character.name, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(character.role.ifBlank { "罗德岛干员" }, color = Color(0xFFD2D8E0), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AffinityMeterCard(value: Float) {
    ArchiveCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("RELATION LEVEL", color = archiveSecondaryColor(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("好感度 ${formatAffinity(value)}", color = Color(0xFFF2F0EA), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("/ 200", color = Color(0xFFAAB4C1), fontSize = 13.sp)
        }
        androidx.compose.material3.LinearProgressIndicator(progress = { (value / 200f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = archivePrimaryColor(), trackColor = Color(0xFF2B3543))
        Text(nextAffinityHint(value), color = Color(0xFFAAB4C1), fontSize = 12.sp)
    }
}

@Composable
private fun ArchiveRouteCard(icon: androidx.compose.ui.graphics.vector.ImageVector, code: String, title: String, subtitle: String, hasBadge: Boolean = false, onClick: () -> Unit) {
    Surface(color = archiveSurfaceColor(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(archivePrimaryColor().copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = archivePrimaryColor()) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(code, color = archiveSecondaryColor(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(title, color = Color(0xFFF2F0EA), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFFAAB4C1), fontSize = 12.sp)
            }
            if (hasBadge) Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFE36B5D)))
            Text("›", color = archivePrimaryColor(), fontSize = 28.sp)
        }
    }
}

@Composable
private fun EventArchiveNode(threshold: Int, event: SpecialEvent?, enabled: Boolean, onOpen: () -> Unit) {
    Surface(color = archiveSurfaceColor(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                Text("$threshold", color = if (enabled) archivePrimaryColor() else Color(0xFFAAB4C1), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("AFF", color = Color(0xFFAAB4C1), fontSize = 8.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event?.title ?: "$threshold 好感阶段", color = Color(0xFFF2F0EA), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (!enabled) "尚未达到解锁条件" else if (event?.conversationId != null) "已开始 · 可继续回忆" else "已解锁 · 等待开始", color = Color(0xFFAAB4C1), fontSize = 12.sp)
            }
            if (enabled) TextButton(onClick = onOpen) { Text(if (event?.conversationId != null) "回忆" else "开始", color = archivePrimaryColor()) }
        }
    }
}

@Composable
private fun GiftHistoryCard(history: GiftHistory) {
    ArchiveCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AffinityGiftImage(history.giftImagePath, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(history.giftName, color = Color(0xFFF2F0EA), fontWeight = FontWeight.Bold)
                Text("+${formatAffinity(history.affinityGain)} 好感  ·  ${formatGiftTime(history.sentAt)}", color = archivePrimaryColor(), fontSize = 11.sp)
            }
        }
        if (history.giftDescription.isNotBlank()) Text(history.giftDescription, color = Color(0xFFAAB4C1), fontSize = 12.sp)
        if (history.thankYouText.isNotBlank()) Text("“${history.thankYouText}”", color = Color(0xFFD2D8E0), fontSize = 12.sp)
    }
}

@Composable
private fun EmptyArchiveCard(label: String, description: String) {
    ArchiveCard {
        Text("ARCHIVE EMPTY", color = archiveSecondaryColor(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFF2F0EA), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Color(0xFFAAB4C1), fontSize = 12.sp)
    }
}

private fun formatGiftTime(timestamp: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(timestamp))
