package com.rhodesisland.terminal.ui.groupchat

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalDarkTheme
import com.rhodesisland.terminal.util.GroupCoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新建群聊弹窗：群名称（可空，默认「群聊」）+ 群封面（可选）+ 成员多选（2–10 人）。
 * 创建成功后回调 [onCreated]（新群 id）。
 */
@Composable
fun GroupCreateDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())

    var name by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingCover by remember { mutableStateOf<Uri?>(null) }
    var coverCleared by remember { mutableStateOf(false) }
    var coverError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingCover = uri
            coverCleared = false
            coverError = null
        }
    }

    val isDark = LocalDarkTheme.current
    val textColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFE8E4E0) else androidx.compose.ui.graphics.Color(0xFF161616)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(GlassShapes.card, borderWidth = 1.dp, blurRadius = 20.dp)
                .padding(18.dp),
        ) {
            Text("新建群聊", color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)

            // 弹窗整体可滚（小屏不为难用户），成员列表内部固定高度独立滚动
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val preview: Any? = when {
                        pendingCover != null -> pendingCover
                        coverCleared -> null
                        else -> null
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
                        }) { Text("清除", color = scheme.error, fontSize = 12.sp) }
                    }
                    Column {
                        Text("群封面（选填）", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                        coverError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }
                    }
                }

                Text("群名称（选填，默认「群聊」）", color = scheme.onSurfaceVariant, fontSize = 11.sp)
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "选择成员（已选 ${selectedIds.size}，2–${AppConfig.GroupChat.MAX_MEMBERS} 人）",
                        color = scheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text("清空", color = scheme.error, fontSize = 12.sp)
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                    items(characters, key = { it.id }) { c ->
                        val checked = c.id in selectedIds
                        val atCap = selectedIds.size >= AppConfig.GroupChat.MAX_MEMBERS && !checked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (atCap) {
                                        Toast.makeText(context, "最多选择 ${AppConfig.GroupChat.MAX_MEMBERS} 名成员", Toast.LENGTH_SHORT).show()
                                    } else {
                                        selectedIds = if (checked) selectedIds - c.id else selectedIds + c.id
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                c.name,
                                color = when {
                                    atCap -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    checked -> scheme.primary
                                    else -> scheme.onSurface
                                },
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (checked) Text("✓", color = scheme.primary, fontSize = 14.sp)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant, fontSize = 13.sp) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        if (selectedIds.size < 2) {
                            Toast.makeText(context, "至少选择 2 名成员", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            creating = true
                            var cover: String? = null
                            val chosen = pendingCover
                            if (chosen != null) {
                                cover = withContext(Dispatchers.IO) { GroupCoverStore.save(context, chosen) }
                                if (cover == null) {
                                    coverError = "封面保存失败"
                                    creating = false
                                    return@launch
                                }
                            }
                            val id = container.groupChatRepository.createGroup(name.trim(), cover, selectedIds.toList())
                            creating = false
                            onCreated(id)
                            onDismiss()
                        }
                    },
                    enabled = !creating,
                ) {
                    Text(if (creating) "创建中…" else "创建", fontSize = 13.sp)
                }
            }
        }
    }
}