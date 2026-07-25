# 性能浮窗 改为应用内液态玻璃（真折射）

## 背景（上一轮失败根因）
- 玻璃没生效：QmDeve 库 `record()` 是把**绑定的源 View**画进 RenderNode 再上折射 shader（反编译确认）。系统悬浮窗背后是别的 App，**没有源 View**；旧代码把指标 View 既当子 View 又 `bind` 成源 -> 折射的是指标自己，看不到玻璃。仓库 demo 能跑是因为玻璃在 Activity 内、绑定同窗口的背景兄弟 View。
- 高度没生效：`LiquidGlassView.onMeasure` 用 `getDefaultSize`，系统浮窗 `WRAP_CONTENT` 下取窗口默认尺寸，**不随内容** -> 压 padding/进度条对窗口高度无效。
- 反编译还确认：`ensureGlass()` 把内部 `LiquidGlass`(调 `impl.draw` 画折射结果) 加为子 View，`bind()` 设其为 target。所以**源不能包含玻璃**(否则 `record()` 把玻璃画进自己的 node -> 递归)。

## 方案：应用内浮层 + 镜像背景源（真折射）

把浮窗从 WindowManager 系统悬浮窗改为 ChatScreen 内的 Compose 浮层。`LiquidGlassView` 绑定一个**镜像背景源的 INVISIBLE 兄弟 View**：它只在 `record()` 时被画进 node、不上屏，与玻璃互不包含(不递归)，每帧 `record()` 只画一张图(便宜)。

### 源视图（镜像聊天背景）
- `FrameLayout`：[ `ImageView`(Coil 加载 `getBackground(bgIndex)`) + scrim `View`(BgPrimary α0.8) ]，`fillMaxSize` + `statusBarsPadding`，与 `ChatBackground` 同位置同尺寸。
- `visibility = INVISIBLE`（不上屏，但 `record()` 直接调 `target.draw()` 仍能画）。
- 与 `LiquidGlassView` 是兄弟 -> 不递归。
- 背景索引同步：把 `bgIndex` 状态提到 `ChatScreen`，`ChatBackground` 和源都用同一个 `getBackground(bgIndex)`。

### 玻璃面板
- `LiquidGlassView`（AndroidView），WRAP_CONTENT，初始 top-start，可拖动。
- 子 View = `PerformanceOverlayView`（指标，已精简）。
- `bind(镜像源)` 在 attach 后（保留 bind 先于 setter 的坑）。
- 参数（已调）：cornerRadius 28 / refractionHeight 14 / refractionOffset 60 / blur 2.5 / dispersion 0.4 / tint 淡冷色 α0.10。保留 `setTouchEffectEnabled(true)`。
- <API33 库无效果 -> `PerformanceOverlayView` 自带深底回退。

### 高度
- 应用内 WRAP_CONTENT 真正按内容包裹（不再取窗口默认尺寸）-> 止于「等待推理」下方一点。`PerformanceOverlayView` 已压紧 padding(10,7,10,5)/进度条(2dp)/行距(1dp)/log take(60)。

### 数据流
- `PerformanceCollector` 保留。采集循环移入 `PerfOverlay` 的 `LaunchedEffect`(500ms)：后台 `collect()` -> 主线程 `updateData`。

### 拖动
- Compose `pointerInput` 检测拖动 -> 更新 offset 状态 -> 应用到 `LiquidGlassView` 的 `translationX/Y`。短按(未拖)切换展开/折叠。源(INVISIBLE)不挡触摸，其余区域透传给消息列表。

### liquidGlass 开关（保留设置）
- =true：用 `LiquidGlassView`(玻璃) 绑定镜像源。
- =false：直接渲染 `PerformanceOverlayView`(深底圆角面板，无玻璃)。
- `PerfOverlay` 据此分支。

## 文件改动
1. **新建 `PerformanceGlassOverlay.kt`**：`@Composable PerfOverlay(container, bgIndex, liquidGlassEnabled)`，封装镜像源 + 玻璃 + 采集循环 + 拖动 + 展开/折叠。
2. **改 `ChatScreen.kt`**：
   - 提取 `var bgIndex by remember` + 8s 轮播 `LaunchedEffect`，传给 `ChatBackground` 和 `PerfOverlay`。
   - 删除 WindowManager overlay 逻辑 + `OverlayPermissionHelper` 权限流程（应用内不需要）。
   - 仅 `isLocal` 时 compose `PerfOverlay`。
3. **改 `ChatBackground`**：签名加 `bgIndex: Int` 参数，不再自管 index/轮播。
4. **删 `PerformanceOverlayManager.kt`**：WindowManager 路径废弃，逻辑移入 `PerfOverlay`。
5. **改 `AppContainer.kt`**：移除 `performanceOverlay`/`overlayInstance`；保留 `performanceCollector`；`init` 里 `setLiquidGlassEnabled` 推送删除（`PerfOverlay` 直接读设置流或由 ChatScreen 传入）。
6. **保留** `PerformanceOverlayView.kt`、`PerformanceCollector.kt`；`OverlayPermissionHelper.kt` 留作 unused（或删）。Manifest 的 `SYSTEM_ALERT_WINDOW` 权限留（无害）。
7. **死代码** `LiquidGlassConfig/Drawable/Renderer.kt` 本次仍不动。

## 验证
- `JAVA_HOME='D:/新建文件夹/jdk-21' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache`。
- 装机：玻璃折射/色散可见（折射聊天背景）、高度止于「等待推理」下方、拖动+展开折叠正常、流式输出时不卡。

## 风险
- 镜像源 Coil 加载背景图有延迟 -> 玻璃短暂折射空内容；源 ImageView 先填占位色兜底。
- Compose/View 触摸互操作：确保源(INVISIBLE)不挡触摸、玻璃可拖、其余透传（用 `pointerInput` 消费玻璃区，Box 不加 clickable）。
- `record()` 每帧跑(PreDrawListener 在源所在树)，但源只画一张图+scrim -> 便宜。
