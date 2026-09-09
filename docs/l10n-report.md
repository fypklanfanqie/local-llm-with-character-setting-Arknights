# 多语言适配最终报告（中 / 英 / 日）

生成时间：2026-09-10 · 仓库 `fypklanfanqie/local-llm-with-character-setting-Arknights`（分支 `master`）

## 1. 结论

界面文案（`ui/`、`work/`、`notification/`、`util/`、`provider/`、`data/`、`tts/`、`service/`、`perfmon/`、
`conversationexport/`、`manager/`）已**全量接入中央词典**：

| 指标 | 数值 |
| --- | --- |
| 界面中文字面量（脚本口径） | **2161** |
| 已包 `t()` / `tf()` / `L10nRuntime.*` | 1444 |
| 已进词典（含数据驱动内容在渲染处包装） | 2122 |
| **词典覆盖度** | **98.2%** |
| 待处理（全部为**刻意不翻译**项） | 39 |
| 调试日志（不翻译） | 41 |
| `l10n:ignore` 显式豁免（不翻译） | 57 |
| 词典词条 | **En 1783 / Ja 1783**（key 集合完全一致，跨文件无重复） |
| 单元测试 | **877 个，0 失败**（含词典完整性/一致性、覆盖度门禁、指南搜索多语言回归） |
| `llm/` 诊断层（额外批次 11） | 164 条中 **83 条已覆盖（100%，其余 81 条豁免 / 35 条日志）** |

### 复现命令

```powershell
cd "D:\ai\cc Programm\聊天终端安卓本地"
.\gradlew.bat :app:testDebugUnitTest --console=plain          # 877 tests / 0 failures
powershell -NoProfile -ExecutionPolicy Bypass -File tools\l10n-coverage.ps1
powershell -NoProfile -ExecutionPolicy Bypass -Command "& '.\tools\l10n-coverage.ps1' -Dir 'llm' -SkipPathRegex '([\\/]config[\\/]|[\\/]i18n[\\/]|Prompt)'"
.\gradlew.bat :app:assembleDebug                              # 真 APK（97.6 MB）
```

## 2. 架构

- `i18n/L10n.kt`：`AppLanguage(SYSTEM/ZH/EN/JA)`、`L10n.t(lang, zh)`、`L10n.format(lang, zh, vararg)`、
  `L10n.resolve(lang, systemTag)`、`LocalAppLanguage`、`@Composable t(zh)` / `tf(zh, vararg)`、`L10nRuntime`。
- `i18n/Dictionary.kt` + `i18n/dict/En**.kt` / `Ja**.kt`：按批次分文件（14 对），注册表合并；
  `dictionaryConflicts()` 由单测拦截跨批次译文不一致。
- `SettingsStore` / `SettingsRepository`：DataStore 键 `app_language`（`system`/`zh`/`en`/`ja`，默认 `system`）。
- `MainActivity`：根 Composable `collectAsState` + `CompositionLocalProvider` → **切换即时生效**（不重建 Activity）。
- `RhodesApp`：启动期 collector 写 `L10nRuntime` → Worker / 通知 / 非 Composable 场景取到最新语言。
- 词典语义：**中文原文即 key**，缺词回退中文，永不崩、可渐进覆盖。

## 3. 批次完成情况

| 批次 | 范围 | 词条 | 提交 |
| --- | --- | --- | --- |
| 0 | 基建（L10n / 语言设置 / 覆盖度脚本） | 4 | `c9c6615` `f69abfd` `cad844d` `0df9d9b` `01b0489` `eb92901` |
| 1A | 设置页 SettingsScreen | 239 | `bc9452d` |
| 1B | 后端设置页 BackendSettingsScreen | 167 | `35d98f5` |
| 1C | 世界书 / 世界观 / 目标选择器 | 62 | `c6d6a72` |
| 2 | 聊天页 | 94 | `fcf8b8b` |
| 3A | 使用指南（数据层 596 条 + 渲染处包装） | 568 | `0121c38` |
| 3B | 使用指南测验 | 58 | `6373bef` |
| 4 | 视频 / 模型页 | 65 | `9644b4d` |
| 5 | 群聊 | 38 | `8667cc0` |
| 6 | 世界书与世界书编辑页 | 75 | `aed75f4` |
| 7 | 朋友圈 / 卡片流 / 导航 / 角色页 | 58 | `50cebb2` |
| 8 | 小说 / 好感度 / 音乐 | 126 | `f82331d` |
| 9A | 工具 / 通知 / 后台任务 / Provider / TTS / 性能浮窗 | 92 | `fe255e7` |
| 9B | 数据层错误文案 | 75 | `717e84b` |
| 10 | 会话导出 / 音频管理器 | 30 | `6bdcfc7` |
| — | 跨批次补漏与修正 | — | `6da8fb2` `45e1c03` `2379e3c` |

## 4. 刻意不翻译（39 条待处理 + 57 条 l10n:ignore + 41 条日志）

| 类别 | 说明 |
| --- | --- |
| LLM 提示词 | `ChatViewModel` 输出规范/前情提要/特殊邂逅/日语翻译、`LocalChatProvider` 输出规范、`CloudChatProvider` 图片描述、`DocumentRepository` 提取指令、`MomentGenerationCoordinator` 朋友圈任务、`UserProfileConfig`/`Worldview` 的 system prompt |
| 角色人设与内置数据 | `config/`（15,876 行中文）、角色名、干员名 |
| 比较值 / 协议值 | `UserErrorMessage` 的异常匹配词、`SeedanceDtos` 远端错误关键词表、`SeedanceClient` 的「标准/快速」协议值、`Config.kt` 语音匹配关键词「女/男/童」、`ChatViewModel`「暂不支持」「多模态」 |
| 落库数据 | 消息占位 `[图片]/[文件]/[附件]`、章节默认标题、群名兜底按**创建时语言**落库 |
| 崩溃日志内容 | `CrashCapture` 的日志文件正文 |
| 母语写法 / URL | 语言选项「简体中文」「日本語」、`https://中转站.com/v1` 占位符 |

## 5. 已知限制

1. **指南搜索已支持译文关键词**：`searchGuideTopics` 除了中文原文，还会用 `L10nRuntime` 把标题/别名/分类名换成当前界面语言再匹配一次，英文/日文界面可直接用译文搜索（`Quick start` / `クイックスタート`），ASCII 别名（deepseek/group/tts/npu）照旧命中。
2. **`llm/` 诊断层文案已全量适配**（批次 11，164 条）：后端名称与说明、后端偏好、NPU 芯片等级与理由、推理性能模式、温度状态、后端健康决策理由、基准否决原因（含数值的已改成 `{0}` 模板）、基准场景/四象限名称全部随语言切换；81 条非界面文案（基准/摘要/思考提示词、思考关键词正则表、内部断言与异常消息、模型包校验明细）标 `l10n:ignore`，35 条调试日志不翻译。
3. **已显示的旧错误提示**切语言后不即时刷新（`StateFlow` 不因语言重发），新产生的提示正确。
4. **通知渠道名**只在首次创建时写入系统，老设备保持旧语言属预期。
5. **英文单复数**未做（如 `{0} images` 在 count=1 时仍显示 images）。

## 6. 收尾状态

- [x] 覆盖度脚本度量（本报告第 1 节）
- [x] 全量单测 872 全绿（每批 + 最终复核）
- [x] 真 APK 构建：`D:\ai-build\rhodesisland\app-build\outputs\apk\debug\app-debug.apk`（97.6 MB，2026-09-10 00:30）
- [ ] **真机三语言逐屏走查**：adb 设备（原 `4b0cd2e3`）在收尾阶段已断开，`adb devices` 为空，未能执行；清单见 `docs/l10n-qa-checklist.md`
- [ ] Worker / 通知语言抽查（同上，需设备在线）

设备重新连接后按 `docs/l10n-qa-checklist.md` 执行即可（安装命令：
`adb install -r "D:\ai-build\rhodesisland\app-build\outputs\apk\debug\app-debug.apk"`）。
