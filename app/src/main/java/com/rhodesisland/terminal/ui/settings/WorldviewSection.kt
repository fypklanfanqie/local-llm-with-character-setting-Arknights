package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.Worldview
import com.rhodesisland.terminal.data.model.WorldviewTargetType
import com.rhodesisland.terminal.ui.glass.CollapsibleSection
import com.rhodesisland.terminal.ui.glass.GlassSegmented
import com.rhodesisland.terminal.ui.theme.fieldPlaceholderColor
import com.rhodesisland.terminal.ui.theme.fieldTextColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
/** 解析世界观目标的显示名（角色名 / 群聊标题；找不到时给兜底文案）。挂起：需查 DataStore/Room。 */
private suspend fun resolveWorldviewTargetName(
    container: AppContainer,
    targetType: String,
    targetId: String,
): String = when (targetType) {
    WorldviewTargetType.CHARACTER ->
        container.characterRepository.getNow(targetId)?.name
            ?: "角色 $targetId（已不存在）"
    WorldviewTargetType.GROUP ->
        container.groupChatRepository.getGroup(targetId.toLongOrNull() ?: -1L)?.title?.ifBlank { "群聊" }
            ?: "群聊（已删除）"
    else -> targetId
}

/**
 * 查找占用指定目标的其它世界观（一一对应冲突检查）。
 * [excludeId] 用于编辑场景排除自身。
 */
private suspend fun worldviewConflictNow(
    container: AppContainer,
    targetType: String,
    targetId: String,
    excludeId: String?,
): Worldview? = container.settingsRepository.getWorldviewsNow().firstOrNull {
    it.targetType == targetType && it.targetId == targetId && it.id != excludeId
}

/**
 * 「自定义世界观」设置分区：列表 + 新建/编辑弹窗。
 *
 * 一一对应：一条世界观只绑定一个目标（某角色的私聊 / 某个群聊会话）；
 * 同一目标重复保存即替换旧绑定（[com.rhodesisland.terminal.data.repository.SettingsRepository.upsertWorldview]）。
 * 注入点见 [Worldview.directiveText] 的四处调用方。
 */
@Composable
fun WorldviewSection(container: AppContainer, scope: CoroutineScope) {
    val worldviews by container.settingsRepository.worldviews.collectAsState(initial = emptyList())

    var editTarget by remember { mutableStateOf<Worldview?>(null) }   // 编辑既有条目
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Worldview?>(null) }

    CollapsibleSection(
        title = "自定义世界观",
        key = "worldview",
        summary = if (worldviews.isEmpty()) "未添加" else "${worldviews.size} 条已生效",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "世界观是一段注入对话提示词的自定义设定（如「故事发生在末日废土」）。" +
                    "每条世界观绑定一个应用对象——某个角色的私聊或某个群聊；同一对象重复保存将替换旧设定。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (worldviews.isNotEmpty()) {
                worldviews.forEach { w ->
                    WorldviewRow(
                        container = container,
                        worldview = w,
                        onClick = { editTarget = w },
                        onDelete = { deleteTarget = w },
                    )
                }
            }
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建世界观", color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showCreate) {
        WorldviewEditDialog(
            container = container,
            existing = null,
            onDismiss = { showCreate = false },
        )
    }
    editTarget?.let { w ->
        WorldviewEditDialog(
            container = container,
            existing = w,
            onDismiss = { editTarget = null },
        )
    }
    deleteTarget?.let { w ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("删除世界观", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    "确定删除「${w.name}」？该对象将恢复为无自定义世界观。",
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { container.settingsRepository.removeWorldview(w.id) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun WorldviewRow(
    container: AppContainer,
    worldview: Worldview,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 目标显示名异步解析（DataStore/Room 读取），解析完成前显示占位
    var targetName by remember(worldview.targetId) { mutableStateOf("…") }
    androidx.compose.runtime.LaunchedEffect(worldview.targetType, worldview.targetId) {
        targetName = resolveWorldviewTargetName(container, worldview.targetType, worldview.targetId)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(worldview.name.ifBlank { "未命名世界观" }, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "→ $targetName · ${worldview.content.take(24)}${if (worldview.content.length > 24) "…" else ""}",
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        // 删除按钮：加大触达区（40dp）避免误触困难
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = scheme.error, modifier = Modifier.size(17.dp))
        }
    }
}

/**
 * 世界观新建/编辑弹窗：名称 + 正文 + 应用对象选择（私聊角色 / 群聊 二选一，均带搜索）。
 */
@Composable
private fun WorldviewEditDialog(
    container: AppContainer,
    existing: Worldview?,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var targetType by remember { mutableStateOf(existing?.targetType ?: WorldviewTargetType.CHARACTER) }
    var targetId by remember { mutableStateOf(existing?.targetId ?: "") }

    // 目标候选数据
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
    val groups by container.groupChatRepository.observeGroups().collectAsState(initial = emptyList())

    /** 保存时若目标已被其它世界观占用，先弹替换确认。 */
    var pendingReplace by remember { mutableStateOf<Worldview?>(null) }

    fun doSave() {
        scope.launch {
            container.settingsRepository.upsertWorldview(
                Worldview(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    content = content.trim(),
                    targetType = targetType,
                    targetId = targetId,
                ),
            )
            pendingReplace = null
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text(if (existing == null) "新建世界观" else "编辑世界观", color = scheme.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FieldLabel("名称")
                GlassInputField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "如：末日废土设定",
                )
                FieldLabel("世界观内容（注入提示词）")
                GlassInputField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = "描述这个世界观的规则、背景、氛围…",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                FieldLabel("应用到")
                GlassSegmented(
                    options = listOf(
                        WorldviewTargetType.CHARACTER to "私聊角色",
                        WorldviewTargetType.GROUP to "群聊",
                    ),
                    selected = targetType,
                    onSelect = {
                        targetType = it
                        targetId = ""
                    },
                )
                if (targetType == WorldviewTargetType.CHARACTER) {
                    CharacterPickerList(
                        characters = characters,
                        selectedId = targetId,
                        onSelect = { targetId = it },
                    )
                } else {
                    GroupPickerList(
                        groups = groups,
                        selectedId = targetId,
                        onSelect = { targetId = it.id.toString() },
                    )
                }
                if (name.isBlank() || content.isBlank() || targetId.isBlank()) {
                    Text("名称、内容与应用对象均为必填项", color = scheme.tertiary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && content.isNotBlank() && targetId.isNotBlank(),
                onClick = {
                    scope.launch {
                        // 一一对应检查：目标已被其它条目占用 → 先确认替换
                        val conflict = worldviewConflictNow(
                            container, targetType, targetId, excludeId = existing?.id,
                        )
                        if (conflict != null) {
                            pendingReplace = conflict
                        } else {
                            doSave()
                        }
                    }
                },
            ) { Text(if (existing == null) "创建" else "保存", color = scheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )

    pendingReplace?.let { conflict ->
        AlertDialog(
            onDismissRequest = { pendingReplace = null },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("替换已有世界观", color = scheme.primary) },
            text = {
                Text(
                    "该对象已有世界观「${conflict.name}」，保存后将替换它。",
                    color = scheme.onSurface, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { doSave() }) { Text("替换", color = scheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplace = null }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }
}

// 角色单选列表 CharacterPickerList / 群聊单选列表 GroupPickerList 已提升为
// TargetPickers.kt 的 internal 共享组件（世界书编辑页复用），同包直接引用。
