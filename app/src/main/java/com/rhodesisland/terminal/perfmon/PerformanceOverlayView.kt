package com.rhodesisland.terminal.perfmon

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Performance monitoring overlay content view.
 *
 * Contains all the performance metric TextViews and ProgressBars. The background
 * glass effect is provided externally by [com.qmdeve.liquidglass.widget.LiquidGlassView]
 * which wraps this view as its child. This view itself has a transparent background.
 *
 * Collapsed state shows only a ⚡ icon; tap to expand, tap the ▼ button to collapse.
 */
class PerformanceOverlayView(context: Context) : FrameLayout(context) {

    private var isExpanded = false
    private var liquidGlassEnabled = true

    private val density = resources.displayMetrics.density

    // ---- UI elements ----
    private lateinit var contentLayout: LinearLayout
    private lateinit var titleBar: LinearLayout
    private lateinit var tvTokenRate: TextView
    private lateinit var tvCpuUsage: TextView
    private lateinit var cpuProgress: ProgressBar
    private lateinit var tvCpuFreq: TextView
    private lateinit var tvGpuUsage: TextView
    private lateinit var gpuProgress: ProgressBar
    private lateinit var tvNpuUsage: TextView
    private lateinit var npuProgress: ProgressBar
    private lateinit var tvTemp: TextView
    private lateinit var tempProgress: ProgressBar
    private lateinit var tvMemory: TextView
    private lateinit var memProgress: ProgressBar
    private lateinit var tvBackend: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnCollapse: ImageView
    private lateinit var expandedContainer: LinearLayout
    private lateinit var collapsedIcon: LinearLayout

    init {
        // On Android 13+ (TIRAMISU) the LiquidGlassView parent renders the refraction +
        // dispersion + blur glass effect over a transparent background. Below API 33 the
        // library has no effect (RuntimeShader / AGSL requires API 33), so fall back to a
        // plain semi-transparent panel so the metrics stay readable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setBackgroundColor(Color.TRANSPARENT)
        } else {
            setBackgroundColor(Color.parseColor("#990A0A0F"))
        }

        setupContent()
    }

    // ---- Content Setup ----

    private fun setupContent() {
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Tight padding so the glass panel ends just below the last ("等待推理") line.
            setPadding(dp(10), dp(7), dp(10), dp(5))
        }

        // Collapsed icon — tap to expand.
        // 注意：展开/折叠统一由外层 dragListener 的 ACTION_UP（点击判定）触发，这里**不**再挂
        // setOnClickListener——玻璃态 consume=false 时事件会继续分发给子 View，子点击 + 父监听
        // 同时 toggleExpansion() 会造成「展开后立即折叠」（无法展开）。dragListener 在两种面板态
        // （consume=false 玻璃 / consume=true 普通）下都收得到事件，是唯一可靠的触发路径。
        collapsedIcon = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val icon = TextView(context).apply {
                text = "⚡"
                textSize = 20f
            }
            addView(icon)
        }
        contentLayout.addView(collapsedIcon)

        // Title bar (hidden when collapsed)
        titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }

        val titleText = TextView(context).apply {
            text = "⚡ 性能监控"
            setTextColor(Color.parseColor("#7fc8ff"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        btnCollapse = ImageView(context).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setColorFilter(Color.parseColor("#888899"))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            // 同 collapsedIcon：收起也由外层 dragListener 触发，避免玻璃态双击/双 toggle。
        }
        titleBar.addView(btnCollapse)
        contentLayout.addView(titleBar)

        // Expanded content container (hidden when collapsed)
        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            visibility = GONE
        }

        // Backend label
        tvBackend = TextView(context).apply {
            setTextColor(Color.parseColor("#888899"))
            textSize = 9f
        }
        expandedContainer.addView(tvBackend)

        // Token rate (highlighted)
        val tokenRow = createHighlightRow("🚀 Token 速率")
        tvTokenRate = tokenRow.second
        expandedContainer.addView(tokenRow.first)

        expandedContainer.addView(createDivider())

        // CPU usage
        val cpuRow = createProgressBarRow("💻 CPU")
        tvCpuUsage = cpuRow.first
        cpuProgress = cpuRow.second
        expandedContainer.addView(cpuRow.third)

        // CPU frequency
        val freqRow = createTextRow("📈 大核频率")
        tvCpuFreq = freqRow.second
        expandedContainer.addView(freqRow.first)

        // GPU usage
        val gpuRow = createProgressBarRow("🎨 GPU")
        tvGpuUsage = gpuRow.first
        gpuProgress = gpuRow.second
        expandedContainer.addView(gpuRow.third)

        // NPU usage
        val npuRow = createProgressBarRow("🧠 NPU")
        tvNpuUsage = npuRow.first
        npuProgress = npuRow.second
        expandedContainer.addView(npuRow.third)

        // Temperature
        val tempRow = createProgressBarRow("🌡️ 温度")
        tvTemp = tempRow.first
        tempProgress = tempRow.second
        expandedContainer.addView(tempRow.third)

        // Memory
        val memRow = createProgressBarRow("💾 内存")
        tvMemory = memRow.first
        memProgress = memRow.second
        expandedContainer.addView(memRow.third)

        // Log area
        expandedContainer.addView(createDivider())
        tvLog = TextView(context).apply {
            text = "等待推理..."
            setTextColor(Color.parseColor("#aaaabb"))
            textSize = 8f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        expandedContainer.addView(tvLog)

        contentLayout.addView(expandedContainer)
        addView(contentLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    // ---- Public API ----

    /** Update displayed performance data */
    fun updateData(m: RealtimeMetrics) {
        // Token rate
        tvTokenRate.text = String.format("%.1f tok/s", m.tokenRate)
        tvTokenRate.setTextColor(when {
            m.tokenRate >= 25 -> Color.parseColor("#00ff88")
            m.tokenRate >= 12 -> Color.parseColor("#ffaa00")
            m.tokenRate > 0 -> Color.parseColor("#ff6666")
            else -> Color.parseColor("#666677")
        })

        // Backend
        tvBackend.text = "引擎: ${m.activeBackend.displayName}"

        // CPU
        tvCpuUsage.text = String.format("%.0f%%", m.cpuUsage)
        cpuProgress.progress = m.cpuUsage.toInt()
        cpuProgress.progressTintList = ColorStateList.valueOf(when {
            m.cpuUsage > 90 -> Color.parseColor("#ff4444")
            m.cpuUsage > 70 -> Color.parseColor("#ffaa00")
            else -> Color.parseColor("#5a9fff")
        })

        // CPU freq
        tvCpuFreq.text = if (m.cpuBigCoreFreqGHz > 0)
            String.format("%.2f GHz", m.cpuBigCoreFreqGHz) else "-"

        // GPU
        if (m.gpuUsage < 0) {
            tvGpuUsage.text = "N/A"
            tvGpuUsage.setTextColor(Color.parseColor("#666677"))
            gpuProgress.progress = 0
            gpuProgress.progressTintList = ColorStateList.valueOf(Color.parseColor("#333344"))
        } else {
            tvGpuUsage.text = String.format("%.0f%%", m.gpuUsage)
            tvGpuUsage.setTextColor(Color.parseColor("#ffffff"))
            gpuProgress.progress = m.gpuUsage.toInt()
            gpuProgress.progressTintList = ColorStateList.valueOf(
                if (m.activeBackend == BackendType.GPU)
                    Color.parseColor("#5a9fff") else Color.parseColor("#444455"))
        }

        // NPU
        if (m.npuUsage < 0) {
            tvNpuUsage.text = "N/A"
            tvNpuUsage.setTextColor(Color.parseColor("#666677"))
            npuProgress.progress = 0
            npuProgress.progressTintList = ColorStateList.valueOf(Color.parseColor("#333344"))
        } else {
            tvNpuUsage.text = String.format("%.0f%%", m.npuUsage)
            tvNpuUsage.setTextColor(Color.parseColor("#ffffff"))
            npuProgress.progress = m.npuUsage.toInt()
            npuProgress.progressTintList = ColorStateList.valueOf(
                if (m.activeBackend == BackendType.NPU)
                    Color.parseColor("#aa55ff") else Color.parseColor("#444455"))
        }

        // Temperature
        tvTemp.text = String.format("%.1f°C", m.temperatureC)
        val tempPercent = ((m.temperatureC / 80f) * 100).toInt().coerceIn(0, 100)
        tempProgress.progress = tempPercent
        tempProgress.progressTintList = ColorStateList.valueOf(when {
            m.temperatureC >= 55 -> Color.parseColor("#ff4444")
            m.temperatureC >= 45 -> Color.parseColor("#ffaa00")
            m.temperatureC >= 30 -> Color.parseColor("#ffdd44")
            else -> Color.parseColor("#00cc88")
        })

        // Memory
        tvMemory.text = "${m.usedMemoryMB}MB / ${m.totalMemoryMB}MB"
        memProgress.progress = if (m.totalMemoryMB > 0)
            ((m.usedMemoryMB.toFloat() / m.totalMemoryMB) * 100).toInt() else 0
        memProgress.progressTintList = ColorStateList.valueOf(when {
            m.totalMemoryMB > 0 && m.usedMemoryMB.toFloat() / m.totalMemoryMB > 0.85f
                -> Color.parseColor("#ff4444")
            else -> Color.parseColor("#5a9fff")
        })

        // Log
        tvLog.text = m.lastLog.take(60)
    }

    /** Toggle expanded / collapsed */
    fun toggleExpansion() {
        isExpanded = !isExpanded
        if (isExpanded) {
            titleBar.visibility = VISIBLE
            expandedContainer.visibility = VISIBLE
            collapsedIcon.visibility = GONE
        } else {
            titleBar.visibility = GONE
            expandedContainer.visibility = GONE
            collapsedIcon.visibility = VISIBLE
        }
        requestLayout()
    }

    /** Toggle liquid glass effect on/off */
    fun setLiquidGlassEnabled(enabled: Boolean) {
        liquidGlassEnabled = enabled
        // The LiquidGlassView parent handles the glass effect.
        // This flag is kept for potential future use (e.g. to tint text colors).
    }

    // ---- Internal helpers ----

    private fun createHighlightRow(label: String): Pair<View, TextView> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#7fc8ff"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(context).apply {
            text = "- tok/s"
            setTextColor(Color.parseColor("#00ff88"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        container.addView(labelTv)
        container.addView(valueTv)
        return Pair(container, valueTv)
    }

    private fun createProgressBarRow(label: String): Triple<TextView, ProgressBar, View> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#aaaabb"))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(context).apply {
            text = "-"
            setTextColor(Color.parseColor("#ffffff"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }
        labelRow.addView(labelTv)
        labelRow.addView(valueTv)
        container.addView(labelRow)
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.parseColor("#5a9fff"))
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333344"))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
                topMargin = dp(1)
            }
        }
        container.addView(progress)
        return Triple(valueTv, progress, container)
    }

    private fun createTextRow(label: String): Pair<View, TextView> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#aaaabb"))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(context).apply {
            text = "-"
            setTextColor(Color.parseColor("#ffffff"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        container.addView(labelTv)
        container.addView(valueTv)
        return Pair(container, valueTv)
    }

    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#333344"))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * density).toInt()
}
