package com.rhodesisland.terminal.perfmon

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import com.qmdeve.liquidglass.widget.LiquidGlassView
import com.rhodesisland.terminal.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 应用内液态玻璃性能浮窗（真折射，折射全部聊天内容）。
 *
 * 库的 `record()` 把**绑定的源 View** 画进 RenderNode 再上折射/色散 shader。要折射「全部界面内容」
 * （背景图 + 消息气泡 + 顶栏），源必须是承载全部内容的根 ComposeView。但玻璃**不能在源内部**：
 * record() 画源时会画到玻璃 -> 玻璃的 `impl.draw` 把 node 画进 node 自己的 RecordingCanvas ->
 * `drawRenderNode` 抛 IllegalArgumentException（Cannot draw a RenderNode into its own recording canvas）。
 *
 * 解法：把玻璃作为 ComposeView 的**兄弟**加到 Activity 内容层（根 ComposeView 的 parent），`bind(根 ComposeView)`
 * -> 源是 ComposeView、玻璃不在其中 -> record() 不画玻璃、不递归、折射全部聊天内容。玻璃是小面板，
 * 只占自身区域，不挡其余触摸；metrics 作为子 View 浮在折射之上。
 *
 * 注意：`LocalView.current` 返回内部 `AndroidComposeView`，其 parent 才是公开 `ComposeView`。故不能直接用
 * `rootView.parent` 当面板父容器（那会加进 ComposeView，触发 `Cannot add views to ComposeView`）；
 * 需 [findRootComposeHost] 上溯到根 ComposeView，面板加到它的 parent。
 *
 * 高度：`LiquidGlassView.onMeasure` 用 `getDefaultSize`、不按子 View 包裹（反编译确认），WRAP_CONTENT
 * 下会铺满父尺寸。故需 [fitGlassToContent] 显式按 metrics 测量结果设置玻璃 LayoutParams 为定值
 * （EXACTLY spec 才被 getDefaultSize 采纳），并在展开/折叠、数据更新时重新 fit，止于「等待推理」下方。
 *
 * [liquidGlassEnabled] = false 或 <API33 时回退普通半透明圆角面板（FrameLayout 天然 wrap，无玻璃、无 record 开销）。
 */
@Composable
fun PerformanceGlassOverlay(
    container: AppContainer,
    liquidGlassEnabled: Boolean,
) {
    val rootView = LocalView.current          // 内部 AndroidComposeView；其 parent 才是根 ComposeView
    val collector = container.performanceCollector
    val refs = remember { OverlayRefs() }

    // 采集循环：500ms 一次，sysfs 读取放 IO 线程，回主线程更新 UI；更新后重 fit 玻璃尺寸（值变宽时跟随）。
    LaunchedEffect(Unit) {
        while (isActive) {
            val metrics = withContext(Dispatchers.IO) { runCatching { collector.collect() }.getOrNull() }
            if (metrics != null) {
                refs.metrics?.updateData(metrics)
                refs.glass?.post { refs.glass?.let { g -> refs.metrics?.let { m -> fitGlassToContent(g, m) } } }
            }
            delay(REFRESH_MS)
        }
    }

    // 把玻璃作为根 ComposeView 的兄弟加到内容层；离开本地聊天/切换开关时移除。
    // rootView(LocalView.current) 是内部 AndroidComposeView，其 parent 才是公开 ComposeView(AbstractComposeView)。
    // 不能把面板加进 ComposeView——AbstractComposeView 重写 addView 抛
    // UnsupportedOperationException("Cannot add views to ComposeView")。故上溯到根 ComposeView 作折射源，
    // 面板加到它的 parent(Activity 内容层)做兄弟：record(源) 折射全部聊天内容、不画面板、不递归、不崩。
    DisposableEffect(liquidGlassEnabled) {
        val source = findRootComposeHost(rootView) ?: rootView
        val panelParent = source.parent as? ViewGroup
        val ctx = rootView.context
        if (panelParent == null || panelParent is AbstractComposeView) {
            onDispose { }
        } else {
            val panel = createPanel(ctx, source, liquidGlassEnabled, refs)
            // WRAP_CONTENT 给容器初始态；fitGlassToContent 会把玻璃改成定值（按 metrics 尺寸）。
            panelParent.addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            onDispose {
                runCatching { panelParent.removeView(panel) }
                refs.metrics = null
                refs.glass = null
            }
        }
    }
}

/**
 * 从 [start] 向上找到最顶层的 [AbstractComposeView]（承载全部内容的根 ComposeView）。
 *
 * `LocalView.current` 在本 Compose 版本返回内部 `AndroidComposeView`，其 parent 才是公开 `ComposeView`。
 * 直接拿 `rootView.parent` 当面板父容器会把面板加进 `ComposeView`——`AbstractComposeView` 重写 `addView`
 * 抛 `UnsupportedOperationException("Cannot add views to ComposeView")`。故需上溯到根 ComposeView 作折射源，
 * 面板再加到它的 parent。取最顶层以兼容 ComposeView 经 AndroidView 嵌套的场景。
 */
private fun findRootComposeHost(start: View): AbstractComposeView? {
    var v: View? = start
    var host: AbstractComposeView? = null
    while (v != null) {
        if (v is AbstractComposeView) host = v
        v = v.parent as? View
    }
    return host
}

/** 创建玻璃面板或普通面板，绑定 [source]（根 ComposeView）做全内容折射。 */
private fun createPanel(
    ctx: Context,
    source: View,
    liquidGlassEnabled: Boolean,
    refs: OverlayRefs,
): View {
    val metrics = PerformanceOverlayView(ctx).also { refs.metrics = it }
    // 尝试创建 LiquidGlassView（需 API 33+）。原生库在部分 GPU（老旧 Mali/PowerVR/Adreno 5xx）
    // 上可能初始化失败抛异常；捕获后静默回退到普通圆角面板，不影响聊天主体。
    if (liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            LiquidGlassView(ctx).also { glass ->
                refs.glass = glass
                // bind 先于任何 setter；源是兄弟(ComposeView)、不含玻璃 -> record() 不递归、不崩。
                glass.bind(source as ViewGroup)
                glass.addView(metrics)
                glass.translationX = dp(ctx, 16).toFloat()
                glass.translationY = dp(ctx, 120).toFloat()
                // 拖动 return false -> 让 LiquidGlassView.onTouchEvent 跑 touchEffect 发光；
                // 点击展开/折叠后重 fit 玻璃尺寸。
                glass.setOnTouchListener(
                    dragListener(glass, metrics, false, ctx) {
                        glass.post { fitGlassToContent(glass, metrics) }
                    },
                )
                glass.post {
                    runCatching {
                        glass.setTouchEffectEnabled(true)
                        glass.setCornerRadius(dp(ctx, 28).toFloat())
                        glass.setRefractionHeight(dp(ctx, 14).toFloat())
                        glass.setRefractionOffset(dp(ctx, 60).toFloat())
                        glass.setBlurRadius(2.5f)
                        glass.setDispersion(0.4f)
                        // 淡冷色 tint：压底保文字可读，玻璃仍可见
                        glass.setTintColorRed(0.05f)
                        glass.setTintColorGreen(0.07f)
                        glass.setTintColorBlue(0.12f)
                        glass.setTintAlpha(0.10f)
                    }
                    fitGlassToContent(glass, metrics)
                }
                return glass
            }
        } catch (e: Exception) {
            Log.w(TAG, "LiquidGlassView 创建失败（GPU 不兼容？），回退普通面板", e)
            // 继续走下方普通面板逻辑
        }
    }
    // 普通面板：FrameLayout 天然 wrap 内容，无需 fit；圆角深底、无玻璃、无 record 开销。
    return FrameLayout(ctx).also { panel ->
        panel.background = roundedDarkDrawable(ctx)
        panel.addView(metrics)
        panel.translationX = dp(ctx, 16).toFloat()
        panel.translationY = dp(ctx, 120).toFloat()
        panel.setOnTouchListener(dragListener(panel, metrics, true, ctx) {})
    }
}

/**
 * 把 [glass] 的 LayoutParams 宽高设为 [metrics] 的测量尺寸。
 *
 * 必要性：[LiquidGlassView.onMeasure] 用 `getDefaultSize`，WRAP_CONTENT(AT_MOST) 下返回父尺寸而非子内容尺寸，
 * 玻璃会铺满全屏。改成定值后 spec 变 EXACTLY，getDefaultSize 才采纳 -> 玻璃按指标内容包裹，止于「等待推理」下方。
 * 用 UNSPECIFIED 测 [metrics] 拿其自然内容尺寸。
 */
private fun fitGlassToContent(glass: LiquidGlassView, metrics: PerformanceOverlayView) {
    val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    metrics.measure(spec, spec)
    val w = metrics.measuredWidth.coerceAtLeast(1)
    val h = metrics.measuredHeight.coerceAtLeast(1)
    val lp = glass.layoutParams ?: ViewGroup.LayoutParams(w, h)
    if (lp.width != w || lp.height != h) {
        lp.width = w
        lp.height = h
        glass.layoutParams = lp
    }
}

/**
 * 拖动 + 点击展开/折叠 监听。
 *
 * [consume] = false（玻璃态）：return false 让 [LiquidGlassView.onTouchEvent] 接管事件流以触发
 * touchEffect 发光；拖动作为副作用做。[consume] = true（普通面板）：无发光，必须消费事件流否则收不到后续 MOVE/UP。
 * [onToggle] 在点击展开/折叠后调用（玻璃态用它重 fit 尺寸）。
 */
private fun dragListener(
    panel: View,
    metrics: PerformanceOverlayView,
    consume: Boolean,
    ctx: Context,
    onToggle: () -> Unit,
): View.OnTouchListener {
    var initialTx = 0f
    var initialTy = 0f
    var initialTouchX = 0f
    var initialTouchY = 0f
    var isDragging = false
    var downTime = 0L
    val clickTimeout = 200L
    val dragThreshold = dp(ctx, 10).toFloat()

    return View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTx = panel.translationX
                initialTy = panel.translationY
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                downTime = System.currentTimeMillis()
                isDragging = false
                consume
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > dragThreshold || abs(dy) > dragThreshold) isDragging = true
                if (isDragging) {
                    panel.translationX = initialTx + dx
                    panel.translationY = initialTy + dy
                }
                consume
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && System.currentTimeMillis() - downTime < clickTimeout) {
                    metrics.toggleExpansion()
                    onToggle()
                }
                consume
            }
            else -> consume
        }
    }
}

private fun roundedDarkDrawable(ctx: Context): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(ctx, 20).toFloat()
        setColor(Color.parseColor("#CC0A0A0F"))
    }

private fun dp(ctx: Context, value: Int): Int =
    (value * ctx.resources.displayMetrics.density).toInt()

/** 跨 DisposableEffect/LaunchedEffect 共享指标/玻璃 View 引用（非 State：effect 中写它不触发重组）。 */
private class OverlayRefs {
    var metrics: PerformanceOverlayView? = null
    var glass: LiquidGlassView? = null
}

private const val REFRESH_MS = 500L
private const val TAG = "PerformanceGlass"
