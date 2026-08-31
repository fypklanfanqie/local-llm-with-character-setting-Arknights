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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CurrencyYen
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import com.rhodesisland.terminal.affinity.CheckinResult
import com.rhodesisland.terminal.affinity.GiftImageStore
import com.rhodesisland.terminal.affinity.GiftPurchaseResult
import com.rhodesisland.terminal.affinity.OwnedGift
import com.rhodesisland.terminal.affinity.affinityGainForGiftPrice
import com.rhodesisland.terminal.affinity.formatAffinity
import com.rhodesisland.terminal.data.model.LungmenWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CheckinShopScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val wallet by container.affinityRepository.observeWallet().collectAsState(initial = LungmenWallet(0L, 0L))
    val checkedIn by container.affinityRepository.observeCheckinClaimed().collectAsState(initial = false)
    val gifts by container.affinityRepository.observeOwnedGifts().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<OwnedGift?>(null) }

    AffinityArchivePage(
        title = "每日供应与商店",
        code = "LOGISTICS / DAILY SUPPLY",
        onBack = onBack,
        scrollTag = CHECKIN_SCROLL_TAG,
    ) {
        item {
            CheckinHero(
                wallet = wallet,
                checkedIn = checkedIn,
                onClaim = {
                    scope.launch {
                        message = when (container.affinityRepository.claimDailyCheckin()) {
                            is CheckinResult.Claimed -> "签到成功，获得 10,000 龙门币"
                            is CheckinResult.AlreadyClaimed -> "今日已领取"
                        }
                    }
                },
            )
        }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                ArchiveSectionLabel("GIFT MARKET", "礼物商店", Modifier.weight(1f))
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = archivePrimaryColor(), modifier = Modifier.size(16.dp))
                    Text("新建礼物", color = archivePrimaryColor())
                }
            }
        }
        if (gifts.isEmpty()) {
            item { EmptyArchiveCard("暂无礼物档案", "创建一份礼物，再用龙门币购买并赠送给干员。") }
        } else {
            items(gifts.size, key = { gifts[it].definition.id }) { index ->
                GiftShopCard(gifts[index], onBuy = {
                    scope.launch {
                        message = when (container.affinityRepository.buyGift(gifts[index].definition.id)) {
                            is GiftPurchaseResult.Purchased -> "购买成功，已加入库存"
                            GiftPurchaseResult.InsufficientFunds -> "龙门币不足"
                            GiftPurchaseResult.GiftMissing -> "礼物档案不存在"
                        }
                    }
                }, onDelete = { deleteTarget = gifts[index] })
            }
        }
        if (message != null) item { ArchiveNotice(message!!) }
    }
    if (showCreate) {
        CreateGiftDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, description, path, price ->
                scope.launch {
                    runCatching { container.affinityRepository.createGift(name, description, path, price) }
                        .onSuccess { message = "礼物档案已建立"; showCreate = false }
                        .onFailure { message = it.message ?: "礼物创建失败" }
                }
            },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除礼物档案") },
            text = {
                Text(
                    "确定删除「${target.definition.name}」？已购买未送出的库存（${target.inventory.quantity} 份）将一并删除，送礼历史保留。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val imagePath = container.affinityRepository.deleteGift(target.definition.id)
                        if (imagePath != null) GiftImageStore.deleteDefinitionImage(context, imagePath)
                        message = "礼物档案已删除"
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CheckinHero(wallet: LungmenWallet, checkedIn: Boolean, onClaim: () -> Unit) {
    ArchiveCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LungmenCoinIcon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("LMD BALANCE", color = archiveSecondaryColor(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("${wallet.balance}", color = Color(0xFFF2F0EA), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("每日补给 · +10,000 龙门币", color = Color(0xFFB6BEC9), fontSize = 12.sp)
            }
            Text(if (checkedIn) "CLAIMED" else "READY", color = if (checkedIn) Color(0xFF76C9D6) else archivePrimaryColor(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onClaim, enabled = !checkedIn, modifier = Modifier.fillMaxWidth()) {
            Text(if (checkedIn) "今日补给已领取" else "领取今日补给")
        }
    }
}

@Composable
private fun GiftShopCard(gift: OwnedGift, onBuy: () -> Unit, onDelete: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = archiveSurfaceColor()) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GiftImage(gift.definition.imagePath, 64.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(gift.definition.name, color = Color(0xFFF2F0EA), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (gift.definition.description.isNotBlank()) Text(gift.definition.description, color = Color(0xFFAAB4C1), fontSize = 12.sp, maxLines = 2)
                    Text("${gift.definition.price} LMD  ·  +${formatAffinity(gift.definition.affinityGain)} 好感  ·  库存 ${gift.inventory.quantity}", color = archivePrimaryColor(), fontSize = 11.sp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = Color(0xFFE57373), fontSize = 13.sp) }
                }
                TextButton(onClick = onBuy) { Text("采购", color = archivePrimaryColor()) }
            }
        }
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

@Composable
private fun ArchiveNotice(message: String) {
    Text(message, color = archiveSecondaryColor(), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun GiftImage(path: String, size: androidx.compose.ui.unit.Dp) {
    if (path.isBlank()) {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(archivePrimaryColor().copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CardGiftcard, contentDescription = null, tint = archivePrimaryColor())
        }
    } else {
        AsyncImage(model = path, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun LungmenCoinIcon() {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0B93F)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CurrencyYen, contentDescription = "龙门币", tint = Color(0xFF412F00), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun CreateGiftDialog(onDismiss: () -> Unit, onCreate: (String, String, String, Long) -> Unit) {
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
        title = { Text("建立礼物档案") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ArchiveInput("礼物名称 *", name) { name = it }
                ArchiveInput("礼物描述（可选）", description) { description = it }
                ArchiveInput("价格 5000–20000", priceText) { priceText = it.filter(Char::isDigit) }
                Text(gain?.let { "对应关系增益：+${formatAffinity(it)}" } ?: "价格必须落在有效档位", color = if (gain == null) MaterialTheme.colorScheme.error else archivePrimaryColor(), fontSize = 12.sp)
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !savingImage) { Text(if (imagePath.isBlank()) "选择档案图片 *" else "更换档案图片") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && imagePath.isNotBlank() && price != null && gain != null && !savingImage, onClick = { onCreate(name, description, imagePath, price!!) }) { Text("建立") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ArchiveInput(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFFAAB4C1), fontSize = 10.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Color(0xFFF2F0EA), fontSize = 14.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1420), RoundedCornerShape(10.dp)).padding(12.dp),
        )
    }
}
