package com.rhodesisland.terminal.ui.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.ui.glass.GlassLargeTitle
import com.rhodesisland.terminal.ui.glass.GlassSheet
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.glass.monogramGradient
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.LocalDarkTheme
import com.rhodesisland.terminal.util.CharacterImageStore
import com.rhodesisland.terminal.util.PrtsImageLoader
import com.rhodesisland.terminal.ui.affinity.AffinityScreen
import com.rhodesisland.terminal.ui.affinity.CheckinShopScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun CharactersScreen(
    container: AppContainer,
    onNavigateToChat: () -> Unit,
) {
    val characters by container.characterRepository.characters.collectAsState(
        initial = Characters.getOrderedList(),
    )
    val activeCharacter by container.settingsRepository.activeCharacter.collectAsState(initial = Characters.DEFAULT_CHARACTER_ID)
    val unreadAffinityEvents by container.affinityRepository.observeUnreadUnlockCount(activeCharacter).collectAsState(initial = 0)
    val volume by container.settingsRepository.volume.collectAsState(initial = 60)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showPersona by remember { mutableStateOf<Character?>(null) }
    var showAffinity by remember { mutableStateOf<Character?>(null) }
    var showCheckinShop by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 干员搜索：按名称 / 代号过滤（全量 384 位干员）
    var searchQuery by remember { mutableStateOf("") }
    val filteredCharacters = remember(characters, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) characters
        else characters.filter {
            it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true)
        }
    }

    // 确保 PRTS 立绘反热链 cookie 就绪（启动预热可能失败/延迟，进角色页再补一次，幂等）
    LaunchedEffect(Unit) { PrtsImageLoader.prewarm() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassLargeTitle(title = "角色") {
                TextButton(onClick = { showCheckinShop = true }) {
                    Icon(Icons.Filled.Redeem, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("每日签到", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { showImport = true }) { Text("导入", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = {
                    scope.launch {
                        val list = container.characterRepository.exportCustom()
                        if (list.isEmpty()) {
                            toast = "没有自定义角色可导出"
                        } else {
                            clipboard.setText(AnnotatedString(Json.encodeToString(list)))
                            toast = "已复制 ${list.size} 个自定义角色 JSON"
                        }
                    }
                }) { Text("导出", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            // 干员搜索框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .frostedGlass(RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "搜索干员名称 / 代号…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (filteredCharacters.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("未找到「${searchQuery.trim()}」相关的干员", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
                items(filteredCharacters) { char ->
                    CharacterCard(
                        character = char,
                        isActive = char.id == activeCharacter,
                        imageUrl = if (char.isCustom && char.image.isNotBlank()) char.image else container.assetRepository.getSelectionPicture(char.id),
                        onSelect = {
                            scope.launch {
                                container.settingsRepository.setActiveCharacter(char.id)
                                onNavigateToChat()
                            }
                        },
                        onVoiceClick = {
                            scope.launch {
                                val voiceUrl = container.assetRepository.getVoice(char.id)
                                if (voiceUrl.isNotBlank()) {
                                    container.audioManager.playVoice(voiceUrl, volume)
                                }
                            }
                        },
                        onDelete = if (char.isCustom) {
                            {
                                scope.launch {
                                    CharacterImageStore.delete(context, char.image)
                                    container.characterRepository.removeCustom(char.id)
                                }
                            }
                        } else null,
                        onViewPersona = { showPersona = char },
                        onViewAffinity = { showAffinity = char },
                        hasUnreadAffinityEvent = char.id == activeCharacter && unreadAffinityEvents > 0,
                    )
                }
            }
        }

        toast?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { toast = null }) { Text("知道了") } },
            ) { Text(msg) }
        }
    }

    if (showCheckinShop) {
        CheckinShopScreen(container = container, onBack = { showCheckinShop = false })
        return
    }
    showAffinity?.let { char ->
        AffinityScreen(
            container = container,
            character = char,
            imageUrl = if (char.isCustom && char.image.isNotBlank()) char.image else container.assetRepository.getSelectionPicture(char.id),
            onBack = { showAffinity = null },
            onOpenEventConversation = {
                showAffinity = null
                onNavigateToChat()
            },
        )
        return
    }

    if (showCreate) {
        CustomCharacterDialog(
            onDismiss = { showCreate = false },
            onConfirm = { c ->
                scope.launch { container.characterRepository.addCustom(c) }
                showCreate = false
            },
        )
    }
    if (showImport) {
        ImportCharacterDialog(
            onDismiss = { showImport = false },
            onImport = { text ->
                scope.launch {
                    try {
                        val list = Json.decodeFromString<List<Character>>(text)
                        container.characterRepository.importCustom(list)
                        toast = "已导入 ${list.size} 个自定义角色"
                    } catch (e: Exception) {
                        toast = "导入失败：JSON 格式错误"
                    }
                    showImport = false
                }
            },
        )
    }

    showPersona?.let { char ->
        PersonaSheet(
            character = char,
            imageUrl = if (char.isCustom && char.image.isNotBlank())
                char.image
            else
                container.assetRepository.getSelectionPicture(char.id),
            onDismiss = { showPersona = null },
        )
    }
}

@Composable
private fun CharacterCard(
    character: Character,
    isActive: Boolean,
    imageUrl: String,
    onSelect: () -> Unit,
    onVoiceClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onViewPersona: () -> Unit,
    onViewAffinity: () -> Unit,
    hasUnreadAffinityEvent: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassShapes.card)
            .frostedGlass(GlassShapes.card, shadowElevation = if (isActive) 12.dp else 6.dp)
            .then(if (isActive) Modifier.border(2.dp, scheme.primary, GlassShapes.card) else Modifier)
            .clickable(onClick = onSelect)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CharacterPortrait(
                imageUrl = imageUrl,
                name = character.name,
                onClick = {
                    onVoiceClick()
                    onSelect()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(10.dp))
            if (character.code.isNotBlank() || character.isCustom) {
                Text(
                    character.code.ifBlank { if (character.isCustom) "CUSTOM" else "" },
                    color = scheme.onSurfaceVariant,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                character.name,
                color = scheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (character.role.isNotBlank()) {
                Text(character.role, color = scheme.onSurfaceVariant, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onViewPersona)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("查看人设", color = scheme.onSurfaceVariant, fontSize = 11.5.sp)
                }
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onViewAffinity)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("好感度", color = scheme.primary, fontSize = 11.5.sp)
                    }
                    if (hasUnreadAffinityEvent) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(scheme.error),
                        )
                    }
                }
            }
        }
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(GlassShapes.pill)
                    .background(scheme.primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("使用中", color = scheme.onPrimary, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopStart).size(26.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = scheme.error, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/**
 * 角色人设底部抽屉：圆形小头像 + 姓名 + 可滚动、可选择的详细人设正文。
 */
@Composable
fun PersonaSheet(
    character: Character,
    imageUrl: String,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CharacterPortrait(
                imageUrl = imageUrl,
                name = character.name,
                modifier = Modifier.size(64.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                character.name,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
            if (character.skills.isNotEmpty() || character.talents.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (character.skills.isNotEmpty()) {
                        Text(
                            "技能：${character.skills.joinToString(" · ")}",
                            fontSize = 12.sp, color = scheme.primary,
                        )
                    }
                    if (character.talents.isNotEmpty()) {
                        Text(
                            "天赋：${character.talents.joinToString(" · ")}",
                            fontSize = 12.sp, color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = extractPersonaBody(character.systemPrompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 角色立绘：有图用图，否则按姓名稳定渐变 + 首字占位。
 */
@Composable
fun CharacterPortrait(
    imageUrl: String,
    name: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val portraitModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    val placeholder: @Composable () -> Unit = {
        Box(
            modifier = portraitModifier.background(Brush.linearGradient(monogramGradient(name))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (imageUrl.isBlank()) {
        placeholder()
        return
    }

    coil.compose.SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = name,
        modifier = portraitModifier,
        contentScale = ContentScale.Crop,
        loading = { placeholder() },
        error = { placeholder() },
    )
}

/**
 * 新建自定义角色弹窗
 */
@Composable
fun CustomCharacterDialog(
    onDismiss: () -> Unit,
    onConfirm: (Character) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var race by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("") }
    var savingImage by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            savingImage = true
            saveError = false
            scope.launch(Dispatchers.IO) {
                CharacterImageStore.delete(context, image)
                val saved = CharacterImageStore.save(context, uri)
                withContext(Dispatchers.Main) {
                    if (saved != null) image = saved else saveError = true
                    savingImage = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text("新建自定义角色", color = scheme.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field("名称 *", name) { name = it }
                Field("代号 / 编号", code) { code = it }
                Field("职位 / 定位", role) { role = it }
                Field("种族", race) { race = it }
                Text("立绘（可选）", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                PortraitPicker(
                    imageUri = image,
                    saving = savingImage,
                    onPick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClear = {
                        CharacterImageStore.delete(context, image)
                        image = ""
                        saveError = false
                    },
                )
                if (saveError) {
                    Text("立绘保存失败，请重试", color = scheme.error, fontSize = 11.sp)
                }
                Text("人格设定（System Prompt）*", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                GlassField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                if (name.isBlank() || systemPrompt.isBlank()) {
                    Text("名称与人格设定为必填项", color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !savingImage,
                onClick = {
                    if (name.isBlank() || systemPrompt.isBlank()) return@TextButton
                    val id = "custom-" + name.trim().replace(" ", "_")
                    onConfirm(
                        Character(
                            id = id,
                            name = name.trim(),
                            code = code.trim(),
                            role = role.trim(),
                            race = race.trim(),
                            systemPrompt = systemPrompt.trim(),
                            image = image.trim(),
                            isCustom = true,
                        ),
                    )
                },
            ) { Text("创建", color = if (savingImage) scheme.onSurfaceVariant else scheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun PortraitPicker(
    imageUri: String,
    saving: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .frostedGlass(RoundedCornerShape(14.dp), shadowElevation = 0.dp)
            .clickable(enabled = !saving) { onPick() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            saving -> CircularProgressIndicator(
                color = scheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
            imageUri.isNotBlank() -> Box(modifier = Modifier.fillMaxSize()) {
                coil.compose.AsyncImage(
                    model = imageUri,
                    contentDescription = "立绘预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "移除立绘", tint = scheme.error, modifier = Modifier.size(16.dp))
                }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = scheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(6.dp))
                Text("点击上传手机本地照片", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

/**
 * 导入自定义角色弹窗（粘贴 JSON）
 */
@Composable
private fun ImportCharacterDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text("导入自定义角色", color = scheme.onSurface) },
        text = {
            Column {
                Text("粘贴导出的角色 JSON：", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                GlassField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) {
                Text("导入", color = scheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        GlassField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth())
    }
}

/** 玻璃输入框：半透明填充 + 圆角。 */
@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current
    val textColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFE8E4E0) else androidx.compose.ui.graphics.Color(0xFF161616)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface.copy(alpha = 0.6f))
            .padding(10.dp),
        textStyle = TextStyle(color = textColor, fontSize = 13.sp),
        singleLine = singleLine,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
    )
}
