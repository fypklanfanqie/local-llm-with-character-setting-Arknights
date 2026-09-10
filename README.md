# Rhodes Island Terminal · 罗德岛通讯终端

**简体中文** ｜ [English](README_EN.md) ｜ [日本語](README_JA.md)

> 明日方舟同人 AI 角色扮演聊天应用：端侧 **MNN 本地大模型推理**（CPU / GPU / NPU 自适应 + 深度思考）+ 云端双引擎 + 内置方舟 BGM / 语音 / 立绘。所有本地对话数据完全保存在设备内，可全程离线使用。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![MNN](https://img.shields.io/badge/Local%20LLM-MNN-00C4A7?logo=alibabacloud)](https://github.com/alibaba/MNN)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🖼️ 功能总览 · Tour

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/01-feed.jpg" width="360" alt="通讯·角色卡片流"/><br>**① 通讯 · 角色卡片流**<br><sub>全屏立绘滑动切换，一键开始对话</sub> | <img src="docs/screenshots/02-local-models.jpg" width="360" alt="本地大模型"/><br>**② 本地大模型**<br><sub>MNN 三后端自适应，离线推理</sub> | <img src="docs/screenshots/03-moments.jpg" width="360" alt="朋友圈"/><br>**③ 朋友圈**<br><sub>角色自动发动态，可点赞评论</sub> |
| <img src="docs/screenshots/04-cloud-api.jpg" width="360" alt="云端AI配置"/><br>**④ 云端 AI · API 配置**<br><sub>预设模型商，填 Key 即用</sub> | <img src="docs/screenshots/05-novel.jpg" width="360" alt="互动小说"/><br>**⑤ 互动小说模式**<br><sub>旁白 + 对白脚本，AI 续写整章</sub> | <img src="docs/screenshots/06-worldview-lorebook.jpg" width="360" alt="世界观与世界书"/><br>**⑥ 世界观与世界书**<br><sub>设定注入对话，关键词触发</sub> |
| <img src="docs/screenshots/07-guide.jpg" width="360" alt="使用指南"/><br>**⑦ 使用指南**<br><sub>应用内检索式功能手册</sub> | <img src="docs/screenshots/08-daily-supply.jpg" width="360" alt="每日补给与商店"/><br>**⑧ 每日补给与礼物商店**<br><sub>签到领龙门币，送礼涨好感</sub> | <img src="docs/screenshots/09-multilang.jpg" width="360" alt="多语言"/><br>**⑨ 多语言界面**<br><sub>中 / 英 / 日，AI 回复同步切换</sub> |

---

## 📖 功能详解

### ① 通讯 · 角色卡片流

- 抖音式全屏立绘卡片流：上下滑动浏览 **384 位罗德岛干员**（20 位内置语音与本地立绘 + 364 位自动生成，含游戏技能 / 天赋人设）
- 角色卡直达「**开始对话 / 好感 / 小说**」；顶栏玻璃按钮直达「朋友圈 / 邂逅 / 群聊 / 新建」
- 立绘主题色实时驱动按钮与底栏配色，界面随角色变化

### ② 本地大模型 · 端侧 MNN 推理

- **MNN CPU / OpenCL GPU / QNN NPU** 三后端自适应调度（自动推荐 / 强制指定），GPU 失败自动回退 CPU，完全离线、数据不出设备
- 内置模型市场：**Qwen3.5 等 13 款 MNN 模型**，多镜像下载（ModelScope → hf-mirror → HuggingFace）、断点续传 + SHA-256 完整性校验
- **本地深度思考**分级（AUTO / SHORT / MEDIUM / LONG），思考过程可折叠展示；内存不足自动按轮减半上下文，不崩溃不报错
- 实验加速（lookahead / 多 token 解码）必须通过真机基准认证才启用
- **液态玻璃性能浮窗**：token/s、CPU / GPU / NPU、温度、内存实时监控

### ③ 朋友圈 · Moments

- 仿微信朋友圈信息流：角色按人设**自动发动态**（AI 文案 + AI 配图），可点赞、评论，角色会回复你的评论
- 自己也能发帖带图；支持自动发圈调度（8–23 点），随时刷到干员们的日常

### ④ 云端 AI · LLM API 配置

- OpenAI 兼容 `/chat/completions` **SSE 流式直连**；内置 DeepSeek / OpenAI / 通义千问 / 智谱等预设模型商，选择后填 API Key 即用，一键测试连接
- 云端 / 本地双引擎随时切换，会话按角色独立保存
- 多模态模型支持图片（最多 3 张）/ PDF（前 6 页）/ 文本文件直传

### ⑤ 互动小说模式

- 「旁白 + 角色对白」脚本体：AI 一次续写整章剧情，按说话人分色气泡展示
- 章节本地自动保存，随时回来继续写；多故事 / 多章节管理

### ⑥ 世界观与世界书

- **世界观**：自定义设定（如「故事发生在末日废土」）注入对话提示词，可绑定单个角色私聊或某个群聊
- **世界书**：关键词触发式背景设定库，支持导入 **SillyTavern 世界书 JSON**；扫描深度 / Token 预算上限 / 递归扫描可调

### ⑦ 使用指南

- 应用内检索式功能手册：热门搜索 + 功能分类（快速上手 / 云端 AI / 本地大模型 / 聊天 / TTS / 世界观与世界书 / 角色·问候·群聊…）
- 跟随界面语言显示中 / 英 / 日，搜关键词即出答案

### ⑧ 每日补给与礼物商店

- 每日签到领取 **10,000 龙门币**；礼物商店支持自定义礼物（价格 / 好感加成 / 库存），采购后赠送角色
- **好感度系统**：等级上限 200、礼物墙回顾、特殊邂逅事件档案

### ⑨ 多语言 · 界面与 AI 回复同步

- **跟随系统 / 简体中文 / English / 日本語** 一键切换
- 切换后不止界面翻译：**角色的回答也会用对应语言**（人设 / 世界观 / 世界书等中文设定无需翻译，AI 照常理解）

---

## ✨ 更多特性

- **🔊 双 TTS 语音合成** — 系统离线 TTS（默认免配置）+ 火山引擎豆包云端声音复刻（每角色独立音色，中日双语）；朗读自动剥离思考块
- **🎬 邂逅 · 角色视频** — Seedance 生成角色短片（自动触发 / 播放 / 导出 / 历史），自定义参考图与场景
- **💬 群聊** — 多角色群聊：@ 指定必答、成员后台互聊、新消息通知
- **⏰ 角色主动问候** — 15 分钟周期调度 + 精确闹钟兜底，角色会在你离开后主动发来消息（类微信横幅通知，跨重启存活）
- **📝 Markdown 渲染** — 代码高亮 + 数学公式；深度思考过程可折叠展示
- **🎵 音乐播放器** — 内置方舟 BGM + 网易云方舟 OST 目录 / 在线搜歌 / 本地音乐导入，后台持续播放（位于「设置 → 音乐」）
- **🎨 PRTS 深色终端 UI** — 深藏青底 + 罗德岛金强调的液态玻璃界面，衬线标题、科幻终端风

## 384 位干员 · Operators

> 下表为基础 20 位（内置语音与本地立绘）；其余 364 位干员由人格档案自动生成，含游戏技能 / 天赋与精二 / 皮肤立绘（网络加载）。全量干员在角色页 / 通讯 feed 中均可见。

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

> 本项目为明日方舟同人作品，所有角色、立绘、音乐版权归 **Hypergryph / 鹰角网络** 所有。本项目仅用于学习交流，不作商业用途

## 致谢

> 本软件的不断完善离不开一开始我发抖音，B 站粉丝群里面各位粉丝朋友的优化建议和新功能提议，感谢各位！
> 他们分别是 咕咕火 id V.I.P_520 白夜执 1185531741 不知道 buzhidao350543 辋川星梦 1023422036

## License

MIT License — see [LICENSE](LICENSE) for details.
