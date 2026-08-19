package com.rhodesisland.terminal.ui.affinity

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CurrencyYen
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.affinity.AFFINITY_EVENT_THRESHOLDS
import com.rhodesisland.terminal.affinity.AffinityRepository
import com.rhodesisland.terminal.affinity.CheckinResult
import com.rhodesisland.terminal.affinity.GiftImageStore
import com.rhodesisland.terminal.affinity.GiftPurchaseResult
import com.rhodesisland.terminal.affinity.OwnedGift
import com.rhodesisland.terminal.affinity.SpecialEventLaunchResult
import com.rhodesisland.terminal.affinity.affinityGainForGiftPrice
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.GiftHistory
import com.rhodesisland.terminal.data.model.SpecialEvent
import com.rhodesisland.terminal.ui.characters.CharacterPortrait
import com.rhodesisland.terminal.ui.glass.GlassLargeTitle
import com.rhodesisland.terminal.ui.glass.GlassSheet
import com.rhodesisland.terminal.ui.glass.frostedGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CheckinShopScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val wallet by container.affinityRepository.observeWallet().collectAsState(initial = com.rhodesisland.terminal.data.model.LungmenWallet(0L, 0L))
    val checkedIn by container.affinityRepository.observeCheckinClaimed().collectAsState(initial = false)
    val gifts by container.affinityRepository.observeOwnedGifts().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassLargeTitle("每日签到与商店") {
            TextButton(onClick = onBack) { Text("返回") }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LungmenCoinIcon()
                    Spacer(Modifier.width(8.dp))
                    Text("龙门币 ${wallet.balance}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Text(if (checkedIn) "今日已签到，明天再来吧。" else "每日签到可领取 10,000 龙门币。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        scope.launch {
                            when (val result = container.affinityRepository.claimDailyCheckin()) {
                                is CheckinResult.Claimed -> message = "签到成功，获得 10,000 龙门币"
                                is CheckinResult.AlreadyClaimed -> message = "今天已经签到过了"
                            }
                        }
                    },
                    enabled = !checkedIn,
                ) { Text(if (checkedIn) "今日已签到" else "每日签到") }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("礼物商店", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("新建礼物")
            }
        }
        if (gifts.isEmpty()) {
            Text("还没有自定义礼物。创建礼物后可以购买并赠送给干员。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(gifts, key = { it.definition.id }) { gift ->
                    GiftShopCard(gift) {
                        scope.launch {
                            when (container.affinityRepository.buyGift(gift.definition.id)) {
                                is GiftPurchaseResult.Purchased -> message = "购买成功，已放入礼物库存"
                                GiftPurchaseResult.InsufficientFunds -> message = "龙门币不足"
                                GiftPurchaseResult.GiftMissing -> message = "礼物已不存在"
                            }
                        }
                    }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
    if (showCreate) {
        CreateGiftDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, description, path, price ->
                scope.launch {
                    runCatching { container.affinityRepository.createGift(name, description, path, price) }
                        .onSuccess { message = "礼物已创建"; showCreate = false }
                        .onFailure { message = it.message ?: "礼物创建失败" }
                }
            },
        )
    }
}

@Composable
fun AffinityScreen(
    container: AppContainer,
    character: Character,
    imageUrl: String,
    onBack: () -> Unit,
    onOpenEventConversation: () -> Unit,
) {
    val affinity by container.affinityRepository.observeAffinity(character.id).collectAsState(initial = com.rhodesisland.terminal.data.model.CharacterAffinity(character.id, 0f, 0L))
    val events by container.affinityRepository.observeSpecialEvents(character.id).collectAsState(initial = emptyList())
    val giftHistory by container.affinityRepository.observeGiftHistory(character.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassLargeTitle("${character.name} · 好感度") { TextButton(onClick = onBack) { Text("返回") } }
        CharacterPortrait(imageUrl = imageUrl, name = character.name, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)))
        Text("好感度 ${formatAffinity(affinity.value)} / 200", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { affinity.value / 200f }, modifier = Modifier.fillMaxWidth())
        Text(nextAffinityHint(affinity.value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("特殊邂逅", style = MaterialTheme.typography.titleLarge)
        AFFINITY_EVENT_THRESHOLDS.forEach { threshold ->
            val event = events.firstOrNull { it.threshold == threshold }
            EventCard(
                threshold = threshold,
                event = event,
                enabled = affinity.value >= threshold,
                onOpen = {
                    scope.launch {
                        when (container.specialEventConversationCoordinator.launch(character.id, threshold)) {
                            is SpecialEventLaunchResult.Ready, is SpecialEventLaunchResult.Existing -> onOpenEventConversation()
                            SpecialEventLaunchResult.Missing -> message = "该阶段尚未解锁"
                        }
                    }
                },
            )
        }
        Text("礼物墙", style = MaterialTheme.typography.titleLarge)
        if (giftHistory.isEmpty()) Text("还没有收到礼物。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        giftHistory.forEach { history -> GiftHistoryCard(history) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun DailyCheckinDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val checkedIn by container.affinityRepository.observeCheckinClaimed().collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    if (!checkedIn) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("每日签到") },
            text = { Text("今日可领取 10,000 龙门币。现在领取，或稍后从角色页进入每日签到。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.affinityRepository.claimDailyCheckin() }
                    onDismiss()
                }) { Text("领取") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后再说") } },
        )
    }
}

@Composable
fun GiftInventorySheet(
    gifts: List<OwnedGift>,
    onSend: (OwnedGift) -> Unit,
    onPickAttachment: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("赠送礼物", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onPickAttachment) { Text("添加附件") }
            }
            if (gifts.none { it.inventory.quantity > 0 }) {
                Text("没有可赠送的礼物，请先到每日签到与商店购买。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                gifts.filter { it.inventory.quantity > 0 }.forEach { gift ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().clickable { onSend(gift) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            GiftImage(gift.definition.imagePath)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(gift.definition.name, fontWeight = FontWeight.Bold)
                                Text("库存 ${gift.inventory.quantity} · +${formatAffinity(gift.definition.affinityGain)} 好感", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.Redeem, contentDescription = "赠送")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GiftShopCard(gift: OwnedGift, onBuy: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GiftImage(gift.definition.imagePath)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(gift.definition.name, fontWeight = FontWeight.Bold)
                if (gift.definition.description.isNotBlank()) Text(gift.definition.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("${gift.definition.price} 龙门币 · +${formatAffinity(gift.definition.affinityGain)} 好感 · 库存 ${gift.inventory.quantity}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            TextButton(onClick = onBuy) { Text("购买") }
        }
    }
}

@Composable
private fun EventCard(threshold: Int, event: SpecialEvent?, enabled: Boolean, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Event, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(event?.title ?: "$threshold 好感邂逅")
                Text(
                    when {
                        !enabled -> "达到 $threshold 好感后解锁"
                        event?.conversationId != null -> "已开启，可回忆"
                        else -> "已解锁，开始邂逅"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (enabled) TextButton(onClick = onOpen) { Text(if (event?.conversationId != null) "回忆" else "开始邂逅") }
        }
    }
}

@Composable
private fun GiftHistoryCard(history: GiftHistory) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GiftImage(history.giftImagePath)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(history.giftName, fontWeight = FontWeight.SemiBold)
                if (history.giftDescription.isNotBlank()) Text(history.giftDescription, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+${formatAffinity(history.affinityGain)} 好感", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GiftImage(path: String) {
    if (path.isBlank()) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    } else {
        AsyncImage(model = path, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun LungmenCoinIcon() {
    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFF0B93F)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CurrencyYen, contentDescription = "龙门币", tint = Color(0xFF412F00), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CreateGiftDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("5000") }
    var imagePath by remember { mutableStateOf("") }
    var savingImage by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            savingImage = true
            scope.launch(Dispatchers.IO) {
                val saved = GiftImageStore.save(context, uri)
                withContext(Dispatchers.Main) { imagePath = saved.orEmpty(); savingImage = false }
            }
        }
    }
    val price = priceText.toLongOrNull()
    val gain = price?.let(::affinityGainForGiftPrice)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建礼物") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.text.BasicTextField(name, { name = it }, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth().frostedGlass(RoundedCornerShape(10.dp)).padding(10.dp), decorationBox = { inner -> if (name.isBlank()) Text("礼物名称 *"); inner() })
                androidx.compose.foundation.text.BasicTextField(description, { description = it }, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth().frostedGlass(RoundedCornerShape(10.dp)).padding(10.dp), decorationBox = { inner -> if (description.isBlank()) Text("礼物描述（可选）"); inner() })
                androidx.compose.foundation.text.BasicTextField(priceText, { priceText = it.filter(Char::isDigit) }, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth().frostedGlass(RoundedCornerShape(10.dp)).padding(10.dp), decorationBox = { inner -> if (priceText.isBlank()) Text("价格：5000–20000"); inner() })
                Text("${gain?.let { "将增加 ${formatAffinity(it)} 好感" } ?: "价格须为 5000–9999、10000–14999 或 15000–20000"}", color = if (gain == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !savingImage) { Text(if (savingImage) "保存图片中…" else if (imagePath.isBlank()) "选择礼物图片 *" else "更换礼物图片") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && imagePath.isNotBlank() && price != null && gain != null && !savingImage, onClick = { onCreate(name, description, imagePath, price!!) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatAffinity(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)
private fun nextAffinityHint(value: Float): String = AFFINITY_EVENT_THRESHOLDS.firstOrNull { it > value }
    ?.let { "距离下一阶段 $it 好感还差 ${formatAffinity(it - value)}" }
    ?: "已达到最高好感度。"
