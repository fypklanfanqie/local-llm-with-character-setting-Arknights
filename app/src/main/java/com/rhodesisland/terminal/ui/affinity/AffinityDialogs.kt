package com.rhodesisland.terminal.ui.affinity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.affinity.OwnedGift
import kotlinx.coroutines.launch

@Composable
fun DailyCheckinDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val checkedIn by container.affinityRepository.observeCheckinClaimed().collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    if (!checkedIn) {
        androidx.compose.material3.AlertDialog(
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
    com.rhodesisland.terminal.ui.glass.GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text("赠送礼物", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onPickAttachment) { Text("添加附件") }
            }
            if (gifts.none { it.inventory.quantity > 0 }) {
                Text("没有可赠送的礼物，请先到每日签到与商店购买。", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                gifts.filter { it.inventory.quantity > 0 }.forEach { gift ->
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        onClick = { onSend(gift) },
                    ) {
                        androidx.compose.foundation.layout.Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AffinityGiftImage(gift.definition.imagePath, 48.dp)
                            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(gift.definition.name, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                                Text("库存 ${gift.inventory.quantity} · +${com.rhodesisland.terminal.affinity.formatAffinity(gift.definition.affinityGain)} 好感", fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.Redeem, contentDescription = "赠送", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
