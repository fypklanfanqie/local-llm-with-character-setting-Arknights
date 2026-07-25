# 预设模型供应商 — 设计文档

**日期**: 2026-07-18
**状态**: 已确认

## 1. 概述

在系统设置页增加预设模型供应商选择功能，覆盖 DeepSeek、OpenAI、通义千问、智谱 GLM 四家，提供各家最新模型列表供用户快捷选择。API Key 仍由用户自行申请填入。

## 2. 数据模型

新增 `config/ModelProviders.kt`，包含 `PresetModel` 和 `ModelProvider` 两个数据类，以及 `PRESET_PROVIDERS` 常量。

```kotlin
data class PresetModel(
    val id: String,           // API 模型名
    val displayName: String,  // 展示名
    val description: String = "",
)

data class ModelProvider(
    val id: String,           // 供应商标识
    val displayName: String,  // 展示名
    val baseUrl: String,      // API 地址
    val defaultModel: String, // 默认模型
    val models: List<PresetModel>,
    val requiresApiKey: Boolean = true,
)
```

### 预设供应商

| 供应商 | Base URL | 默认模型 |
|--------|----------|----------|
| DeepSeek | `https://api.deepseek.com` | `deepseek-v4-flash` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3.7-max` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.2` |

### 预设模型列表

**DeepSeek**:
- `deepseek-v4-flash` — DeepSeek-V4-Flash（旗舰，1M 上下文）
- `deepseek-v4-pro` — DeepSeek-V4-Pro（高端增强）

**OpenAI**:
- `gpt-4o` — GPT-4o（多模态旗舰）
- `gpt-4o-mini` — GPT-4o Mini（轻量快速）
- `gpt-4.1` — GPT-4.1（编程优化）
- `gpt-4.1-mini` — GPT-4.1 Mini（轻量编程）
- `o4` — o4（高级推理）
- `o4-mini` — o4-mini（轻量推理）

**通义千问**:
- `qwen3.7-max` — Qwen3.7-Max（最强旗舰）
- `qwen3.7-plus` — Qwen3.7-Plus（平衡性能与成本）
- `qwen3.6-flash` — Qwen3.6-Flash（轻量快速）

**智谱 GLM**:
- `glm-5.2` — GLM-5.2（最新旗舰，1M 上下文）
- `glm-5.1` — GLM-5.1（高性能通用）
- `glm-5-turbo` — GLM-5-Turbo（快速高性价比）
- `glm-4.7-flash` — GLM-4.7-Flash（免费轻量）

## 3. UI 设计

### 设置页改造

在现有"LLM API 配置"区块中，将手动输入的 Base URL 和 Model 字段替换为下拉选择器：

```
LLM API 配置
──────────────────
模型商    [DeepSeek         ▼]
模型      [DeepSeek-V4-Flash▼]
API KEY  [····················]
[保存 API 设置]
```

### 交互逻辑

1. **初始化匹配**：根据当前 `ApiConfig.baseUrl` 匹配预设供应商，匹配不到显示"自定义"
2. **切换供应商** → 自动更新 Base URL → 模型列表切换为该供应商的模型 → 自动选中默认模型
3. **切换模型** → 更新 `model` 字段
4. **保存** → 写入 DataStore（逻辑不变）
5. **自定义**：当 URL 不匹配任何预设时，保留原有三个输入框均可手动编辑

### 新增 UI 组件

- `ProviderDropdown`：供应商下拉选择器
- `ModelDropdown`：模型下拉选择器（根据供应商动态更新）

复用现有 `PrtsColors` 主题色，与暗色终端风格一致。

## 4. 数据流

```
SettingsScreen
  ├─ ProviderDropdown → 选中 ModelProvider
  ├─ ModelDropdown    → 选中 PresetModel
  ├─ apiBase = provider.baseUrl
  ├─ apiModel = model.id
  ├─ apiKey  = 用户手动输入
  └─ [保存] → SettingsRepository.setApiConfig(ApiConfig(...))
                  │
                  ▼
             DataStore (持久化)
                  │
                  ▼
             CloudChatProvider 读取 apiConfig → 发起请求
```

## 5. 不变的部分

- `ApiConfig` 数据类
- `SettingsRepository` / `SettingsStore`
- `CloudChatProvider`
- `AppContainer` DI 容器
- TTS 配置区块
- "关于"区块

## 6. 新增文件

- `app/src/main/java/com/rhodesisland/terminal/config/ModelProviders.kt` — 数据类 + 预设常量

## 7. 修改文件

- `app/src/main/java/com/rhodesisland/terminal/ui/settings/SettingsScreen.kt` — UI 改造