# Rhodes Island Terminal · 罗德岛通讯终端

> A fan-made Arknights AI character roleplay chat app for Android — 明日方舟同人 AI 角色扮演聊天应用

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.06-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![API](https://img.shields.io/badge/API-24%2B-34A853?logo=android)](https://developer.android.com/about/versions)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

---

[English](#english) | [中文](#中文)

---

## English

### Overview

Rhodes Island Terminal is a fan-made Android app that lets you chat with AI-powered Arknights characters. Originally migrated from a WeChat Mini Program, this native Android version features 20 fully-voiced operators, multiple AI backends (cloud + local), TTS, BGM player, and a PRTS-style sci-fi terminal UI.
<img width="1080" height="2400" alt="Screenshot_2026-07-25-14-55-13-225_com rhodesisl" src="https://github.com/user-attachments/assets/4ea3ea8e-2b50-4433-9a05-af4d20c31466" />

### Features

- 🤖 **Cloud AI** — Direct API connection to DeepSeek, OpenAI, Qwen (通义千问), and GLM (智谱). SSE streaming with configurable endpoints.
- 📱 **Local AI** — MNN engine with QNN NPU (Qualcomm HTP) acceleration for offline inference. Supports `.mnn` models. Low-memory optimized.
- <img width="1080" height="2400" alt="Screenshot_2026-07-25-14-58-36-685_com rhodesisl" src="https://github.com/user-attachments/assets/8ee0870b-d23b-401a-80d8-2b3996291a99" />

- 🔄 **Model Switching** — One-tap toggle between cloud API and local MNN inference on the chat screen.
- ⬇️ **Model Management** — Download/pause/resume/delete local models from a remote server, with SHA256 integrity verification.
- <img width="1080" height="2400" alt="Screenshot_2026-07-25-14-57-48-347_com rhodesisl" src="https://github.com/user-attachments/assets/6d081ad8-e333-40a3-8fc6-5e62d23fe421" />

- 🎙️ **Volcano Engine TTS** — ByteDance Doubao voice synthesis (Chinese + Japanese), text-to-speech for character voice lines.
- 🎵 **BGM Player** — 26 Arknights original soundtrack tracks as background music.
- <img width="1080" height="2400" alt="Screenshot_2026-07-25-14-57-39-734_com rhodesisl" src="https://github.com/user-attachments/assets/3acd6ae2-7aec-42eb-8a92-be45388fa374" />

- 📝 **Markdown Rendering** — Full Markdown support with VS Code Dark+ code highlighting and mathematical formula blocks.
- 🎨 **PRTS Terminal UI** — Dark sci-fi themed interface with iOS Liquid Glass frosted-glass bottom navigation.
- 📊 **Performance Overlay** — Real-time CPU/GPU/NPU usage and temperature monitoring via Liquid Glass overlay (non-root, system overlay permission required).
- 🆓 **Completely Free** — All payment, credit, and ad features have been removed.

### Characters (20 Operators)
<img width="1080" height="2400" alt="Screenshot_2026-07-25-14-57-44-111_com rhodesisl" src="https://github.com/user-attachments/assets/a7436538-5e69-4b1a-9407-283561db1beb" />


| # | Character | Race | Role |
|---|-----------|------|------|
| 1 | 羽毛笔 La Pluma | 黎博利 | 近卫干员 / 调酒师 |
| 2 | 阿米娅 Amiya | 卡特斯/奇美拉 | 罗德岛公开领袖 |
| 3 | 艾雅法拉 Eyjafjalla | 卡普里尼 | 火山学家 / 天灾信使 |
| 4 | 澄闪 Goldenglow | 菲林 | 理发师 / 驭械术师 |
| 5 | 泥岩 Mudrock | 萨卡兹 | 萨卡兹雇佣兵 / 不屈者 |
| 6 | 逻各斯 Logos | 萨卡兹/妖 | 精英术师 / 咒术大师 |
| 7 | 蜜莓 Honeyberry | 札拉克 | 医疗部 / 草药医生 |
| 8 | 遥 Haruka | 阿戈尔 | 东国艺人 |
| 9 | 维什戴尔 Wis'adel | 萨卡兹 | 雇佣兵领袖 / 巴别塔议长 |
| 10 | 左乐 Zuole | 斐迪亚 | 司岁台秉烛人 |
| 11 | 麦哲伦 Magallan | 黎博利 | 莱茵生命外勤专员 |
| 12 | 黍 Shu | 岁兽碎片 | 炎国农业天师 |
| 13 | 史尔特尔 Surtr | 萨卡兹 | 近卫干员 |
| 14 | 晓歌 Cantabile | 黎博利 | 先锋干员 / 情报官 |
| 15 | 林 Lin | 札拉克 | 龙门合作者 |
| 16 | 拉普兰德 Lappland | 鲁珀 | 近卫干员 / 领主 |
| 17 | 送葬人 Executor | 萨科塔 | 拉特兰公证所执行者 |
| 18 | Mon3tr | 未公开 | 罗德岛特别顾问 |
| 19 | 星源 Xingyuan | 黎博利 | 莱茵生命能量科研究员 |
| 20 | 德克萨斯 Texas | 鲁珀 | 企鹅物流信使 / 先锋干员 |

Each character has a detailed system prompt defining personality, speech patterns, and lore background.

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 100% |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository + Manager |
| DI | Manual (AppContainer) |
| Network | Retrofit2 + OkHttp3 |
| Persistence | Room + DataStore Preferences |
| Image | Coil |
| Audio | ExoPlayer (Media3) / MediaPlayer |
| Local LLM | MNN (libMNN.so) + JNI via CMake |
| NPU | QNN HTP (Qualcomm Neural Network) |
| Markdown | Custom parser + VS Code Dark+ highlight |
| UI Effects | Liquid Glass (QmDeve) |

### Project Structure

```
app/src/main/java/com/rhodesisland/terminal/
├── config/              # AppConfig, Characters, ModelProviders, AssetPaths
├── data/
│   ├── local/           # Room DB, DataStore
│   ├── model/           # Character, ChatMessage, Conversation, LocalModel
│   ├── remote/          # CloudRunApi, DirectLlmClient, RetrofitClient
│   └── repository/      # Chat, Character, Settings, Conversation, Asset repos
├── download/            # Model download with resume/SHA256
├── llm/                 # LLM inference optimization
│   └── backend/         # BackendManager, MnnBackend, MnnBridge, BackendSelector
├── manager/             # AudioManager, ModelManager, TtsManager
├── perfmon/             # Performance monitoring overlay
├── provider/
│   ├── cloud/           # CloudChatProvider (SSE streaming)
│   └── local/           # LocalChatProvider (MNN inference)
├── tts/                 # VolcTtsClient (Volcano Engine TTS)
├── ui/
│   ├── chat/            # ChatScreen, ChatViewModel, MathView
│   ├── characters/      # Character selection
│   ├── models/          # Model download/management
│   ├── music/           # BGM player
│   ├── navigation/      # AppNavGraph
│   ├── settings/        # Settings, BackendSettings, Guide
│   └── theme/           # Color, Theme, Type (PRTS dark sci-fi)
├── util/                # MarkdownParser, DeviceId, CharacterImageStore
└── MainActivity.kt
```

### Native Code

```
app/src/main/cpp/
├── CMakeLists.txt         # always compiles cpu_sys_jni; optionally mnn_jni
├── cpu_affinity.h         # CPU topology probe (big core IDs, frequencies)
├── cpu_affinity_jni.cpp   # JNI for read-only CPU sysfs queries
└── mnn_jni.cpp            # MNN LLM JNI wrapper (ported from MnnLlmChat)
```

Prebuilt libraries in `app/src/main/jniLibs/arm64-v8a/`:
- `libMNN.so` — MNN inference engine (CPU, OpenCL GPU, QNN NPU backends)
- `libQnnHtp.so` + `libQnnHtpV*Skel.so` — Qualcomm HTP (NPU) runtime
- `libQnnSystem.so` — QNN system library
- `libc++_shared.so` — C++ shared runtime

### Build

#### Prerequisites
- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 21** (Temurin recommended)
- **NDK 27.2.12479018** (r27c)
- **CMake 3.22.1** (via SDK Manager)

#### Quick Build

```bash
# Clone
git clone https://github.com/fypklanfanqie/local-llm-with-character-setting-Arknights.git
cd local-llm-with-character-setting-Arknights

# Build (debug APK)
./gradlew assembleDebug
```

Or open in Android Studio and **Build > Build APK**.

#### MNN / QNN Setup

The MNN JNI wrapper (`libmnn_jni.so`) is compiled at build time via `externalNativeBuild`. Configure:

1. Prepare MNN prebuilt directory with:
   ```
   <MNN_DIR>/
   ├── include/          # MNN core headers + llm/llm.hpp
   └── lib/
       └── libMNN.so     # MNN shared library (arm64-v8a)
   ```

2. Set `MNN_DIR` in `gradle.properties`:
   ```properties
   MNN_DIR=D:/path/to/mnn-matched
   ```

3. Build — CMake auto-compiles `libmnn_jni.so` and links against `libMNN.so`.

> **Note:** `cpu_sys_jni` (CPU topology probe) always compiles — no configuration needed.

### Configuration

In `app/src/main/java/com/rhodesisland/terminal/config/AppConfig.kt`:

```kotlin
// Cloud Run proxy (currently only used for Volcano Engine TTS)
const val CLOUD_RUN_BASE_URL = "https://your-proxy.cloudrun.cloudbase.net"

// Asset CDN base URL (optional — falls back to local assets)
const val ASSET_CDN_BASE = "https://your-cdn.com/arknights"

// Cloud AI defaults
const val DEFAULT_API_BASE = "https://api.deepseek.com/v1"
const val DEFAULT_MODEL = "deepseek-chat"
```

Cloud AI providers (DeepSeek, OpenAI, Qwen, GLM) are configured in `ModelProviders.kt` with preset models. API keys are entered in the Settings screen.

### Local AI: First Run

1. Open the app → **Settings** → **Model Management**
2. The app fetches the model list from the configured server
3. Download a `.mnn` model (e.g., Qwen3-4B-MNN)
4. Go to chat → switch to **Local AI** → start chatting offline

Model files are stored in:
```
Android/data/com.rhodesisland.terminal/files/models/
```

### Assets

Character art, voice lines, and BGM are stored in `app/src/main/assets/`:
- `picture/` — Operator artwork (webp)
- `music/` — Voice lines (wav) + BGM (mp3)
- `background/` — Background images (webp/jpg)

To reduce APK size, configure `ASSET_CDN_BASE` in AppConfig to serve assets from a public CDN. The app falls back to local assets if a CDN is not configured.

### Disclaimer

> This is a fan-made Arknights project. All characters, artwork, and music are copyrighted by **Hypergryph / 鹰角网络**. This project is for learning and communication purposes only — not for commercial use.

---

## 中文

### 概述

罗德岛通讯终端是一款明日方舟同人 AI 角色扮演聊天 Android 应用。从微信小程序迁移而来，原生 Android 版本内置 20 位明日方舟干员，支持云端/本地双 AI 引擎、TTS 语音合成、BGM 播放器、PRTS 科幻终端风格 UI。

### 特性

- 🤖 **云端 AI** — 直连 DeepSeek、OpenAI、通义千问、智谱 GLM 对话 API，SSE 流式输出，支持自定义 API 地址和密钥
- 📱 **本地 AI** — MNN 引擎 + QNN NPU 加速（高通 HTP），离线推理 `.mnn` 模型，低内存优化
- 🔄 **模型切换** — 聊天页一键切换云端/本地 AI，无需重启对话
- ⬇️ **模型管理** — 从服务器动态获取模型列表，支持下载/暂停/恢复/删除，SHA256 完整性校验
- 🎙️ **火山引擎 TTS** — 字节跳动豆包语音合成，支持中日双语
- 🎵 **BGM 播放器** — 26 首明日方舟原声音乐
- 📝 **Markdown 渲染** — 完整 Markdown 支持，VS Code Dark+ 代码高亮，物化公式块
- 🎨 **PRTS 终端 UI** — 深色科幻风格界面，iOS Liquid Glass 液态玻璃底部导航
- 📊 **性能监控** — Liquid Glass 浮窗实时显示 CPU/GPU/NPU 占用和温度（非 root，需悬浮窗权限）
- 🆓 **完全免费** — 已移除所有付费/积分/广告功能

### 20 位干员

每位干员均配有详细的系统提示词，定义了性格、语气特征和背景故事。

| # | 干员 | 种族 | 定位 |
|---|------|------|------|
| 1 | 羽毛笔 | 黎博利 | 近卫干员 / 调酒师 |
| 2 | 阿米娅 | 卡特斯/奇美拉 | 罗德岛公开领袖 |
| 3 | 艾雅法拉 | 卡普里尼 | 火山学家 / 天灾信使 |
| 4 | 澄闪 | 菲林 | 理发师 / 驭械术师 |
| 5 | 泥岩 | 萨卡兹 | 萨卡兹雇佣兵 / 不屈者 |
| 6 | 逻各斯 | 萨卡兹/妖 | 精英术师 / 咒术大师 |
| 7 | 蜜莓 | 札拉克 | 医疗部 / 草药医生 |
| 8 | 遥 | 阿戈尔 | 东国艺人 |
| 9 | 维什戴尔 | 萨卡兹 | 雇佣兵领袖 / 巴别塔议长 |
| 10 | 左乐 | 斐迪亚 | 司岁台秉烛人 |
| 11 | 麦哲伦 | 黎博利 | 莱茵生命外勤专员 |
| 12 | 黍 | 岁兽碎片 | 炎国农业天师 |
| 13 | 史尔特尔 | 萨卡兹 | 近卫干员 |
| 14 | 晓歌 | 黎博利 | 先锋干员 / 情报官 |
| 15 | 林 | 札拉克 | 龙门合作者 |
| 16 | 拉普兰德 | 鲁珀 | 近卫干员 / 领主 |
| 17 | 送葬人 | 萨科塔 | 拉特兰公证所执行者 |
| 18 | Mon3tr | 未公开 | 罗德岛特别顾问 |
| 19 | 星源 | 黎博利 | 莱茵生命能量科研究员 |
| 20 | 德克萨斯 | 鲁珀 | 企鹅物流信使 / 先锋干员 |

### 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 100% |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Repository + Manager |
| 依赖注入 | 手动 (AppContainer) |
| 网络 | Retrofit2 + OkHttp3 |
| 持久化 | Room + DataStore Preferences |
| 图片加载 | Coil |
| 音频 | ExoPlayer (Media3) / MediaPlayer |
| 本地大模型 | MNN (libMNN.so) + JNI via CMake |
| NPU 加速 | QNN HTP (高通神经网络) |
| Markdown | 自定义解析器 + VS Code Dark+ 语法高亮 |
| UI 特效 | Liquid Glass (QmDeve) |

### 项目结构

```
app/src/main/java/com/rhodesisland/terminal/
├── config/              # 应用配置、角色定义、模型供应商、资源路径
├── data/
│   ├── local/           # Room 数据库、DataStore 偏好设置
│   ├── model/           # 角色、消息、会话、本地模型数据类
│   ├── remote/          # CloudRun API、直连对话商客户端、Retrofit 配置
│   └── repository/      # 聊天、角色、设置、会话、资源仓库
├── download/            # 模型下载（断点续传/SHA256校验）
├── llm/                 # LLM 推理优化（CPU提频、线程优化、热监控、内存估算）
│   └── backend/         # 后端管理器、MNN后端、MNN桥接、后端选择器
├── manager/             # 音频管理器、模型管理器、TTS管理器
├── perfmon/             # 性能监控浮窗（Liquid Glass 液态玻璃）
├── provider/
│   ├── cloud/           # 云端对话提供者（SSE 流式）
│   └── local/           # 本地对话提供者（MNN 推理）
├── tts/                 # 火山引擎 TTS 客户端
├── ui/
│   ├── chat/            # 聊天界面、ViewModel、数学公式渲染
│   ├── characters/      # 角色选择页
│   ├── models/          # 模型管理页
│   ├── music/           # BGM 播放器
│   ├── navigation/      # 应用导航图
│   ├── settings/        # 设置页、后端设置、使用指南
│   └── theme/           # 颜色、主题、字体（PRTS 深色科幻风）
├── util/                # Markdown 解析器、设备ID、角色图片存储
└── MainActivity.kt
```

### 原生代码

```
app/src/main/cpp/
├── CMakeLists.txt         # 始终编译 cpu_sys_jni；可选编译 mnn_jni
├── cpu_affinity.h         # CPU 拓扑探测（大核ID、频率）
├── cpu_affinity_jni.cpp   # 只读 CPU sysfs 查询 JNI
└── mnn_jni.cpp            # MNN LLM JNI 包装（移植自 MnnLlmChat）
```

预编译库在 `app/src/main/jniLibs/arm64-v8a/`：
- `libMNN.so` — MNN 推理引擎（CPU/OpenCL GPU/QNN NPU 后端）
- `libQnnHtp.so` + `libQnnHtpV*Skel.so` — 高通 HTP (NPU) 运行时
- `libQnnSystem.so` — QNN 系统库
- `libc++_shared.so` — C++ 共享运行时

### 构建

#### 前提条件
- **Android Studio** Hedgehog (2023.1.1) 或更新版本
- **JDK 21**（推荐 Temurin）
- **NDK 27.2.12479018** (r27c)
- **CMake 3.22.1**（通过 SDK Manager 安装）

#### 快速构建

```bash
# 克隆仓库
git clone https://github.com/fypklanfanqie/local-llm-with-character-setting-Arknights.git
cd local-llm-with-character-setting-Arknights

# 构建 Debug APK
./gradlew assembleDebug
```

或在 Android Studio 中打开项目，点击 **Build > Build APK**。

#### MNN / QNN 配置

MNN JNI 包装 (`libmnn_jni.so`) 在构建时通过 `externalNativeBuild` 编译。配置步骤：

1. 准备 MNN 预编译目录：
   ```
   <MNN_DIR>/
   ├── include/          # MNN 核心头文件 + llm/llm.hpp
   └── lib/
       └── libMNN.so     # MNN 共享库 (arm64-v8a)
   ```

2. 在 `gradle.properties` 中设置 `MNN_DIR`：
   ```properties
   MNN_DIR=D:/path/to/mnn-matched
   ```

3. 构建 — CMake 自动编译 `libmnn_jni.so` 并链接 `libMNN.so`。

> **注意：** `cpu_sys_jni`（CPU 拓扑探测）始终编译，无需额外配置。

### 配置说明

在 `app/src/main/java/com/rhodesisland/terminal/config/AppConfig.kt` 中：

```kotlin
// CloudRun 代理地址（目前仅用于火山引擎 TTS）
const val CLOUD_RUN_BASE_URL = "https://your-proxy.cloudrun.cloudbase.net"

// 资源 CDN 地址（可选 — 留空则使用本地 assets）
const val ASSET_CDN_BASE = "https://your-cdn.com/arknights"

// 云端 AI 默认配置
const val DEFAULT_API_BASE = "https://api.deepseek.com/v1"
const val DEFAULT_MODEL = "deepseek-chat"
```

云端 AI 供应商（DeepSeek、OpenAI、通义千问、智谱 GLM）及其预设模型在 `ModelProviders.kt` 中定义。API 密钥在设置页面输入。

### 本地 AI：首次使用

1. 打开应用 → **设置** → **模型管理**
2. 应用自动从服务器拉取模型列表
3. 下载 `.mnn` 模型（如 Qwen3-4B-MNN）
4. 返回聊天页 → 切换至 **本地 AI** → 离线流式对话

模型文件存储在：
```
Android/data/com.rhodesisland.terminal/files/models/
```

### 资源文件

角色立绘、语音和 BGM 存储在 `app/src/main/assets/`：
- `picture/` — 干员立绘 (webp)
- `music/` — 角色语音 (wav) + BGM (mp3)
- `background/` — 背景图 (webp/jpg)

为减小 APK 体积，可在 `AppConfig` 中配置 `ASSET_CDN_BASE` 将资源托管至公网 CDN。未配置 CDN 时自动回退到本地 assets。

### 免责声明

> 本项目为明日方舟同人作品，所有角色、立绘、音乐版权归 **Hypergryph / 鹰角网络** 所有。本项目仅用于学习交流，不作商业用途。

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

<p align="center">
  <sub>Made with ❤️ for the Arknights community · 为明日方舟社区而建</sub>
</p>
