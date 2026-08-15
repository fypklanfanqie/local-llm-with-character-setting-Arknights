# 角色问候（角色主动消息）功能实现计划

## 目标
在设置中新增「角色问候」开关。开启后，用户选择一个角色（含自定义角色），该角色会在白天随机时间**主动**给用户发消息（早安/晚安/关心/主动开话题），内容符合角色人设。仅云端 AI（CLOUD）模式可用。主动消息以**类微信通知**提醒用户；次数可由用户设置（每天 N 条）。消息会写入该角色的活跃会话，打开 App 即可在聊天里看到。

## 关键技术决策
- **调度方案：WorkManager**。项目当前**零**通知/调度基础设施（无 WorkManager/AlarmManager/FCM/Service/Receiver，无 `POST_NOTIFICATIONS` 等权限）。App 已无自建服务器（旧 `CloudRunApi` 已删除，现为直连厂商 OpenAI 兼容 API），故 FCM 服务端推送不适用。WorkManager 是唯一能「跨进程死亡/重启存活、无需服务器、直连云 API 生成内容」的方案：`OneTimeWorkRequest` 会持久化并跨重启复跑，自延续链即「发一条→调度下一条」。
- **内容生成：复用 `DirectLlmClient.chatOnce(...)`**（已存在的非流式一次性调用）。把 `Character.systemPrompt` + 时段指令 + 最近几条历史拼成上下文，让消息既符合人设又能衔接话题。
- **消息落库：写入角色活跃会话**（`ChatRepository.addMessage`，role=assistant）。Room 的 `getHistoryFlow` 会自动推送，故 App 在前台且正好在该会话时消息**实时冒泡**（类微信正在聊天时不弹通知，由前台状态 + 当前角色判断抑制）。
- **切角色入口：通知 PendingIntent → MainActivity**。`MainActivity` 读取 intent extra 写入 `setActiveCharacter` + `setActiveConversation`，`ChatViewModel` 现有 collector 自动响应（无需改 ViewModel）。
- **后台可靠性说明**：国产 ROM（MIUI/EMUI/ColorOS）可能杀后台导致 WorkManager 延迟/不触发。设置页加提示「请允许本应用后台运行/自启动」。这是 WorkManager 方案的固有局限，非代码可完全解决。

## 新增依赖（`gradle/libs.versions.toml` + `app/build.gradle.kts`）
- `work = "2.9.1"` → `androidx-work-runtime-ktx = { group="androidx.work", name="work-runtime-ktx", version.ref="work" }`
- `androidx-lifecycle-process = { group="androidx.lifecycle", name="lifecycle-process", version.ref="lifecycle" }`（前台观察，复用现有 `lifecycle=2.8.2`）
- `app/build.gradle.kts`：`implementation(libs.androidx.work.runtime.ktx)` + `implementation(libs.androidx.lifecycle.process)`

## AndroidManifest.xml
- 新增权限：`android.permission.POST_NOTIFICATIONS`（Android 13+ 必须）、`android.permission.VIBRATE`（通知振动）。
- 无需新增 `<service>/<receiver>`：Worker 由 WorkManager 工厂实例化；WorkManager 自带合并进 manifest 的 boot/wake 组件，跨重启自动复跑，**不需要** `RECEIVE_BOOT_COMPLETED`/`WAKE_LOCK`。

## 配置常量（`config/AppConfig.kt`）
新增 `object Greeting`：
- `DEFAULT_DAILY_COUNT = 3`，`MIN_DAILY_COUNT = 1`，`MAX_DAILY_COUNT = 10`
- `HOUR_START = 8`，`HOUR_END = 23`（仅在 08:00–23:00 触发，避免深夜打扰）

## 设置层（`SettingsStore.kt` + `SettingsRepository.kt`）
新增 DataStore key 与 Flow / setter / 同步 getter（沿用现有模式）：
- `GREETING_ENABLED`(bool)、`GREETING_CHARACTER_ID`(string, 默认 `Characters.DEFAULT_CHARACTER_ID`)、`GREETING_DAILY_COUNT`(int, 默认 `AppConfig.Greeting.DEFAULT_DAILY_COUNT`)
- 每日配额：`GREETING_QUOTA_DATE`(string `yyyy-MM-dd`)、`GREETING_QUOTA_COUNT`(int)
- Flow：`greetingEnabled` / `greetingCharacterId` / `greetingDailyCount`
- setter：`setGreetingEnabled` / `setGreetingCharacterId` / `setGreetingDailyCount`
- 同步 getter（供 Worker 用，带 5s 超时兜底）：`getGreetingEnabledNow()` / `getGreetingCharacterIdNow()` / `getGreetingDailyCountNow()` / `getGreetingQuotaNow(): Pair<String,Int>` / `setGreetingQuota(date, count)`（配额重置/自增原子化）
- `SettingsRepository` 透传以上全部。

## 新增文件

### 1. `work/GreetingScheduler.kt`（调度入口，`object`）
- `scheduleNext(context, delayMillis)`：`enqueueUniqueWork("greeting_work", REPLACE, OneTimeWorkRequest<GreetingWorker>.setInitialDelay(delayMillis, MS))`。
- `ensureScheduled(context)`：查询 `WorkManager.getWorkInfosForUniqueWork("greeting_work")`；若无 RUNNING/ENQUEUED 项且「已开启 + 云端」则 `scheduleNext(计算的下一次延迟)`。用于 App 启动 / 设置变更后重启死链。
- `cancel(context)`：`cancelUniqueWork("greeting_work")`（关闭开关或切到本地时调用）。
- `reschedule(context)`：`cancel` + 若启用则 `scheduleNext`（设置变更时重算）。
- `computeNextDelay(now, remainingToday)`：剩余条数 R、剩余清醒分钟 M（到 `HOUR_END`）。
  - R==0 → 延迟到次日 `HOUR_START`。
  - R>0 → 平均间隔 `M/(R+1)`，下一次延迟 = 平均 × 随机系数(0.6–1.4)，限制在窗口内。用 `Calendar`/`System.currentTimeMillis`（Worker 内可用，非 Workflow 脚本限制）。

### 2. `work/GreetingWorker.kt`（`CoroutineWorker`）
`doWork()` 流程：
1. 取 `RhodesApp.container`。
2. `getActiveProviderNow()` ≠ CLOUD → `scheduleNext(次日早晨)`，return success（本地模式静默暂停）。
3. `getGreetingEnabledNow()` == false → return success（链自然终止，待 `ensureScheduled` 重启）。
4. 读 `greetingCharacterId` / `greetingDailyCount`。
5. 配额：`(date,count)=getGreetingQuotaNow()`；`date≠今天` → 重置 count=0。`count≥dailyCount` → `scheduleNext(次日早晨)`，success。
6. 时段：当前小时 `<HOUR_START || >=HOUR_END` → `scheduleNext(下一个 HOUR_START)`，success。
7. `characterRepository.getNow(charId)` 为 null → success（角色已删）。
8. 会话：`getActiveConversationNow(charId)` 为 null 则 `conversationRepository.create(charId)` + `setActiveConversation`；`chatRepository.getHistory(convId).takeLast(6)` 作上下文。
9. 生成：`apiConfig=getApiConfigNow()`；apiKey 空 → success（静默重排）。system = `char.systemPrompt + 时段指令`（见下），messages = [system, ...历史 DTO]，`directLlmClient.chatOnce(baseUrl,apiKey,model,messages)`，`withTimeout(60s)` + try/catch。失败 → `scheduleNext(30–60min)` + success（避免 retry 风暴）。
10. 落库：`chatRepository.addMessage(charId, convId, ChatMessage(role="assistant", content=resp, timestamp=now))` + `conversationRepository.touch(convId)`。
11. 通知：若 `!(AppLifecycleObserver.isForeground && ui活跃角色==charId)` → `GreetingNotificationManager.notify(...)`（前台且正在看该角色则抑制，消息已实时冒泡）。
12. `setGreetingQuota(今天, count+1)`。
13. `scheduleNext(computeNextDelay(remaining = dailyCount-(count+1)))`。
14. return success。

**时段指令**（追加到 systemPrompt，按当前小时选「清晨/上午/中午/下午/傍晚/深夜」）：
```
现在请你主动给用户发一条消息。当前时间 HH:mm（时段）。
要求：完全符合你的人设与说话风格；可以是打招呼/问候/关心/主动开话题；自然简短(1-3句)，像真人随手发的消息；只输出消息内容本身，不要角色名前缀/引号/解释。
```

### 3. `notification/GreetingNotificationManager.kt`（`object`）
- `createChannel(ctx)`：channel id `character_greeting`，名「角色问候」，importance HIGH（横幅通知，类微信）。`RhodesApp.onCreate` 调用。
- `notify(ctx, characterId, conversationId, charName, message)`：`NotificationCompat.Builder`，小图标 `R.drawable.ic_notification`（新增白色剪影矢量图），title=charName，text=消息(截断)，`BigTextStyle` 展开全文，auto-cancel，默认提示音/振动。PendingIntent → `MainActivity`（extras: `EXTRA_CHARACTER_ID`/`EXTRA_CONVERSATION_ID`，flags `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`，Intent 加 `FLAG_ACTIVITY_SINGLE_TOP|FLAG_ACTIVITY_CLEAR_TOP`）。通知 id 用 `characterId.hashCode()`。
- 新增 `res/drawable/ic_notification.xml`（白色简单剪影矢量图）。

### 4. `notification/AppLifecycleObserver.kt`
- `DefaultLifecycleObserver` + `ProcessLifecycleOwner`，维护 `@Volatile var isForeground`。`RhodesApp.onCreate` 注册。

## 修改文件

### `RhodesApp.kt`
- 新增 `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`。
- `onCreate`：`GreetingNotificationManager.createChannel(this)`；`ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver)`；`applicationScope.launch { GreetingScheduler.ensureScheduled(this@RhodesApp) }`。

### `MainActivity.kt`
- `onCreate` + `onNewIntent`：解析 `EXTRA_CHARACTER_ID`/`EXTRA_CONVERSATION_ID`，在协程里 `setActiveCharacter` + `setActiveConversation`（冷启动在 `setContent` 前写，`ChatViewModel` init 即读到；热启动 collector 自动响应）。

### `ui/settings/SettingsScreen.kt`
- 新增「角色问候」section（`SectionDivider`，置于深度思考附近）：
  - 开关 `Switch`：绑 `greetingEnabled`/`setGreetingEnabled`。当 `activeProvider==LOCAL` 时禁用并提示「仅云端 AI 可用」。开启时：请求 `POST_NOTIFICATIONS`（13+，`rememberLauncherForActivityResult`）+ `GreetingScheduler.ensureScheduled`；若 `greetingCharacterId` 为空则默认填当前活跃角色。
  - 角色选择行：显示当前角色名，点击弹 Dialog 列出内置+自定义（`characterRepository.characters`），绑 `greetingCharacterId`。未开启/本地时禁用。
  - 每日次数 `Slider`（1–10）+ 数值，绑 `greetingDailyCount`。未开启/本地时禁用。
  - 说明文字 + 国产 ROM 后台运行提示。
  - 任意变更 → `GreetingScheduler.reschedule`。

## 不改动
- `ChatViewModel` / `ChatScreen`：切角色/会话已有 collector；落库消息经 Room Flow 自动冒泡；通知 deep-link 仅写设置。无需改动。
- `Character` 数据类：不新增字段（问候内容动态生成，非静态属性）。

## 验证（`JAVA_HOME='D:/新建文件夹/jdk-21'`）
1. `./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache` 编译通过。
2. `./gradlew :app:assembleDebug` 出包。
3. 手动验证（设备/模拟器）：开启功能→选角色→设次数→切云端→改 Worker 触发延迟为短时间（临时调试）验证：通知弹出、消息入库、点通知跳转到对应角色会话、前台抑制、配额计数、本地模式禁用、关闭开关取消调度。

## 风险/局限
- 国产 ROM 杀后台 → 通知可能延迟/丢失（设置页提示用户授权自启动）。
- WorkManager 不保证精确时刻（符合「随机时间」诉求）。
- 云 API 失败时静默重排，不影响配额。
