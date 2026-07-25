package com.rhodesisland.terminal.perfmon

/**
 * Configuration for the liquid glass effect, matching the parameters
 * from [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView).
 *
 * All dimension values are in pixels.
 */
data class LiquidGlassConfig(
    /** Rounded corner radius in pixels */
    var cornerRadiusPx: Float = 28f,
    /** Width/height of the refractive edge zone in pixels */
    var refractionHeight: Float = 20f,
    /** Offset amount of the refraction displacement */
    var refractionOffset: Float = -70f,
    /** Depth effect strength: blend between shape-gradient and radial-gradient for refraction direction */
    var depthEffect: Float = 0.3f,
    /** Chromatic dispersion intensity (0 = none, 1 = max) */
    var dispersion: Float = 0.5f,
    /** Contrast adjustment: 0 = neutral, positive = more contrast, negative = less */
    var contrast: Float = 0f,
    /** White point shift: positive = brighten, negative = darken */
    var whitePoint: Float = 0f,
    /** Chroma/saturation multiplier: 1 = neutral, 0 = grayscale, >1 = oversaturated */
    var chromaMultiplier: Float = 1f,
    /** Blur radius in pixels */
    var blurRadius: Float = 24f,
    /** Tint color alpha (0 = no tint, 1 = fully tinted) */
    var tintAlpha: Float = 0f,
    /** Tint color red component (0..1) */
    var tintColorRed: Float = 1f,
    /** Tint color green component (0..1) */
    var tintColorGreen: Float = 1f,
    /** Tint color blue component (0..1) */
    var tintColorBlue: Float = 1f,
    /** View width in pixels (set at layout time) */
    var width: Int = 0,
    /** View height in pixels (set at layout time) */
    var height: Int = 0,
)
