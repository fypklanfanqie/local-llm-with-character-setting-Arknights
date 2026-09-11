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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.fieldTextColor
import com.rhodesisland.terminal.data.model.LorebookScopeType
import com.rhodesisland.terminal.data.model.WorldviewTargetType
import com.rhodesisland.terminal.i18n.L10nRuntime
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.settings.characterMatchesQuery
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

    // ===== 成员管理（随时加入/移出，每步立即落库）=====
    // 本地先改再落库：增删立即生效，对话框背后群聊页经 onSaved -> refreshGroup 实时刷新。
    var memberIds by remember { mutableStateOf(group.memberIds) }
    var memberError by remember { mutableStateOf<String?>(null) }
    var showMemberPicker by remember { mutableStateOf(false) }
    var memberSearch by remember { mutableStateOf("") }
    val allCharacters by container.characterRepository.characters.collectAsState(initial = emptyList())

    fun persistMembers(ids: List<String>) {
        memberIds = ids
        scope.launch {
            container.conversationRepository.setGroupMembers(group.id, ids)
            onSaved() // 让背后的群聊页立刻刷新成员条与下一轮回复人池
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingCover = uri
            coverCleared = false
            coverError = null
        }
    }

    val textColor = fieldTextColor()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(GlassShapes.card, borderWidth = 1.dp, blurRadius = 20.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(t("群聊信息"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)

            Text(t("群封面"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
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
                    Text(if (preview == null) t("＋ 封面") else t("更换"), color = scheme.primary, fontSize = 11.sp)
                }
                if (preview != null) {
                    TextButton(onClick = {
                        pendingCover = null
                        coverCleared = true
                        coverError = null
                    }) { Text(t("清除"), color = scheme.error, fontSize = 12.sp) }
                }
            }
            coverError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }

            Text(t("群名称"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
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

            // ===== 群成员：随时加入 / 移出（立即生效，不依赖「保存」）=====
            Text(
                t("群成员") + "（${memberIds.size}/${com.rhodesisland.terminal.config.AppConfig.GroupChat.MAX_MEMBERS}）",
                color = scheme.onSurfaceVariant, fontSize = 11.sp,
            )
            val memberCharacters = allCharacters.filter { it.id in memberIds }
            memberCharacters.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = if (member.isCustom && member.image.isNotBlank()) member.image else null,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        member.name,
                        color = scheme.onSurface, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    // 下限 2 人：与建群口径一致，避免把群删空
                    TextButton(
                        onClick = {
                            if (memberIds.size <= 2) {
                                memberError = L10nRuntime.t("至少保留 2 名成员")
                            } else {
                                memberError = null
                                persistMembers(memberIds - member.id)
                            }
                        },
                    ) { Text(t("移除"), color = scheme.error, fontSize = 12.sp) }
                }
            }
            memberError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }
            Text(
                // 「＋」是装饰前缀，词条本体是「添加成员」（避免为装饰符号再养一条词典 key）
                text = "＋ " + t("添加成员"),
                color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        enabled = memberIds.size < com.rhodesisland.terminal.config.AppConfig.GroupChat.MAX_MEMBERS,
                    ) {
                        memberError = null
                        memberSearch = ""
                        showMemberPicker = true
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDeleted != null) {
                    TextButton(onClick = { deleteConfirm = true }) {
                        Text(t("删除群聊"), color = scheme.error, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(t("取消"), color = scheme.onSurfaceVariant, fontSize = 13.sp) }
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
                                    coverError = L10nRuntime.t("封面保存失败")
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
                    Text(if (saving) t("保存中…") else t("保存"), fontSize = 13.sp)
                }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text(t("删除群聊"), color = scheme.onSurface) },
            text = { Text(tf("确定删除「{0}」？该群的全部消息将被清除。", group.title.ifBlank { t("群聊") }), color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    scope.launch {
                        container.groupChatRepository.deleteGroup(group.id)
                        // 级联清理：移除绑定到该群聊的世界观与世界书
                        container.settingsRepository.removeWorldviewsForTarget(
                            WorldviewTargetType.GROUP, group.id.toString(),
                        )
                        container.settingsRepository.removeLorebooksForTarget(
                            LorebookScopeType.GROUP, group.id.toString(),
                        )
                        onDeleted?.invoke()
                        onDismiss()
                    }
                }) { Text(t("删除"), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text(t("取消"), color = scheme.onSurfaceVariant) }
            },
        )
    }

    // 添加成员弹窗：搜索 → 点选即加（立即落库），可连续加多人；上限与建群一致
    if (showMemberPicker) {
        val maxMembers = com.rhodesisland.terminal.config.AppConfig.GroupChat.MAX_MEMBERS
        val candidates = allCharacters
            .filter { it.id !in memberIds }
            .filter { characterMatchesQuery(it, memberSearch) }
        AlertDialog(
            onDismissRequest = { showMemberPicker = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text(t("添加成员"), color = scheme.onSurface) },
            text = {
                Column {
                    BasicTextField(
                        value = memberSearch,
                        onValueChange = { memberSearch = it },
                        singleLine = true,
                        textStyle = TextStyle(color = textColor, fontSize = 14.sp),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surface.copy(alpha = 0.6f))
                            .padding(12.dp),
                        decorationBox = { inner ->
                            if (memberSearch.isEmpty()) {
                                Text(t("搜索角色名 / 代号…"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    if (memberSearch.isNotBlank()) {
                        Text(
                            tf("找到 {0} 个角色", candidates.size),
                            color = scheme.onSurfaceVariant, fontSize = 11.sp,
                        )
                    }
                    if (memberIds.size >= maxMembers) {
                        Text(
                            tf("已达成员上限（{0} 人）", maxMembers),
                            color = scheme.error, fontSize = 11.sp,
                        )
                    }
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(candidates, key = { it.id }) { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = memberIds.size < maxMembers) {
                                        memberError = null
                                        persistMembers(memberIds + candidate.id)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = if (candidate.isCustom && candidate.image.isNotBlank()) candidate.image else null,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    candidate.name,
                                    color = scheme.onSurface, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(t("添加"), color = scheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMemberPicker = false }) { Text(t("完成"), color = scheme.onSurfaceVariant) }
            },
        )
    }
}