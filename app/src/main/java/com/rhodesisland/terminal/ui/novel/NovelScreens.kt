package com.rhodesisland.terminal.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.local.NovelChapterEntity
import com.rhodesisland.terminal.data.local.NovelLineEntity
import com.rhodesisland.terminal.data.repository.NovelRepository
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
import com.rhodesisland.terminal.llm.NovelScriptParser
import com.rhodesisland.terminal.ui.glass.GlassTextField
import com.rhodesisland.terminal.util.RelativeTime

private val NOVEL_BG = Color(0xFF0E1116)
private val NOVEL_SURFACE = Color(0xFF1A2029)
private val NOVEL_SURFACE_2 = Color(0xFF242B36)
private val NOVEL_TEXT = Color(0xFFDDE2E9)
private val NOVEL_TEXT_DIM = Color(0xFF8A93A0)
private val NOVEL_ACCENT = Color(0xFFC9A0DC)
private val NOVEL_ACCENT_DIM = Color(0x33C9A0DC)

/**
 * 小说模式主页：故事列表 + 新建故事。
 */
@Composable
fun NovelHomeScreen(
    container: AppContainer,
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
    onOpenStory: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: NovelHomeViewModel = viewModel(
        factory = viewModelFactory { initializer { NovelHomeViewModel(container) } },
    )
    val stories by viewModel.stories.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize().background(NOVEL_BG)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
            item {
                NovelTopBar(title = t("小说"), onBack = onBack) {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Filled.Add, contentDescription = t("新建故事"), tint = NOVEL_TEXT)
                    }
                }
            }
            if (stories.isEmpty()) {
                item {
                    Text(
                        t("还没有故事\n点右上角 + 创建你的第一部小说"),
                        color = NOVEL_TEXT_DIM, fontSize = 13.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    )
                }
            }
            items(stories, key = { it.id }) { story ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NOVEL_SURFACE)
                        .clickable { onOpenStory(story.id) }
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            story.title,
                            color = NOVEL_TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { deleteTarget = story.id }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = t("删除故事"), tint = NOVEL_TEXT_DIM, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (story.background.isNotBlank()) {
                        Text(
                            story.background,
                            color = NOVEL_TEXT_DIM, fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tf(t("更新于 {0}"), RelativeTime.format(story.updatedAt, System.currentTimeMillis())),
                        color = NOVEL_TEXT_DIM.copy(alpha = 0.7f), fontSize = 10.sp,
                    )
                }
            }
            item { Spacer(Modifier.height(bottomBarHeight + 24.dp)) }
        }

        error?.let { message ->
            NovelErrorBar(message, bottomBarHeight) { viewModel.clearError() }
        }
    }

    if (showCreate) {
        CreateStoryDialog(
            container = container,
            onDismiss = { showCreate = false },
            onCreate = { title, bg, memberIds, npcs, pName, pPersona, castRoles ->
                viewModel.createStory(title, bg, memberIds, npcs, pName, pPersona, castRoles) {
                    showCreate = false
                    onOpenStory(it)
                }
            },
        )
    }
    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("删除故事")) },
            text = { Text(t("删除后该故事的全部章节与正文都将移除，确定？")) },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; viewModel.deleteStory(id) }) {
                    Text(t("删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t("取消")) } },
        )
    }
}

/**
 * 章节管理页：章节行 + 新建一话 + 上移/下移/删除。
 */
@Composable
fun NovelStoryScreen(
    container: AppContainer,
    storyId: Long,
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: NovelStoryViewModel = viewModel(
        key = "novel_story_$storyId",
        factory = viewModelFactory { initializer { NovelStoryViewModel(container, storyId) } },
    )
    val state by viewModel.uiState.collectAsState()
    val castError by viewModel.castErrorMessage.collectAsState()
    var showCast by remember { mutableStateOf(false) }
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())

    Box(Modifier.fillMaxSize().background(NOVEL_BG)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
            item {
                NovelTopBar(title = state.story?.title ?: t("小说"), onBack = onBack) {
                    IconButton(onClick = { showCast = true }) {
                        Icon(Icons.Filled.Group, contentDescription = t("角色阵容"), tint = NOVEL_TEXT)
                    }
                    IconButton(onClick = { viewModel.createChapter(onCreated = {}) }) {
                        Icon(Icons.Filled.Add, contentDescription = t("新建一话"), tint = NOVEL_TEXT)
                    }
                }
            }
            state.story?.let { story ->
                val memberIds = container.novelRepository.decodeMemberIds(story)
                val npcs = container.novelRepository.decodeCustomNpcs(story)
                item {
                    Text(
                        castSummaryText(
                            characters = characters,
                            memberIds = memberIds,
                            npcs = npcs,
                            castRoles = container.novelRepository.decodeCastRoles(story),
                        ),
                        color = NOVEL_ACCENT, fontSize = 11.sp,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NOVEL_SURFACE_2)
                            .clickable { showCast = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                Text(
                    t("点击章节进入创作"),
                    color = NOVEL_TEXT_DIM.copy(alpha = 0.7f), fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            itemsIndexed(state.chapters, key = { _, c -> c.id }) { index, chapter ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NOVEL_SURFACE)
                        .clickable { onOpenChapter(chapter.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%02d".format(index + 1),
                            color = NOVEL_ACCENT, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                chapter.title.ifBlank { tf("第 {0} 话", index + 1) },
                                color = NOVEL_TEXT, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = buildList {
                                if (chapter.summary.isNotBlank()) add(t("有设定"))
                                if (chapter.requirements.isNotBlank()) add(t("有要求"))
                            }.joinToString(" · ")
                            Text(
                                subtitle.ifBlank { t("尚未设定") },
                                color = NOVEL_TEXT_DIM, fontSize = 11.sp,
                            )
                        }
                        // 上移 / 下移 / 删除
                        if (index > 0) {
                            Text("↑", color = NOVEL_TEXT_DIM, fontSize = 16.sp,
                                modifier = Modifier.clickable { viewModel.moveChapter(chapter, -1) }.padding(6.dp))
                        }
                        if (index < state.chapters.size - 1) {
                            Text("↓", color = NOVEL_TEXT_DIM, fontSize = 16.sp,
                                modifier = Modifier.clickable { viewModel.moveChapter(chapter, +1) }.padding(6.dp))
                        }
                        IconButton(onClick = { viewModel.deleteChapter(chapter.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = t("删除章节"), tint = NOVEL_TEXT_DIM, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NOVEL_SURFACE_2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { viewModel.createChapter(onCreated = {}) },
                ) {
                    Row(
                        Modifier.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = NOVEL_ACCENT, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("新建一话"), color = NOVEL_ACCENT, fontSize = 14.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(bottomBarHeight + 24.dp)) }
        }

        castError?.let { message ->
            NovelErrorBar(message, bottomBarHeight) { viewModel.clearError() }
        }
    }

    // 阵容弹窗：写作过程中随时增删角色 / 改主角配角，保存后 story Flow 立即刷新
    val castStory = state.story
    if (showCast && castStory != null) {
        CastManagerSheet(
            characters = characters,
            memberIds = container.novelRepository.decodeMemberIds(castStory),
            npcs = container.novelRepository.decodeCustomNpcs(castStory),
            castRoles = container.novelRepository.decodeCastRoles(castStory),
            onDismiss = { showCast = false },
            onSave = { memberIds, npcs, castRoles ->
                viewModel.updateCast(memberIds, npcs, castRoles)
                showCast = false
            },
        )
    }
}

/**
 * 对白编辑器：脚本行流 + 发言人切换 + 手动追加 + 本话设定 + AI 续写（流式预览）。
 */
@Composable
fun NovelEditorScreen(
    container: AppContainer,
    chapterId: Long,
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: NovelEditorViewModel = viewModel(
        key = "novel_editor_$chapterId",
        factory = viewModelFactory { initializer { NovelEditorViewModel(container, chapterId) } },
    )
    val state by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    /**
     * 发送后是否立刻让 AI 顺着这一行推进。
     *
     * 默认关闭 = 保持原逻辑（发言只追加一行，续写由「AI」按钮手动触发）；打开后每次发言都自动续写。
     * 存成偏好而非页面态：用户选过一次就一直是他的选择，不必每次进编辑器重设。
     */
    val autoContinue by container.settingsRepository.novelAutoContinue.collectAsState(initial = false)
    val autoContinueScope = rememberCoroutineScope()
    // 发言人选择：0 = 旁白；1 = 主控；2.. = 角色
    var selectedSpeaker by remember { mutableStateOf(0) }
    var showChapterSetting by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<NovelLineEntity?>(null) }

    val story by container.novelRepository.observeStory(state.chapter?.storyId ?: 0L)
        .collectAsState(initial = null)
    val memberIds = story?.let { container.novelRepository.decodeMemberIds(it) } ?: emptyList()
    val npcs = story?.let { container.novelRepository.decodeCustomNpcs(it) } ?: emptyList()
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
    val speakerNames = buildList {
        add(t("旁白") to Pair(NovelScriptParser.TYPE_NARRATION, null as String?))
        story?.protagonistName?.takeIf { it.isNotBlank() }?.let {
            add(it to Pair(NovelScriptParser.TYPE_USER, null as String?))
        }
        memberIds.forEach { id ->
            characters.firstOrNull { it.id == id }?.let { add(it.name to Pair(NovelScriptParser.TYPE_CHARACTER, it.id)) }
        }
        npcs.forEach { add(it.name to Pair(NovelScriptParser.TYPE_CHARACTER, null as String?)) }
    }
    val sel = selectedSpeaker.coerceIn(0, speakerNames.size - 1)
    val (selLabel, selTypePair) = speakerNames[sel]
    LaunchedEffect(speakerNames.size) {
        if (selectedSpeaker >= speakerNames.size) selectedSpeaker = 0
    }

    Box(Modifier.fillMaxSize().background(NOVEL_BG)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏：返回 + 章节名 + 本话设定
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"), tint = NOVEL_TEXT)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.chapter?.title?.ifBlank { t("未命名") } ?: "…",
                        color = NOVEL_TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(t("已自动保存到本机"), color = NOVEL_TEXT_DIM.copy(alpha = 0.7f), fontSize = 10.sp)
                }
                IconButton(onClick = { showCast = true }) {
                    Icon(Icons.Filled.Group, contentDescription = t("角色阵容"), tint = NOVEL_TEXT)
                }
                IconButton(onClick = { showChapterSetting = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = t("本话设定"), tint = NOVEL_TEXT)
                }
            }

            // 脚本行流
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 操作提示常驻列表首位：点行 = 编辑/上移/下移/删除。没有它用户根本不知道行可以操作
                if (state.lines.isNotEmpty()) {
                    item(key = "line_hints") {
                        Text(
                            t("点按任意一行可编辑、上移/下移、删除；改完点 AI 从当前位置续写"),
                            color = NOVEL_TEXT_DIM.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }
                }
                items(state.lines, key = { it.id }) { line ->
                    NovelLineRow(line = line, onClick = { editTarget = line })
                }
                if (state.isGenerating) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(NOVEL_SURFACE).padding(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = NOVEL_ACCENT, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("AI 正在续写…"), color = NOVEL_TEXT_DIM, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { viewModel.stopGenerating() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Stop, contentDescription = t("停止"), tint = NOVEL_ACCENT, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (state.streamingText.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    state.streamingText.takeLast(600),
                                    color = NOVEL_TEXT_DIM, fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic,
                                )
                            }
                        }
                    }
                }
            }

            // 发言人切换条
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 自动续写开关放在**最左端**：这一行可横向滚动，角色一多就会把末尾的控件滚出屏幕，
                // 放最前才能保证任何阵容下都一眼可见（设置页里也有同一开关）。
                item {
                    Text(
                        if (autoContinue) t("发送后自动续写") else t("仅添加不续写"),
                        color = if (autoContinue) NOVEL_ACCENT else NOVEL_TEXT_DIM,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (autoContinue) NOVEL_ACCENT.copy(alpha = 0.18f) else NOVEL_SURFACE_2,
                            )
                            .clickable {
                                autoContinueScope.launch {
                                    container.settingsRepository.setNovelAutoContinue(!autoContinue)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                itemsIndexed(speakerNames) { i, (label, _) ->
                    val selected = i == sel
                    Text(
                        label,
                        color = if (selected) NOVEL_BG else NOVEL_TEXT,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) NOVEL_ACCENT else NOVEL_SURFACE_2)
                            .clickable { selectedSpeaker = i }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // 输入栏 + AI 续写
            Row(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(color = NOVEL_TEXT, fontSize = 14.sp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NOVEL_SURFACE)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (inputText.isEmpty()) {
                            Text(tf("{0}：输入对白或描写…", selLabel), color = NOVEL_TEXT_DIM.copy(alpha = 0.6f), fontSize = 14.sp)
                        }
                        inner()
                    },
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = {
                        viewModel.addLine(
                            speakerType = selTypePair.first,
                            speakerName = selLabel,
                            characterId = selTypePair.second,
                            text = inputText,
                            continueAfter = autoContinue,
                        )
                        inputText = ""
                    },
                    enabled = inputText.isNotBlank() && !state.isGenerating,
                ) { Text("↑", color = NOVEL_TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Button(
                    onClick = { viewModel.continuePlot() },
                    enabled = !state.isGenerating,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = NOVEL_ACCENT.copy(alpha = 0.25f),
                    ),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = NOVEL_ACCENT, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("AI", color = NOVEL_ACCENT, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(bottomBarHeight))
        }

        state.errorMessage?.let { message ->
            NovelErrorBar(message, bottomBarHeight) { viewModel.clearError() }
        }
    }

    // 行编辑弹窗：改内容 / 删除 / 上下移动（移动后点「AI」续写即从新顺序接着推进）
    editTarget?.let { line ->
        var editContent by remember(line.id) { mutableStateOf(line.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(tf("编辑（{0}）", line.speakerName)) },
            text = {
                Column {
                    BasicTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            viewModel.moveLine(line, -1)
                            editTarget = null
                        }) { Text(t("上移"), fontSize = 12.sp) }
                        TextButton(onClick = {
                            viewModel.moveLine(line, +1)
                            editTarget = null
                        }) { Text(t("下移"), fontSize = 12.sp) }
                        Text(
                            t("移动后可点 AI 从新位置续写"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateLine(line, editContent)
                    editTarget = null
                }) { Text(t("保存")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.deleteLine(line.id)
                        editTarget = null
                    }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editTarget = null }) { Text(t("取消")) }
                }
            },
        )
    }

    // 本话设定弹层
    state.chapter?.let { chapter ->
        if (showChapterSetting) {
            ChapterSettingDialog(
                chapter = chapter,
                onDismiss = { showChapterSetting = false },
                onSave = { updated ->
                    viewModel.saveChapterSetting(updated)
                    showChapterSetting = false
                },
            )
        }
    }

    // 阵容弹层：写作中随时增删角色/改主角配角（用 story 快照开草稿，保存后 Flow 刷新重开即最新）
    val castStory = story
    if (showCast && castStory != null) {
        CastManagerSheet(
            characters = characters,
            memberIds = container.novelRepository.decodeMemberIds(castStory),
            npcs = container.novelRepository.decodeCustomNpcs(castStory),
            castRoles = container.novelRepository.decodeCastRoles(castStory),
            onDismiss = { showCast = false },
            onSave = { newMembers, newNpcs, newRoles ->
                viewModel.updateCast(newMembers, newNpcs, newRoles)
                showCast = false
            },
        )
    }
}

/** 脚本行渲染：旁白通栏斜体；角色左对齐带名；主控右对齐强调色。 */
@Composable
private fun NovelLineRow(line: NovelLineEntity, onClick: () -> Unit) {
    when (line.speakerType) {
        NovelScriptParser.TYPE_NARRATION -> {
            Text(
                line.content,
                color = NOVEL_TEXT_DIM, fontSize = 13.sp, lineHeight = 19.sp, fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        NovelScriptParser.TYPE_USER -> {
            Column(
                Modifier.fillMaxWidth().clickable(onClick = onClick),
                horizontalAlignment = Alignment.End,
            ) {
                Text(line.speakerName, color = NOVEL_ACCENT, fontSize = 11.sp)
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NOVEL_ACCENT_DIM)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(line.content, color = NOVEL_TEXT, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        else -> {
            Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
                Text(line.speakerName, color = NOVEL_TEXT_DIM, fontSize = 11.sp)
                Box(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NOVEL_SURFACE)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(line.content, color = NOVEL_TEXT, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** 本话设定弹层（标题/摘要/开场白/要求）。 */
@Composable
private fun ChapterSettingDialog(
    chapter: NovelChapterEntity,
    onDismiss: () -> Unit,
    onSave: (NovelChapterEntity) -> Unit,
) {
    var title by remember { mutableStateOf(chapter.title) }
    var summary by remember { mutableStateOf(chapter.summary) }
    var opening by remember { mutableStateOf(chapter.opening) }
    var requirements by remember { mutableStateOf(chapter.requirements) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("本话设定")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NovelSettingField(t("本话标题"), title, 80) { title = it }
                NovelSettingField(t("前情/本话摘要"), summary, 500) { summary = it }
                NovelSettingField(t("本话开场白（无正文时自动成为第一行）"), opening, 500) { opening = it }
                NovelSettingField(t("发生、发展、结果与写作要求"), requirements, 2000) { requirements = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    chapter.copy(
                        title = title.trim().take(80),
                        summary = summary.trim(),
                        opening = opening.trim(),
                        requirements = requirements.trim(),
                    ),
                )
            }) { Text(t("保存设定")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消")) } },
    )
}

@Composable
private fun NovelSettingField(label: String, value: String, maxLength: Int, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onChange(it) },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (maxLength > 100) 90.dp else 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(8.dp),
        )
        Text("${value.length}/$maxLength", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End))
        Spacer(Modifier.height(8.dp))
    }
}

/** 新建故事弹窗：故事名/背景/主控/角色多选（带搜索 + 主角配角）/NPC。 */
@Composable
private fun CreateStoryDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onCreate: (
        title: String, background: String, memberIds: List<String>,
        npcs: List<NovelRepository.CustomNpc>, protagonistName: String, protagonistPersona: String,
        castRoles: Map<String, String>,
    ) -> Unit,
) {
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("") }
    var protagonistName by remember { mutableStateOf("") }
    var protagonistPersona by remember { mutableStateOf("") }
    // 有序列表：第一位即默认主角（与读取端"成员第一位视为主角"的推导一致，避免同阵容两种结论）
    var selectedIds by remember { mutableStateOf(listOf<String>()) }
    var leadId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var npcs by remember { mutableStateOf(listOf<NovelRepository.CustomNpc>()) }
    var npcName by remember { mutableStateOf("") }
    var npcPersona by remember { mutableStateOf("") }

    // 首个选中的成员自动成为主角：新故事立刻有一套可用的阵容，不必手动点标记
    LaunchedEffect(selectedIds.size) {
        if (leadId == null || leadId !in selectedIds) leadId = selectedIds.firstOrNull()
    }
    val shown = characters.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("新建故事")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NovelSettingField(t("故事名 *"), title, 60) { title = it }
                NovelSettingField(t("故事背景（世界观/场景）"), background, 1000) { background = it }
                NovelSettingField(t("主控名字（选填，你的角色）"), protagonistName, 30) { protagonistName = it }
                NovelSettingField(t("主控性格与设定（选填）"), protagonistPersona, 300) { protagonistPersona = it }
                Text(t("选择参与角色（可多选）"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = t("搜索角色"),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(shown, key = { it.id }) { char ->
                        val checked = char.id in selectedIds
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (checked) selectedIds - char.id else selectedIds + char.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                char.name, fontSize = 13.sp,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (checked) {
                                // 主角/配角就地切换：先选中的成员默认主角，点配角即让位给下一位
                                NovelRoleChip(t("主角"), leadId == char.id) { leadId = char.id }
                                Spacer(Modifier.width(4.dp))
                                NovelRoleChip(t("配角"), leadId != char.id) {
                                    if (leadId == char.id) leadId = selectedIds.firstOrNull { it != char.id }
                                }
                            }
                        }
                    }
                }
                if (selectedIds.isNotEmpty()) {
                    Text(
                        tf("阵容：主角 {0} · 其余为配角", selectedIds.firstOrNull { it == leadId }
                            ?.let { id -> characters.firstOrNull { it.id == id }?.name } ?: "—"),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(t("自定义 NPC（可选）"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = npcName,
                        onValueChange = { npcName = it },
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.weight(0.35f).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(6.dp),
                        decorationBox = { inner -> if (npcName.isEmpty()) Text(t("名字"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() },
                    )
                    BasicTextField(
                        value = npcPersona,
                        onValueChange = { npcPersona = it },
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.weight(0.45f).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(6.dp),
                        decorationBox = { inner -> if (npcPersona.isEmpty()) Text(t("设定"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() },
                    )
                    TextButton(
                        enabled = npcName.isNotBlank(),
                        onClick = {
                            npcs = npcs + NovelRepository.CustomNpc(npcName.trim(), npcPersona.trim())
                            npcName = ""; npcPersona = ""
                        },
                    ) { Text(t("添加")) }
                }
                npcs.forEach { npc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("· ${npc.name}（${t("配角")}）", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(t("移除"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { npcs = npcs - npc }.padding(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (selectedIds.isNotEmpty() || npcs.isNotEmpty()),
                onClick = {
                    val roles = buildMap {
                        selectedIds.forEach { id -> put(id, NovelRepository.ROLE_SUPPORTING) }
                        npcs.forEach { npc -> put(npc.name.trim(), NovelRepository.ROLE_SUPPORTING) }
                        // 主角 key：主控（用户自己）不算阵容成员，所以主角只能是选中角色之一
                        leadId?.takeIf { it in selectedIds }?.let { put(it, NovelRepository.ROLE_PROTAGONIST) }
                    }
                    onCreate(title, background, selectedIds, npcs, protagonistName, protagonistPersona, roles)
                },
            ) { Text(t("创建并进入")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消")) } },
    )
}

/** 主角/配角就地切换药丸（新建故事弹窗用；阵容弹窗里是同款交互的另一实现）。 */
@Composable
private fun NovelRoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary.copy(alpha = 0.22f) else scheme.surface.copy(alpha = 0.04f))
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun NovelTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"), tint = NOVEL_TEXT)
        }
        Text(
            title, color = NOVEL_TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.NovelErrorBar(
    message: String,
    bottomBarHeight: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
) {
    Surface(
        color = Color(0xCC3A2A2A),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomBarHeight + 24.dp)
            .clickable(onClick = onDismiss),
    ) {
        Text(message, color = Color(0xFFFFC9C9), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
