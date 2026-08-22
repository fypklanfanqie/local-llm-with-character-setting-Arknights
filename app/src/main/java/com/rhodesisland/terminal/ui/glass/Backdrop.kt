package com.rhodesisland.terminal.ui.glass

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.util.Log
import com.rhodesisland.terminal.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// ===== 极光配色与光斑几何（与 MeshBackground 共享，保证全分辨率背景与低分辨率背板同相位） =====

/** 一个极光光斑：颜色 + 不透明度。圆心/半径按尺寸比例由 [meshBlobPositions] 动态计算。 */
internal data class MeshBlobSpec(val color: Color, val alpha: Float)

internal data class MeshSpec(val base: Color, val blobs: List<MeshBlobSpec>)

/** 亮/暗两套极光配色。暗色：PRTS 深藏青底 + 金/青光斑（透出玻璃后呈液态光感）；亮色保留原淡紫/冰蓝/粉。 */
internal fun meshSpec(dark: Boolean): MeshSpec =
    if (dark) MeshSpec(
        base = Color(0xFF0A0A0F),
        blobs = listOf(
            MeshBlobSpec(Color(0xFFC9A87C), 0.24f),  // 罗德岛金
            MeshBlobSpec(Color(0xFF4FA5A0), 0.22f),  // 青（PRTS 辅色）
            MeshBlobSpec(Color(0xFF2F6F8A), 0.26f),  // 深蓝
            MeshBlobSpec(Color(0xFF8A7355), 0.20f),  // 暗金
        ),
    ) else MeshSpec(
        base = Color(0xFFF2F3F8),
        blobs = listOf(
            MeshBlobSpec(Color(0xFF6E4DFF), 0.24f), // 淡紫
            MeshBlobSpec(Color(0xFF0A84FF), 0.18f), // 冰蓝
            MeshBlobSpec(Color(0xFFFF6B9D), 0.20f), // 粉
            MeshBlobSpec(Color(0xFF6E4DFF), 0.14f), // 淡紫（第二团，铺底）
        ),
    )

/** 光斑圆心/半径：相对尺寸比例 × 相位三角函数（历史算法保持原样，仅抽取共享）。 */
internal fun meshBlobPositions(phase: Float, w: Float, h: Float): List<Triple<Float, Float, Float>> {
    val a = phase * 2f * PI.toFloat()
    return listOf(
        Triple(w * (0.25f + 0.18f * sin(a)), h * (0.28f + 0.18f * cos(a)), w * 0.55f),
        Triple(w * (0.80f + 0.16f * sin(a + 2.1f)), h * (0.62f + 0.16f * cos(a + 2.1f)), w * 0.50f),
        Triple(w * (0.50f + 0.20f * sin(a + 4.2f)), h * (0.88f + 0.12f * cos(a + 4.2f)), w * 0.45f),
        Triple(w * (0.10f + 0.10f * sin(a + 1.0f)), h * (0.85f + 0.10f * cos(a + 1.0f)), w * 0.35f),
    )
}

// ===== 背板状态：共享给所有玻璃面板采样 =====

/**
 * 真实背景模糊的共享背板：持有「已预模糊的低分辨率极光位图」，玻璃面板在其 draw 阶段按自身位置采样。
 *
 * - [bitmap] 用 [mutableStateOf] 包住：组合期只读 holder 引用（不触发重组），draw 阶段读 [bitmap] 才失效绘制。
 * - 双缓冲：避免上一帧仍被绘制时原地改写位图。
 * - 全 API 24+ 一条路径：低分辨率 + BlurMaskFilter（CPU/GPU 皆可），无需 RenderEffect。
 */
class BackdropState {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var rootSize by mutableStateOf(IntSize.Zero)
    var hostComposeView: AbstractComposeView? = null
    val downscale: Float = 4f

    private var meshBitmap: Bitmap? = null
    private val blurBitmaps = arrayOfNulls<Bitmap>(2)
    private var frameIndex = 0
    private var cachedDark: Boolean? = null
    private var cachedSpec: MeshSpec? = null

    /** 玻璃面板所在窗口是否与本背板同窗口。Dialog / ModalBottomSheet 是独立 window，需回退半透明。 */
    fun isSameWindow(view: View): Boolean {
        val host = hostComposeView ?: return false
        var v: View? = view
        while (v != null) {
            if (v is AbstractComposeView) return v == host
            v = v.parent as? View
        }
        return false
    }

    /** 渲染一帧：先画清晰网格到 [meshBitmap]，再模糊 + 调饱和到当前帧模糊位图并发布。 */
    fun renderFrame(phase: Float, dark: Boolean, blurRadiusPx: Float, saturation: Float) {
        val size = rootSize
        if (size.width <= 0 || size.height <= 0) return
        val w = max(1, (size.width / downscale).roundToInt())
        val h = max(1, (size.height / downscale).roundToInt())
        if (meshBitmap == null || meshBitmap!!.width != w || meshBitmap!!.height != h) {
            meshBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            blurBitmaps[0] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            blurBitmaps[1] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameIndex = 0
        }
        val target = blurBitmaps[frameIndex]!!
        renderBackdrop(meshBitmap!!, target, specFor(dark), phase, blurRadiusPx, saturation)
        bitmap = target.asImageBitmap()
        frameIndex = 1 - frameIndex
    }

    private fun specFor(dark: Boolean): MeshSpec {
        if (cachedDark != dark) {
            cachedDark = dark
            cachedSpec = meshSpec(dark)
        }
        return cachedSpec!!
    }
}

val LocalBackdropState = staticCompositionLocalOf<BackdropState?> { null }

/**
 * 极光相位：由 [GlassBackdrop] 唯一动画源下发，MeshBackground 读它保证与背板同相位。
 *
 * 注意：这里下发的是**稳定 [State] 引用**而非每帧变化的 Float —— `staticCompositionLocalOf` 在提供值变化时
 * 会重组整个 content lambda，若直接把 Float 塞进去，22s 动画每帧（60fps）都会全应用重组，滑动必卡。
 * 下游一律在 draw 阶段读 `.value`，只失效绘制、不触发重组。
 */
val LocalMeshPhase = staticCompositionLocalOf<State<Float>?> { null }

// ===== 原生渲染：低分辨率清晰网格 → 模糊+调饱和位图 =====

private val BACKDROP_BLUR_RADIUS = 18.dp
private const val BACKDROP_SATURATION = 1.6f
private const val FRAME_INTERVAL_MILLIS = 33L // ~30fps 节流

private fun renderBackdrop(
    meshBitmap: Bitmap,
    blurBitmap: Bitmap,
    spec: MeshSpec,
    phase: Float,
    blurRadiusPx: Float,
    saturation: Float,
) {
    val w = meshBitmap.width.toFloat()
    val h = meshBitmap.height.toFloat()
    val positions = meshBlobPositions(phase, w, h)

    val meshCanvas = android.graphics.Canvas(meshBitmap)
    meshCanvas.drawColor(spec.base.toArgb())
    positions.forEachIndexed { i, (cx, cy, radius) ->
        val blob = spec.blobs[i]
        val argb = blob.color.copy(alpha = blob.alpha).toArgb()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, radius, argb, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        meshCanvas.drawRect(0f, 0f, w, h, p)
    }

    val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        colorFilter = ColorMatrixColorFilter(saturationMatrix(saturation))
    }
    val blurCanvas = android.graphics.Canvas(blurBitmap)
    blurCanvas.drawColor(android.graphics.Color.TRANSPARENT)
    blurCanvas.drawBitmap(meshBitmap, 0f, 0f, blurPaint)
}

/** 标准亮度感知饱和度矩阵。 */
private fun saturationMatrix(s: Float): FloatArray {
    val r = 0.2126f
    val g = 0.7152f
    val b = 0.0722f
    val i = 1f - s
    return floatArrayOf(
        r + (1f - r) * s, g * i, b * i, 0f, 0f,
        r * i, g + (1f - g) * s, b * i, 0f, 0f,
        r * i, g * i, b + (1f - b) * s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * 应用级背板容器：包住整个应用内容。
 *
 * - 唯一 [rememberInfiniteTransition]（22s 线性循环）提供 [LocalMeshPhase]，全分辨率 [MeshBackground] 与低分辨率背板同步。
 * - 后台 30fps 渲染模糊背板到 [BackdropState.bitmap]。
 * - 通过 [LocalBackdropState] 供 [frostedGlass]/[liquidGlass] 采样。
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = remember { BackdropState() }
    val dark = LocalDarkTheme.current
    val density = LocalDensity.current
    val view = LocalView.current
    // 模糊半径按 downscale 缩放：在 1/4 背板上直接用全分辨率半径会过度模糊且更贵，缩放到低分辨率等价半径。
    val blurRadiusPx = with(density) { BACKDROP_BLUR_RADIUS.toPx() } / state.downscale

    val transition = rememberInfiniteTransition(label = "mesh")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "meshPhase",
    ) // State<Float>：稳定引用，仅供后台渲染循环读取，不进重组路径

    // 记录背板所在窗口的根 ComposeView，供 isSameWindow 守卫（Dialog/Sheet 独立窗口）。
    LaunchedEffect(view) {
        var v: View? = view
        var host: AbstractComposeView? = null
        while (v != null) {
            if (v is AbstractComposeView) host = v
            v = v.parent as? View
        }
        state.hostComposeView = host
    }

    // 应用可见才渲染，避免退到后台空转耗电。
    var isVisible by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isVisible = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ~30fps 后台渲染循环：CPU 位图模糊不占主线程，滚动时不抢帧。
    // renderFrame 包 runCatching：低内存机型 createBitmap/BlurMaskFilter 可能抛 OOM/
    // IllegalStateException——失败只跳过本帧（玻璃面板回退半透明），绝不让渲染循环带崩进程。
    LaunchedEffect(state, dark, blurRadiusPx) {
        withContext(Dispatchers.Default) {
            while (isActive) {
                if (isVisible) {
                    runCatching {
                        state.renderFrame(phase.value, dark, blurRadiusPx, BACKDROP_SATURATION)
                    }.onFailure { Log.w("GlassBackdrop", "renderFrame failed: ${it.message}") }
                }
                delay(FRAME_INTERVAL_MILLIS)
            }
        }
    }

    CompositionLocalProvider(
        LocalMeshPhase provides phase,
        LocalBackdropState provides state,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { state.rootSize = it },
        ) {
            content()
        }
    }
}
