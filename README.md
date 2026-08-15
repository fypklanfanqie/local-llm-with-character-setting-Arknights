# Rhodes Island Terminal · 罗德岛通讯终端

> 明日方舟同人 AI 角色扮演聊天应用：端侧 **MNN 本地大模型推理**（自适应三后端 + 基准认证 + 深度思考）+ 云端双引擎 + 内置方舟 BGM/语音/立绘 · A fan-made Arknights on-device LLM roleplay chat app (MNN local inference + cloud engine + built-in Arknights BGM/voice/art)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![MNN](https://img.shields.io/badge/Local%20LLM-MNN-00C4A7?logo=alibabacloud)](https://github.com/alibaba/MNN)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🆕 本次更新 · What's New

本版本把本地 LLM 与聊天体验整体升级为「自适应推理 + 工程化闭环」架构，并合入一批新功能：

- **🧠 端侧 MNN 自适应推理引擎** — CPU / OpenCL GPU / QNN NPU 三后端自适应调度、失败自动回退链、**GPU 自愈健康**（隔离进程探测 + 冷却/黑名单状态机）、一键 GPU 预热。完全离线推理，数据不出设备。
- **🚀 本地深度思考分级** — AUTO / SHORT / MEDIUM / LONG 思考分级 + 字节预算截断，推理过程以可折叠「思考过程」块展示；思考开关有效性由聊天模板能力探测判定。
- **🛡️ 内存准入 + 基准认证** — 大模型不 OOM：内存不足自动按轮减半上下文（最低 512）而**不崩溃不报错**；lookahead / 多 token 解码等实验加速**必须在真机基准测试中证明收益**后才启用。
- **🎬 角色视频生成 · Seedance** — 聊天回复自动触发角色短片生成（「邂逅」时间线：播放 / 导出 / 历史），自定义参考图与场景。
- **🔊 双 TTS 引擎** — 系统离线 TTS（默认，免配置）＋ 火山引擎豆包云端声音复刻（每角色独立音色，中日双语）。
- **💬 聊天可靠性重构** — 思考流 30fps 节流渲染、用户控制底部跟随、「停止」保留部分输出、首答不再闪烁消失、支持删除单条消息。
- **📦 模型下载可靠性** — 多镜像自动回退（ModelScope → hf-mirror → HuggingFace）+ 目录大小 / SHA-256 / 权重文件完整性校验，损坏模型不会被打上「已完成」。
- **⏰ 后台保活增强** — 角色主动问候改为 15 分钟周期调度 + 精确闹钟兜底 + 前台服务/WakeLock 保护推理生成，国产 ROM 也能稳定触发。

---

## 🧠 本地 LLM 推理 · On-device Local LLM（重点）

基于 [MNN](https://github.com/alibaba/MNN) 的**自适应端侧推理栈**：从设备能力探测、内存准入、后端调度、健康自愈，到基准认证、性能遥测的一整套工程化闭环。全部推理在设备本地完成，**对话数据不离开手机**。

### 自适应后端调度 · Adaptive backend scheduling

- **三后端自动选择**：`CPU` / `OpenCL GPU` / `QNN NPU`。系统根据设备能力（SoC 芯片等级、CPU 大核数、总内存、NPU 支持）推荐首选后端，并按「用户偏好 × 模型大小 × GPU 就绪度」生成每条消息的回退尝试链；GPU 空输出 / 加载失败自动回退 CPU，CPU 永远是最终兜底。
- **大模型 GPU 准入**：AUTO 模式下总参数量 **> 7B** 的模型才尝试 GPU（OpenCL），≤ 7B 默认走 CPU，避免小模型在 GPU 上的无谓开销。

### GPU 自愈健康 · Self-healing GPU health

- **隔离进程 OpenCL 探测**：在独立 `:mnn_probe` 进程中真正执行 OpenCL（15s 超时），主进程永远不被 GPU 崩溃拖垮；文件通道跨进程回传结果。
- **健康状态机**：每个「设备 × 模型 × 后端 × 变体」维护 probe-ok / model-ok / 冷却 / 崩溃黑名单记录；设备 / 系统 / 模型变化后指纹自动过期。
- **一键 GPU 预热**：手动预载 >7B 模型并跑一次 ≤8 token 的极短生成，预编译 OpenCL kernel 缓存，显著降低首条消息 TTFT。

### 内存准入 · Memory admission

- 每条本地消息生成前检查系统内存 + 进程 PSS；内存不足时**自动按轮减半上下文（最低 512）**而不崩溃、不报错，且不改动用户设置。
- KV 缓存按模型架构精确估算（GQA 感知），上下文滑杆旁实时显示对应内存占用。
- 进程真实峰值 PSS 被采样回灌，后续准入不断自我校准。

### 基准测试与认证 · Benchmark & certification

- **六场景基准**：冷加载 / 短 TTFT / 长 prefill / 固定 decode / 二轮 KV 复用 / 空响应检查，覆盖 **CPU × GPU × 思考开关** 四象限，P95 统计、热拒绝与可靠度运行。
- **设备端认证门**：lookahead、多 token 解码等实验特性**必须**在真机上证明 ≥10% decode 提升、无 TTFT/PSS 明显回退，才写入 DataStore 认证并被启用。

### 本地深度思考 · Local deep thinking

- 思考分级 **AUTO / SHORT / MEDIUM / LONG**（仅本地模型生效），AUTO 按问题复杂度自动分级。
- 思考区有软目标时长与硬字节预算；超预算自动截断并「合并直接作答」，不拖死整轮生成。
- 思考开关有效性由**聊天模板能力探测**判定（模板无 `enable_thinking` 分支时不会误报可用）。

### 性能与遥测 · Performance & telemetry

- 两种性能模式：**综合平衡**（稳定解码，默认）与**最高速度**（最大解码吞吐，过热 / 内存吃紧自动降级）。
- 非 root CPU 提速（PerformanceHint API 31+ + 线程优先级 + Sustained Performance Mode）、热感应降线程（中热减半 / 严重 2 线程 / 危急 1 线程）、大核拓扑感知选线程数。
- **液态玻璃性能浮窗**实时监控 token/s、CPU / GPU / NPU、温度、内存；每轮推理生成结构化遥测（加载耗时、TTFT、prefill/decode、KV 复用、回退链、降级原因、思考分类）。

### 本地模型管理 · Local model management

- 内置 **13 款 MNN 模型清单**（无网络模型市场），支持下载 / 暂停续传 / 删除 / 切换，多文件分块合并 + 完整性校验。
- 下载多镜像自动回退：**ModelScope（国内）→ hf-mirror → HuggingFace**。
- 删除 / 切换活动模型即时释放 MNN native 句柄（生成中安全延迟释放）。

---

## ✨ 特性 · Features

- **🤖 云端 + 本地双引擎** — 云端 OpenAI 兼容 API（DeepSeek / OpenAI / 通义千问 / 智谱，SSE 流式）与本地 MNN 离线推理一键切换，对话按角色独立保存。
- **🎎 20 位罗德岛干员** — 完整人格 system prompt + 立绘 + 语音，支持新建 / 导入 / 导出自定义角色。
- **🎵 音乐播放器** — **内置 7 首方舟 BGM（本地 mp3，离线可播）+ 178 首网易云方舟 OST 目录**，另支持网易云在线搜索与本地音乐导入；进度 / 音量 / 歌词 / 收藏 / 随机 / 三档循环。
- **🔊 双 TTS 语音合成** — 系统离线 TTS（默认）+ 火山引擎豆包云端声音复刻（每角色独立音色，中日双语）；朗读自动剥离 `<think>` 思考块。
- **🖼️ 多模态对话** — 图片（最多 3 张）、PDF（前 6 页）、纯文本文件直连多模态模型。
- **📝 Markdown 渲染** — 完整 Markdown 支持，代码高亮 + 数学公式块。
- **📊 性能浮窗** — 本地推理时实时监控 Token 速率 / CPU / GPU / NPU / 温度 / 内存，液态玻璃风格浮窗（非 root）。
- **💬 角色主动问候** — 15 分钟周期调度 + 精确闹钟兜底，角色会在你离开后主动发来消息（跨重启存活，限云端），类微信横幅通知。
- **🎨 PRTS 深色终端 UI** — 深藏青底 + 罗德岛金强调的液态玻璃界面，衬线标题、科幻终端风。

## 20 位干员 · Operators

| # | 干员 | 种族 | 定位 |
|---|------|------|------|
| 1 | 羽毛笔 | 黎博利 | 近卫干员 / 调酒师 |
| 2 | 阿米娅 | 卡特斯 / 奇美拉 | 罗德岛公开领袖 |
| 3 | 艾雅法拉 | 卡普里尼 | 火山学家 / 天灾信使 |
| 4 | 澄闪 | 菲林 | 理发师 / 驭械术师 |
| 5 | 泥岩 | 萨卡兹 | 萨卡兹雇佣兵 / 不屈者 |
| 6 | 逻各斯 | 萨卡兹 / 妖 | 精英术师 / 咒术大师 |
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

每位干员均配有详细的系统提示词，定义了性格、语气特征和背景故事。

## 技术栈 · Tech Stack

| 分类 | 技术 |
|------|------|
| 语言 | Kotlin 100%（2.0.0） |
| UI | Jetpack Compose + Material 3，液态玻璃（霜玻璃模糊 + 动态渐变网格背景） |
| 架构 | MVVM + Repository + Manager，手动 DI（AppContainer） |
| 本地推理 | **MNN 自适应引擎**（CPU / OpenCL GPU / QNN NPU），arm64-v8a only · NDK 27 预编译库 |
| 深度思考 | 本地思考分级 + 字节预算 + 聊天模板能力探测 |
| 基准认证 | 六场景四象限基准、DataStore 认证存储、实验特性设备端认证门 |
| 视频生成 | Seedance 2.0（火山方舟 / 媒体中继协议）、WorkManager 管线、ExoPlayer 播放 |
| TTS | Android 系统 TTS ＋ 火山引擎豆包声音复刻 |
| 网络 | Retrofit 2.11 / OkHttp 4.12 / kotlinx-serialization |
| 数据 | Room 2.6.1 / DataStore 1.1.1 |
| 媒体 | Media3 1.3.1 (ExoPlayer) / Coil 2.6 |
| 后台 | WorkManager 2.9.1（问候周期 + Seedance 视频管线） |

## 项目结构 · Project Structure

```
app/src/main/java/com/rhodesisland/terminal/
├── config/          # 应用配置、干员表、模型 Provider、资源路径（立绘/语音/BGM/背景）
├── data/            # model / local(Room,DataStore) / remote(Retrofit,网易云,Seedance) / repository
├── llm/             # ★ 本地 LLM 核心：backend(CPU/GPU/NPU 调度、健康、预热)、benchmark(基准+认证)、
│                    #   metrics(遥测)、profile(性能模式/执行计划)、template(模板能力探测)、thinking(思考分级)
├── provider/        # 聊天 Provider（cloud / local）抽象与切换
├── tts/             # 双 TTS 引擎（系统 TTS + 火山豆包）
├── video/           # Seedance 视频管线：提示词生成、校验、状态机、参考图/场景存储、导出
├── download/        # MNN 模型多镜像下载（断点续传、分块合并、SHA-256/大小校验）
├── manager/         # Audio / Model / Tts 管理器
├── perfmon/         # 液态玻璃性能浮窗
├── notification/    # 角色主动问候通知
├── service/         # 本地推理前台服务（生成保活）
├── work/            # WorkManager 调度（问候周期 / 精确闹钟 / Seedance 视频管线）
├── ui/              # glass 组件、chat / characters / feed / music / models / settings / theme / video / navigation
└── util/            # 工具类（电池白名单/自启引导、立绘存储、Markdown 等）
```

## 构建 · Build

### 环境要求

- Android SDK（compileSdk 34）
- **JDK 17+**（本机验证 Temurin 17：`D:/jdk-temurin-17/jdk-17.0.20+8`）
- NDK 27.2.12479018（`app/build.gradle.kts` 的 `ndkVersion`）

### 说明

- **Native 库已预编译**并放入 `app/src/main/jniLibs/arm64-v8a/`（`libMNN.so` + `libmnn_jni.so` + `libcpu_sys_jni.so` + `libbackend_probe.so` + `libc++_shared.so`）。Gradle 构建时不再调用 CMake，**无需配置 `MNN_DIR`**。
- 仅打包 `arm64-v8a`：与预编译 MNN 库架构一致。

### 命令

```bash
# 编译（验证 .kt 改动时务必 --rerun-tasks --no-build-cache，避免 build cache 假象 UP-TO-DATE）
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache

# Debug 构建
./gradlew :app:assembleDebug

# Release 构建（默认 debug 签名，发布前请自行配置签名）
./gradlew :app:assembleRelease
```

## 本地 AI：首次使用

1. 打开应用 → **设置** → **模型管理**
2. 选择要下载的 `.mnn` 模型（如 `Qwen3.5-2B-MNN`，支持断点续传）
3. 返回聊天页 → 切换至 **本地 AI** → 离线流式对话

模型文件存储在：
```
Android/data/com.rhodesisland.terminal/files/models/
```

## 资源配置

角色立绘、语音和 BGM 存储在 `app/src/main/assets/`：
- `picture/` — 干员立绘 (webp)
- `music/` — 内置 BGM (mp3) + 干员语音 (wav)
- `background/` — 背景图 (webp/jpg)

---

## 免责声明

> 本项目为明日方舟同人作品，所有角色、立绘、音乐版权归 **Hypergryph / 鹰角网络** 所有。本项目仅用于学习交流，不作商业用途。

## License

MIT License — see [LICENSE](LICENSE) for details.
