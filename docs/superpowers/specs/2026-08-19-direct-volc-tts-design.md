# 火山引擎 TTS 直连与资源绑定设计

**日期：** 2026-08-19

## 目标

将 Android TTS 从 CloudBase 代理切换为直连火山引擎官方 HTTP V3 Chunked 接口，并完整修复新版 API Key、旧版 App ID/Access Key 鉴权、音色与 Resource ID 绑定、官方响应解析、设置保存和测试覆盖问题。

## 已确认决策

- TTS 直连官方 HTTP Chunked endpoint：`https://openspeech.bytedance.com/api/v3/tts/unidirectional`。
- 不再依赖 CloudBase TTS 代理；客户端只解析官方 Chunked JSON 行，不承担 SSE 解析。
- 同时支持新版 API Key 和旧版 App ID + Access Key。
- 新版 Header：`X-Api-Key`、`X-Api-Resource-Id`，`X-Api-Request-Id` 可选。
- 旧版 Header：`X-Api-App-Id`、`X-Api-Access-Key`、`X-Api-Resource-Id`，`X-Api-Request-Id` 可选。
- API Key 优先；没有 API Key 时才使用完整的旧版凭据；不完整凭据在发请求前失败。
- 中文和日文音色分别保存 `voiceId` 与 `resourceId`，不再猜测或依赖全局固定 Resource ID。
- 手动音色必须同时填写对应 Resource ID；缺少任一值时阻止网络请求并显示可操作错误。
- 保留旧版音色映射 JSON 的读取兼容；旧纯字符串音色可读取但 Resource ID 为空，必须由用户补填后才能使用。
- 官方响应按 `code` 判定：`0` 为音频分片，`20000000` 为成功结束，其他值为错误；空 message 和 `ok` 都不是错误。
- 直连客户端不解析 SSE `event:`/`data:` 包装。
- API Key 仍由用户手动输入，不写入 APK、源码、日志或测试 fixture。

## 官方协议依据

- HTTP Chunked/SSE V3：<https://www.volcengine.com/docs/6561/1598757?lang=zh>
- 音色和 Resource ID：<https://www.volcengine.com/docs/6561/1257544?lang=zh>
- 错误码：<https://www.volcengine.com/docs/6561/2534853?lang=zh>

官方请求体的核心字段为 `user.uid`、`namespace`、`req_params.text`、`req_params.speaker` 和 `req_params.audio_params`；MP3 与 24 kHz 保持现有实现。`req_params.additions` 继续作为 JSON 字符串发送，但修正文案和语义说明，不把空 message 或官方成功状态误报为错误。

## 数据模型

新增可序列化的音色配置：

```kotlin
@Serializable
data class VoiceConfig(
    val voiceId: String = "",
    val resourceId: String = "",
)

@Serializable
data class VoicePair(
    val zh: VoiceConfig = VoiceConfig(),
    val ja: VoiceConfig = VoiceConfig(),
)
```

`TtsConfig` 保留 `apiKey`、`appId`、`accessKey` 三字段，以支持两套官方鉴权方式。请求层按凭据优先级严格选择一种方式，并始终发送选中的音色对应 Resource ID。

## 请求与数据流

```text
设置页保存凭据/音色
        ↓ DataStore
TtsManager 根据语言读取 VoiceConfig
        ↓
VolcTtsClient 校验凭据与 voice/resource
        ↓ HTTPS POST 官方 Chunked endpoint
火山引擎返回 JSON 音频分片
        ↓
客户端按 code 拼接 Base64 音频
        ↓
TtsManager 写入 cache 临时 MP3
        ↓
MediaPlayer 播放并在完成/失败/停止时删除文件
```

`AppConfig.TTS_PROXY_URL` 删除或改为不再参与 TTS 的废弃配置，`AppContainer` 改为注入官方 endpoint。网络客户端使用有限连接/读取/调用超时，协程取消时取消 OkHttp call。

## 设置界面

- 云端 TTS 设置增加旧版 `App ID` 与 `Access Key` 输入，同时保留 API Key。
- 明确显示凭据选择规则：API Key 优先；否则需要 App ID 与 Access Key 同时填写。
- 角色音色映射的中文和日文各显示音色 ID、Resource ID。
- 保存时保留所有三个凭据字段，不能再用 `TtsConfig(ttsApiKey)` 清空旧字段。
- 音色输入校验 `voiceId` 与 `resourceId` 成对存在；保存前提供错误提示。
- 帮助文案说明 Resource ID 必须来自该音色在火山控制台所属资源，不默认猜测 `seed-icl-2.0`。

## 错误处理

- 凭据为空或旧版凭据不完整：本地立即报错，不发送请求。
- API Key 无效：展示 HTTP 状态、火山 code 和 message，不暴露 Key。
- Resource ID/音色不匹配：展示官方错误，并提示核对音色所属资源。
- 非零且非 `20000000` 的 code：展示具体 code/message；附带 `X-Tt-Logid` 仅用于本地诊断，不记录凭据。
- HTTP 非 2xx、响应无音频、非法 Base64、网络超时/DNS/TLS/连接拒绝：分别给出明确错误。
- 音频播放失败或中断：释放播放器并清理临时文件。

## 测试策略

- JVM MockWebServer 契约测试验证 endpoint、JSON body、API Key/旧版 Header、按语言选择 Resource ID 和不完整凭据不发请求。
- 解析器测试覆盖多分片拼接、`code=0` 空 message、`code=20000000`、错误响应、空响应、非法 Base64 和不把 SSE 当作 Chunked 成功。
- DataStore/设置测试覆盖三字段凭据和新旧音色 JSON round-trip、旧字符串兼容、缺 Resource ID 校验。
- TtsManager 测试覆盖系统/云端分支、语言选择、凭据门禁、临时文件清理和停止语义。
- Android/Compose 测试覆盖云端设置编辑、保存重启、错误提示与资源字段展示。
- 运行项目既有构建与 TTS 相关测试；不在测试中使用真实 API Key 或真实网络。

## 非目标

- 不实现 SSE 客户端；直连 endpoint 固定为 HTTP Chunked。
- 不把开发者 API Key 内置到 APK。
- 不修改 CloudBase 函数或部署配置。
- 不重构与 TTS 无关的聊天、视频或本地推理模块。
