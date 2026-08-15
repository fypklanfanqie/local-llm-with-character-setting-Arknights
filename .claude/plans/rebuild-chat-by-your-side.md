# 重构计划：罗德岛通讯终端 → Chat by your side（液态玻璃大众版）

## 目标与决策
把现有明日方舟聊天 App 复制到 `D:\ai\cc Programm\本地ai聊天大众版`，**保留全部功能**（本地 MNN 大模型、云端 API、火山 TTS、模型管理下载、性能浮窗、角色主动问候），**删除所有明日方舟内容**，**重新设计为 Cresto Glasense 风格的苹果液态玻璃/毛玻璃 UI**，角色预设替换为 **20 个男女混合的热门人设**。

已确认决策：
- 应用名 **Chat by your side**，包名 `com.chatbyyourside`
- 主强调色 **冰蓝 Iris**（#0A84FF 暗 / #007AFF 亮），中性毛玻璃底
- 音乐：保留界面，**接入网易云 API（搜索/播放）+ 本地歌曲上传**，删除方舟 OST

## 总体策略：复制 → 重构（不从零建）
从零建会丢失 MNN 原生构建等硬成果。改为：先整目录复制，再分阶段重构。MNN 原生构建依赖 `D:/mnn-matched`、NDK 27.2.12479018、arm64-v8a，复制后路径不变仍可用。

---

## Phase 0 — 复制项目到新目录
- 复制 `D:\ai\cc Programm\聊天终端安卓本地` → `D:\ai\cc Programm\本地ai聊天大众版`
- 排除：`build/`、`.gradle/`、`.cxx/`、`app/build/`（构建产物），保留 `src/`、`cpp/`、`jniLibs/`、`gradle/`、`.claude/`、git 历史
- 新目录独立 git（可选保留历史）

## Phase 1 — 包名与品牌重命名（机械替换 + 编译验证）
全量替换 `com.rhodesisland.terminal` → `com.chatbyyourside`（.kt/.xml/.cpp/.pro/.kts）：
- 目录移动：`app/src/main/java/com/rhodesisland/terminal/` → `com/chatbyyourside/`
- `app/build.gradle.kts`：`namespace`、`applicationId` → `com.chatbyyourside`
- `AndroidManifest.xml`：`android:name=".RhodesApp"` → `.ChatApp`；theme 名
- `RhodesApp.kt` → `ChatApp.kt`（类名同步）
- `app/src/main/cpp/mnn_jni.cpp:74`：JNI 硬编码类路径 `com/rhodesisland/terminal/...` → `com/chatbyyourside/...`
- `app/proguard-rules.pro:3`：keep 规则包名
- `download/DownloadManager.kt:194`：User-Agent `RhodesIslandTerminal/1.0` → `ChatByYourSide/1.0`
- `res/values/strings.xml`：app_name→`Chat by your side`；tab：通讯→聊天、干员→角色、音乐、设置、模型
- `res/values/themes.xml` + `colors.xml`：`Theme.RhodesIslandTerminal`→`Theme.ChatByYourSide`，`prts_*` 色→中性玻璃色
- `LICENSE`、`README.md`：去「Rhodes Island / 罗德岛 / Hypergryph」
- 验证：`JAVA_HOME='D:/新建文件夹/jdk-21' ./gradlew compileDebugKotlin --rerun-tasks --no-build-cache`

## Phase 2 — 新玻璃设计系统（替换 PRTS 主题）
删除 `PrtsColors`/`PrtsTypography`/`PrtsColorScheme`/`RhodesIslandTheme`，新建：

`ui/theme/`：
- `Color.kt`：`IrisColors` + 亮/暗 Material3 colorScheme（冰蓝主色、中性玻璃面、支持跟随系统亮暗）
- `Theme.kt`：`ChatTheme`（按系统选亮/暗，挂 colorScheme + typography + GlassTokens）
- `Type.kt`：Apple 风字号字重（系统无衬线）
- `Shape.kt`：连续圆角（squircle）形状

`ui/glass/`（新设计系统包）：
- `GlassTokens.kt`：模糊半径、遮罩 alpha、高光描边、圆角
- `GlassModifiers.kt`：
  - `Modifier.frostedGlass(shape,tint,blur)`：API31+ 用 RenderEffect 模糊背景 + 半透明填充 + 顶部 1px 高光 + 柔阴影；<31 退化为遮罩+描边
  - `Modifier.glassBorder()` / `glassShadow()`
- `GlassCard.kt`、`GlassButton.kt`（实心冰蓝 + 透明玻璃两式）、`GlassTopBar.kt`（吸顶毛玻璃+大标题）、`GlassNavBar.kt`（浮动毛玻璃胶囊底栏）、`GlassChip.kt`、`GlassTextField.kt`、`GlassSheet.kt`（ModalBottomSheet 包装）
- `MeshBackground.kt`：AGSL RuntimeShader 多色渐变网格（冰蓝/紫/薄荷柔光斑慢漂移）作根背景，给玻璃提供折射/模糊内容

## Phase 3 — 20 个热门人设（替换方舟干员）
重写 `config/Characters.kt`：20 个原创原型人设，男女混合，每人写完整 systemPrompt（核心性格/语气特征/回答要求，质量对标现有干员 prompt）。头像=冰蓝系渐变+姓名首字 monogram（无图片文件，无版权风险）。

| # | id | 姓名 | 原型 | 性别 |
|---|----|----|------|----|
|1|tsundere|凛|傲娇大小姐|女|
|2|senpai|苏晚|温柔学姐|女|
|3|yandere|小染|病娇青梅|女|
|4|kouhai|小鹿|元气学妹|女|
|5|mature|薇拉|魅惑御姐|女|
|6|ceo-f|顾清寒|高冷女总裁|女|
|7|sister|糯米|软萌妹妹|女|
|8|neighbor|阿橙|治愈邻家|女|
|9|sharp|夏夏|毒舌损友|女|
|10|scholar-f|沈知微|清冷学霸|女|
|11|fox|九黎|妖媚狐妖|女|
|12|butler|陆执|腹黑管家|男|
|13|ceo-m|霍司珩|高冷霸总|男|
|14|bookman|沈砚书|温润书生|男|
|15|knight|亚瑟|忠犬骑士|男|
|16|villain|夜烬|邪魅反派|男|
|17|grumpy|陆霆|暴躁老板|男|
|18|chuuni|黑炎|中二少年|男|
|19|teacher|林叙白|儒雅老师|男|
|20|bamboo|江阳|阳光竹马|男|

- `data/model/Character.kt`：`watermarkName` 去方舟 id 映射，简化为 `name`；`voiceFile`/`voiceLines` 留字段但置空（无语音素材）；`ttsEnabled=true`（火山 TTS 朗读回复，与语音素材无关）
- `config/AssetPaths.kt`：删 PICTURES/SELECTION_PICTURES/VOICES/BGM/BACKGROUNDS（角色用 monogram，背景用 MeshBackground，音乐走网易云/本地）
- `util/CharacterImageStore.kt`：适配 monogram 头像生成
- `ui/characters/CharactersScreen.kt`：玻璃卡片网格重设计，去「OPERATOR // SELECT」等方舟文案

## Phase 4 — 屏幕玻璃化重设计
逐屏替换 PrtsColors/PrtsTypography → ChatTheme/Glass 组件：
- `ui/chat/ChatScreen.kt`：玻璃消息气泡、玻璃输入栏、玻璃吸顶栏
- `ui/characters/CharactersScreen.kt`：玻璃角色卡网格
- `ui/settings/*`、`ui/models/ModelManagerScreen.kt`：玻璃列表卡
- `ui/navigation/AppNavGraph.kt` + 底栏：GlassNavBar 浮动胶囊
- `ui/LoadingScreen.kt`：玻璃
- `ui/chat/ChatViewModel.kt`：仅去方舟文案引用，逻辑不动

## Phase 5 — 音乐功能改造（网易云 API + 本地上传）
- `data/remote/NeteaseApiService.kt`：新增 `search(keyword)`（/cloudsearch 或 /search）、`songUrl(id)`（/song/url/v1 播放地址）；保留 fetchCover/fetchLyric
- `ui/music/MusicScreen.kt`：玻璃重设计——搜索框、结果列表（玻璃卡+封面）、正在播放玻璃卡（进度条+歌词）、播放列表；两 Tab：网易云搜索 / 本地音乐
- `manager/AudioManager.kt`：ExoPlayer 队列管理（网易云 URL + 本地 contentUri 混合）
- 本地导入：`ActivityResultContracts.OpenMultipleDocuments`（audio/*），URI 持久化存 DataStore
- 删 `assets/music` 方舟 mp3（31M）；`LrcParser` 歌词显示保留

## Phase 6 — 清理方舟残留
- 删二进制资源：`assets/picture`(23M)、`assets/music`(31M)、`assets/background`(14M)；`ASSETS_README.md` 重写
- 全局字符串去方舟：博士/干员/罗德岛/PRTS/通讯→对应中性词；角色主动问候 `notification/`、`work/` 里的 prompt 文案
- `docs/youtube_script_en.md` 等方舟文档删除/重写
- `README.md`、`LICENSE` 重写为 Chat by your side
- `data/repository/ChatBackgroundRepository.kt`：背景改用 MeshBackground（无图片）

## Phase 7 — 构建与验证
- `JAVA_HOME='D:/新建文件夹/jdk-21'`
- `./gradlew compileDebugKotlin --rerun-tasks --no-build-cache`（避免 build cache 假象 UP-TO-DATE）
- `./gradlew :app:assembleDebug`（含 native：cpu_sys_jni 必编 + mnn_jni 需 MNN_DIR=D:/mnn-matched）
- 修编译错误（包名遗漏、JNI 路径、proguard）
- 验证：应用名/包名已改、20 人设可切换聊天、玻璃 UI 生效、音乐可搜索播放、本地模型可加载对话、性能浮窗正常

## 风险与说明
- 包名替换涉及 ~80 文件，用脚本 sed 批量替换 + 编译兜底
- RenderEffect 模糊需 API31+；minSdk 24 → 低版本退化为遮罩玻璃（可接受）
- 网易云非官方 API 可能有频控/变动，做好失败兜底（封面/歌词/地址取不到时降级）
- 工作量大，按 Phase 顺序执行，每大阶段后编译验证再推进
