package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.repository.ChatBackgroundConfig
import com.rhodesisland.terminal.data.repository.ChatBackgroundRepository
import com.rhodesisland.terminal.ui.glass.GlassLargeTitle
import com.rhodesisland.terminal.ui.glass.GlassListRow
import com.rhodesisland.terminal.ui.glass.GlassListSection
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.config.ModelProvider
import com.rhodesisland.terminal.config.PresetModel
import com.rhodesisland.terminal.config.PRESET_PROVIDERS
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.ThemeMode
import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.TtsEngine
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.video.SeedanceSceneStore
import com.rhodesisland.terminal.work.GreetingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.rhodesisland.terminal.data.remote.SeedanceProbeResult
import java.io.File

@Composable
fun SettingsScreen(
    container: AppContainer,
    onNavigateToBackendSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val apiConfig by container.settingsRepository.apiConfig.collectAsState(initial = ApiConfig())
    val ttsConfig by container.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    val deepThinking by container.settingsRepository.deepThinking.collectAsState(initial = false)
    val liquidGlass by container.settingsRepository.liquidGlass.collectAsState(initial = true)
    val themeMode by container.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()

    val matchedProvider: ModelProvider? = PRESET_PROVIDERS.find { provider ->
        provider.baseUrl.trimEnd('/').equals(apiConfig.baseUrl.trimEnd('/'), ignoreCase = true)
    }
    var selectedProvider by remember(apiConfig) { mutableStateOf(matchedProvider) }
    val isCustom = selectedProvider == null

    var selectedModel by remember(apiConfig, selectedProvider) {
        mutableStateOf(
            selectedProvider?.models?.find { it.id == apiConfig.model }
                ?: selectedProvider?.models?.firstOrNull()
        )
    }
    var apiKey by remember(apiConfig) { mutableStateOf(apiConfig.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }

    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    var customBaseUrl by remember(apiConfig) { mutableStateOf(apiConfig.baseUrl) }
    var customModel by remember(apiConfig) { mutableStateOf(apiConfig.model) }

    var apiSaved by remember { mutableStateOf(false) }
    LaunchedEffect(apiSaved) {
        if (apiSaved) { delay(2000); apiSaved = false }
    }

    var ttsApiKey by remember(ttsConfig) { mutableStateOf(ttsConfig.apiKey) }
    var showTtsKey by remember { mutableStateOf(false) }

    // 朗读引擎（系统自带 / 云端）与系统声音模板。
    val ttsEngine by container.settingsRepository.ttsEngine.collectAsState(initial = TtsEngine.DEFAULT)
    val ttsTemplate by container.settingsRepository.ttsSystemTemplate.collectAsState(initial = SystemVoiceTemplate.DEFAULT_TEMPLATE)
    var ttsEngineEdit by remember(ttsEngine) { mutableStateOf(ttsEngine) }
    var ttsTemplateEdit by remember(ttsTemplate) { mutableStateOf(ttsTemplate) }
    var ttsSaved by remember { mutableStateOf(false) }
    LaunchedEffect(ttsSaved) {
        if (ttsSaved) { delay(2000); ttsSaved = false }
    }
    var ttsPreviewBusy by remember { mutableStateOf(false) }
    var ttsPreviewError by remember { mutableStateOf<String?>(null) }

    val ttsVoiceMap by container.settingsRepository.ttsVoiceMap.collectAsState(initial = emptyMap())
    var voiceEdit by remember(ttsVoiceMap) { mutableStateOf(ttsVoiceMap) }
    var voiceSaved by remember { mutableStateOf(false) }
    LaunchedEffect(voiceSaved) {
        if (voiceSaved) { delay(2000); voiceSaved = false }
    }

    val customCharacters by container.settingsRepository.customCharacters.collectAsState(initial = emptyList())
    val voiceCharacters = remember(customCharacters) {
        Characters.ORDER.mapNotNull { id -> Characters.ALL[id] } + customCharacters
    }

    var showGuide by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp),
    ) {
        GlassLargeTitle("设置")

        GlassListSection {
            GlassListRow(
                title = "使用指南",
                subtitle = "快速了解全部功能与配置",
                onClick = { showGuide = true },
                trailing = { Chevron() },
                showDivider = false,
            )
        }

        // ===== 本地 AI 引擎 =====
        GlassListSection(title = "本地 AI 引擎") {
            GlassListRow(
                title = "推理引擎设置",
                subtitle = "CPU / GPU / NPU 后端与参数",
                onClick = onNavigateToBackendSettings,
                trailing = { Chevron() },
            )
            GlassListRow(
                title = "性能浮窗液态玻璃",
                subtitle = "背景模糊 + 虹彩光晕（Android 12+）",
                trailing = {
                    Switch(
                        checked = liquidGlass,
                        onCheckedChange = { scope.launch { container.settingsRepository.setLiquidGlass(it) } },
                    )
                },
                showDivider = false,
            )
        }

        // ===== LLM API 配置 =====
        GlassListSection(title = "LLM API 配置") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel("模型商")
                ProviderDropdown(
                    selectedProvider = selectedProvider,
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                    onProviderSelected = { provider ->
                        selectedProvider = provider
                        selectedModel = provider?.models?.firstOrNull()
                        providerExpanded = false
                    },
                )
                if (!isCustom && selectedProvider != null) {
                    FieldLabel("模型")
                    ModelDropdown(
                        provider = selectedProvider!!,
                        selectedModel = selectedModel,
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it },
                        onModelSelected = { model ->
                            selectedModel = model
                            modelExpanded = false
                        },
                    )
                } else {
                    GlassInputField(value = customBaseUrl, onValueChange = { customBaseUrl = it }, placeholder = "API BASE URL")
                    GlassInputField(value = customModel, onValueChange = { customModel = it }, placeholder = "MODEL")
                }
                PasswordField("API KEY", apiKey, showApiKey, { apiKey = it }, { showApiKey = !showApiKey })
            }
        }
        SaveButton(
            text = "保存 API 设置",
            saved = apiSaved,
            onClick = {
                scope.launch {
                    val baseUrl = if (isCustom) customBaseUrl else selectedProvider?.baseUrl ?: customBaseUrl
                    val model = if (isCustom) customModel else selectedModel?.id ?: customModel
                    container.settingsRepository.setApiConfig(ApiConfig(baseUrl, apiKey, model))
                    apiSaved = true
                }
            },
        )

        // ===== 对话 =====
        GlassListSection(title = "对话") {
            GlassListRow(
                title = "深度思考模式",
                subtitle = "展示并折叠模型推理过程",
                trailing = {
                    Switch(
                        checked = deepThinking,
                        onCheckedChange = { scope.launch { container.settingsRepository.setDeepThinking(it) } },
                    )
                },
                showDivider = false,
            )
        }

        GreetingSection(container = container, scope = scope)
        ChatBackgroundSection(container = container, scope = scope)
        SeedanceSettingsSection(container = container, scope = scope)

        // ===== 语音合成（朗读）=====
        GlassListSection(title = "语音合成 (TTS) · 朗读") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "手机系统语音：离线、免费、开箱即用；云端（火山豆包）：支持声音复刻音色与中日双语，需配置凭据。",
                    color = scheme.onSurfaceVariant, fontSize = 11.sp,
                )
                FieldLabel("朗读引擎")
                SeedanceDropdown(
                    items = TtsEngine.entries.map { it to it.label },
                    selected = ttsEngineEdit,
                    onSelect = { ttsEngineEdit = it },
                )
                if (ttsEngineEdit == TtsEngine.SYSTEM) {
                    FieldLabel("声音模板")
                    SeedanceDropdown(
                        items = SystemVoiceTemplate.entries.map { it to it.label },
                        selected = ttsTemplateEdit,
                        onSelect = { ttsTemplateEdit = it },
                    )
                    Text(
                        "模板按手机已装语音自动匹配；无匹配语音时自动回落默认语音（语速/音调仍按模板生效）。音量跟随系统媒体音量。",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                } else {
                    Text(
                        "云端引擎需火山引擎凭据；声音复刻音色按下方「角色音色映射」配置。",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                    PasswordField("API Key", ttsApiKey, showTtsKey, { ttsApiKey = it }, { showTtsKey = !showTtsKey })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                ttsPreviewBusy = true
                                ttsPreviewError = null
                                val result = runCatching {
                                    if (ttsEngineEdit == TtsEngine.SYSTEM) {
                                        container.ttsManager.previewSystem("你好，这是朗读语音的试听效果。", ttsTemplateEdit)
                                    } else {
                                        container.ttsManager.speak("你好，这是朗读语音的试听效果。", "")
                                    }
                                }
                                ttsPreviewError = result.exceptionOrNull()?.message
                                ttsPreviewBusy = false
                            }
                        },
                        enabled = !ttsPreviewBusy,
                    ) {
                        Text(if (ttsPreviewBusy) "试听中…" else "试听", fontSize = 12.sp)
                    }
                    ttsPreviewError?.let {
                        Text(it, color = scheme.error, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        SaveButton(
            text = "保存 TTS 设置",
            saved = ttsSaved,
            onClick = {
                scope.launch {
                    container.settingsRepository.setTtsEngine(ttsEngineEdit)
                    container.settingsRepository.setTtsSystemTemplate(ttsTemplateEdit)
                    if (ttsEngineEdit == TtsEngine.CLOUD) {
                        container.settingsRepository.setTtsConfig(TtsConfig(ttsApiKey))
                    }
                    ttsSaved = true
                }
            },
        )

        TtsGuideButton()

        // ===== 角色音色映射 =====
        GlassListSection(title = "角色音色映射（声音复刻 ID · 仅云端引擎）") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "为角色填入火山引擎声音复刻音色 ID（S_xxx），留空则用默认音色。中日分别配置。",
                    color = scheme.onSurfaceVariant, fontSize = 11.sp,
                )
                voiceCharacters.forEach { char ->
                    val id = char.id
                    val pair = voiceEdit[id] ?: VoicePair()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            char.name,
                            color = scheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(56.dp),
                            maxLines = 1,
                        )
                        VoiceField(
                            label = "中",
                            value = pair.zh,
                            onValueChange = { v ->
                                val cur = voiceEdit[id] ?: VoicePair()
                                voiceEdit = voiceEdit + (id to cur.copy(zh = v))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        VoiceField(
                            label = "日",
                            value = pair.ja,
                            onValueChange = { v ->
                                val cur = voiceEdit[id] ?: VoicePair()
                                voiceEdit = voiceEdit + (id to cur.copy(ja = v))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        SaveButton(
            text = "保存音色映射",
            saved = voiceSaved,
            onClick = {
                scope.launch {
                    val toSave = voiceEdit.filter { it.value.zh.isNotBlank() || it.value.ja.isNotBlank() }
                    container.settingsRepository.setTtsVoiceMap(toSave)
                    voiceSaved = true
                }
            },
        )

        // ===== 主题模式 =====
        GlassListSection {
            GlassListRow(
                title = "主题模式",
                subtitle = when (themeMode) {
                    ThemeMode.SYSTEM -> "跟随系统"
                    ThemeMode.LIGHT -> "浅色"
                    ThemeMode.DARK -> "深色"
                },
                onClick = { showThemePicker = true },
                trailing = { Chevron() },
                showDivider = false,
            )
        }

        // ===== 关于 =====
        GlassListSection(title = "关于") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Chat by your side", color = scheme.onSurface, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Android 版 v1.0.0", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Text("AI 角色扮演聊天应用", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "内置 20 位原创人设，角色与音乐资源均为原创或来自公开接口。仅用于学习交流，不作商业用途。",
                    color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp,
                )
            }
        }
    }

    if (showGuide) {
        GuideDialog(onDismiss = { showGuide = false })
    }

    if (showThemePicker) {
        ThemePickerDialog(
            currentMode = themeMode,
            onSelect = { mode ->
                scope.launch { container.settingsRepository.setThemeMode(mode) }
                showThemePicker = false
            },
            onDismiss = { showThemePicker = false },
        )
    }
}

@Composable
private fun ThemePickerDialog(
    currentMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val modes = listOf(
        ThemeMode.SYSTEM to "跟随系统",
        ThemeMode.LIGHT to "浅色",
        ThemeMode.DARK to "深色",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        title = { Text("主题模式", color = scheme.onSurface) },
        text = {
            Column {
                modes.forEach { (mode, label) ->
                    val selected = mode == currentMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            color = if (selected) scheme.primary else scheme.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Text("✓", color = scheme.primary, fontSize = 16.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun SaveButton(text: String, saved: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
        ) {
            Text(text, color = scheme.onPrimary)
        }
        if (saved) {
            Text("✓ 已保存", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 语音合成使用指南：打开 B 站分 P 教程视频（网站端演示，与本软件逻辑相同）。 */
@Composable
private fun TtsGuideButton() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TTS_GUIDE_URL))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
        ) {
            Text("了解语音合成使用指南", color = scheme.onPrimary)
        }
        Text(
            "视频为网站端设置演示，与本软件逻辑相同（视频分 P，可在 B 站内选集）",
            color = scheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val TTS_GUIDE_URL = "https://www.bilibili.com/video/BV1uCNA6uETD"

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

/** 玻璃输入框（带占位符）。 */
@Composable
private fun GlassInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface.copy(alpha = 0.6f))
            .padding(12.dp),
        textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
        singleLine = singleLine,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = scheme.onSurfaceVariant, fontSize = 14.sp)
            inner()
        },
    )
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    show: Boolean,
    onValueChange: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface.copy(alpha = 0.6f))
                    .padding(12.dp),
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
            )
            TextButton(onClick = onToggle) {
                Text(if (show) "🙈" else "👁", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun VoiceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ", color = scheme.onSurfaceVariant, fontSize = 10.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 12.sp),
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBackgroundSection(container: AppContainer, scope: CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val bgConfig by container.chatBackgroundRepository.config.collectAsState(
        initial = ChatBackgroundConfig(enabled = false, paths = emptyList())
    )
    var showClearConfirm by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { container.chatBackgroundRepository.addUris(uris) }
        }
    }

    GlassListSection(title = "聊天背景") {
        GlassListRow(
            title = "自定义背景图片",
            subtitle = "从相册选择图片作为聊天背景轮播（最多 ${ChatBackgroundRepository.MAX_BACKGROUNDS} 张）",
            trailing = {
                Switch(
                    checked = bgConfig.enabled,
                    onCheckedChange = { scope.launch { container.chatBackgroundRepository.setEnabled(it) } },
                )
            },
            showDivider = !bgConfig.enabled,
        )
        if (bgConfig.enabled) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${bgConfig.paths.size} / ${ChatBackgroundRepository.MAX_BACKGROUNDS}",
                        color = scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    if (bgConfig.paths.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text("清空", color = scheme.error, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    bgConfig.paths.forEach { path ->
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            AsyncImage(
                                model = File(path),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(scheme.scrim.copy(alpha = 0.7f))
                                    .clickable { scope.launch { container.chatBackgroundRepository.removePath(path) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", color = scheme.error, fontSize = 10.sp)
                            }
                        }
                    }
                    if (bgConfig.paths.size < ChatBackgroundRepository.MAX_BACKGROUNDS) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(scheme.primary.copy(alpha = 0.12f))
                                .border(1.dp, scheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable { imagePicker.launch(arrayOf("image/*")) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("＋", color = scheme.primary, fontSize = 20.sp)
                                Text("添加", color = scheme.primary, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("清空背景", color = scheme.onSurface) },
            text = { Text("确定清空全部自定义背景图片？将删除已保存的图片文件。", color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.chatBackgroundRepository.clearAll() }
                    showClearConfirm = false
                }) { Text("清空", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }
}

@Composable
private fun GreetingSection(container: AppContainer, scope: CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings = container.settingsRepository

    val enabled by settings.greetingEnabled.collectAsState(initial = false)
    val charIds by settings.greetingCharacterIds.collectAsState(initial = emptySet())
    val dailyCount by settings.greetingDailyCount.collectAsState(initial = AppConfig.Greeting.DEFAULT_DAILY_COUNT)
    val provider by settings.activeProvider.collectAsState(initial = ChatProviderType.CLOUD)
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
    val isCloud = provider == ChatProviderType.CLOUD

    var showCharPicker by remember { mutableStateOf(false) }
    var sliderValue by remember(dailyCount) { mutableStateOf(dailyCount.toFloat()) }
    var testScheduled by remember { mutableStateOf(false) }
    LaunchedEffect(testScheduled) {
        if (testScheduled) { delay(12_000); testScheduled = false }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    GlassListSection(title = "角色问候") {
        GlassListRow(
            title = "角色主动问候",
            subtitle = if (isCloud) "所选角色白天随机时间主动给你发消息。仅云端 AI 可用。"
            else "仅云端 AI 模式可用，请先切换为云端 AI。",
            trailing = {
                Switch(
                    checked = enabled,
                    enabled = isCloud,
                    onCheckedChange = { on ->
                        scope.launch {
                            settings.setGreetingEnabled(on)
                            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            GreetingScheduler.reschedule(context, settings)
                        }
                    },
                )
            },
            showDivider = isCloud && enabled,
        )
        if (isCloud) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        GreetingScheduler.scheduleTest(context)
                        testScheduled = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary.copy(alpha = 0.16f)),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Text("测试主动问候（10 秒后）", color = scheme.primary, fontSize = 13.sp)
                }
                if (testScheduled) {
                    Text("✓ 已触发，约 10 秒后收到问候通知", color = scheme.tertiary, fontSize = 11.sp)
                }
                if (enabled) {
                    val selectedNames = characters.filter { it.id in charIds }.map { it.name }
                    val preview = when {
                        selectedNames.isEmpty() -> "未选择"
                        selectedNames.size <= 3 -> selectedNames.joinToString("、")
                        else -> "${selectedNames.take(3).joinToString("、")} 等 ${selectedNames.size} 个"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showCharPicker = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("问候角色（已选 ${charIds.size} 个，可多选）", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                            Text(preview, color = scheme.primary, fontSize = 13.sp, maxLines = 1)
                        }
                        Text("▾", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text("每日主动消息条数：${sliderValue.toInt()}", color = scheme.onSurface, fontSize = 12.sp)
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val v = sliderValue.toInt().coerceIn(
                                AppConfig.Greeting.MIN_DAILY_COUNT,
                                AppConfig.Greeting.MAX_DAILY_COUNT,
                            )
                            scope.launch {
                                settings.setGreetingDailyCount(v)
                                GreetingScheduler.reschedule(context, settings)
                            }
                        },
                        valueRange = AppConfig.Greeting.MIN_DAILY_COUNT.toFloat()..
                            AppConfig.Greeting.MAX_DAILY_COUNT.toFloat(),
                        steps = AppConfig.Greeting.MAX_DAILY_COUNT - AppConfig.Greeting.MIN_DAILY_COUNT - 1,
                    )
                    Text(
                        "提示：部分手机需在系统设置中允许本应用「后台运行 / 自启动」。",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                }
            }
        }
    }

    if (showCharPicker) {
        AlertDialog(
            onDismissRequest = { showCharPicker = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("选择问候角色（可多选）", color = scheme.onSurface) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = {
                            scope.launch {
                                settings.setGreetingCharacterIds(characters.map { it.id }.toSet())
                                GreetingScheduler.reschedule(context, settings)
                            }
                        }) { Text("全选", color = scheme.primary, fontSize = 12.sp) }
                        TextButton(onClick = {
                            scope.launch {
                                settings.setGreetingCharacterIds(emptySet())
                                GreetingScheduler.reschedule(context, settings)
                            }
                        }) { Text("清空", color = scheme.error, fontSize = 12.sp) }
                    }
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp),
                    ) {
                        characters.forEach { c ->
                            val checked = c.id in charIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val next = if (checked) charIds - c.id else charIds + c.id
                                            settings.setGreetingCharacterIds(next)
                                            GreetingScheduler.reschedule(context, settings)
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    c.name,
                                    color = if (checked) scheme.primary else scheme.onSurface,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (checked) Text("✓", color = scheme.primary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCharPicker = false }) { Text("完成", color = scheme.onSurfaceVariant) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    selectedProvider: ModelProvider?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onProviderSelected: (ModelProvider?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        BasicTextField(
            value = selectedProvider?.displayName ?: "自定义",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface.copy(alpha = 0.6f))
                .clickable { onExpandedChange(true) }
                .padding(12.dp),
            textStyle = TextStyle(
                color = if (selectedProvider != null) scheme.onSurface else scheme.onSurfaceVariant,
                fontSize = 14.sp,
            ),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    innerTextField()
                    Text(if (expanded) "▲" else "▼", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(scheme.surfaceContainerHigh),
        ) {
            DropdownMenuItem(
                text = { Text("自定义 (手动输入)", color = if (selectedProvider == null) scheme.primary else scheme.onSurface, fontSize = 13.sp) },
                onClick = { onProviderSelected(null) },
            )
            HorizontalDivider(color = scheme.outline.copy(alpha = 0.5f))
            PRESET_PROVIDERS.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName, color = if (selectedProvider == provider) scheme.primary else scheme.onSurface, fontSize = 13.sp) },
                    onClick = { onProviderSelected(provider) },
                )
            }
        }
    }
}

private fun resolutionLabel(resolution: SeedanceResolution): String = when (resolution) {
    SeedanceResolution.P480 -> "480p"
    SeedanceResolution.P720 -> "720p"
    SeedanceResolution.P1080 -> "1080p"
    SeedanceResolution.P4K -> "4K"
}

private fun variantLabel(variant: SeedanceModelVariant): String = when (variant) {
    SeedanceModelVariant.STANDARD -> "标准（2.0）"
    SeedanceModelVariant.FAST -> "Fast（2.0）"
}

/** “测试连接”探测的超时上限（ms）：远超底层 OkHttp 连接超时，但保证 UI 按钮不会久转。 */
private const val PROBE_TIMEOUT_MS = 10_000L

/**
 * “Seedance 对话视频”设置分区（自包含；文件较大故独立成函数）。
 * API Key 密码输入、服务地址、模型（2.0 标准/Fast）、按模型能力动态的分辨率与时长
 * （Fast 仅 480p/720p）、画幅比例、水印、可选背景图
 * （经 [SeedanceSceneStore] 校验并复制到内部存储）、可选场景描述、固定开启语音的只读说明。
 * 无 fps 选项。含「测试连接」按钮校验服务地址。
 */
@Composable
private fun SeedanceSettingsSection(container: AppContainer, scope: CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings = container.settingsRepository
    val sceneStore = remember { SeedanceSceneStore(context) }

    // 可编辑字段只在进入组合后播种一次（读持久化配置），之后不再随 config 流回填。
    // 背景图选择/清除仅改本地 UI 状态（pendingBackgroundUri / backgroundCleared / 预览路径），
    // 不触碰磁盘与 DataStore；install/remove 与 setSeedanceConfig 在“保存”时一起原子完成，
    // 避免“改了背景但未保存”时 DataStore 仍指向已被删除的旧文件（悬空路径）。
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(SeedanceConfig().baseUrl) }
    var relayModelId by remember { mutableStateOf(SeedanceConfig().relayModelId) }
    var variant by remember { mutableStateOf(SeedanceConfig().variant) }
    var resolution by remember { mutableStateOf(SeedanceConfig().resolution) }
    var duration by remember { mutableStateOf(SeedanceConfig().durationSeconds) }
    var ratio by remember { mutableStateOf(SeedanceConfig().ratio) }
    var watermark by remember { mutableStateOf(SeedanceConfig().watermark) }
    var sceneDescription by remember { mutableStateOf(SeedanceConfig().sceneDescription) }
    var backgroundPath by remember { mutableStateOf(SeedanceConfig().backgroundImagePath) }
    // 待保存的背景变更：选择来源 Uri（未落盘）与“清除”标记，均在“保存”时才落实到磁盘。
    var pendingBackgroundUri by remember { mutableStateOf<Uri?>(null) }
    var backgroundCleared by remember { mutableStateOf(false) }
    var backgroundError by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    // “测试连接”按钮状态：结果与进行中标记。
    var probeResult by remember { mutableStateOf<SeedanceProbeResult?>(null) }
    var probeRunning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val c = settings.getSeedanceConfigNow()
        apiKey = c.apiKey
        baseUrl = c.baseUrl
        relayModelId = c.relayModelId
        variant = c.variant
        resolution = c.resolution
        duration = c.durationSeconds
        ratio = c.ratio
        watermark = c.watermark
        sceneDescription = c.sceneDescription
        backgroundPath = c.backgroundImagePath
    }
    LaunchedEffect(saved) {
        if (saved) { delay(2000); saved = false }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingBackgroundUri = uri
            backgroundCleared = false
            backgroundError = null
        }
    }

    GlassListSection(title = "Seedance 对话视频") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "角色回复后自动生成对应短视频（Seedance 2.0），支持火山方舟官方与中转站（如 dm1124 媒体协议），API Key 与对话模型分开配置。",
                color = scheme.onSurfaceVariant, fontSize = 11.sp,
            )
            PasswordField("API Key", apiKey, showApiKey, { apiKey = it }, { showApiKey = !showApiKey })
            FieldLabel("服务地址")
            GlassInputField(value = baseUrl, onValueChange = { baseUrl = it }, placeholder = SeedanceConfig().baseUrl)
            Text(
                "官方方舟填 base（含 /api/v3）。中转站可填完整「创建任务」地址（如 https://api.lk888.ai/v1/media/generate）或只填主机（如 https://api.lk888.ai），将自动识别媒体协议并调用 /v1/media/generate 与 /v1/media/status。",
                color = scheme.onSurfaceVariant, fontSize = 10.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        scope.launch {
                            probeRunning = true
                            probeResult = null
                            val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                                container.seedanceClient.probeEndpoint(
                                    SeedanceConfig(baseUrl = baseUrl, apiKey = apiKey)
                                )
                            } ?: SeedanceProbeResult.Failed("连接超时，请检查地址与网络")
                            probeResult = result
                            probeRunning = false
                        }
                    },
                    enabled = !probeRunning,
                ) {
                    Text(if (probeRunning) "测试中…" else "测试连接", fontSize = 12.sp)
                }
                when (val r = probeResult) {
                    is SeedanceProbeResult.Ok ->
                        Text(r.message, color = scheme.tertiary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    is SeedanceProbeResult.Failed ->
                        Text(r.message, color = scheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    null -> {}
                }
            }
            FieldLabel("模型 ID（中转站媒体协议）")
            GlassInputField(
                value = relayModelId,
                onValueChange = { relayModelId = it },
                placeholder = SeedanceConfig().relayModelId,
            )
            Text(
                "仅中转站媒体协议使用（官方方舟忽略此项）。默认 kwvideo-v2-ref 即该站 Seedance 2.0 参考生视频模型。",
                color = scheme.onSurfaceVariant, fontSize = 10.sp,
            )
            FieldLabel("模型")
            SeedanceDropdown(
                items = SeedanceModelVariant.entries.map { it to variantLabel(it) },
                selected = variant,
                onSelect = { v ->
                    variant = v
                    // 切模型后按新模型能力纠正非法分辨率/时长，避免保存时被 clamp 但 UI 显示过期值。
                    if (resolution !in v.supportedResolutions) {
                        resolution = v.supportedResolutions.first()
                    }
                    if (duration !in v.minDurationSeconds..v.maxDurationSeconds) {
                        duration = duration.coerceIn(v.minDurationSeconds, v.maxDurationSeconds)
                    }
                },
            )
            Text(
                "中转站映射：标准→version「标准」，Fast→version「快速」（1080p/4K 仅标准版可用）。",
                color = scheme.onSurfaceVariant, fontSize = 10.sp,
            )
            FieldLabel("分辨率")
            SeedanceDropdown(
                items = variant.supportedResolutions.map { it to resolutionLabel(it) },
                selected = resolution,
                onSelect = { resolution = it },
            )
            Text("视频时长：$duration 秒", color = scheme.onSurface, fontSize = 12.sp)
            Slider(
                value = duration.toFloat().coerceIn(variant.minDurationSeconds.toFloat(), variant.maxDurationSeconds.toFloat()),
                onValueChange = {
                    duration = it.toInt().coerceIn(variant.minDurationSeconds, variant.maxDurationSeconds)
                },
                valueRange = variant.minDurationSeconds.toFloat()..variant.maxDurationSeconds.toFloat(),
                steps = variant.maxDurationSeconds - variant.minDurationSeconds - 1,
            )
            FieldLabel("画幅比例")
            SeedanceDropdown(
                items = SeedanceRatio.entries.map { it to it.apiValue },
                selected = ratio,
                onSelect = { ratio = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("生成结果带水印", color = scheme.onSurface, fontSize = 13.sp)
                    Text("默认关闭；仅方舟官方生效", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Switch(checked = watermark, onCheckedChange = { watermark = it })
            }
            Text(
                "视频语音固定开启（Seedance 2.0 不支持关闭，中转站自动生成有声视频）。",
                color = scheme.onSurfaceVariant, fontSize = 10.sp,
            )
            FieldLabel("背景图（可选）")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val preview: Any? = when {
                    pendingBackgroundUri != null -> pendingBackgroundUri
                    backgroundCleared -> null
                    else -> backgroundPath?.let { File(it).takeIf { f -> f.exists() } }
                }
                if (preview != null) {
                    Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))) {
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
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.primary.copy(alpha = 0.12f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable { imagePicker.launch(arrayOf("image/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (preview == null) "＋ 选择" else "更换", color = scheme.primary, fontSize = 11.sp)
                }
                if (preview != null) {
                    TextButton(onClick = {
                        pendingBackgroundUri = null
                        backgroundCleared = true
                        backgroundError = null
                    }) { Text("清除", color = scheme.error, fontSize = 12.sp) }
                }
            }
            backgroundError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }
            FieldLabel("场景描述（可选）")
            GlassInputField(
                value = sceneDescription,
                onValueChange = { sceneDescription = it },
                placeholder = "如「雨夜的街道」",
                singleLine = false,
            )
        }
    }

    SaveButton(
        text = "保存 Seedance 设置",
        saved = saved,
        onClick = {
            scope.launch {
                val safeResolution = if (resolution !in variant.supportedResolutions) {
                    variant.supportedResolutions.first()
                } else {
                    resolution
                }
                // 磁盘变更与路径持久化在“保存”时原子完成：先 install/remove，再把结果路径写入 DataStore。
                var finalBackgroundPath = backgroundPath
                val chosenUri = pendingBackgroundUri
                if (chosenUri != null) {
                    val installedPath = sceneStore.install(chosenUri).getOrNull()
                    if (installedPath == null) {
                        backgroundError = "背景图保存失败"
                        return@launch
                    }
                    finalBackgroundPath = installedPath
                    backgroundPath = installedPath
                    pendingBackgroundUri = null
                    backgroundCleared = false
                    backgroundError = null
                } else if (backgroundCleared) {
                    sceneStore.remove()
                    finalBackgroundPath = null
                    backgroundPath = null
                    backgroundCleared = false
                    backgroundError = null
                }
                settings.setSeedanceConfig(
                    SeedanceConfig(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        relayModelId = relayModelId,
                        variant = variant,
                        resolution = safeResolution,
                        ratio = ratio,
                        durationSeconds = duration.coerceIn(variant.minDurationSeconds, variant.maxDurationSeconds),
                        watermark = watermark,
                        backgroundImagePath = finalBackgroundPath,
                        sceneDescription = sceneDescription,
                    )
                )
                saved = true
            }
        },
    )
}

/** 玻璃风格下拉选择框（通用，供 Seedance 分区选用枚举档位）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SeedanceDropdown(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        BasicTextField(
            value = items.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface.copy(alpha = 0.6f))
                .clickable { expanded = true }
                .padding(12.dp),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    innerTextField()
                    Text(if (expanded) "▲" else "▼", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(scheme.surfaceContainerHigh),
        ) {
            items.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (value == selected) scheme.primary else scheme.onSurface, fontSize = 13.sp) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    provider: ModelProvider,
    selectedModel: PresetModel?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onModelSelected: (PresetModel) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        BasicTextField(
            value = selectedModel?.displayName ?: provider.defaultModel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface.copy(alpha = 0.6f))
                .clickable { onExpandedChange(true) }
                .padding(12.dp),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    innerTextField()
                    Text(if (expanded) "▲" else "▼", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(scheme.surfaceContainerHigh),
        ) {
            provider.models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName, color = if (selectedModel == model) scheme.primary else scheme.onSurface, fontSize = 13.sp)
                            if (model.description.isNotBlank()) {
                                Text(model.description, color = scheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }
                    },
                    onClick = { onModelSelected(model) },
                )
            }
        }
    }
}
