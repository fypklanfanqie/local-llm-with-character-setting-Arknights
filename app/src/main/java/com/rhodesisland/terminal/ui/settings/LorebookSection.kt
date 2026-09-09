package com.rhodesisland.terminal.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.lorebook.LorebookJson
import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookScopeType
import com.rhodesisland.terminal.i18n.L10nRuntime
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
import com.rhodesisland.terminal.ui.glass.CollapsibleSection
import com.rhodesisland.terminal.ui.glass.GlassButton
import com.rhodesisland.terminal.ui.glass.GlassButtonStyle
import com.rhodesisland.terminal.ui.glass.GlassListRow
import com.rhodesisland.terminal.ui.glass.GlassTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 · 世界书分区（移植自大众版）：多本世界书管理入口 + 全局参数
 * （总开关/扫描深度/递归/token 预算）。
 *
 * 世界书按作用域路由（全局 / 多选角色 / 多选群聊）；条目管理与编辑走独立路由页
 * （lorebook/{bookId}），本分区只承载书列表与开关。导入解析见 data/lorebook/LorebookJson。
 */
@Composable
fun LorebookSection(
    container: AppContainer,
    onNavigateToLorebook: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val books by container.settingsRepository.lorebooks.collectAsState(initial = emptyList())
    val config by container.settingsRepository.lorebookConfig.collectAsState(initial = com.rhodesisland.terminal.data.model.LorebookGlobalConfig())

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Lorebook?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var showScanDepthEdit by remember { mutableStateOf(false) }
    var showBudgetEdit by remember { mutableStateOf(false) }

    // SAF 导入：部分文件管理器对 .json 给 application/json，其余可能给 text/plain / octet-stream
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            when {
                result == null -> importMessage = L10nRuntime.t("读取文件失败")
                else -> when (val parsed = LorebookJson.parseSillyTavern(result)) {
                    is LorebookJson.ParseResult.Fail -> importMessage = L10nRuntime.format("导入失败：{0}", parsed.message)
                    is LorebookJson.ParseResult.Ok -> {
                        // 追加新书（id 全新生成，不做同名覆盖防误删）
                        val bookName = parsed.name ?: L10nRuntime.t("导入的世界书")
                        val newBook = Lorebook(
                            id = "lb-" + System.currentTimeMillis(),
                            name = bookName,
                            enabled = true,
                            entries = parsed.entries,
                        )
                        container.settingsRepository.updateLorebooks { it + newBook }
                        importMessage = buildString {
                            append(L10nRuntime.format("已导入「{0}」共 {1} 条", newBook.name, parsed.entries.size))
                            parsed.warning?.let { append("\n$it") }
                        }
                    }
                }
            }
        }
    }

    CollapsibleSection(
        title = t("世界书"),
        key = "lorebook",
        initiallyExpanded = false,
        headerExtra = {
            Switch(
                checked = config.masterEnabled,
                onCheckedChange = { checked ->
                    scope.launch { container.settingsRepository.updateLorebookConfig { it.copy(masterEnabled = checked) } }
                },
            )
        },
    ) {
        Text(
            text = t("按关键词触发的背景设定库：对话提到关键词时自动注入对应设定。") +
                t("支持导入 SillyTavern 世界书 JSON 文件，可按作用域绑定角色私聊与群聊。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (!config.masterEnabled) {
            Text(
                text = t("世界书总开关已关闭，所有条目均不会注入。"),
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (books.isEmpty()) {
            Text(
                text = t("还没有世界书，点下方「新建」或「导入 .json」开始。"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        books.forEach { book ->
            GlassListRow(
                title = if (book.name.isBlank()) t("未命名世界书") else book.name,
                subtitle = scopeSummary(book) + " · " +
                    tf("{0} 条 · {1} 启用", book.entries.size, book.entries.count { it.enabled }) +
                    if (book.enabled) "" else " · " + t("已停用"),
                onClick = { onNavigateToLorebook(book.id) },
                showDivider = false,
                trailing = {
                    Switch(
                        checked = book.enabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                container.settingsRepository.updateLorebooks { list ->
                                    list.map { if (it.id == book.id) it.copy(enabled = checked) else it }
                                }
                            }
                        },
                    )
                },
            )
        }

        // 全局参数
        GlassListRow(
            title = t("扫描深度"),
            subtitle = tf("扫描最近 {0} 条消息中的关键词", config.scanDepth),
            onClick = { showScanDepthEdit = true },
            showDivider = false,
        )
        GlassListRow(
            title = t("Token 预算上限"),
            subtitle = if (config.budgetCapTokens > 0) tf("单次注入不超过约 {0} tokens", config.budgetCapTokens) else t("不限"),
            onClick = { showBudgetEdit = true },
            showDivider = false,
        )
        GlassListRow(
            title = t("递归扫描"),
            subtitle = t("已激活条目的内容可再触发其他条目"),
            trailing = {
                Switch(
                    checked = config.recursiveScanning,
                    onCheckedChange = { checked ->
                        scope.launch { container.settingsRepository.updateLorebookConfig { it.copy(recursiveScanning = checked) } }
                    },
                )
            },
            showDivider = false,
        )

        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                GlassButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth(), style = GlassButtonStyle.Tinted) {
                    Text(t("新建"), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
            }
            Box(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                GlassButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                    style = GlassButtonStyle.Tinted,
                ) { Text(t("导入 .json"), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) }
            }
        }
    }

    // 新建命名弹窗
    if (showCreate) {
        NamePromptDialog(
            title = t("新建世界书"),
            placeholder = t("如：修仙世界"),
            confirmText = t("创建"),
            onConfirm = { name ->
                scope.launch {
                    container.settingsRepository.updateLorebooks {
                        it + Lorebook(id = "lb-" + System.currentTimeMillis(), name = name.trim())
                    }
                }
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(t("删除世界书"), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    tf(
                        "确定删除「{0}」及其全部 {1} 个条目？该操作不可恢复。",
                        if (target.name.isBlank()) t("未命名世界书") else target.name,
                        target.entries.size,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.settingsRepository.updateLorebooks { list -> list.filterNot { it.id == target.id } }
                    }
                    deleteTarget = null
                }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t("取消"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
    // 导入结果反馈
    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(t("世界书导入"), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { importMessage = null }) { Text(t("知道了")) }
            },
        )
    }
    // 扫描深度编辑
    if (showScanDepthEdit) {
        NumberEditDialog(
            title = t("扫描深度"),
            label = t("扫描最近多少条消息中的关键词（1-20）"),
            current = config.scanDepth,
            range = 1..20,
            onSave = { value ->
                scope.launch { container.settingsRepository.updateLorebookConfig { it.copy(scanDepth = value) } }
                showScanDepthEdit = false
            },
            onDismiss = { showScanDepthEdit = false },
        )
    }
    // token 预算编辑（0 = 不限）
    if (showBudgetEdit) {
        NumberEditDialog(
            title = t("Token 预算上限"),
            label = t("单次注入的 token 上限（0 表示不限）"),
            current = config.budgetCapTokens,
            range = 0..8192,
            onSave = { value ->
                scope.launch { container.settingsRepository.updateLorebookConfig { it.copy(budgetCapTokens = value) } }
                showBudgetEdit = false
            },
            onDismiss = { showBudgetEdit = false },
        )
    }
}

/** 单行命名输入弹窗（新建世界书用）。 */
@Composable
private fun NamePromptDialog(
    title: String,
    placeholder: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text(title, color = scheme.onSurface) },
        text = {
            Column {
                GlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = placeholder,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text(confirmText, color = if (name.isNotBlank()) scheme.primary else scheme.onSurfaceVariant) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消"), color = scheme.onSurfaceVariant) }
        },
    )
}

/** 书作用域摘要：全局 / N 个角色 / N 个群聊。 */
internal fun scopeSummary(book: Lorebook): String = when (book.scopeType) {
    LorebookScopeType.ALL -> L10nRuntime.t("全局")
    LorebookScopeType.CHARACTER ->
        if (book.scopeIds.isEmpty()) L10nRuntime.t("未绑定角色") else L10nRuntime.format("{0} 个角色", book.scopeIds.size)
    LorebookScopeType.GROUP ->
        if (book.scopeIds.isEmpty()) L10nRuntime.t("未绑定群聊") else L10nRuntime.format("{0} 个群聊", book.scopeIds.size)
}

/** 数值输入弹窗（扫描深度 / token 预算）。 */
@Composable
private fun NumberEditDialog(
    title: String,
    label: String,
    current: Int,
    range: IntRange,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val canSave = parsed != null && parsed in range
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text(title, color = scheme.onSurface) },
        text = {
            Column {
                Text(label, color = scheme.onSurfaceVariant, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    // 纯数字输入：非数字字符直接吞掉
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 5) text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(parsed!!) }) {
                Text(t("保存"), color = if (canSave) scheme.primary else scheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消"), color = scheme.onSurfaceVariant) }
        },
    )
}
