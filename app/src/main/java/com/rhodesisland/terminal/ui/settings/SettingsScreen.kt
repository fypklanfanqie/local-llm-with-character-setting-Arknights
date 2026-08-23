package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import kotlinx.serialization.json.JsonPrimitive
import com.rhodesisland.terminal.util.BackgroundSurvivalHelper
import com.rhodesisland.terminal.util.RomDetector
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.model.validationError
import com.rhodesisland.terminal.data.repository.ChatBackgroundConfig
import com.rhodesisland.terminal.data.repository.ChatBackgroundRepository
import com.rhodesisland.terminal.ui.glass.CollapsibleSection
import com.rhodesisland.terminal.ui.glass.GlassLargeTitle
import com.rhodesisland.terminal.ui.glass.GlassListRow
import com.rhodesisland.terminal.ui.glass.GlassListSection
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes
import com.rhodesisland.terminal.ui.theme.fieldPlaceholderColor
import com.rhodesisland.terminal.ui.theme.fieldTextColor
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.config.ModelProvider
import com.rhodesisland.terminal.config.PresetModel
import com.rhodesisland.terminal.config.PRESET_PROVIDERS
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.GroupChatConfig
import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.TtsEngine
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.UserProfileConfig
import com.rhodesisland.terminal.util.AppStorageUsage
import com.rhodesisland.terminal.util.CrashCapture
import com.rhodesisland.terminal.util.UserProfileImageStore
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.video.SeedanceSceneStore
import com.rhodesisland.terminal.work.GreetingScheduler
import com.rhodesisland.terminal.work.GroupChatScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()

    val matchedProvider: ModelProvider? = PRESET_PROVIDERS.find { provider ->
        provider.baseUrl.trimEnd('/').equals(apiConfig.baseUrl.trimEnd('/'), ignoreCase = true)
    }
    var selectedProvider by remember(apiConfig) { mutableStateOf(matchedProvider) }
    val isCustom = selectedProvider == null
    /** 是否内置免费服务商（SiliconFlow 免费 7B）：key 内置、无需用户填写。 */
    val isFreeProvider = selectedProvider?.id == "siliconflow-free"
    /** 首次选择「免费对话」时弹提示。 */
    var showFreeTip by remember { mutableStateOf(false) }

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

    // 测试连接：用当前编辑框里的 baseUrl/model/key 发一条最短请求，当场看通不通
    // （自定义提供商 400/404/401 的根因——未知参数注入、模型名/Key 带空白等——直接暴露）。
    var probeRunning by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }

    /**
     * 由当前 UI 编辑态组装 ApiConfig（预设=preset baseUrl/model，自定义=手动输入值；
     * 免费服务商 key 恒为空）。用于切换服务商时保存旧槽、以及保存按钮写入。
     */
    fun buildCurrentUiConfig(): ApiConfig {
        val baseUrl = if (selectedProvider == null) customBaseUrl else selectedProvider?.baseUrl ?: customBaseUrl
        val model = if (selectedProvider == null) customModel else selectedModel?.id ?: customModel
        val key = if (selectedProvider?.id == "siliconflow-free") "" else apiKey
        return ApiConfig(baseUrl, key, model)
    }

    var ttsApiKey by remember(ttsConfig) { mutableStateOf(ttsConfig.apiKey) }
    var showTtsKey by remember { mutableStateOf(false) }

    // 朗读引擎（系统自带 / 云端）与系统声音模板。
    val ttsEngine by container.settingsRepository.ttsEngine.collectAsState(initial = TtsEngine.DEFAULT)
    val ttsTemplate by container.settingsRepository.ttsSystemTemplate.collectAsState(initial = SystemVoiceTemplate.DEFAULT_TEMPLATE)
    /** 自动朗读完整角色回复（默认关闭）。 */
    val ttsAutoRead by container.settingsRepository.ttsAutoRead.collectAsState(initial = false)
    var ttsEngineEdit by remember(ttsEngine) { mutableStateOf(ttsEngine) }
    var ttsTemplateEdit by remember(ttsTemplate) { mutableStateOf(ttsTemplate) }
    var ttsSaved by remember { mutableStateOf(false) }
    LaunchedEffect(ttsSaved) {
        if (ttsSaved) { delay(2000); ttsSaved = false }
    }
    var ttsPreviewBusy by remember { mutableStateOf(false) }
    var ttsPreviewError by remember { mutableStateOf<String?>(null) }
    var voiceSearch by remember { mutableStateOf("") }
    var ttsPreviewCharacterId by remember { mutableStateOf<String?>(null) }

    val ttsVoiceMap by container.settingsRepository.ttsVoiceMap.collectAsState(initial = emptyMap())
    var voiceEdit by remember(ttsVoiceMap) { mutableStateOf(ttsVoiceMap) }
    var voiceSaved by remember { mutableStateOf(false) }
    LaunchedEffect(voiceSaved) {
        if (voiceSaved) { delay(2000); voiceSaved = false }
    }

    val customCharacters by container.settingsRepository.customCharacters.collectAsState(initial = emptyList())
    // 全量干员（基础 20 + 自动生成 364）+ 自定义角色，供角色音色表搜索选用。
    val voiceCharacters = remember(customCharacters) {
        Characters.ORDER_ALL.mapNotNull { id -> Characters.ALL[id] } + customCharacters
    }

    var showGuide by remember { mutableStateOf(false) }
    // 崩溃日志查看/分享对话框（OPPO/鸿蒙闪退排查：用户闪退后无需 adb，从这里把日志发给开发者）。
    var showCrashLogs by remember { mutableStateOf(false) }

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

        // ===== 崩溃日志（诊断：让受影响用户无需 adb 即可把崩溃堆栈发给开发者）=====
        GlassListSection {
            GlassListRow(
                title = "崩溃日志",
                subtitle = "查看并分享应用崩溃 / 启动异常日志",
                onClick = { showCrashLogs = true },
                trailing = { Chevron() },
                showDivider = false,
            )
        }

        // ===== 本地 AI 引擎 =====
        CollapsibleSection(
            title = "本地 AI 引擎",
            summary = if (liquidGlass) "推理引擎 · 性能浮窗开" else "推理引擎",
        ) {
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
        CollapsibleSection(title = "LLM API 配置", summary = apiConfig.model.ifBlank { "未配置模型" }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel("模型商")
                ProviderDropdown(
                    selectedProvider = selectedProvider,
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                    onProviderSelected = { provider ->
                        // 切换服务商：先把旧服务商的编辑状态存回它自己的槽位，再加载新服务商
                        // 的独立配置并立即生效（baseUrl/model/key 互不串）。
                        val oldKey = selectedProvider?.id ?: "custom"
                        val oldConfig = buildCurrentUiConfig()
                        selectedProvider = provider
                        selectedModel = provider?.models?.firstOrNull()
                        providerExpanded = false
                        if (provider?.id == "siliconflow-free") showFreeTip = true
                        scope.launch {
                            container.settingsRepository.setProviderApiConfig(oldKey, oldConfig)
                            val newKey = provider?.id ?: "custom"
                            val stored = container.settingsRepository.getProviderApiConfigNow(newKey)
                            if (provider != null) {
                                val model = stored?.model?.let { m -> provider.models.find { it.id == m } }
                                    ?: provider.models.firstOrNull()
                                val key = if (provider.id == "siliconflow-free") "" else (stored?.apiKey ?: "")
                                selectedModel = model
                                apiKey = key
                                container.settingsRepository.setApiConfig(
                                    ApiConfig(provider.baseUrl, key, model?.id ?: provider.defaultModel),
                                )
                            } else {
                                customBaseUrl = stored?.baseUrl ?: customBaseUrl
                                customModel = stored?.model ?: customModel
                                apiKey = stored?.apiKey ?: apiKey
                                container.settingsRepository.setApiConfig(
                                    ApiConfig(customBaseUrl, apiKey, customModel),
                                )
                            }
                        }
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
                if (isFreeProvider) {
                    // 内置免费服务商：key 由 Cloudflare 代理注入，客户端无需填写
                    PasswordField("API KEY", "通过云端代理（无需密钥）", true, {}, {})
                } else {
                    PasswordField("API KEY", apiKey, showApiKey, { apiKey = it }, { showApiKey = !showApiKey })
                }
                // 测试连接：用当前输入实时验证（未保存也测）。自定义提供商 400/404/401 当场可见原因。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                probeRunning = true
                                probeResult = null
                                val baseUrl = if (isCustom) customBaseUrl else selectedProvider?.baseUrl ?: customBaseUrl
                                val model = if (isCustom) customModel else selectedModel?.id ?: customModel
                                val key = if (isFreeProvider) "" else apiKey
                                probeResult = runCatching {
                                    container.directLlmClient.chatOnce(
                                        baseUrl = baseUrl,
                                        apiKey = key,
                                        model = model,
                                        messages = listOf(ChatMessageDto("user", JsonPrimitive("你好，请回复「测试通过」")))
                                    ).take(60).let { "连接成功：$it" }
                                }.onFailure {
                                    // 上游 400/401/404 的真实 message 已在异常文案里（HTTP {code}: {msg}）。
                                    android.util.Log.w("SettingsScreen", "LLM 测试连接失败", it)
                                }.exceptionOrNull()?.message?.let { "连接失败：$it" }
                                probeRunning = false
                            }
                        },
                        enabled = !probeRunning,
                    ) { Text(if (probeRunning) "测试中…" else "测试连接", fontSize = 12.sp) }
                    probeResult?.let {
                        Text(
                            it,
                            color = if (it.startsWith("连接成功")) scheme.tertiary else scheme.error,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        SaveButton(
            text = "保存 API 设置",
            saved = apiSaved,
            onClick = {
                scope.launch {
                    // 写入当前服务商的独立槽位 + 生效为 active（聊天即时用这份配置）。
                    val config = buildCurrentUiConfig()
                    val slotKey = selectedProvider?.id ?: "custom"
                    container.settingsRepository.setProviderApiConfig(slotKey, config)
                    container.settingsRepository.setApiConfig(config)
                    apiSaved = true
                }
            },
        )

        // ===== 免费对话提示弹窗 =====
        if (showFreeTip) {
            AlertDialog(
                onDismissRequest = { showFreeTip = false },
                containerColor = scheme.surfaceContainerHigh,
                title = { Text("免费对话", color = scheme.primary) },
                text = {
                    Text(
                        "此为免费模型，参数量为7b，如果出现错误稍等就行，免费的服务请大家谅解！",
                        color = scheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showFreeTip = false }) { Text("知道了", color = scheme.primary) }
                },
            )
        }

        // ===== 对话 =====
        CollapsibleSection(title = "对话", summary = if (deepThinking) "深度思考开" else "深度思考关") {
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
        GroupChatSection(container = container, scope = scope)
        WorldviewSection(container = container, scope = scope)
        UserProfileSection(container = container, scope = scope)
        ChatBackgroundSection(container = container, scope = scope)
        StorageSection(container = container, scope = scope)
        SeedanceSettingsSection(container = container, scope = scope)

        // ===== 语音合成（朗读）=====
        CollapsibleSection(
            title = "语音合成 (TTS) · 朗读",
            summary = "引擎：${ttsEngineEdit.label}",
        ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动朗读角色回复", color = scheme.onSurface, fontSize = 13.sp)
                        Text(
                            "角色完整回复生成后自动使用当前朗读引擎播放；手动停止的部分回复不朗读。",
                            color = scheme.onSurfaceVariant, fontSize = 10.sp,
                        )
                    }
                    Switch(
                        checked = ttsAutoRead,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settingsRepository.setTtsAutoRead(enabled) }
                        },
                    )
                }
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
                        "从火山引擎「API Key 管理」复制 API Key。下方为每个角色分别配置中文/日文 speaker_id；日语模式必须配置日文 speaker_id。资源版本已自动配置，无需填写 App ID、Access Key 或 Resource ID。",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                    PasswordField("火山引擎 API Key", ttsApiKey, showTtsKey, { ttsApiKey = it }, { showTtsKey = !showTtsKey })
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
                        val characterId = ttsPreviewCharacterId
                            ?: voiceCharacters.firstOrNull()?.id
                            ?: throw IllegalStateException("请先配置至少一个角色音色")
                        container.ttsManager.speak(
                            if (container.settingsRepository.getTtsLanguageNow() == TtsLanguage.JA) "こんにちは、これは日本語の音声テストです。" else "你好，这是声音复刻朗读的试听效果。",
                            characterId,
                        )
                                    }
                                }
                                ttsPreviewError = result.exceptionOrNull()?.message
                                ttsPreviewBusy = false
                            }
                        },
                        enabled = !ttsPreviewBusy,
                    ) { Text(if (ttsPreviewBusy) "试听中…" else "试听", fontSize = 12.sp) }
                    ttsPreviewError?.let {
                        Text(it, color = scheme.error, fontSize = 10.sp, modifier = Modifier.weight(1f))
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
                            val config = TtsConfig(apiKey = ttsApiKey.trim())
                            val error = config.validationError()
                            if (error != null) {
                                ttsPreviewError = error
                                return@launch
                            }
                            container.settingsRepository.setTtsConfig(config)
                        }
                        ttsSaved = true
                    }
                },
            )

            TtsGuideButton()
        }

        // ===== 角色双语音色 =====
        CollapsibleSection(title = "角色双语音色（speaker_id）") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "每个角色分别填写中文和日文 speaker_id。日语模式只使用日文音色，缺失时会提示配置，不会用中文音色硬读日文。",
                    color = scheme.onSurfaceVariant, fontSize = 11.sp,
                )
                // 角色音色表：嵌套折叠区（默认收起），替代旧 TextButton 裸 if 展开
                CollapsibleSection(
                    title = "角色音色表",
                    summary = "搜索角色后逐个配置中 / 日 speaker_id",
                ) {
                    // 搜索框：按中文名或英文 ID 快速筛选全量干员（384）与自定义角色。
                    GlassInputField(
                        value = voiceSearch,
                        onValueChange = { voiceSearch = it },
                        placeholder = "搜索角色名 / ID（如 阿米娅 / amiya）",
                    )
                    Spacer(Modifier.height(2.dp))
                    val filtered = if (voiceSearch.isBlank()) {
                        // 未搜索：默认只展示已配置音色的角色（避免 384 个全展开）；搜索后展示全部匹配。
                        voiceCharacters.filter { char ->
                            val p = voiceEdit[char.id]
                            !p?.zh?.voiceId.isNullOrBlank() || !p?.ja?.voiceId.isNullOrBlank()
                        }
                    } else {
                        val q = voiceSearch.trim()
                        voiceCharacters.filter { it.name.contains(q, true) || it.id.contains(q, true) }
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            if (voiceSearch.isBlank()) "尚未配置任何角色音色，输入角色名搜索后开始配置。"
                            else "未找到「${voiceSearch.trim()}」，试试中文名或英文 ID。",
                            color = scheme.onSurfaceVariant, fontSize = 11.sp,
                        )
                    }
                    filtered.forEach { char ->
                        val id = char.id
                        val pair = voiceEdit[id] ?: VoicePair()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(char.name, color = scheme.primary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(if (pair.zh.voiceId.isBlank()) "中 未配置" else "中 已配置", color = if (pair.zh.voiceId.isBlank()) scheme.error else scheme.tertiary, fontSize = 10.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(if (pair.ja.voiceId.isBlank()) "日 未配置" else "日 已配置", color = if (pair.ja.voiceId.isBlank()) scheme.error else scheme.tertiary, fontSize = 10.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VoiceField(
                                    label = "中文 speaker_id",
                                    value = pair.zh.voiceId,
                                    onValueChange = { value ->
                                        val cur = voiceEdit[id] ?: VoicePair()
                                        voiceEdit = voiceEdit + (id to cur.copy(zh = cur.zh.copy(voiceId = value, resourceId = "")))
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(6.dp))
                                VoiceField(
                                    label = "日文 speaker_id",
                                    value = pair.ja.voiceId,
                                    onValueChange = { value ->
                                        val cur = voiceEdit[id] ?: VoicePair()
                                        voiceEdit = voiceEdit + (id to cur.copy(ja = cur.ja.copy(voiceId = value, resourceId = "")))
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            SaveButton(
                text = "保存角色双语音色",
                saved = voiceSaved,
                onClick = {
                    scope.launch {
                        val toSave = voiceEdit.mapValues { (_, pair) ->
                            pair.copy(zh = pair.zh.copy(resourceId = ""), ja = pair.ja.copy(resourceId = ""))
                        }.filter { (_, pair) -> !pair.zh.isEmpty || !pair.ja.isEmpty }
                        container.settingsRepository.setTtsVoiceMap(toSave)
                        voiceSaved = true
                    }
                },
            )
        }

        // ===== 关于（含主题模式静态展示，合并为一组）=====
        CollapsibleSection(title = "关于", summary = "版本 · 免责声明 · 主题") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("罗德岛通讯终端", color = scheme.onSurface, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Android 版 v1.0.0", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Text("明日方舟同人 AI 角色扮演聊天应用", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "内置 384 位罗德岛干员（20 位含语音/本地立绘 + 364 位自动生成）。项目为明日方舟同人作品，所有角色、立绘、音乐版权归 Hypergryph / 鹰角网络所有，仅用于学习交流，不作商业用途。",
                    color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "主题模式：深色主题（PRTS 终端风，固定）",
                    color = scheme.onSurfaceVariant, fontSize = 12.sp,
                )
            }
        }
    }

    if (showGuide) {
        GuideDialog(onDismiss = { showGuide = false })
    }

    if (showCrashLogs) {
        CrashLogDialog(onDismiss = { showCrashLogs = false })
    }
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

/** 崩溃日志对话框：列出 filesDir/crash/ 下的崩溃/事件日志，点按即分享（用户无需 adb 即可把日志发给开发者）。 */
@Composable
private fun CrashLogDialog(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 打开时扫描一次日志文件（最新在前）。
    val logFiles = remember {
        CrashCapture.crashLogDir(context).listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        title = { Text("崩溃日志", color = scheme.onSurface) },
        text = {
            if (logFiles.isEmpty()) {
                Text("暂无崩溃日志。", color = scheme.onSurfaceVariant)
            } else {
                Column {
                    Text(
                        "共 ${logFiles.size} 条。点击条目即可分享给开发者（内容含机型 / 系统 / ABI 与崩溃堆栈）。",
                        color = scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(logFiles) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val content = withContext(Dispatchers.IO) {
                                                runCatching { file.readText() }.getOrNull()
                                            }
                                            if (content != null) {
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, content)
                                                }
                                                runCatching {
                                                    context.startActivity(Intent.createChooser(intent, "分享崩溃日志"))
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, color = scheme.onSurface, fontSize = 13.sp)
                                    Text(
                                        "大小 ${(file.length() + 1023) / 1024} KB",
                                        color = scheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }
                                Text("分享", color = scheme.primary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
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

/** 字段标签：小号灰字。 */
@Composable
internal fun FieldLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

/** 玻璃输入框（带占位符）。 */
@Composable
internal fun GlassInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    // 文字/占位符颜色按主题自适应（统一 token，见 Color.kt），不依赖 scheme.onSurface 的解析
    val textColor = fieldTextColor()
    val placeholderColor = fieldPlaceholderColor()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 保留原有半透明背景外观（不改 UI）
            .background(scheme.surface.copy(alpha = 0.6f))
            .padding(12.dp),
        textStyle = TextStyle(color = textColor, fontSize = 14.sp),
        singleLine = singleLine,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, color = placeholderColor, fontSize = 14.sp)
            }
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
    val textColor = fieldTextColor()
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
                textStyle = TextStyle(color = textColor, fontSize = 14.sp),
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

    CollapsibleSection(
        title = "聊天背景",
        summary = if (bgConfig.enabled) "已启用 · ${bgConfig.paths.size} 张" else "未启用",
    ) {
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

    // 后台保活相关权限状态（国产 ROM）：通知权限 / 电池白名单 / 精确闹钟。ON_RESUME 重新核验，
    // 用户从系统设置跳回后刷新，避免状态过期。
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var ignoringBattery by remember {
        mutableStateOf(BackgroundSurvivalHelper.isIgnoringBatteryOptimizations(context))
    }
    var exactAlarmGranted by remember {
        mutableStateOf(BackgroundSurvivalHelper.canScheduleExactAlarms(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true
                ignoringBattery = BackgroundSurvivalHelper.isIgnoringBatteryOptimizations(context)
                exactAlarmGranted = BackgroundSurvivalHelper.canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGranted = it
    }

    CollapsibleSection(
        title = "角色问候",
        summary = if (enabled) "已启用 · 每日 $dailyCount 条" else "未启用",
    ) {
        GlassListRow(
            title = "角色主动问候",
            subtitle = if (isCloud) "所选角色白天随机时间主动给你发消息。仅云端 AI 可用。"
            else "仅云端 AI 模式可用，请先切换为云端 AI。",
            trailing = {
                Switch(
                    checked = enabled,
                    // 始终可点：本地下尝试开启时以 Toast 说明原因，而不是整条置灰让人以为坏了
                    enabled = true,
                    onCheckedChange = { on ->
                        if (on && !isCloud) {
                            Toast.makeText(context, "角色主动问候仅云端 AI 可用，请先切换到云端 AI", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                settings.setGreetingEnabled(on)
                                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                GreetingScheduler.reschedule(context, settings)
                            }
                        }
                    },
                )
            },
            showDivider = isCloud && enabled,
        )
        if (enabled && isCloud && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠️", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "通知权限未开启，收不到主动消息提醒",
                    color = scheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    runCatching { context.startActivity(intent) }
                }) { Text("去开启", color = scheme.primary, fontSize = 12.sp) }
            }
        }
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
                        "部分国产 ROM 需手动允许后台运行 / 自启动，否则可能收不到主动消息：" +
                            "当前系统 ${RomDetector.detect().type.displayName}",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                    // 电池优化白名单：未允许时可能被省电冻结，点「去允许」跳系统电池设置
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🔋", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ignoringBattery) "后台运行：已允许" else "后台运行：未允许（可能被省电冻结）",
                            color = if (ignoringBattery) scheme.tertiary else scheme.error,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (!ignoringBattery) {
                            TextButton(onClick = { BackgroundSurvivalHelper.requestIgnoreBatteryOptimizations(context) }) {
                                Text("去允许", color = scheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
                    // 厂商自启动设置（仅当厂商入口可达时显示，如小米/OPPO/vivo 等）
                    val autostartIntent = remember { BackgroundSurvivalHelper.manufacturerAutostartIntent(context) }
                    if (autostartIntent != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📱", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "自启动管理（厂商设置）",
                                color = scheme.onSurface,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { runCatching { context.startActivity(autostartIntent) } }) {
                                Text("去设置", color = scheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
                    // 后台弹出界面（仅小米系）：MIUI/HyperOS 独立开关，未开启时通知点按无法跳转会话
                    val popupIntent = remember { BackgroundSurvivalHelper.backgroundPopupIntent(context) }
                    if (popupIntent != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🪟", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "后台弹出界面（影响点通知跳转）",
                                color = scheme.onSurface,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { runCatching { context.startActivity(popupIntent) } }) {
                                Text("去设置", color = scheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
                    // 精确闹钟（Android 12+）：未授权时提醒，提升后台触发可靠性
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmGranted) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("⏰", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "精确闹钟未授权（后台触发可靠性降低）",
                                color = scheme.onSurface,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { BackgroundSurvivalHelper.requestScheduleExactAlarm(context) }) {
                                Text("去授权", color = scheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
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
                    // 固定高度 LazyColumn：AlertDialog 内 verticalScroll + heightIn 在无界约束下不滚动，
                    // 列表会撑满整屏导致下方干员选不到（bug 修复）。
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(characters, key = { it.id }) { c ->
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

@Composable
private fun GroupChatSection(container: AppContainer, scope: CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings = container.settingsRepository

    val config by settings.groupChatConfig.collectAsState(initial = GroupChatConfig())
    val dailyRounds by settings.groupDailyRounds.collectAsState(initial = AppConfig.GroupChat.DEFAULT_DAILY_ROUNDS)
    val provider by settings.activeProvider.collectAsState(initial = ChatProviderType.CLOUD)
    val isCloud = provider == ChatProviderType.CLOUD

    var roundsValue by remember(dailyRounds) { mutableStateOf(dailyRounds.toFloat()) }
    var testScheduled by remember { mutableStateOf(false) }
    LaunchedEffect(testScheduled) {
        if (testScheduled) { delay(12_000); testScheduled = false }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    CollapsibleSection(
        title = "群聊",
        summary = if (config.enabled) "已启用 · ${config.memberIds.size} 名成员" else "未启用",
    ) {
        GlassListRow(
            title = "多人角色群聊",
            subtitle = if (isCloud) "勾选角色同群聊天；空闲时自动互相聊天并可主动向你提问。仅云端 AI 可用。"
            else "仅云端 AI 模式可用，请先切换为云端 AI。",
            trailing = {
                Switch(
                    checked = config.enabled,
                    // 始终可点：本地下尝试开启时以 Toast 说明原因
                    enabled = true,
                    onCheckedChange = { on ->
                        if (on && !isCloud) {
                            Toast.makeText(context, "群聊仅云端 AI 可用，请先切换到云端 AI", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                settings.setGroupChatConfig(config.copy(enabled = on))
                                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                GroupChatScheduler.reschedule(context, settings)
                            }
                        }
                    },
                )
            },
            showDivider = isCloud && config.enabled,
        )
        if (isCloud) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        GroupChatScheduler.scheduleTest(context)
                        testScheduled = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary.copy(alpha = 0.16f)),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Text("测试群聊（10 秒后）", color = scheme.primary, fontSize = 13.sp)
                }
                if (testScheduled) {
                    Text("✓ 已触发，约 10 秒后收到群聊通知", color = scheme.tertiary, fontSize = 11.sp)
                }
                if (config.enabled) {
                    // 多群聊：成员在「群聊列表 → 新建群聊 / 群信息」里按群设置，设置页不再重复选人
                    Text(
                        "群成员到首页「群聊」的群列表里按群设置（新建群聊时勾选）。",
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("空闲自动聊天", color = scheme.onSurface, fontSize = 13.sp)
                            Text("成员空闲时自动互相聊，并主动向你提问", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                        Switch(
                            checked = config.autoChat,
                            onCheckedChange = { on ->
                                scope.launch {
                                    settings.setGroupChatConfig(config.copy(autoChat = on))
                                    GroupChatScheduler.reschedule(context, settings)
                                }
                            },
                        )
                    }
                    Text("每日自动聊天轮次：${roundsValue.toInt()}", color = scheme.onSurface, fontSize = 12.sp)
                    Slider(
                        value = roundsValue,
                        onValueChange = { roundsValue = it },
                        onValueChangeFinished = {
                            val v = roundsValue.toInt().coerceIn(
                                AppConfig.GroupChat.MIN_DAILY_ROUNDS,
                                AppConfig.GroupChat.MAX_DAILY_ROUNDS,
                            )
                            scope.launch {
                                settings.setGroupDailyRounds(v)
                                GroupChatScheduler.reschedule(context, settings)
                            }
                        },
                        valueRange = AppConfig.GroupChat.MIN_DAILY_ROUNDS.toFloat()..
                            AppConfig.GroupChat.MAX_DAILY_ROUNDS.toFloat(),
                        steps = AppConfig.GroupChat.MAX_DAILY_ROUNDS - AppConfig.GroupChat.MIN_DAILY_ROUNDS - 1,
                    )
                    Text(
                        "部分国产 ROM 需手动允许后台运行 / 自启动，否则收不到自动聊天提醒" +
                            "（同「角色问候」，当前系统 ${RomDetector.detect().type.displayName}）。",
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserProfileSection(container: AppContainer, scope: CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings = container.settingsRepository

    // 表单语义：头像选择/清除先挂起（pending），人设/关系文本本地编辑，全部在「保存」时原子落盘（仿 Seedance 分区）。
    var avatarUri by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var pendingAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var avatarCleared by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val p = settings.getUserProfileNow()
        avatarUri = p.avatarPath
        persona = p.persona
        relationship = p.relationship
    }
    LaunchedEffect(saved) {
        if (saved) { delay(2000); saved = false }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingAvatarUri = uri
            avatarCleared = false
            avatarError = null
        }
    }

    CollapsibleSection(
        title = "我的形象（博士 · 选填）",
        summary = if (persona.isNotBlank() || relationship.isNotBlank()) "已填写" else "未填写",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "以下全部为选填：留空则使用默认身份、不注入额外设定；填写后会把设定带进群聊、单聊与角色主动消息。",
                color = scheme.onSurfaceVariant, fontSize = 11.sp,
            )
            FieldLabel("头像")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val preview: Any? = when {
                    pendingAvatarUri != null -> pendingAvatarUri
                    avatarCleared -> null
                    else -> avatarUri.takeIf { it.isNotBlank() }
                }
                if (preview != null) {
                    Box(modifier = Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
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
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(scheme.primary.copy(alpha = 0.12f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape)
                        .clickable { imagePicker.launch(arrayOf("image/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (preview == null) "＋ 选择" else "更换", color = scheme.primary, fontSize = 11.sp)
                }
                if (preview != null) {
                    TextButton(onClick = {
                        pendingAvatarUri = null
                        avatarCleared = true
                        avatarError = null
                    }) { Text("清除", color = scheme.error, fontSize = 12.sp) }
                }
            }
            avatarError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }
            FieldLabel("人设（我是谁）")
            GlassInputField(
                value = persona,
                onValueChange = { persona = it },
                placeholder = "如「罗德岛的博士，温和可靠，战斗与战术都值得信赖」",
                singleLine = false,
            )
            FieldLabel("与角色之间的关系")
            GlassInputField(
                value = relationship,
                onValueChange = { relationship = it },
                placeholder = "如「我是共建罗德岛的战友，也是他们可以依赖的上司」",
                singleLine = false,
            )
        }
    }

    SaveButton(
        text = "保存我的形象",
        saved = saved,
        onClick = {
            scope.launch {
                var finalAvatar = avatarUri
                val chosen = pendingAvatarUri
                if (chosen != null) {
                    val installed = withContext(Dispatchers.IO) { UserProfileImageStore.save(context, chosen) }
                    if (installed == null) {
                        avatarError = "头像保存失败"
                        return@launch
                    }
                    finalAvatar = installed
                    avatarUri = installed
                    pendingAvatarUri = null
                    avatarCleared = false
                    avatarError = null
                } else if (avatarCleared) {
                    withContext(Dispatchers.IO) { UserProfileImageStore.remove(context) }
                    finalAvatar = ""
                    avatarUri = ""
                    avatarCleared = false
                    avatarError = null
                }
                settings.setUserProfileConfig(
                    UserProfileConfig(
                        avatarPath = finalAvatar,
                        persona = persona.trim(),
                        relationship = relationship.trim(),
                    )
                )
                saved = true
            }
        },
    )
}

@Composable
private fun StorageSection(container: AppContainer, scope: CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    var items by remember { mutableStateOf<List<AppStorageUsage.StorageItem>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmKey by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            refreshing = true
            items = withContext(Dispatchers.IO) { AppStorageUsage.computeItems(context) }
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    CollapsibleSection(
        title = "存储管理",
        summary = "总占用 ${AppStorageUsage.formatBytes(items.sumOf { it.sizeBytes })}",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "统计聊天数据 / 图片缓存 / 视频 / 导入图片占用；模型文件请到「模型」页管理。",
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "总占用：${AppStorageUsage.formatBytes(items.sumOf { it.sizeBytes })}",
                        color = scheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text("不含模型文件", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
                TextButton(onClick = { refresh() }) {
                    Text(if (refreshing) "统计中…" else "刷新", color = scheme.primary, fontSize = 12.sp)
                }
            }
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = scheme.onSurface, fontSize = 13.sp)
                        Text(item.description, color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    Text(
                        AppStorageUsage.formatBytes(item.sizeBytes),
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        // 定宽右对齐：各行大小数字纵向对齐，按钮列不随文案长度错位
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.width(76.dp).padding(end = 6.dp),
                    )
                    TextButton(
                        onClick = { confirmKey = item.key },
                        enabled = item.sizeBytes > 0,
                        modifier = Modifier.width(64.dp),
                    ) {
                        Text(
                            if (item.key == "cache" || item.key == "chatRecords") "清空" else "删除",
                            color = scheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    confirmKey?.let { key ->
        val meta = when (key) {
            "cache" -> Triple(
                "清空缓存",
                "确定清空图片与临时缓存？聊天内容与文件不受影响。",
                "清空",
            )
            "videos" -> Triple(
                "删除视频",
                "确定删除全部 Seedance 视频文件与任务快照？任务记录保留，视频卡片将显示「尚未就绪」。",
                "删除",
            )
            "backgrounds" -> Triple(
                "删除聊天背景",
                "确定删除全部自定义聊天背景？将恢复内置背景轮播。",
                "删除",
            )
            "portraits" -> Triple(
                "删除自定义立绘",
                "确定删除全部自定义角色立绘？自定义角色将恢复无立绘状态，可重新上传。",
                "删除",
            )
            "chatRecords" -> Triple(
                "清空聊天记录",
                "确定清空全部聊天记录（单聊与群聊）？此操作不可恢复；Seedance 任务记录保留。",
                "清空",
            )
            else -> Triple("", "", "")
        }
        AlertDialog(
            onDismissRequest = { confirmKey = null },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text(meta.first, color = scheme.onSurface) },
            text = { Text(meta.second, color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    confirmKey = null
                    scope.launch {
                        when (key) {
                            "cache" -> withContext(Dispatchers.IO) {
                                context.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                            }
                            "videos" -> withContext(Dispatchers.IO) {
                                File(context.filesDir, "seedance/tasks").deleteRecursively()
                            }
                            "backgrounds" -> container.chatBackgroundRepository.clearAll()
                            "portraits" -> withContext(Dispatchers.IO) {
                                File(context.filesDir, "character_images").deleteRecursively()
                            }
                            "chatRecords" -> {
                                container.conversationRepository.clearAll()
                                container.settingsRepository.clearAllActiveConversations()
                            }
                        }
                        refresh()
                    }
                }) { Text(meta.third, color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmKey = null }) { Text("取消", color = scheme.onSurfaceVariant) }
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

    CollapsibleSection(
        title = "Seedance 对话视频",
        summary = if (apiKey.isNotBlank()) "已配置 · ${relayModelId.ifBlank { variantLabel(variant) }}" else "未配置 API Key",
    ) {
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
