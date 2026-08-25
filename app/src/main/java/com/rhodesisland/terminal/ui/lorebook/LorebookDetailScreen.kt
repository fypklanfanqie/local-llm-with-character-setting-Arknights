package com.rhodesisland.terminal.ui.lorebook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.lorebook.LorebookJson
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookEntry
import com.rhodesisland.terminal.data.model.LorebookInsertPosition
import com.rhodesisland.terminal.data.model.LorebookScopeType
import com.rhodesisland.terminal.ui.settings.filterCharacters
import com.rhodesisland.terminal.ui.settings.filterGroups
import com.rhodesisland.terminal.ui.glass.GlassButton
import com.rhodesisland.terminal.ui.glass.GlassButtonStyle
import com.rhodesisland.terminal.ui.glass.GlassListRow
import com.rhodesisland.terminal.ui.glass.GlassSegmented
import com.rhodesisland.terminal.ui.glass.GlassTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 世界书详情页（独立路由 lorebook/{bookId}，移植自大众版）：书名编辑、条目列表（启用开关）、
 * 生效范围（多选角色/群聊）、添加条目、导出本书（SillyTavern 兼容 JSON）。
 */
@Composable
fun LorebookDetailScreen(
    container: AppContainer,
    bookId: String,
    onBack: () -> Unit,
    onOpenEntry: (entryId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // initial=null 区分「加载中」与「确认不存在」，避免首帧闪现错误空态
    val books by container.settingsRepository.lorebooks.collectAsState(initial = null)
    val book = books?.firstOrNull { it.id == bookId }

    // 书不存在（已被删除或路由参数无效）：不自动返回——DataStore 首次发射前列表为空，
    // 立即 pop 会误伤正常打开路径；展示空态由用户手动返回
    var showRename by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    // 启动选择器时快照序列化结果，回调直接写盘（避免等待期间重命名/编辑导致内容不一致）
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExportJson
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "w")?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            if (!ok) exportError = "导出失败，请重试"
        }
    }

    if (book != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            // 顶部：返回 + 标题 + 重命名
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    book.name.ifBlank { "未命名世界书" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { showRename = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "重命名", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = "${book.entries.size} 个条目 · 点击条目编辑详情",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

            // 生效范围：全局 / 指定角色（多选）/ 指定群聊（多选）
            val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
            val groups by container.groupChatRepository.observeGroups().collectAsState(initial = emptyList())
            var showScopePicker by remember { mutableStateOf(false) }
            GlassListRow(
                title = "生效范围",
                subtitle = scopeDescription(book, characters, groups),
                onClick = { showScopePicker = true },
                showDivider = false,
                trailing = {
                    Icon(
                        Icons.Filled.Edit, contentDescription = "修改生效范围",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                    )
                },
            )
            if (showScopePicker) {
                ScopePickerDialog(
                    book = book,
                    characters = characters,
                    groups = groups,
                    onConfirm = { scopeType, scopeIds ->
                        scope.launch {
                            container.settingsRepository.updateLorebooks { list ->
                                list.map {
                                    if (it.id == book.id) it.copy(scopeType = scopeType, scopeIds = scopeIds) else it
                                }
                            }
                        }
                        showScopePicker = false
                    },
                    onDismiss = { showScopePicker = false },
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                items(book.entries, key = { it.id }) { entry ->
                    GlassListRow(
                        title = entry.displayTitle(),
                        subtitle = buildString {
                            append(positionLabel(entry))
                            append(" · 顺序 ${entry.order}")
                            if (entry.constant) append(" · 常驻")
                            if (!entry.enabled) append(" · 已停用")
                            append("\n")
                            append(entry.content.lineSequence().firstOrNull().orEmpty().take(30))
                            if (entry.content.length > 30) append("…")
                        },
                        onClick = { onOpenEntry(entry.id) },
                        showDivider = false,
                        trailing = {
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        container.settingsRepository.updateLorebooks { list ->
                                            list.map { b ->
                                                if (b.id != book.id) b
                                                else b.copy(entries = b.entries.map { e ->
                                                    if (e.id == entry.id) e.copy(enabled = checked) else e
                                                })
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }

            // 底部操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GlassButton(
                        onClick = { onOpenEntry("new") },
                        modifier = Modifier.fillMaxWidth(),
                        style = GlassButtonStyle.Tinted,
                    ) { Text("添加条目", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
                Box(modifier = Modifier.weight(1f)) {
                    GlassButton(
                        onClick = {
                            pendingExportJson = LorebookJson.toSillyTavernJson(book)
                            exportLauncher.launch("${book.name.ifBlank { "世界书" }}.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = GlassButtonStyle.Tinted,
                    ) { Text("导出本书", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    } else {
        // 空态：书不存在（已被删除）或 DataStore 仍在加载
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "世界书",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (books == null) "加载中…" else "世界书不存在或已被删除",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
    }

    // 重命名弹窗（复用分区的命名弹窗样式）
    if (showRename && book != null) {
        RenameDialog(
            current = book.name,
            onConfirm = { name ->
                scope.launch {
                    container.settingsRepository.updateLorebooks { list ->
                        list.map { if (it.id == book.id) it.copy(name = name) else it }
                    }
                }
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("导出", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { exportError = null }) { Text("知道了") }
            },
        )
    }
}

/** 条目标题展示：备注名 > 首关键词 > 未命名。 */
internal fun LorebookEntry.displayTitle(): String = when {
    title.isNotBlank() -> title.trim()
    keys.isNotEmpty() -> keys.first().trim()
    else -> "未命名条目"
}

/** 位置标签：设定前 / 设定后 / @深度N。 */
internal fun positionLabel(entry: LorebookEntry): String = when (entry.position) {
    LorebookInsertPosition.BEFORE_CHAR -> "角色设定前"
    LorebookInsertPosition.AFTER_CHAR -> "角色设定后"
    LorebookInsertPosition.AT_DEPTH -> "@深度${entry.depth}"
}

/** 生效范围描述：全局 / 角色名列表 / 群名列表（悬空 id 显式提示）。 */
internal fun scopeDescription(
    book: Lorebook,
    characters: List<com.rhodesisland.terminal.data.model.Character>,
    groups: List<Conversation>,
): String = when (book.scopeType) {
    LorebookScopeType.ALL -> "全局：所有角色聊天与群聊都生效"
    LorebookScopeType.CHARACTER -> {
        if (book.scopeIds.isEmpty()) "未绑定角色（点此选择）"
        else book.scopeIds.map { id -> characters.firstOrNull { it.id == id }?.name ?: "已删除角色" }
            .joinToString("、").let { "仅角色：$it" }
    }
    LorebookScopeType.GROUP -> {
        if (book.scopeIds.isEmpty()) "未绑定群聊（点此选择）"
        else book.scopeIds.map { id ->
            groups.firstOrNull { it.id.toString() == id }?.title?.ifBlank { "群聊" } ?: "已删除群聊"
        }.joinToString("、").let { "仅群聊：$it" }
    }
}

/**
 * 生效范围编辑弹窗：类型分段（全局/角色/群聊）+ 多选列表（固定高度 LazyColumn，
 * AlertDialog 内长列表不能 verticalScroll+heightIn 无界约束——项目已知踩坑）。
 */
@Composable
private fun ScopePickerDialog(
    book: Lorebook,
    characters: List<com.rhodesisland.terminal.data.model.Character>,
    groups: List<Conversation>,
    onConfirm: (LorebookScopeType, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 每次打开某本书都播种新草稿；角色/群聊选择分桶，切换类型不再串 ID。
    var scopeType by remember(book.id) { mutableStateOf(book.scopeType) }
    var characterSelectedIds by remember(book.id) {
        mutableStateOf(if (book.scopeType == LorebookScopeType.CHARACTER) book.scopeIds.toSet() else emptySet())
    }
    var groupSelectedIds by remember(book.id) {
        mutableStateOf(if (book.scopeType == LorebookScopeType.GROUP) book.scopeIds.toSet() else emptySet())
    }
    var searchQuery by rememberSaveable(book.id) { mutableStateOf("") }

    fun toggleCharacter(id: String) {
        characterSelectedIds = if (id in characterSelectedIds) characterSelectedIds - id else characterSelectedIds + id
    }
    fun toggleGroup(id: String) {
        groupSelectedIds = if (id in groupSelectedIds) groupSelectedIds - id else groupSelectedIds + id
    }

    val characterSelected = characterSelectedIds
    val groupSelected = groupSelectedIds
    val filteredCharacters = filterCharacters(characters, searchQuery)
    val filteredGroups = filterGroups(groups, searchQuery)
    val missingCharacters = characterSelected - characters.map { it.id }.toSet()
    val missingGroups = groupSelected - groups.map { it.id.toString() }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text("生效范围", color = scheme.onSurface) },
        text = {
            Column {
                GlassSegmented(
                    options = listOf(
                        LorebookScopeType.ALL to "全局",
                        LorebookScopeType.CHARACTER to "指定角色",
                        LorebookScopeType.GROUP to "指定群聊",
                    ),
                    selected = scopeType,
                    onSelect = {
                        scopeType = it
                        searchQuery = ""
                    },
                )
                if (scopeType != LorebookScopeType.ALL) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surface.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = scheme.onSurface, fontSize = 12.sp),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        if (scopeType == LorebookScopeType.CHARACTER) "搜索角色名、代号或 ID" else "搜索群聊名称或 ID",
                                        color = scheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                }
                when (scopeType) {
                    LorebookScopeType.ALL -> Text(
                        "所有角色聊天与群聊都会应用本书。",
                        color = scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    LorebookScopeType.CHARACTER -> {
                        val rows = missingCharacters.map { it to "已删除角色（ID: $it）" } +
                            filteredCharacters.map { it.id to it.name }
                        if (rows.isEmpty()) {
                            Text("没有找到匹配的角色", color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
                                items(rows, key = { it.first }) { (id, label) ->
                                    ScopeOptionRow(label = label, checked = id in characterSelected, onToggle = { toggleCharacter(id) })
                                }
                            }
                        }
                    }
                    LorebookScopeType.GROUP -> {
                        val rows = missingGroups.map { it to "已删除群聊（ID: $it）" } +
                            filteredGroups.map { it.id.toString() to (it.title.ifBlank { "群聊" }) }
                        if (rows.isEmpty()) {
                            Text("没有找到匹配的群聊", color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
                                items(rows, key = { it.first }) { (id, label) ->
                                    ScopeOptionRow(label = label, checked = id in groupSelected, onToggle = { toggleGroup(id) })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ids = when (scopeType) {
                    LorebookScopeType.ALL -> emptyList()
                    LorebookScopeType.CHARACTER -> characterSelectedIds.toList()
                    LorebookScopeType.GROUP -> groupSelectedIds.toList()
                }
                onConfirm(scopeType, ids)
            }) { Text("保存", color = scheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun ScopeOptionRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text("重命名世界书", color = scheme.onSurface) },
        text = {
            GlassTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "世界书名称",
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text("保存", color = if (name.isNotBlank()) scheme.primary else scheme.onSurfaceVariant) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )
}