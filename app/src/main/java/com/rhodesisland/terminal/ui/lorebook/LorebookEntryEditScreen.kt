package com.rhodesisland.terminal.ui.lorebook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookEntry
import com.rhodesisland.terminal.data.model.LorebookInsertPosition
import com.rhodesisland.terminal.data.model.LorebookSecondaryLogic
import com.rhodesisland.terminal.ui.glass.CollapsibleSection
import com.rhodesisland.terminal.ui.glass.GlassButton
import com.rhodesisland.terminal.ui.glass.GlassButtonStyle
import com.rhodesisland.terminal.ui.glass.GlassListRow
import com.rhodesisland.terminal.ui.glass.GlassSegmented
import com.rhodesisland.terminal.ui.glass.GlassTextField
import kotlinx.coroutines.launch

/**
 * 世界书条目编辑页（独立路由 lorebook/{bookId}/entry/{entryId}，entryId="new" 表新建，
 * 移植自大众版）。
 *
 * 完整版字段全量呈现；低频项收进「高级」折叠区。保存走 updateLorebooks 单事务原位替换，
 * 删除同理。关键词输入支持顿号/逗号/分号/换行分隔。
 *
 * 时序说明：外层以 `collectAsState(initial = null)` 区分「加载中 / 不存在 / 就绪」，
 * 表单体 [EntryFormBody] 仅在目标书就绪后进入组合——保证 rememberSaveable 草稿以真实数据播种，
 * 不会出现「先空白后填充导致保存清空条目」的竞态。
 */
@Composable
fun LorebookEntryEditScreen(
    container: AppContainer,
    bookId: String,
    entryId: String,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val books by container.settingsRepository.lorebooks.collectAsState(initial = null)
    val book = books?.firstOrNull { it.id == bookId }
    val isNew = entryId == "new"
    val editing = if (isNew) null else book?.entries?.firstOrNull { it.id == entryId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // 顶部：返回 + 标题 + 删除（编辑模式）
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = scheme.onSurface)
            }
            Text(
                if (isNew) "添加条目" else "编辑条目",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
        }

        when {
            // DataStore 首次发射前：加载态（不进表单，防草稿误播种）
            books == null -> CenterHint("加载中…")
            book == null || (!isNew && editing == null) -> CenterHint("世界书或条目不存在，可能已被删除")
            else -> EntryFormBody(
                container = container,
                bookId = bookId,
                isNew = isNew,
                editing = editing,
                onBack = onBack,
            )
        }
    }
}

/** 表单主体：仅在目标书确认存在后组合，草稿播种时机即正确。 */
@Composable
private fun EntryFormBody(
    container: AppContainer,
    bookId: String,
    isNew: Boolean,
    editing: LorebookEntry?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var title by rememberSaveable { mutableStateOf(editing?.title ?: "") }
    var keysText by rememberSaveable { mutableStateOf(editing?.keys?.joinToString("、") ?: "") }
    var secondaryText by rememberSaveable { mutableStateOf(editing?.secondaryKeys?.joinToString("、") ?: "") }
    var logic by rememberSaveable { mutableStateOf(editing?.logic ?: LorebookSecondaryLogic.AND_ANY) }
    var content by rememberSaveable { mutableStateOf(editing?.content ?: "") }
    var constant by rememberSaveable { mutableStateOf(editing?.constant ?: false) }
    var position by rememberSaveable { mutableStateOf(editing?.position ?: LorebookInsertPosition.BEFORE_CHAR) }
    var depthText by rememberSaveable { mutableStateOf((editing?.depth ?: 4).toString()) }
    var orderText by rememberSaveable { mutableStateOf((editing?.order ?: 100).toString()) }
    var probabilityText by rememberSaveable { mutableStateOf((editing?.probability ?: 100).toString()) }
    var caseSensitive by rememberSaveable { mutableStateOf(editing?.caseSensitive ?: false) }
    var wholeWords by rememberSaveable { mutableStateOf(editing?.matchWholeWords ?: false) }
    var preventRecursion by rememberSaveable { mutableStateOf(editing?.preventRecursion ?: false) }
    var excludeRecursion by rememberSaveable { mutableStateOf(editing?.excludeRecursion ?: false) }
    var scanDepthOverrideText by rememberSaveable { mutableStateOf(editing?.scanDepthOverride?.toString() ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val keys = parseKeywords(keysText)
    val secondaryKeys = parseKeywords(secondaryText)
    val depth = depthText.toIntOrNull()?.coerceIn(0, 100) ?: 4
    val order = orderText.toIntOrNull() ?: 100
    val probability = probabilityText.toIntOrNull()?.coerceIn(1, 100) ?: 100
    // 扫描深度覆盖：留空 = 继承全局（null）
    val scanDepthOverride = scanDepthOverrideText.toIntOrNull()?.takeIf { it > 0 }
    val canSave = content.isNotBlank() && (constant || keys.isNotEmpty())

    fun saveEntry() {
        val built = LorebookEntry(
            id = editing?.id ?: ("lbe-" + System.currentTimeMillis()),
            title = title.trim(),
            keys = keys,
            secondaryKeys = secondaryKeys,
            logic = logic,
            content = content.trim(),
            constant = constant,
            enabled = editing?.enabled ?: true,
            position = position,
            depth = depth,
            order = order,
            probability = probability,
            caseSensitive = caseSensitive,
            matchWholeWords = wholeWords,
            scanDepthOverride = scanDepthOverride,
            preventRecursion = preventRecursion,
            excludeRecursion = excludeRecursion,
        )
        // 写入完成后再返回：rememberCoroutineScope 随页面离开组合而取消，
        // 若先 pop 再等写盘，协程会被取消导致保存丢失。DataStore 写入毫秒级，无感知延迟。
        scope.launch {
            container.settingsRepository.updateLorebooks { list ->
                list.map { b ->
                    if (b.id != bookId) b
                    else if (isNew) b.copy(entries = b.entries + built)
                    else b.copy(entries = b.entries.map { if (it.id == editing?.id) built else it })
                }
            }
            onBack()
        }
    }

    fun deleteEntry() {
        // 同 saveEntry：等删除写盘完成再返回，避免协程被取消导致删除丢失
        scope.launch {
            container.settingsRepository.updateLorebooks { list ->
                list.map { b ->
                    if (b.id != bookId) b
                    else b.copy(entries = b.entries.filterNot { it.id == editing?.id })
                }
            }
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isNew) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = scheme.error)
                }
                Text(
                    "删除此条目",
                    color = scheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }

        FormLabel("备注名")
        GlassTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = "如：青云宗（留空时用首个关键词当标题）",
        )

        FormLabel("主关键词（顿号/逗号分隔，命中任一即触发）")
        GlassTextField(
            value = keysText,
            onValueChange = { keysText = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = "如：青云宗、玄真子、大师姐",
        )

        FormLabel("条目内容")
        BasicTextField(
            value = content,
            onValueChange = { if (it.length <= 8000) content = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface.copy(alpha = 0.6f))
                .padding(10.dp),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp),
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                Box {
                    if (content.isEmpty()) {
                        Text(
                            "命中后注入给 AI 的背景设定正文…",
                            color = scheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                }
            },
        )

        FormLabel("次级关键词（可选，配合逻辑做二次筛选）")
        GlassTextField(
            value = secondaryText,
            onValueChange = { secondaryText = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = "留空则不做次级判定",
        )
        if (secondaryKeys.isNotEmpty()) {
            FormLabel("次级逻辑")
            GlassSegmented(
                options = listOf(
                    LorebookSecondaryLogic.AND_ANY to "任一在",
                    LorebookSecondaryLogic.AND_ALL to "全在",
                    LorebookSecondaryLogic.NOT_ALL to "非全在",
                    LorebookSecondaryLogic.NOT_ANY to "全不在",
                ),
                selected = logic,
                onSelect = { logic = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        FormLabel("插入位置")
        GlassSegmented(
            options = listOf(
                LorebookInsertPosition.BEFORE_CHAR to "设定前",
                LorebookInsertPosition.AFTER_CHAR to "设定后",
                LorebookInsertPosition.AT_DEPTH to "@深度",
            ),
            selected = position,
            onSelect = { position = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (position == LorebookInsertPosition.AT_DEPTH) {
            NumberFieldRow(label = "插入深度（插到倒数第几条消息上方）", value = depthText) { depthText = it }
        }

        NumberFieldRow(label = "插入顺序（越大越靠下、影响越强）", value = orderText) { orderText = it }
        NumberFieldRow(label = "触发概率 %（100 = 必触发）", value = probabilityText) { probabilityText = it }

        GlassListRow(
            title = "常驻条目（蓝灯）",
            subtitle = "无需关键词，每次对话都注入",
            trailing = {
                Switch(checked = constant, onCheckedChange = { constant = it })
            },
            showDivider = false,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (!constant && keys.isEmpty()) {
            Text(
                text = "非常驻条目至少需要一个主关键词",
                color = scheme.tertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        // 高级选项折叠区
        CollapsibleSection(title = "高级", key = "lorebook_entry_advanced", initiallyExpanded = false) {
            GlassListRow(
                title = "区分大小写",
                subtitle = "主要针对英文关键词",
                trailing = { Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it }) },
                showDivider = false,
            )
            GlassListRow(
                title = "匹配整个单词",
                subtitle = "英文全词匹配；中文关键词自动按子串处理",
                trailing = { Switch(checked = wholeWords, onCheckedChange = { wholeWords = it }) },
                showDivider = false,
            )
            GlassListRow(
                title = "防止递归",
                subtitle = "本条内容不再触发其他条目",
                trailing = { Switch(checked = preventRecursion, onCheckedChange = { preventRecursion = it }) },
                showDivider = false,
            )
            GlassListRow(
                title = "排除递归",
                subtitle = "本条只能由对话文本直接触发，不被其他条目连锁激活",
                trailing = { Switch(checked = excludeRecursion, onCheckedChange = { excludeRecursion = it }) },
                showDivider = false,
            )
            NumberFieldRow(
                label = "扫描深度覆盖（留空 = 用全局设置）",
                value = scanDepthOverrideText,
                allowEmpty = true,
            ) { scanDepthOverrideText = it }
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            GlassButton(
                onClick = { saveEntry() },
                modifier = Modifier.fillMaxWidth(),
                style = GlassButtonStyle.Tinted,
                enabled = canSave,
            ) {
                Text(if (isNew) "创建条目" else "保存", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = scheme.surfaceContainerHigh,
            titleContentColor = scheme.onSurface,
            title = { Text("删除条目", color = scheme.onSurface) },
            text = {
                Text(
                    "确定删除「${displayTitleCompat(title, keys)}」？该操作不可恢复。",
                    color = scheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; deleteEntry() }) {
                    Text("删除", color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }
}

/** 关键词解析：顿号/逗号/分号/换行均可分隔，trim 去空去重。 */
internal fun parseKeywords(raw: String): List<String> =
    raw.split('、', '，', ',', ';', '；', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun displayTitleCompat(title: String, keys: List<String>): String =
    when {
        title.trim().isNotBlank() -> title.trim()
        keys.isNotEmpty() -> keys.first()
        else -> "未命名条目"
    }

@Composable
private fun CenterHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** 单行数字输入（标签 + 玻璃输入框）；allowEmpty 时允许清空（表示继承全局）。 */
@Composable
private fun NumberFieldRow(
    label: String,
    value: String,
    allowEmpty: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.padding(top = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = { input ->
                if ((input.all(Char::isDigit) && input.length <= 5) || (allowEmpty && input.isEmpty())) {
                    onValueChange(input)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surface.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp),
            singleLine = true,
        )
    }
}