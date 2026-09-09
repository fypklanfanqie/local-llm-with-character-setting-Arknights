# 多语言（中/英/日）实施手册 —— 中央词典 + t() 包装

> 本文是**执行规范**，所有按批次推进的实现者（人或 AI）都必须先读完再动手。
> 方案已定，不要重新选型（不用 strings.xml / 不引入新框架）。

## 1. 架构（已实现，批次 0）

| 文件 | 作用 |
| --- | --- |
| `i18n/L10n.kt` | `enum AppLanguage(SYSTEM/ZH/EN/JA)`、纯函数 `L10n.t(lang, zh)` / `L10n.format(lang, zh, vararg)` / `L10n.resolve(...)`、`LocalAppLanguage` CompositionLocal、`@Composable fun t(zh)` / `tf(zh, vararg)` |
| `i18n/Dictionary.kt` | 词典注册表：把各批次词典文件合并成 `EnStrings` / `JaStrings`；`dictionaryConflicts` 供单测拦截跨批次译文冲突 |
| `i18n/dict/En**.kt`、`i18n/dict/Ja**.kt` | **各批次词条**，一个批次一对文件，避免多人改同一个文件 |
| `SettingsStore` / `SettingsRepository` | DataStore 键 `app_language`（`system`/`zh`/`en`/`ja`，默认 `system`）；`getResolvedAppLanguageNow()` 给 Worker/VM 用 |
| `MainActivity` | 根 Composable `collectAsState` + `CompositionLocalProvider(LocalAppLanguage provides …)` → **切换即时生效** |
| `ui/settings/SettingsScreen.kt` `LanguageSection` | 语言设置区 |
| `tools/l10n-coverage.ps1` | 覆盖度脚本（扫未包装中文字面量） |

核心语义：**中文原文就是 key**，查不到回退中文，永不崩。因此可以分批、渐进覆盖。

## 2. 包装规则

### 2.1 Composable 里（绝大多数 UI）
```kotlin
Text("设置")                 // ✗
Text(t("设置"))              // ✓
GlassListRow(title = t("语言"), subtitle = t("界面语言"))   // ✓
contentDescription = t("返回")                                // ✓
```

### 2.2 带变量的字符串 —— 必须改成占位符模板
```kotlin
Text("已选择 ${count} 个角色")            // ✗ 动态串查不到词典
Text(tf("已选择 {0} 个角色", count))       // ✓
```
⚠️ **Kotlin 中文标识符坑**：`"$var中文"` 会被解析成变量名 `var中文`。中文紧贴变量时必须写 `${var}`。

⚠️ **不要在字符串模板里嵌套 t()**：`"${t("前缀")}${t("后缀")}"` 既让跨语言语序无法调整，覆盖度脚本也看不见
（`${...}` 整体被当作模板）。正确做法是先取变量再拼：
```kotlin
val prefix = t("前缀")            // 或 L10nRuntime.t("前缀")
val suffix = t("后缀")
return "$prefix$suffix"
```

### 2.3 非 Composable（ViewModel / Worker / 通知 / 纯函数）
```kotlin
val lang = settings.getResolvedAppLanguageNow()   // suspend，返回已解析语言（非 SYSTEM）
val text = L10n.t(lang, "朋友圈已更新")
val line = L10n.format(lang, "共 {0} 条", n)
```
- 若所在函数不是 `suspend` 且拿不到语言，**不要为了翻译改架构**：保持中文，并在报告里列出该处。
- **既非 Composable 又非 suspend** 的场景（通知构建器、前台服务、纯格式化函数）用运行期缓存：
```kotlin
L10nRuntime.t("角色问候")                  // 读启动期 collector 缓存的语言，默认中文
L10nRuntime.format("共 {0} 条", n)
```
  缓存由 `RhodesApp.onCreate` 的 collector 持续跟随设置更新，无 UI 的进程也有效。
- Worker/通知是「收尾抽查」的重点，务必按上面写法处理。

### 2.6 数据驱动内容（内容在数据层、渲染在 UI 层）
像 `ui/guide/GuideContent.kt` 这种**纯数据文件**（顶层 `val`，非 Composable），不要在数据层翻译
（拿不到语言、且顶层初始化时读不到 DataStore）。做法：
- 数据层保持中文原文；
- 在**渲染处**包一层：`Text(block.text)` → `Text(t(block.text))`、`GlassChip(label = t(lv.chipLabel))`；
- 若渲染前会截断/拼接，**先取到中文原文再包**：`t(firstBlockText(topic))` 之后才截断，
  不要对截断后的字符串查表（查不到）；
- 词典 key 用数据层的中文原文。

### 2.4 不要包装的东西
- **日志**：`Log.d/w/i/e(...)`、`CrashCapture.logEvent(...)` 的中文（方案明确不翻译）。
- **提示词与人设**：`llm/`、`config/`、任何 `*Prompt*` 文件里的中文（AI 输出语言与界面语言独立）。
- **数据/协议字符串**：DataStore 键、JSON 字段名、`when` 分支用于比较的字面量、Map key。
  - 判断法：如果这个字符串被 `==` / `!=` 比较、作为 `map["…"]` 的键、或写进数据库/文件，**不是文案**，保持原样。
  - 若它既是文案又是比较值（例如把中文 label 当 key 用），改结构前先在报告里说明，或两处一起包（`t("中文") == t("中文")` 恒等成立，但更稳的是换成枚举/id）。
- **英文/品牌/型号/路径/API 名**：不翻译。

### 2.5 import
```kotlin
import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.tf
```
若文件里已有名为 `t` 的局部变量/参数（例如 `catch (t: Throwable)`），**改名局部变量**（`e`/`throwable`），不要给 import 起别名 —— 覆盖度脚本只认 `t(` / `tf(`。

## 3. 词条维护

- 词条写进**本批次专属**文件 `i18n/dict/En<批次>.kt` 与 `Ja<批次>.kt`（**必须成对添加**，单测会校验两份词典 key 集合一致）。
- 格式：
```kotlin
internal val En01SettingsAEntries: List<Pair<String, String>> = listOf(
    // ===== 外观 / 主题 =====
    "主题" to "Theme",
    "当前：{0}" to "Current: {0}",
)
```
- key 与代码里 `t("…")` 的字符串**一字不差**（含标点、空格、`：`、`…`、括号）。
- 占位符 `{0}`/`{1}` 下标必须一致。
- 同一句中文若已在别的批次文件里，且译文一致 → 无妨；译文不同 → 单测 `same source text translates consistently across batches` 会失败，必须统一（以本文第 5 节术语表为准）。
- 词典只放**界面文案**，不放日志/提示词。

### 翻译风格
- **英文**：标签用 sentence case、不加句号；说明句用完整句 + 句号；按钮用动词原形（Save / Delete / Retry）。
- **日文**：标签用名词短语；说明句用「です・ます」体；按钮用「保存 / 削除 / 再試行」。
- 标点要换成目标语言习惯：`，`→`, `、`。`→`.`、`（`→` (`、`：`→`: `（日文保留全角）。
- 保留原文里的 emoji / 特殊符号；`%d`/`%s` 这类格式串保持不动。
- 语气跟原中文一致：本应用是「明日方舟同人终端」，界面偏工程/科幻风，别翻得太口语。

## 4. 验证（每批必做）

```powershell
cd "D:\ai\cc Programm\聊天终端安卓本地"
.\gradlew.bat :app:testDebugUnitTest --console=plain     # 编译 + 全量单测（含词典完整性）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\l10n-coverage.ps1 -Dir ui\settings -List
```
- 编译/单测必须**全绿**才进下一批。
- 脚本三行关键指标：
  - **待处理**：既没包 t() 也没进词典的中文（本批次应当趋近 0，剩下的是日志/提示词/数据串）；
  - **已包装但缺词条**：包了 `t()` 但词典没这条 key —— 切到英/日会**静默回退中文**，必须补齐；
  - **词典覆盖度** =（已包装 + 已进词典）/ 全部。
- `-List` 会分别打印「已包装但缺词条」和「待处理明细」，两者都应为空（除不翻译项）。
- 多个目录用 `-Command` 传数组：`powershell -Command "& '.\tools\l10n-coverage.ps1' -Dir @('ui\chat','ui\guide')"`。
- ⚠️ 真 APK 在 `D:\ai-build\rhodesisland\app-build\outputs\apk\debug\app-debug.apk`；`app\build\outputs\` 下的是陈旧残留。

## 5. 术语表（跨批次必须一致）

| 中文 | English | 日本語 |
| --- | --- | --- |
| 罗德岛通讯终端 | Rhodes Island Terminal | ロドス通信端末 |
| 明日方舟 | Arknights | アークナイツ |
| 干员 | Operator | オペレーター |
| 博士 | Doctor | ドクター |
| 角色 | Character | キャラクター |
| 人设 / 角色设定 | Persona | ペルソナ |
| 世界观 | Worldview | 世界観 |
| 世界书 | Lorebook | ロアブック |
| 群聊 | Group chat | グループチャット |
| 朋友圈 | Moments | モーメンツ |
| 卡片流 | Card feed | カードフィード |
| 会话 | Conversation | 会話 |
| 消息 | Message | メッセージ |
| 好感度 | Affinity | 好感度 |
| 语音合成 / 朗读 | TTS / Read aloud | 音声合成 / 読み上げ |
| 音色 | Voice | 音色 |
| 声音复刻 | Voice cloning | 声クローン |
| 立绘 | Illustration | 立ち絵 |
| 生图 | Image generation | 画像生成 |
| 模型 | Model | モデル |
| 推理引擎 | Inference engine | 推論エンジン |
| 后端 | Backend | バックエンド |
| 上下文 | Context | コンテキスト |
| 线程 | Threads | スレッド |
| 温度 | Temperature | 温度 |
| 采样 | Sampling | サンプリング |
| 提示词 | Prompt | プロンプト |
| 降级 / 回退 | Fallback | フォールバック |
| 缓存命中率 | Cache hit rate | キャッシュヒット率 |
| 服务商 | Provider | プロバイダー |
| API 密钥 | API key | API キー |
| 测试连接 | Test connection | 接続テスト |
| 云端 / 本地 | Cloud / Local | クラウド / ローカル |
| 轮次 | Round | ラウンド |
| 间隔 | Interval | 間隔 |
| 抖动 | Jitter | ジッター |
| 定时任务 | Scheduled task | 定期タスク |
| 静默 | Silent | サイレント |
| 通知 | Notification | 通知 |
| 重试 | Retry | 再試行 |
| 令牌 | Token | トークン |
| 主题 | Theme | テーマ |
| 深色 | Dark | ダーク |
| 设置 | Settings | 設定 |
| 使用指南 | User guide | 使い方ガイド |
| 崩溃日志 | Crash logs | クラッシュログ |
| 关于 | About | このアプリについて |
| 免责声明 | Disclaimer | 免責事項 |
| 版本 | Version | バージョン |
| 保存 | Save | 保存 |
| 已保存 | Saved | 保存しました |
| 取消 | Cancel | キャンセル |
| 确定 | OK | OK |
| 关闭 | Close | 閉じる |
| 删除 | Delete | 削除 |
| 重置 | Reset | リセット |
| 添加 | Add | 追加 |
| 编辑 | Edit | 編集 |
| 完成 | Done | 完了 |
| 返回 | Back | 戻る |
| 搜索 | Search | 検索 |
| 全部 | All | すべて |
| 清除 | Remove | 削除 |
| 清空 | Clear | クリア |
| 分享 | Share | 共有 |
| 导入 / 导出 | Import / Export | インポート / エクスポート |
| 启用 / 禁用 | Enable / Disable | 有効 / 無効 |
| 开启 / 关闭（开关态） | On / Off | オン / オフ |
| 默认 | Default | デフォルト |
| 未配置 | Not configured | 未設定 |
| 暂无 | None | なし |
| 立即生效 | Takes effect immediately | すぐに反映されます |

### 批次 1A 补充术语（设置页）

| 中文 | English | 日本語 |
| --- | --- | --- |
| 模型商 | Provider | プロバイダー |
| 免费对话 | Free chat | 無料チャット |
| 深度思考 | Deep thinking | 深い思考 |
| 声音模板 | Voice template | 音声テンプレート |
| 生图 API | Image generation API | 画像生成 API |
| 自动发圈 | Auto posting | 自動投稿 |
| 发圈间隔 | Posting interval | 投稿間隔 |
| 互动角色 | Interacting characters | 交流キャラクター |
| 存储管理 | Storage | ストレージ管理 |
| 电池优化 | Battery optimization | バッテリー最適化 |
| 自启动 | Autostart | 自動起動 |
| 精确闹钟 | Exact alarms | 正確なアラーム |
| 我的形象 | My persona | わたしのプロフィール |
| 头像 | Avatar | アイコン |
| 分辨率 | Resolution | 解像度 |
| 画幅比例 | Aspect ratio | アスペクト比 |
| 水印 | Watermark | 透かし |
| 背景图 | Background image | 背景画像 |
| 场景描述 | Scene description | シーン描写 |
| 中转站 | Relay station | 中継サイト |
| 上下文压缩 | Context compaction | コンテキスト圧縮 |
| 前情提要 | Recap | あらすじ |
| 缓存命中 | Cache hit | キャッシュヒット |
| 调用 | Calls | 呼び出し |
| Token 用量 | Token usage | Token 使用量 |

### 不翻译的「文本」而非「文案」

- **TTS 试听样本**（`SettingsScreen` 里 `ttsSystemPreviewText` / `ttsClonedPreviewText` / `ttsJapaneseSample`）：
  随**朗读语言**（`ttsLanguage`）而非界面语言变化，标 `l10n:ignore`。
- **TTS 文本规范化**（`TtsManager.cleanTtsTextForLanguage` 的 `ドクター → 博士`）：语音读音修正，标 `l10n:ignore`。
- **发给 LLM 的提示词**（含「你好，请回复『测试通过』」这类连通性测试串）：不翻译。

新增术语：在报告里列出（中文 / English / 日本語），由总负责人并入本表。
