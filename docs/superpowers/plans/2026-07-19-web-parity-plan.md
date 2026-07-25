# 安卓本地版 ↔ 网页版 功能对齐计划

- **目标项目**:`D:\ai\cc Programm\聊天终端安卓本地`(Kotlin + Jetpack Compose)
- **参照**:`D:\ai\cc Programm\聊天终端网页版`
- **范围**:Group1(接通已有代码)+ Group2(新功能),共 12 个工作项,不含 Live2D。
- **总体策略**:复用现有架构(Provider/Repository/Manager/DataStore/Room),不引入新框架;每项独立可编译;多模态与自定义角色为两个大项,放后段。

---

## 阶段一:接通已有代码(Group1,高 ROI)

### 1. 代码块 / 公式块 复制按钮
- 改 `ui/chat/ChatScreen.kt` 的 `CodeBlockView` / `ScienceBlockView`:头部行加 📋 按钮,`LocalClipboardManager.current.setText(AnnotatedString(seg.rawCode))`,点按后短暂显示 ✓。
- 风险:无。

### 2. 聊天背景轮播
- 改 `ChatScreen.kt`:最外层 `Box` 叠加 `AsyncImage`(Coil crossfade),背景索引 8s 自增,取 `container.assetRepository.getBackground(idx)`;上层加深色 scrim 保证气泡可读。
- `AssetRepository.getBackground` + 10 张 `AssetPaths.BACKGROUNDS` 已就绪。
- 风险:无。

### 3. TTS 双语字幕渲染
- `ChatUiState` 已有 `subtitleJp/subtitleCn`(角色切换台词)。新增 `ttsSubtitleJp/ttsSubtitleCn`(朗读时的译文/原文)。
- `ChatViewModel.playTts`:合成/翻译时把 speakText(译文)+ 原文写入 state;停止/结束清空。
- `ChatScreen.kt`:消息区底部叠加 `SubtitleBar`,`ttsPlayingIndex>=0` 时显示朗读字幕;角色切换时短暂显示 voiceLines 字幕(复用现有 subtitleJp/Cn)。
- 风险:无。

### 4. 音乐:进度拖动 + 音量 + EP 分类筛选(与第 12 项合并)
- `BgmTrack` 增加 `ep: String` 字段(`config/AssetPaths.kt` 与 `data/repository/AssetRepository.kt` 两处 `BgmTrack`)。
- `MusicScreen.kt`:
  - 进度:`LinearProgressIndicator` → `Slider`(`AudioManager.seekTo` 已有),显示 `mm:ss / mm:ss`。
  - 音量:控制栏加 `Slider`(0-100),调 `audioManager.setVolume`(已持久化)。
  - EP 筛选:顶部加横向 `FilterChip` 行(ALL + 各 EP),`displayList` 增加按 `ep` 过滤。
- `AudioManager` 已有 `seekTo/setVolume/getCurrentPosition/getDuration`,无需改。
- 风险:无。

### 5. 附件 / 多模态 / OCR / 文档解析接通(最大项)
现状:`OcrRepository` 三方法零调用;图片能选能存但不显示、不送 LLM;无文件选择 UI。
- `data/model/ChatMessage.kt`:加 `multimodalImages: List<String> = emptyList()`(base64,瞬态,不持久化——`ChatRepository.toEntity` 不映射它即可)。
- `data/repository/OcrRepository.kt`:加 `uriToBase64(context, uri)` 与 `copyUriToTempFile(context, uri, name)`(读 content URI)。
- `ui/chat/ChatViewModel.kt` `sendMessage`:
  - 文件:每个 `AttachedFile` → copy 临时文件 → `extractFileText` → 文本拼入 content。
  - 图片:读当前模型 `isMultimodalModel(model)`:
    - 是:URI→base64(压缩到 ~1024px)放入 user 消息 `multimodalImages`,走多模态。
    - 否:每张 `ocrImage(base64)` → "[图片文字]: ..." 拼入文本。
  - 构建 `apiMessages` 时把处理后的 user 消息(含 `multimodalImages`)加入。
- `provider/cloud/CloudChatProvider.kt`:注入 `OcrRepository`;`toJsonElement(msg.content)` → `buildContent(msg)`:若 `msg.multimodalImages` 非空 → 构造 `JsonArray`(`{type:text,...}` + `{type:image_url,image_url:{url:data:image/jpeg;base64,...}}`);否则 `JsonPrimitive(text)`。`ChatMessageDto.content` 已是 `JsonElement`,DTO 无需改。
- `provider/local/LocalChatProvider.kt`:本地模型不支持多模态,VM 已把 OCR/extract 合并成纯文本,无需改。
- `ui/chat/ChatScreen.kt`:
  - `ChatInputBar`:加文件选择按钮(📄),`rememberLauncherForActivityResult(OpenDocument)` accept 常见类型;输入区显示已选图片缩略图 + 文件名 chip。
  - `MessageBubble`:用户气泡渲染 `message.images`(Coil `AsyncImage` gallery,点击全屏查看)+ `message.files`(文件名 chip)。
- `ChatViewModel`:加 `uploadedFiles` 状态 + add/remove;`sendMessage` 持久化 images/files。
- 风险:base64 体积(图片压缩);content URI 读取需 IO 线程。

---

## 阶段二:新功能(Group2)

### 6. KaTeX 数学公式
- `data/model/ChatMessage.kt`:加 `MessageSegment.Math(val latex: String, val display: Boolean)`。
- `util/MarkdownParser.kt` `parseContent`:在文本段解析 `$$...$$`、`$...$`、`\[...\]`、`\(...\)`(代码块内不解析),产出 `Math` 段;处理 `\$` 转义。
- `ui/chat/ChatScreen.kt`:新增 `MathBlockView` 用 `AndroidView{WebView}` 加载 HTML(`katex.render(latex, elem)`,KaTeX 走 jsdelivr CDN),内容高度自适应;离线/失败降级为等宽原始 latex。`MessageBubble` 的 `when(seg)` 加 `Math` 分支。
- 风险:WebView 在 LazyColumn 的测量/复用(给定 key、固定宽度);离线降级。

### 7. 自定义角色系统(大项)
- `data/model/Character.kt`:加 `isCustom: Boolean=false`、`voiceZh: String?`、`voiceJa: String?`(音色码);复用 `image`(自定义=本地文件路径)、`voiceFile`(本地语音路径)。
- 新增 `data/local/CustomCharacterStore.kt`:DataStore(JSON 元数据)+ `filesDir/custom_chars/<id>/`(图片/语音文件)。
- 新增 `data/repository/CharacterRepository.kt`:合并 `Characters` 预设 + 自定义;`get/getAll/getOrderedList/add/update/delete/exportJson/importJson`,返回 Flow 响应增删。
- `AppContainer`:加 `characterRepository`。
- `ChatViewModel.loadCharacter`:改用 `container.characterRepository.get(id)`;`getPicture/getVoice` 对自定义角色返回本地文件路径。
- `ui/characters/CharactersScreen.kt`:数据源改 `characterRepository.getOrderedList()`;顶部加 "+ 新建角色" 按钮;自定义卡片带 ✎/✕;新增 `CustomCharacterEditDialog`(名称/代号/种族/职位/System Prompt/中音色码/日音色码/日字幕/中字幕/立绘选择/语音选择)+ 导入/导出 JSON。
- `config/Characters.kt`:预设保持不变。
- 风险:角色列表需响应自定义增删(Flow);图片选择 copy 到 filesDir;同 id 自定义覆盖预设。

### 8. TTS 角色音色映射(用户可配)
- `data/local/SettingsStore.kt`:加 `TTS_VOICE_MAP`(JSON `Map<String,{zh,ja}>`)Flow + setter。
- `ui/settings/SettingsScreen.kt`:新增 "角色音色映射" 区,动态行(角色下拉[预设+自定义] + 中文 S_ + 日文 S_ + ✕)+ "添加" + 保存。
- `manager/TtsManager.kt` `speak`:解析 `voice = voiceMap[charId][lang] ?: customChar.voiceZh/Ja ?: null`,传入 `VolcTtsClient.synthesize(..., voice)`。
- `data/remote/CloudRunApi.kt` `TtsRequest`:加 `voice: String? = null`;`tts/VolcTtsClient.kt` 透传。
- ⚠ **依赖**:CloudRun `/tts` 代理需支持 `voice` 透传到火山 `voice_type`(服务端一行改动:`voice_type = req.body.voice || 默认映射[characterId]`)。服务端未更新前,仅预设 amiya/la-pluma 走原映射生效。计划会附服务端补丁示例。
- 备选(自包含、不改服务端):在 `VolcTtsClient` 内对自定义音色直连火山引擎(移植网页 `tts.js` 鉴权),工作量更大——**如需此方案请告知**。

### ~~9. PRTS 氛围特效~~ — 已取消(用户不需要)

### 10. 启动 Loading 画面
- 新增 `ui/LoadingScreen.kt`:PRTS 风格进度条 + 标题 + 可选 `music/标题.wav`(若 asset 存在)。
- `MainActivity.kt`:首启动显示,约 2s 后 `AnimatedVisibility` 淡出进入主界面。
- 风险:无(纯 Compose)。

### 11. 使用指南
- 新增 `ui/settings/GuideDialog.kt`:全屏 Dialog,6 节(简介 / API 接入 / TTS 配置 / 本地 AI / 使用技巧 / 免责声明)。
- `SettingsScreen.kt`:加 "📖 使用指南" 按钮打开。
- 风险:无。

### 12. 音乐曲目同步到 189 首 + EP 字段
- 读取网页 `js/musicData.js`,生成完整 189 首 `BgmTrack`(含 `ep`),替换 `AssetPaths.BGM`。
- 与第 4 项共用 `ep` 字段,合并实现。
- 风险:纯数据搬运,需对照网页逐条。

---

## 实现顺序(建议)
1 → 2 → 3 → 9 → 10 → 11 → 4+12 → 6 → 5 → 8 → 7
(先小而独立的 UI 项铺底 → 音乐 → KaTeX → 多模态 → TTS 音色 → 自定义角色收尾)

## 不做(已确认)
Live2D、3D 倾斜卡/双立绘 hover、消息编辑/重发/中止、波形可视化。

## 验证
- 每阶段 `./gradlew assembleDebug` 编译(若环境允许)。
- 关键链路自测:多模态发图 / OCR / 文档解析 / KaTeX / 自定义角色增删+TTS / 音乐 seek·音量·EP / 背景轮播 / 字幕 / 指南 / Loading / PRTS overlay。

## 待确认
1. TTS 自定义音色:走"服务端透传"(计划默认,依赖服务端一行改动)还是"客户端直连火山"(自包含,工作量大)?
2. KaTeX:走 CDN(默认,需联网,离线降级)还是离线打包字体(工作量大)?
3. 当前环境能否跑 `gradlew assembleDebug`?若不能,我保证代码正确性但不做编译验证。
