# 修复角色问候后台可靠性（退出 App 后收不到 / 多角色只发一个）

## 问题根因

当前用 WorkManager **自延续链**（`OneTimeWorkRequest` 发一条 → `reschedule` 下一条）调度角色问候。这个机制的致命弱点：

- 链条依赖**每次执行成功后 reschedule 下一次**。一旦某次预定执行因「进程被杀 / 国产 ROM 省电冻结 / 划掉任务卡」而**没发生**，就没有 reschedule，**链条永久断裂**。
- `ensureScheduled` 只在 **App 启动时**重启死链。用户退出 App 后不再打开 → 死链无法恢复 → **「退出后收不到通知、不再主动问候」**。
- 已有的「链条可靠性修复」（`readGatingState` 重试保活）只覆盖「Worker 执行了但读设置超时」的情况，**不覆盖「Worker 根本没被执行」**——而这正是退出后的主因。
- **多角色只发一个** 与之同源：链不可靠 → 每天只触发极少次（甚至 1 次）→ `pickCharacter` 的「避免与上次相同」轮换逻辑没机会生效 → 角色集中在一个上。

## 解决方案：用 PeriodicWork 替代自延续链

`PeriodicWorkRequest`（15 分钟周期，KEEP）是**周期性触发**，不依赖自延续：

- 错失一次，下个周期还会跑（WorkManager 内部维护周期）——远比「错失一次即永久断裂」的自延续链可靠。
- 跨进程死亡 / 重启自动存活，无需 `RECEIVE_BOOT_COMPLETED`。
- KEEP 入队一次即可，重复入队不新建。

Worker 改为「**周期检查目标投递时间 `next_fire_at`，到了就发**」，**去掉脆弱的 reschedule 自延续**。新增 DataStore 字段 `greeting_next_fire_at`（epoch ms）记录「下次该发的时间」。每次发完用 `computeNextDelay` 算出下个目标时间写入；PeriodicWork 每 15 分钟检查 `now >= next_fire_at` 则投递。

时间精度 = 15 分钟 + ROM 延迟，对「白天随机时间」完全够用；可靠性大幅提升。

## 修改清单

### 1. `config/AppConfig.kt`（Greeting object 内新增常量）
- `HEARTBEAT_INTERVAL_MIN = 15` —— PeriodicWork 周期（分钟，WorkManager 最短允许 15）。

### 2. `data/local/SettingsStore.kt`（沿用现有 greeting key 模式新增）
- `Keys.GREETING_NEXT_FIRE_AT = longPreferencesKey("greeting_next_fire_at")`
- `val greetingNextFireAt: Flow<Long>`（默认 0）
- `suspend fun setGreetingNextFireAt(epochMs: Long)`（0 表示未设置）

### 3. `data/repository/SettingsRepository.kt`（透传）
- `val greetingNextFireAt: Flow<Long>`
- `suspend fun setGreetingNextFireAt(epochMs: Long)`
- `suspend fun getGreetingNextFireAtNow(): Long`（5s 超时返回 0）

### 4. `work/GreetingScheduler.kt`（核心重构）
- `ensureScheduled(ctx, settings)`：`enqueueUniquePeriodicWork(WORK_NAME, KEEP, 15min)`。`isEnabledAndCloud==false` → `cancel`；`==null`（读不到设置）→ 仍 KEEP 入队保活，不 cancel。
- `reschedule(ctx, settings)`：设置变更时调用。`false` → `cancel`；`null` → 保活不动；`true` → 若 `next_fire_at<=0` 则初始化 `now + computeNextDelay(remaining)` 写入（让新配额立刻影响节奏，首次启用不立刻发）。
- `cancel(ctx)`：`cancelUniquePeriodicWork`（原 `cancelUniqueWork`）。
- **删除 `scheduleNext`**（自延续专用，不再需要）。**保留 `scheduleTest`**（测试预览，仍用 OneTime + 独立 work name，不动）。
- 新增 `computeNextFireAt(now, settings)`：`now + computeNextDelay(remainingToday(settings))`（绝对时间，供 Worker / reschedule 用）。
- `computeNextDelay` 保持不变（已正确处理「不在时段 / 配额满 → 次日 HOUR_START」）。

### 5. `work/GreetingWorker.kt`（`doWork` 改为周期检查 + 目标时间投递）
逻辑：
1. 测试模式（`KEY_TEST`）→ `runTestGreeting` 不变。
2. `readGatingState`（带重试，沿用现有）。`null`（读不到）→ **直接 `success`**（PeriodicWork 下周期保活，**不再 `scheduleNext`**）。
3. `!enabled` → `success`（开关由 `ensureScheduled`/`reschedule` 负责 cancel）。
4. `provider != CLOUD` → `success`。
5. `charIds` 空 → `success`。
6. 不在时段 / `used >= dailyCount`：把 `next_fire_at` 更新为 `computeNextFireAt(now, settings)`（→ 次日 HOUR_START），`success`。
7. 在时段 + 有配额：读 `nextFire = getGreetingNextFireAtNow()`。若 `nextFire <= 0` 或异常过期 → 补算 `now + computeNextDelay(remaining)` 写入，`success`。若 `now < nextFire` → `success`（没到点）。
8. `now >= nextFire` → `pickCharacter` + `deliverGreeting`：
   - 成功：`withTimeoutOrNull(5s)` 写配额 `used+1` + `lastCharId`；写入 `next_fire_at = now + computeNextDelay(remaining-1)`。
   - 失败：写入 `next_fire_at = now + RETRY_DELAY_MS`（45min 退避，避免每 15min 反复失败重试）。
9. 始终 `success`（靠 PeriodicWork 周期保活，不靠 reschedule）。

- `pickCharacter` 保留「避免与上次相同」轮换（链可靠后多次触发，多角色自然轮换）；`lastCharId` 写入与配额合并进同一个 `withTimeoutOrNull` 块降低丢失。
- `runTestGreeting` 不变（始终弹通知预览）。

### 6. 调用点（语义保留，无需改逻辑）
- `RhodesApp.onCreate`：`ensureScheduled` 不变（内部已改 PeriodicWork）。
- `MainActivity`：通知 deep-link 处理不变。
- `SettingsScreen`：开关 / 角色选择 / 次数变更调 `reschedule`、关闭隐式 `cancel`、测试按钮调 `scheduleTest` —— 全部不变。

## 不改动
- `GreetingNotificationManager`、`AppLifecycleObserver`、`ChatViewModel`/`ChatScreen`、`MainActivity` deep-link 逻辑、`Character` 模型。
- 国产 ROM 极端省电（完全冻结后台）仍可能延迟——系统级限制，设置页已提示授权自启动，非代码可彻底解决；本方案把 WorkManager 机制用到最可靠的程度。

## 验证（`JAVA_HOME='D:/新建文件夹/jdk-21'`）
1. `./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache` 编译通过（记忆 [[gradle-kotlin-verify]]）。
2. `./gradlew :app:assembleDebug` 出包。
3. 手动验证（临时把 `HEARTBEAT_INTERVAL_MIN` 调小 / `next_fire_at` 设近）：开启→选 2 个角色→设次数→切云端→退出 App（划掉任务卡）→ 确认 15 分钟内 Worker 仍周期触发、多角色轮流发、通知弹出、点通知跳转会话、配额计数、前台抑制；关闭开关取消调度。
