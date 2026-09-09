# 多语言批次计划与进度

> 方案与规则见 `docs/l10n-playbook.md`；进度用 `tools/l10n-coverage.ps1` 度量。
> 基线（批次 0 完成时）：界面中文字面量 **2220** 条，其中 **41** 条为调试日志（不翻译）。

| 批次 | 范围 | 中文字面量 | 词典文件 | 状态 |
| --- | --- | --- | --- | --- |
| 0 | 基建：L10n / 语言设置 / 覆盖度脚本 | — | `En00Common` / `Ja00Common` | ✅ 已完成 |
| 1A | `ui/settings/SettingsScreen.kt` | 284 | `01SettingsA` | ✅ 已完成（272 处 + 239 词条） |
| 1B | `ui/settings/BackendSettingsScreen.kt` | 198 | `01SettingsB` | ✅ 已完成（199 处 + 167 词条） |
| 1C | `ui/settings/LorebookSection.kt` + `WorldviewSection.kt` + `TargetPickers.kt` | 85 | `01SettingsC` | ✅ 已完成（84 处 + 62 词条） |
| 2 | `ui/chat/*` | 130 | `02Chat` | ✅ 已完成（122 处 + 94 词条） |
| 3A | `ui/guide/GuideContent.kt` + `ui/settings/GuideDialog.kt` | 617 | `03Guide` | ✅ 已完成（568 词条 + 渲染处包装） |
| 3B | `ui/guide/GuideQuiz.kt` | 68 | `03GuideB` | ✅ 已完成（58 词条 + 10 条 note 豁免） |
| 4 | `ui/video/*` + `ui/models/*` | 120 | `04Video` | ✅ 已完成（120 处 + 65 词条） |
| 5 | `ui/groupchat/*` | 61 | `05GroupChat` | ✅ 已完成（60 处 + 38 词条） |
| 6 | `ui/lorebook/*` + `ui/glass/*` | 99 | `06Lorebook` | ✅ 已完成（99 处 + 75 词条） |
| 7 | `ui/moment/*` + `ui/feed/*` + `ui/navigation/*` + `ui/characters/*` | 100 | `07Moment` | ✅ 已完成（100 处 + 58 词条；Tab 通讯=Comms/トーク） |
| 8 | `ui/novel/*` + `ui/affinity/*` + `ui/music/*` + `ui/theme/*` | 162 | `08Novel` | ✅ 已完成（161 处 + 126 词条） |
| 9A | `util/*` + `notification/*` + `work/*` + `provider/*` + `tts/*` + `service/*` + `perfmon/*` | 93 | `09System` | ✅ 已完成（91 处 + 92 词条） |
| 9B | `data/remote` + `data/model` + `data/repository` + `data/lorebook` | 117 | `09SystemB` | ✅ 已完成（65 处 + 75 词条） |
| 10 | `conversationexport/*` + `manager/*` | 37 | `10Export` | ✅ 已完成（37 处 + 30 词条） |
| 11 | `llm/` 诊断层文案（提示词除外） | ~150 | `11Diagnostics` | ⏳ |
| — | 跨批次补漏（朗读引擎下拉 / 模型说明 / 语音语言角标） | 3 | — | ✅ 已完成 |

## 约定

- 每个批次：包装 → 补 en/ja 词条 → `.\gradlew.bat :app:testDebugUnitTest --console=plain` 全绿 → 覆盖度脚本复查 → 提交。
- 词典文件**一对一批次**，避免并发改同一文件；`Dictionary.kt` 注册表在批次 0 已固定，不需要再动。
- 不翻译：`llm/`（提示词）、`config/`（角色人设）、调试日志、DataStore/JSON 键、比较值。
- AI 输出语言与界面语言独立：改界面语言不影响角色回复语言。

## 已知限制（收尾时说明）

- 指南搜索的「别名关键词」是中文，切到英文/日文后按英文关键词搜不到中文标题 —— 需要时为词条补英文别名。
- 用户自建内容（角色、世界观、世界书、小说）不翻译，按原文显示。
- 角色名/干员名按原文显示（专有名词，不进入词典）。
