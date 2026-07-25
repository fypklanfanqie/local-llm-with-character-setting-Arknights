package com.rhodesisland.terminal.perfmon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import com.rhodesisland.terminal.R
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Core liquid glass renderer using AGSL [RuntimeShader] + [RenderEffect] chain,
 * matching the approach from [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView).
 *
 * Renders a procedural "frosted glass" effect with:
 *   - SDF-based edge refraction (light-bending at rounded corners)
 *   - 7-sample chromatic dispersion (rainbow-like color separation at edges)
 *   - Gaussian blur (frosted look)
 *   - Contrast / white-point / chroma / tint controls
 *
 * Unlike the library, we do NOT capture the Activity's view tree via [RenderNode]
 * (which causes SIGSEGV on many Qualcomm Adreno GPUs due to circular GPU pipeline
 * conflicts when the overlay is inside the same view hierarchy it tries to record).
 * Instead, we draw a procedural backplate into the RenderNode — the shader refracts
 * and disperses this backplate, producing a convincing liquid glass edge effect.
 *
 * Requires API 33+ ([Build.VERSION_CODES.TIRAMISU]).
 */
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
class LiquidGlassRenderer(
    private val host: View,
    val config: LiquidGlassConfig = LiquidGlassConfig(),
) {
    private val node: RenderNode = RenderNode("LiquidGlassRenderer")
    private val liquidShader: RuntimeShader
    private var cachedBlurEffect: RenderEffect? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cached uniform values for change detection
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastCornerRadius = Float.NaN
    private var lastRefractionHeight = Float.NaN
    private var lastRefractionOffset = Float.NaN
    private var lastDepthEffect = Float.NaN
    private var lastDispersion = Float.NaN
    private var lastContrast = Float.NaN
    private var lastWhitePoint = Float.NaN
    private var lastChromaMultiplier = Float.NaN
    private var lastBlurRadius = Float.NaN
    private var lastTintAlpha = Float.NaN
    private var lastTintRed = Float.NaN
    private var lastTintGreen = Float.NaN
    private var lastTintBlue = Float.NaN
    private var lastSigma = Float.NaN
    private var lastBlurUpdateTime = 0L

    private var needsUpdate = true
    private var sizeKnown = false

    init {
        liquidShader = loadAgsl(host.resources)
    }

    // ---- Public API ----

    fun onSizeChanged(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        node.setPosition(0, 0, w, h)
        config.width = w
        config.height = h
        sizeKnown = true
        // Do NOT record or apply effects here — layout is still in progress.
        // Recording happens in onPreDraw().
    }

    fun onPreDraw() {
        if (!sizeKnown) return
        record()

        val paramsChanged =
            lastWidth != config.width ||
            lastHeight != config.height ||
            lastCornerRadius != config.cornerRadiusPx ||
            lastRefractionHeight != config.refractionHeight ||
            lastRefractionOffset != config.refractionOffset ||
            lastDepthEffect != config.depthEffect ||
            lastDispersion != config.dispersion ||
            lastContrast != config.contrast ||
            lastWhitePoint != config.whitePoint ||
            lastChromaMultiplier != config.chromaMultiplier ||
            lastBlurRadius != config.blurRadius ||
            lastTintAlpha != config.tintAlpha ||
            lastTintRed != config.tintColorRed ||
            lastTintGreen != config.tintColorGreen ||
            lastTintBlue != config.tintColorBlue ||
            needsUpdate

        if (paramsChanged) {
            lastWidth = config.width
            lastHeight = config.height
            lastCornerRadius = config.cornerRadiusPx
            lastRefractionHeight = config.refractionHeight
            lastRefractionOffset = config.refractionOffset
            lastDepthEffect = config.depthEffect
            lastDispersion = config.dispersion
            lastContrast = config.contrast
            lastWhitePoint = config.whitePoint
            lastChromaMultiplier = config.chromaMultiplier
            lastBlurRadius = config.blurRadius
            lastTintAlpha = config.tintAlpha
            lastTintRed = config.tintColorRed
            lastTintGreen = config.tintColorGreen
            lastTintBlue = config.tintColorBlue
            needsUpdate = false
            applyRenderEffect()
        }
    }

    fun draw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) return
        canvas.drawRenderNode(node)
    }

    fun dispose() {
        node.discardDisplayList()
        sizeKnown = false
    }

    fun invalidateConfig() {
        needsUpdate = true
    }

    // ---- Internal ----

    /**
     * Records a procedural backplate into the [RenderNode].
     *
     * We draw a soft multi-stop gradient that simulates ambient light passing
     * through the glass pane. The AGSL shader refracts and disperses this at
     * the edges; the blur pass frosts the result. Together they produce the
     * liquid glass look without needing to capture the Activity's actual view
     * tree (which crashes on many Adreno GPUs when called from a child view).
     */
    private fun record() {
        val w = host.width
        val h = host.height
        if (w == 0 || h == 0) return

        // Rebuild the background paint each recording so it matches current size
        val cornerRadius = config.cornerRadiusPx.coerceAtMost(h / 2f)
        val gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(0xFF, 0x1E, 0x2A, 0x44), // dark blue-grey top
                Color.argb(0xFF, 0x18, 0x22, 0x38), // mid-dark
                Color.argb(0xFF, 0x10, 0x18, 0x2C), // darker
                Color.argb(0xFF, 0x0C, 0x12, 0x22), // darkest bottom
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1.0f),
            Shader.TileMode.CLAMP,
        )
        bgPaint.shader = gradient

        val rec = node.beginRecording(w, h)
        rec.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), cornerRadius, cornerRadius, bgPaint)
        node.endRecording()
    }

    /**
     * Builds and applies the [RenderEffect] chain:
     * RuntimeShader (refraction + dispersion) → BlurEffect.
     */
    private fun applyRenderEffect() {
        val w = config.width
        val h = config.height
        if (w == 0 || h == 0) return

        val cornerRadiusPx = config.cornerRadiusPx
        val refractionHeight = config.refractionHeight
        val refractionOffset = config.refractionOffset
        val depthEffect = config.depthEffect
        val dispersion = config.dispersion
        val contrast = config.contrast
        val whitePoint = config.whitePoint
        val chromaMultiplier = config.chromaMultiplier
        val blurRadius = config.blurRadius.coerceAtLeast(0f)
        val tintRed = config.tintColorRed
        val tintGreen = config.tintColorGreen
        val tintBlue = config.tintColorBlue
        val tintAlpha = config.tintAlpha

        val size = floatArrayOf(w.toFloat(), h.toFloat())
        val offset = floatArrayOf(0f, 0f)
        val cornerRadii = floatArrayOf(cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx)

        // Blur effect (cached — only recreate when radius changes significantly)
        var contentEffect: RenderEffect? = null
        if (blurRadius > 0.01f) {
            val now = System.currentTimeMillis()
            if (cachedBlurEffect == null ||
                kotlin.math.abs(blurRadius - lastSigma) > 0.3f ||
                now - lastBlurUpdateTime > 120
            ) {
                try {
                    contentEffect = RenderEffect.createBlurEffect(
                        blurRadius, blurRadius, Shader.TileMode.CLAMP
                    )
                    cachedBlurEffect = contentEffect
                    lastSigma = blurRadius
                    lastBlurUpdateTime = now
                } catch (e: Exception) {
                    Log.w(TAG, "createBlurEffect failed, reusing cached", e)
                    contentEffect = cachedBlurEffect
                }
            } else {
                contentEffect = cachedBlurEffect
            }
        }

        // Configure the AGSL shader uniforms
        try {
            liquidShader.setFloatUniform("size", size)
            liquidShader.setFloatUniform("offset", offset)
            liquidShader.setFloatUniform("cornerRadii", cornerRadii)
            liquidShader.setFloatUniform("refractionHeight", refractionHeight)
            liquidShader.setFloatUniform("refractionAmount", refractionOffset)
            liquidShader.setFloatUniform("depthEffect", depthEffect)
            liquidShader.setFloatUniform("chromaticAberration", dispersion)
            liquidShader.setFloatUniform("contrast", contrast)
            liquidShader.setFloatUniform("whitePoint", whitePoint)
            liquidShader.setFloatUniform("chromaMultiplier", chromaMultiplier)
            liquidShader.setFloatUniform("tintColor", floatArrayOf(tintRed, tintGreen, tintBlue))
            liquidShader.setFloatUniform("tintAlpha", tintAlpha)
        } catch (e: Exception) {
            Log.w(TAG, "setFloatUniform failed — shader uniforms may not match", e)
            return
        }

        val shaderEffect = RenderEffect.createRuntimeShaderEffect(liquidShader, "content")
        val finalEffect = if (contentEffect != null) {
            RenderEffect.createChainEffect(shaderEffect, contentEffect)
        } else {
            shaderEffect
        }

        node.setRenderEffect(finalEffect)
    }

    private fun loadAgsl(resources: android.content.res.Resources): RuntimeShader {
        // This legacy v3/v4 renderer is unused (the overlay now uses QmDeve LiquidGlassView).
        // Point at the library's bundled shader (the app no longer declares its own raw copy).
        val shaderCode = loadRaw(resources, com.qmdeve.liquidglass.R.raw.liquidglass_effect)
        return RuntimeShader(shaderCode)
    }

    private fun loadRaw(resources: android.content.res.Resources, resourceId: Int): String {
        val sb = StringBuilder()
        try {
            val inputStream = resources.openRawResource(resourceId)
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.use { r ->
                var line: String? = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Error loading shader resource: $resourceId", e)
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "LiquidGlassRenderer"
    }
}
