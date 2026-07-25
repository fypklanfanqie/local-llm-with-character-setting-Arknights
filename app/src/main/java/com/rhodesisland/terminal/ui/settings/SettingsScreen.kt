package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.repository.ChatBackgroundConfig
import com.rhodesisland.terminal.data.repository.ChatBackgroundRepository
import com.rhodesisland.terminal.ui.theme.PrtsColors
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.config.ModelProvider
import com.rhodesisland.terminal.config.PresetModel
import com.rhodesisland.terminal.config.PRESET_PROVIDERS
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.data.model.Character
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
    container: AppContainer,
    onNavigateToBackendSettings: () -> Unit,
) {
    val apiConfig by container.settingsRepository.apiConfig.collectAsState(initial = ApiConfig())
    val ttsConfig by container.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    val deepThinking by container.settingsRepository.deepThinking.collectAsState(initial = false)
    val liquidGlass by container.settingsRepository.liquidGlass.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    // 根据当前 baseUrl 匹配预设供应商，匹配不到则为 null（自定义模式）
    val matchedProvider: ModelProvider? = PRESET_PROVIDERS.find { provider ->
        provider.baseUrl.trimEnd('/').equals(apiConfig.baseUrl.trimEnd('/'), ignoreCase = true)
    }
    var selectedProvider by remember(apiConfig) { mutableStateOf(matchedProvider) }
    val isCustom = selectedProvider == null

    // 选中供应商后，匹配当前使用的模型
    var selectedModel by remember(apiConfig, selectedProvider) {
        mutableStateOf(
            selectedProvider?.models?.find { it.id == apiConfig.model }
                ?: selectedProvider?.models?.firstOrNull()
        )
    }
    var apiKey by remember(apiConfig) { mutableStateOf(apiConfig.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }

    // 下拉菜单展开状态
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    // 自定义模式下的手动输入值
    var customBaseUrl by remember(apiConfig) { mutableStateOf(apiConfig.baseUrl) }
    var customModel by remember(apiConfig) { mutableStateOf(apiConfig.model) }

    // 保存反馈
    var apiSaved by remember { mutableStateOf(false) }
    LaunchedEffect(apiSaved) {
        if (apiSaved) { delay(2000); apiSaved = false }
    }

    var ttsApiKey by remember(ttsConfig) { mutableStateOf(ttsConfig.apiKey) }
    var showTtsKey by remember { mutableStateOf(false) }

    // 角色音色映射（编辑态）
    val ttsVoiceMap by container.settingsRepository.ttsVoiceMap.collectAsState(initial = emptyMap())
    var voiceEdit by remember(ttsVoiceMap) { mutableStateOf(ttsVoiceMap) }
    var voiceSaved by remember { mutableStateOf(false) }
    LaunchedEffect(voiceSaved) {
        if (voiceSaved) { delay(2000); voiceSaved = false }
    }

    // 自定义角色也参与音色映射：预设角色 + 用户自定义角色
    val customCharacters by container.settingsRepository.customCharacters.collectAsState(initial = emptyList())
    val voiceCharacters = remember(customCharacters) {
        Characters.ORDER.mapNotNull { id -> Characters.ALL[id] } + customCharacters
    }

    var showGuide by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrtsColors.BgPrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("系统设置", color = PrtsColors.GoldBright, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

        Button(
            onClick = { showGuide = true },
            colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("📖 使用指南", color = PrtsColors.Gold)
        }

        // ===== 本地 AI 引擎 =====
        SectionDivider("本地 AI 引擎")
        Button(
            onClick = onNavigateToBackendSettings,
            colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("⚙ 推理引擎设置（CPU / GPU / NPU）", color = PrtsColors.Gold)
        }

        // 性能浮窗液态玻璃开关（浮窗仅在本地推理时显示；此开关控制其视觉风格，实时生效）
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🫧", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("性能浮窗液态玻璃", color = PrtsColors.TextPrimary, fontSize = 13.sp)
                Text(
                    "性能监控浮窗启用液态玻璃（背景模糊 + 虹彩光晕）；关闭则普通深色面板。背景模糊需 Android 12+",
                    color = PrtsColors.TextDim,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = liquidGlass,
                onCheckedChange = { scope.launch { container.settingsRepository.setLiquidGlass(it) } },
            )
        }

        // ===== LLM API 配置 =====
        SectionDivider("LLM API 配置")
        Text("🔗 直连对话商 API（SSE 流式，不经代理）", color = PrtsColors.Success, fontSize = 11.sp)

        // 供应商下拉
        Text("模型商", color = PrtsColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
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

        Spacer(Modifier.height(12.dp))

        if (!isCustom && selectedProvider != null) {
            // 预设模式：模型下拉
            Text("模型", color = PrtsColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
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
            Spacer(Modifier.height(12.dp))
        } else {
            // 自定义模式：手动输入 Base URL 和 Model
            SettingField("API BASE URL", customBaseUrl) { customBaseUrl = it }
            SettingField("MODEL", customModel) { customModel = it }
        }

        PasswordField("API KEY", apiKey, showApiKey, { apiKey = it }, { showApiKey = !showApiKey })

        Button(
            onClick = {
                scope.launch {
                    val baseUrl = if (isCustom) customBaseUrl else selectedProvider?.baseUrl ?: customBaseUrl
                    val model = if (isCustom) customModel else selectedModel?.id ?: customModel
                    container.settingsRepository.setApiConfig(ApiConfig(baseUrl, apiKey, model))
                    apiSaved = true
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
        ) {
            Text("保存 API 设置", color = PrtsColors.Gold)
        }

        if (apiSaved) {
            Text("✓ 已保存", color = PrtsColors.Success, fontSize = 12.sp)
        }

        // ===== 深度思考模式 =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🧠", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("深度思考模式", color = PrtsColors.TextPrimary, fontSize = 13.sp)
                Text(
                    "展示推理过程并支持折叠（云端 Qwen 可开关，DeepSeek/GLM 展示模型默认思考）",
                    color = PrtsColors.TextDim,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = deepThinking,
                onCheckedChange = { scope.launch { container.settingsRepository.setDeepThinking(it) } },
            )
        }

        // ===== 聊天背景 =====
        ChatBackgroundSection(container = container, scope = scope)

        // ===== TTS 配置 =====
        SectionDivider("语音合成 (TTS) — 火山引擎")
        Text("✅ 通过 CloudRun 代理调用（无需配置域名白名单）", color = PrtsColors.Success, fontSize = 11.sp)

        PasswordField("API Key", ttsApiKey, showTtsKey, { ttsApiKey = it }, { showTtsKey = !showTtsKey })

        Button(
            onClick = {
                scope.launch {
                    container.settingsRepository.setTtsConfig(TtsConfig(ttsApiKey))
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
        ) {
            Text("保存 TTS 设置", color = PrtsColors.Gold)
        }

        // ===== 角色音色映射 =====
        SectionDivider("角色音色映射（声音复刻 ID）")
        Text(
            "为角色填入火山引擎声音复刻音色 ID（S_xxx），留空则使用默认音色。中日分别配置。",
            color = PrtsColors.TextDim, fontSize = 11.sp,
        )
        voiceCharacters.forEach { char ->
            val id = char.id
            val pair = voiceEdit[id] ?: VoicePair()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    char.name,
                    color = PrtsColors.Gold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.width(64.dp),
                )
                BasicTextField(
                    value = pair.zh,
                    onValueChange = { v ->
                        val cur = voiceEdit[id] ?: VoicePair()
                        voiceEdit = voiceEdit + (id to cur.copy(zh = v))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .background(PrtsColors.BgInput, RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    textStyle = TextStyle(color = PrtsColors.TextPrimary, fontSize = 12.sp),
                    singleLine = true,
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("中 ", color = PrtsColors.TextDim, fontSize = 10.sp)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = pair.ja,
                    onValueChange = { v ->
                        val cur = voiceEdit[id] ?: VoicePair()
                        voiceEdit = voiceEdit + (id to cur.copy(ja = v))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .background(PrtsColors.BgInput, RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    textStyle = TextStyle(color = PrtsColors.TextPrimary, fontSize = 12.sp),
                    singleLine = true,
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("日 ", color = PrtsColors.TextDim, fontSize = 10.sp)
                            inner()
                        }
                    },
                )
            }
        }
        Button(
            onClick = {
                scope.launch {
                    val toSave = voiceEdit.filter { it.value.zh.isNotBlank() || it.value.ja.isNotBlank() }
                    container.settingsRepository.setTtsVoiceMap(toSave)
                    voiceSaved = true
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
        ) {
            Text("保存音色映射", color = PrtsColors.Gold)
        }
        if (voiceSaved) {
            Text("✓ 已保存", color = PrtsColors.Success, fontSize = 12.sp)
        }

        // ===== 关于 =====
        SectionDivider("关于")
        Text("RHODES ISLAND // TERMINAL", color = PrtsColors.GoldBright, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Android 本地版 v1.0.0", color = PrtsColors.TextDim, fontSize = 12.sp)
        Text("明日方舟同人角色扮演聊天终端", color = PrtsColors.TextSecondary, fontSize = 12.sp)
        Text("支持云端 AI + 本地 AI（Qwen 系列）、TTS 语音合成、音乐播放", color = PrtsColors.TextSecondary, fontSize = 11.sp)
        Text(
            "本项目为明日方舟同人作品，所有角色、立绘、音乐版权归 Hypergryph / 鹰角网络所有。仅用于学习交流，不作商业用途。",
            color = PrtsColors.TextDim, fontSize = 10.sp,
        )

        Spacer(Modifier.height(80.dp))
    }

    if (showGuide) {
        GuideDialog(onDismiss = { showGuide = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBackgroundSection(container: AppContainer, scope: CoroutineScope) {
    val bgConfig by container.chatBackgroundRepository.config.collectAsState(
        initial = ChatBackgroundConfig(enabled = false, paths = emptyList())
    )
    var showClearConfirm by remember { mutableStateOf(false) }

    // SAF 图片选择器：选中后复制到内部存储（见 ChatBackgroundRepository.addUris），无需持久权限。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { container.chatBackgroundRepository.addUris(uris) }
        }
    }

    // 开关行
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🖼️", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("自定义背景图片", color = PrtsColors.TextPrimary, fontSize = 13.sp)
            Text(
                "从相册选择图片作为通讯界面背景轮播（最多 ${ChatBackgroundRepository.MAX_BACKGROUNDS} 张）。未添加图片或关闭时使用默认 PRTS 背景。",
                color = PrtsColors.TextDim,
                fontSize = 10.sp,
            )
        }
        Switch(
            checked = bgConfig.enabled,
            onCheckedChange = { scope.launch { container.chatBackgroundRepository.setEnabled(it) } },
        )
    }

    if (bgConfig.enabled) {
        // 计数 + 清空
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${bgConfig.paths.size} / ${ChatBackgroundRepository.MAX_BACKGROUNDS}",
                color = PrtsColors.GoldDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (bgConfig.paths.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("清空", color = PrtsColors.DangerBright, fontSize = 12.sp)
                }
            }
        }

        // 缩略图网格 + 添加按钮
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bgConfig.paths.forEach { path ->
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrtsColors.BgInput),
                ) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // 移除按钮
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(PrtsColors.BgPrimary.copy(alpha = 0.7f))
                            .clickable { scope.launch { container.chatBackgroundRepository.removePath(path) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", color = PrtsColors.DangerBright, fontSize = 10.sp)
                    }
                }
            }
            // 添加按钮（未达上限时显示）
            if (bgConfig.paths.size < ChatBackgroundRepository.MAX_BACKGROUNDS) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrtsColors.Gold.copy(alpha = 0.12f))
                        .border(1.dp, PrtsColors.Gold.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { imagePicker.launch(arrayOf("image/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("＋", color = PrtsColors.Gold, fontSize = 20.sp)
                        Text("添加", color = PrtsColors.GoldDim, fontSize = 9.sp)
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空背景") },
            text = { Text("确定清空全部自定义背景图片？将删除已保存的图片文件。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.chatBackgroundRepository.clearAll() }
                    showClearConfirm = false
                }) { Text("清空", color = PrtsColors.DangerBright) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消", color = PrtsColors.TextDim) }
            },
        )
    }
}

@Composable
private fun SectionDivider(text: String) {
    Text(
        text,
        color = PrtsColors.GoldDim,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    HorizontalDivider(color = PrtsColors.AcrylicBorder)
}

@Composable
private fun SettingField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = PrtsColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(PrtsColors.BgInput, RoundedCornerShape(4.dp))
                .padding(10.dp),
            textStyle = TextStyle(color = PrtsColors.TextPrimary, fontSize = 13.sp),
            singleLine = true,
        )
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    show: Boolean,
    onValueChange: (String) -> Unit,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = PrtsColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .background(PrtsColors.BgInput, RoundedCornerShape(4.dp))
                    .padding(10.dp),
                textStyle = TextStyle(color = PrtsColors.TextPrimary, fontSize = 13.sp),
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            )
            TextButton(onClick = onToggle) {
                Text(if (show) "🙈" else "👁", fontSize = 16.sp)
            }
        }
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
                .background(PrtsColors.BgInput, RoundedCornerShape(4.dp))
                .clickable { onExpandedChange(true) }
                .padding(10.dp),
            textStyle = TextStyle(
                color = if (selectedProvider != null) PrtsColors.GoldBright else PrtsColors.TextSecondary,
                fontSize = 13.sp,
            ),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    innerTextField()
                    Text(
                        text = if (expanded) "▲" else "▼",
                        color = PrtsColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(PrtsColors.BgTertiary),
        ) {
            // 自定义选项
            DropdownMenuItem(
                text = {
                    Text(
                        "自定义 (手动输入)",
                        color = if (selectedProvider == null) PrtsColors.GoldBright else PrtsColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                },
                onClick = { onProviderSelected(null) },
            )
            HorizontalDivider(color = PrtsColors.AcrylicBorder)
            // 预设供应商
            PRESET_PROVIDERS.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Text(
                            provider.displayName,
                            color = if (selectedProvider == provider) PrtsColors.GoldBright else PrtsColors.TextPrimary,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = { onProviderSelected(provider) },
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
                .background(PrtsColors.BgInput, RoundedCornerShape(4.dp))
                .clickable { onExpandedChange(true) }
                .padding(10.dp),
            textStyle = TextStyle(color = PrtsColors.GoldBright, fontSize = 13.sp),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    innerTextField()
                    Text(
                        text = if (expanded) "▲" else "▼",
                        color = PrtsColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(PrtsColors.BgTertiary),
        ) {
            provider.models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                model.displayName,
                                color = if (selectedModel == model) PrtsColors.GoldBright else PrtsColors.TextPrimary,
                                fontSize = 13.sp,
                            )
                            if (model.description.isNotBlank()) {
                                Text(
                                    model.description,
                                    color = PrtsColors.TextDim,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    },
                    onClick = { onModelSelected(model) },
                )
            }
        }
    }
}
