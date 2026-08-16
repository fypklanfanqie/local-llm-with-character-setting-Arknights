package com.rhodesisland.terminal.ui.groupchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.util.GroupCoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群信息编辑弹窗：改群名称 + 群封面（选择/更换/清除），可选「删除群聊」。
 * 保存时一并落库（名称+封面）；改名/改封面后回调 [onSaved]。
 */
@Composable
fun GroupInfoDialog(
    group: Conversation,
    container: AppContainer,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(group.title) }
    var coverUri by remember { mutableStateOf(group.coverImagePath ?: "") }
    var pendingCover by remember { mutableStateOf<Uri?>(null) }
    var coverCleared by remember { mutableStateOf(false) }
    var coverError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingCover = uri
            coverCleared = false
            coverError = null
        }
    }

    val isDark = com.rhodesisland.terminal.ui.theme.LocalDarkTheme.current
    val textColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFE8E4E0) else androidx.compose.ui.graphics.Color(0xFF161616)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(GlassShapes.card, borderWidth = 1.dp, blurRadius = 20.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("群聊信息", color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)

            Text("群封面", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val preview: Any? = when {
                    pendingCover != null -> pendingCover
                    coverCleared -> null
                    else -> coverUri.takeIf { it.isNotBlank() }
                }
                if (preview != null) {
                    Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) {
                        AsyncImage(
                            model = preview,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.primary.copy(alpha = 0.12f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { picker.launch(arrayOf("image/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (preview == null) "＋ 封面" else "更换", color = scheme.primary, fontSize = 11.sp)
                }
                if (preview != null) {
                    TextButton(onClick = {
                        pendingCover = null
                        coverCleared = true
                        coverError = null
                    }) { Text("清除", color = scheme.error, fontSize = 12.sp) }
                }
            }
            coverError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }

            Text("群名称", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = textColor, fontSize = 14.sp),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface.copy(alpha = 0.6f))
                    .padding(12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDeleted != null) {
                    TextButton(onClick = { deleteConfirm = true }) {
                        Text("删除群聊", color = scheme.error, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant, fontSize = 13.sp) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            var finalCover = if (coverCleared) null else coverUri.takeIf { it.isNotBlank() }
                            val chosen = pendingCover
                            if (chosen != null) {
                                val savedCover = withContext(Dispatchers.IO) { GroupCoverStore.save(context, chosen) }
                                if (savedCover == null) {
                                    coverError = "封面保存失败"
                                    saving = false
                                    return@launch
                                }
                                if (coverUri.isNotBlank()) {
                                    withContext(Dispatchers.IO) { GroupCoverStore.delete(context, coverUri) }
                                }
                                finalCover = savedCover
                            }
                            container.groupChatRepository.setGroupName(group.id, name.trim())
                            container.groupChatRepository.setGroupCover(group.id, finalCover)
                            saving = false
                            onSaved()
                            onDismiss()
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(if (saving) "保存中…" else "保存", fontSize = 13.sp)
                }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("删除群聊", color = scheme.onSurface) },
            text = { Text("确定删除「${group.title.ifBlank { "群聊" }}」？该群的全部消息将被清除。", color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    scope.launch {
                        container.groupChatRepository.deleteGroup(group.id)
                        onDeleted?.invoke()
                        onDismiss()
                    }
                }) { Text("删除", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }
}