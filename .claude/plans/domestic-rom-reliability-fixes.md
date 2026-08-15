# 国产 ROM 后台可靠性 + 推理保活 修复方案

## 目标与边界
按评估「缺口清单」1-7 全部落实代码修复，并把推理前台服务接入 `BackendManager.generate`（「CPU 后端相关调整」）。
- **不触碰**：native 代码（`mnn_jni.cpp` / `cpu_affinity_jni.cpp` / `.so` 全部不变，故无需 NDK 重编）、芯片 NPU 集成（NNAPI/NeuroPilot/QNN skel 不动）、GPU/OpenCL 检测逻辑不动。
- **全部为 Kotlin + AndroidManifest 改动**，可用 `compileDebugKotlin --rerun-tasks --no-build-cache` 验证（memory `gradle-kotlin-verify`）。

## 缺口 → 修复 映射
| 缺口 | 修复 | 新文件 | 改动文件 |
|---|---|---|---|
| 1 推理无前台服务 | `InferenceForegroundService` + 接入 `generate` | `service/InferenceForegroundService.kt`、`llm/InferenceSessionController.kt` | `BackendManager.kt`、`RhodesApp.kt` |
| 1 问候无前台服务 | `GreetingWorker` 升前台 | — | `GreetingWorker.kt`、`AndroidManifest.xml` |
| 2 无 WakeLock | 推理:服务持锁；问候:Worker 持锁 | — | `InferenceForegroundService.kt`、`GreetingWorker.kt` |
| 3 无电池白名单 | 请求忽略电池优化 | `util/BackgroundSurvivalHelper.kt` | `SettingsScreen.kt`、`AndroidManifest.xml` |
| 4 无 exact alarm | AlarmManager 补时触发（可安全降级） | `work/GreetingAlarmScheduler.kt`、`work/GreetingAlarmReceiver.kt`、`work/BootReceiver.kt` | `GreetingScheduler.kt`、`GreetingWorker.kt`、`AndroidManifest.xml` |
| 5 通知授权空回调 | 处理授权/拒绝/去设置 | — | `SettingsScreen.kt`、`GreetingNotificationManager.kt` |
| 6 SAW 死代码 | 删 `OverlayPermissionHelper` + 权限 | (删文件) | `AndroidManifest.xml` |
| 7 无厂商分支 | 厂商自启动 intent 路由 | (并入 `BackgroundSurvivalHelper`) | `SettingsScreen.kt` |
| CPU 后端调整 | FG 服务接入 `generate` + 通知显示后端 | (同 1) | `BackendManager.kt` |

---

## 阶段 A：推理保活（缺口 1+2 推理侧 + CPU 后端调整）— 最高价值

### 新文件 `llm/InferenceSessionController.kt`
封装「推理期间提权」的 begin/end 句柄，仿 `CpuBoostController` 模式（begin 内部 catch 所有异常，绝不向上抛；end 幂等）。
- `fun begin(backendLabel: String)`：`ContextCompat.startForegroundService(ctx, Intent(ctx, InferenceForegroundService::class.java))`，把 `backendLabel` 作 extra。`BackgroundServiceStartNotAllowedException`（Android 12+ 从后台启动）等异常 catch 后仅记日志、置 `active=false`（生成照常进行，只是无 FG 保护——本地生成始终源自前台「发送」点击，进入 `generate` 时 App 在前台，正常不会触发此异常）。
- `fun end()`：若 `active`，`ctx.stopService(...)`；幂等。

### 新文件 `service/InferenceForegroundService.kt`
- `onStartCommand`：**第一步** `startForeground(NOTIF_ID, buildNotification(backendLabel))`（避免 5s 超时 crash），再 `acquire(PARTIAL_WAKE_LOCK)`（`withReferenceCounted=false`，超时 10min 兜底防泄漏）。返回 `START_NOT_STICKY`（生成结束即停；被杀则推理已终止，无需重启）。
- `onDestroy`：release wakelock、`stopForeground`。
- 通知：新 channel `inference_running`（`IMPORTANCE_LOW`，无声），ongoing，文案「本地 AI 推理中…（{backendLabel}）」。channel 在 `RhodesApp.onCreate` 创建（与 `character_greeting` channel 并列）。
- `foregroundServiceType="dataSync"`。

### 改 `BackendManager.kt`
- 构造增加 `private val appContext = context.applicationContext` 与 `private val inferenceSession = InferenceSessionController(appContext)`。
- `generate()`：进入 try 前 `inferenceSession.begin(desiredBackend(preference).displayName)`（用期望后端名；实际回退后端可能不同，但通知仅为保活提示，不必精确同步）；`finally` 末尾 `inferenceSession.end()`。
- 不改回退链/锁/释放逻辑——纯增量包裹。

> 至此「大模型 prefill/生成期间用户切后台被冻/被杀」的核心风险解除；CPU 后端路径获得 FG 保护（即「CPU 后端相关调整」）。

---

## 阶段 B：问候 Worker 前台化 + WakeLock（缺口 1+2 问候侧）

### 改 `GreetingWorker.kt`
- `deliverGreeting` 调云端 API 前：`setForeground(buildGreetingForegroundInfo())`（`CoroutineWorker.setForeground`）。`ForegroundInfo`：notifId=`NOTIF_FG_GREETING`(2001)、低优先级 ongoing 通知「正在生成角色消息…」、`foregroundServiceType = FOREGROUND_SERVICE_TYPE_DATA_SYNC`（API 34）。
- 同段 `try/finally` 内 `acquire`/`release` 一个 `PARTIAL_WAKE_LOCK`（保证 60s 网络生成期间 CPU 不睡）。
- `setForeground` 失败（如通知权限被拒）→ `runCatching` 吞掉，回退到「无前台但仍持 wakelock + 周期保活」，不阻断投递。

### 改 `AndroidManifest.xml`（累计，阶段 A+B）
新增权限：
```
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
WAKE_LOCK
```
新增/合并组件：
```
<service android:name=".service.InferenceForegroundService"
         android:foregroundServiceType="dataSync" android:exported="false"/>
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
         android:foregroundServiceType="dataSync" tools:node="merge"/>
```
> WorkManager 2.9.1 的 `SystemForegroundService` 需 app 显式声明 `foregroundServiceType` 才能 Android 14 前台化；`tools:node="merge"` 安全覆盖库内声明。

---

## 阶段 C：通知授权修复 + DND/渠道感知（缺口 5）

### 改 `SettingsScreen.kt`（`GreetingSection`）
- 删空回调 `rememberLauncherForActivityResult(...){}`（`:515`），改为持有 `isGranted` 状态：
  - 授权 → 正常。
  - 拒绝且 `shouldShowRequestPermissionRationale` → 行内提示「通知被拒绝，收不到主动消息」+「去开启」按钮。
  - 永久拒绝（rationale 不可再弹）→「去设置」按钮跳 `ACTION_APP_NOTIFICATION_SETTINGS`（带 `EXTRA_APP_PACKAGE`/`EXTRA_CHANNEL_ID`）。
- 用 `ContextCompat.checkSelfPermission` + `remember` 在进入分区时读一次，开关打开时按需请求。
- 增加 `NotificationManager.areNotificationsEnabled()` 与 channel 禁用感知：通知被关时显示同一警告。

### 改 `GreetingNotificationManager.kt`
- `notify` 内 `nm.areNotificationsEnabled()`/channel 关闭时记一条 `Log.w`（便于排查「投递了但用户没看到」）；保持静默 no-op 不改行为（避免打扰）。

---

## 阶段 D：电池白名单 + 厂商自启动路由（缺口 3+7）

### 新文件 `util/BackgroundSurvivalHelper.kt`
- `isIgnoringBatteryOptimizations(ctx): Boolean`（`PowerManager.isIgnoringBatteryOptimizations(pkg)`）。
- `requestIgnoreBatteryOptimizations(ctx)`：`startActivity(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)`（需 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限）。
- `manufacturerAutostartIntent(ctx): Intent?`：按 `Build.MANUFACTURER`/`BRAND` 分支（Xiaomi/Redmi、OPPO/realme、vivo、HONOR、Meizu、samsung、HUAWEI 等）返回各自「自启动/后台管理」设置 Activity intent；每个用 `resolveActivity` 探测，不可达则返回 `null`，UI 上隐藏该项。均 `FLAG_ACTIVITY_NEW_TASK`。
- `openAppNotificationSettings(ctx)`（供阶段 C 复用）。

### 改 `SettingsScreen.kt`（`GreetingSection`）
- 把 `:614` 纯文字提示替换为可操作行：
  - 「允许后台运行（电池优化）」按钮：未被忽略时显示，点击 `requestIgnoreBatteryOptimizations`；已忽略显示「✓ 已允许」。
  - 「允许自启动」按钮：仅当 `manufacturerAutostartIntent != null` 时显示，点击跳转厂商设置。
- 文案保留一句说明「部分国产 ROM 仍需手动开启，否则可能收不到主动消息」。

### 改 `AndroidManifest.xml`
```
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

---

## 阶段 E：Exact Alarm 补时触发（缺口 4）— 最复杂、可安全降级

> **取舍说明**：问候是「白天随机时刻」，WorkManager 15min 精度本够；此阶段解决的是「国产 ROM 冻结导致 PeriodicWork 根本不调度」的可靠性，用 `setExactAndAllowWhileIdle` 在 `next_fire_at` 时刻从 Doze 唤醒一次。极端冻结的 ROM 仍可能拦 alarm，故为**补充**而非替代 WorkManager（两者并存，靠 `next_fire_at` 门控防重复投递）。`canScheduleExactAlarms()` 为 false 时全程 no-op，回退纯 WorkManager，绝不会更糟。
> **如你不想要此复杂度，可整段跳过——其余 6 项不受影响。** 默认实施。

### 新文件 `work/GreetingAlarmScheduler.kt`（object）
- `armNext(ctx, fireAt)`：`AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, fireAt, pendingIntent)`，pendingIntent 指向 `GreetingAlarmReceiver`（explicit，无需 intent-filter）。API 31+ 先 `canScheduleExactAlarms()`，false 则 no-op。
- `cancel(ctx)`：取消 alarm。
- `rearmFromSettings(ctx, settings)`：读 `next_fire_at`，`armNext(max(nextFireAt, now))`。

### 新文件 `work/GreetingAlarmReceiver.kt`（BroadcastReceiver）
- `onReceive`：`enqueueUniqueWork` 一个**即时** OneTime `GreetingWorker`（复用现有门控：到点投递、否则跳过；写回 `next_fire_at` 时一并 `GreetingAlarmScheduler.armNext` 下一轮）。无需 `KEY_TEST`。
- 防重复：与 PeriodicWork 共享 `next_fire_at` 门控——先到者投递并推进 `next_fire_at`，后到者见 `now < next_fire_at` 跳过。

### 新文件 `work/BootReceiver.kt`（BroadcastReceiver，`BOOT_COMPLETED`）
- `onReceive`：`GreetingAlarmScheduler.rearmFromSettings`（alarm 不跨重启；WorkManager 自带 boot 恢复，仅补 alarm）。

### 改 `GreetingScheduler.kt`
- `enqueuePeriodic` 后、`reschedule` 写 `next_fire_at` 后、`ensureScheduled`/`cancel` 中分别 `armNext`/`cancel` alarm（与现有 PeriodicWork 并行）。

### 改 `GreetingWorker.kt`
- 每次写 `next_fire_at`（成功/退避/跨时段推到次日）后 `GreetingAlarmScheduler.armNext(ctx, 新next_fire_at)`，保持 alarm 单一真源。

### 改 `AndroidManifest.xml`
```
SCHEDULE_EXACT_ALARM
RECEIVE_BOOT_COMPLETED
```
```
<receiver android:name=".work.GreetingAlarmReceiver" android:exported="false"/>
<receiver android:name=".work.BootReceiver" android:exported="true">
    <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
</receiver>
```
> settings 页加一个「精确闹钟」开关入口跳 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`（Android 12+ 用户授权用），未授权则 alarm 降级。

---

## 阶段 F：死代码清理（缺口 6）
- 删 `perfmon/OverlayPermissionHelper.kt`（已确认仅自引用、无任何调用方）。
- 删 `AndroidManifest.xml` 的 `SYSTEM_ALERT_WINDOW`（液态玻璃浮窗为应用内 `addView`，非 `TYPE_APPLICATION_OVERLAY`，不需要此权限）。

---

## 验证
1. `JAVA_HOME='D:/新建文件夹/jdk-21' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache`（memory `gradle-kotlin-verify`、`java-home-jdk21`）。
2. 无 native 改动 → 不触发 CMake/NDK；现有 `.so` 不变。
3. 人工核查点：
   - 本地推理中切后台 → 通知栏「本地 AI 推理中…」常驻，生成不被冻断。
   - 问候测试按钮 → 生成期间前台通知「正在生成角色消息…」，投递后高优先横幅通知。
   - 通知权限拒绝 → 设置页显示「去开启」；授予后恢复正常。
   - 电池优化未忽略 → 设置页「允许后台运行」可点；厂商机型显示「允许自启动」跳转。
   - exact alarm：授权后 `adb shell dumpsys alarm | grep rhodes` 可见 setExact 条目；未授权不崩、回退 WorkManager。

## 风险与回退
- **阶段 A/B**：前台服务通知在用户前台聊天时会常驻（FG 服务契约，无法隐藏）——可接受，且可作「正在思考」提示。`startForegroundService` 从后台启动异常已 catch 降级。
- **阶段 B**：WorkManager `SystemForegroundService` manifest 合并若冲突 → 用 `tools:node="replace"` 兜底；构建期即可发现。
- **阶段 E**：alarm/广播新增组件最多引入「重复投递」风险，已由 `next_fire_at` 门控兜底；最坏回退为删阶段 E 三文件 + 撤回 manifest 三项。
