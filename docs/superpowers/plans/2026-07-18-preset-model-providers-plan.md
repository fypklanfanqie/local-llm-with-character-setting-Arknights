# 预设模型供应商 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在系统设置页增加预设模型供应商（DeepSeek/OpenAI/通义千问/智谱GLM）下拉选择功能，含各供应商最新模型列表。

**Architecture:** 新增 `ModelProviders.kt` 存放数据类与预设常量；修改 `SettingsScreen.kt` 将手动输入的 Base URL / Model 替换为 M3 ExposedDropdownMenuBox 下拉选择器，保持现有 DataStore 持久化不变。

**Tech Stack:** Kotlin, Jetpack Compose, Material3, DataStore (已有)

## Global Constraints

- 复用现有 `PrtsColors` 主题色，暗色终端风格
- 不修改 `ApiConfig`、`SettingsRepository`、`SettingsStore`、`CloudChatProvider`、`AppContainer`
- 不修改 TTS 配置和"关于"区块
- 项目无测试框架，不要求测试

---

### Task 1: 创建预设数据文件 `config/ModelProviders.kt`

**Files:**
- Create: `app/src/main/java/com/rhodesisland/terminal/config/ModelProviders.kt`

**Interfaces:**
- Produces: `PresetModel` data class, `ModelProvider` data class, `PRESET_PROVIDERS: List<ModelProvider>` 常量

- [ ] **Step 1: 创建 `ModelProviders.kt`**

```kotlin
package com.rhodesisland.terminal.config

/**
 * 预设模型供应商及其模型列表
 *
 * 覆盖 DeepSeek、OpenAI、通义千问、智谱 GLM 四家，
 * 模型列表为截至 2026 年 7 月的最新版本。
 */

data class PresetModel(
    val id: String,           // API 模型名，如 "deepseek-v4-flash"
    val displayName: String,  // 展示名，如 "DeepSeek-V4-Flash"
    val description: String = "",
)

data class ModelProvider(
    val id: String,           // 供应商标识，如 "deepseek"
    val displayName: String,  // 展示名，如 "DeepSeek"
    val baseUrl: String,      // API 地址
    val defaultModel: String, // 默认模型 id
    val models: List<PresetModel>,
    val requiresApiKey: Boolean = true,
)

val PRESET_PROVIDERS: List<ModelProvider> = listOf(
    ModelProvider(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-v4-flash",
        models = listOf(
            PresetModel("deepseek-v4-flash", "DeepSeek-V4-Flash", "旗舰快速，思考/非思考双模式，1M 上下文"),
            PresetModel("deepseek-v4-pro", "DeepSeek-V4-Pro", "高端增强版"),
        ),
    ),
    ModelProvider(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o",
        models = listOf(
            PresetModel("gpt-4o", "GPT-4o", "多模态旗舰"),
            PresetModel("gpt-4o-mini", "GPT-4o Mini", "轻量快速"),
            PresetModel("gpt-4.1", "GPT-4.1", "最新编程优化版"),
            PresetModel("gpt-4.1-mini", "GPT-4.1 Mini", "轻量编程"),
            PresetModel("o4", "o4", "高级推理"),
            PresetModel("o4-mini", "o4-mini", "轻量推理"),
        ),
    ),
    ModelProvider(
        id = "qwen",
        displayName = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.7-max",
        models = listOf(
            PresetModel("qwen3.7-max", "Qwen3.7-Max", "最强旗舰"),
            PresetModel("qwen3.7-plus", "Qwen3.7-Plus", "平衡性能与成本"),
            PresetModel("qwen3.6-flash", "Qwen3.6-Flash", "轻量快速"),
        ),
    ),
    ModelProvider(
        id = "glm",
        displayName = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-5.2",
        models = listOf(
            PresetModel("glm-5.2", "GLM-5.2", "最新旗舰，1M 上下文"),
            PresetModel("glm-5.1", "GLM-5.1", "高性能通用"),
            PresetModel("glm-5-turbo", "GLM-5-Turbo", "快速高性价比"),
            PresetModel("glm-4.7-flash", "GLM-4.7-Flash", "免费轻量"),
        ),
    ),
)
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew :app:compileDebugKotlin
```

预期：编译通过，无错误。

---

### Task 2: 改造 SettingsScreen 增加供应商/模型下拉选择

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `PRESET_PROVIDERS`, `ModelProvider`, `PresetModel` from Task 1
- Consumes: `ApiConfig` from `com.rhodesisland.terminal.data.model`
- Consumes: `PrtsColors` from `com.rhodesisland.terminal.ui.theme`
- Consumes: `AppContainer` from `com.rhodesisland.terminal`

- [ ] **Step 1: 替换 `SettingsScreen` 中 LLM API 配置区域**

将文件第 31-68 行（`apiConfig` 相关状态 + LLM API 配置区块）替换为以下内容。

新增 import：
```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import com.rhodesisland.terminal.config.ModelProvider
import com.rhodesisland.terminal.config.PRESET_PROVIDERS
```

替换 `SettingsScreen` composable 顶部状态声明（第 27-39 行）：
```kotlin
    val apiConfig by container.settingsRepository.apiConfig.collectAsState(initial = ApiConfig())
    val ttsConfig by container.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
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
```

替换 `LLM API 配置` 区块（原第 52-68 行）：
```kotlin
        // ===== LLM API 配置 =====
        SectionDivider("LLM API 配置")

        // 供应商下拉
        Text("模型商", color = PrtsColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        ProviderDropdown(
            selectedProvider = selectedProvider,
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it },
            onProviderSelected = { provider ->
                selectedProvider = provider
                selectedModel = provider.models.firstOrNull()
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
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
        ) {
            Text("保存 API 设置", color = PrtsColors.Gold)
        }
```

- [ ] **Step 2: 添加 `ProviderDropdown` 组件**

在文件末尾（`PasswordField` 之后）添加：

```kotlin
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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
```

- [ ] **Step 3: 添加 `ModelDropdown` 组件**

在 `ProviderDropdown` 之后添加：

```kotlin
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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
```

- [ ] **Step 4: 验证编译**

```bash
./gradlew :app:compileDebugKotlin
```

预期：编译通过，无错误。

- [ ] **Step 5: 验证构建**

```bash
./gradlew :app:assembleDebug
```

预期：构建成功，生成 APK。