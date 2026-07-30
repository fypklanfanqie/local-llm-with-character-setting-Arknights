package com.rhodesisland.terminal

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.rhodesisland.terminal.notification.GreetingNotificationManager
import com.rhodesisland.terminal.ui.LoadingScreen
import com.rhodesisland.terminal.ui.navigation.AppNavGraph
import com.rhodesisland.terminal.ui.theme.PrtsColors
import com.rhodesisland.terminal.ui.theme.RhodesIslandTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge() 需要 API 29+（Android 10+）；API 24-28 跳过，兼容旧设备及华为 EMUI 9 等魔改 ROM。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enableEdgeToEdge()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 适配高刷新率（120Hz）：请求当前分辨率下刷新率最高的显示模式，让键盘上移/转场等动画跑满帧。
        // 系统在支持的设备上会启用高刷新率；不支持则 no-op（取到当前模式即跳过）。
        requestHighRefreshRate()

        val app = application as RhodesApp

        // 角色问候通知点按跳转：把目标角色/会话写入设置，ChatViewModel 的 collector 自动切换。
        // 冷启动在 setContent 前写入，配合启动 Loading 画面让 DataStore 写入完成，避免先闪默认角色。
        handleGreetingIntent(intent, app)

        // CPU 提频（非 root 路线）：SustainedPerformanceMode 跟随设置开关 `llmCpuBoost`。
        // 窗口级持续高性能模式，抗热降频；与 CpuBoostController 的 PerformanceHintManager hint session
        // + 推理线程高优先级叠加，把推理时大核/超核频率尽量推向最高频。非 root 无法锁满频。
        // 部分设备/窗口不支持 setSustainedPerformanceMode 时静默 no-op。
        lifecycleScope.launch {
            app.container.settingsRepository.llmCpuBoost.collect { enabled ->
                runCatching { window.setSustainedPerformanceMode(enabled) }
                    .onFailure { android.util.Log.w("MainActivity", "setSustainedPerformanceMode($enabled) failed: ${it.message}") }
                    .onSuccess { android.util.Log.i("MainActivity", "SustainedPerformanceMode=$enabled") }
            }
        }

        setContent {
            RhodesIslandTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(PrtsColors.BgPrimary),
                    color = PrtsColors.BgPrimary,
                ) {
                    var showLoading by remember { mutableStateOf(true) }

                    Box(Modifier.fillMaxSize()) {
                        // 启动 Loading 画面，结束后淡出
                        AnimatedVisibility(
                            visible = showLoading,
                            enter = fadeIn(),
                            exit = fadeOut(animationSpec = tween(500)),
                        ) {
                            LoadingScreen(onFinished = { showLoading = false })
                        }

                        // 主应用，Loading 结束后淡入
                        AnimatedVisibility(
                            visible = !showLoading,
                            enter = fadeIn(animationSpec = tween(600)),
                            exit = fadeOut(),
                        ) {
                            AppNavGraph(container = app.container)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGreetingIntent(intent, application as RhodesApp)
    }

    /**
     * 角色问候通知点按跳转：从通知 PendingIntent 的 extra 取目标角色/会话，写入设置。
     * ChatViewModel 的 activeCharacter / activeConversations collector 自动切换到对应会话。
     */
    private fun handleGreetingIntent(intent: Intent?, app: RhodesApp) {
        val charId = intent?.getStringExtra(GreetingNotificationManager.EXTRA_CHARACTER_ID) ?: return
        val convId = intent.getLongExtra(GreetingNotificationManager.EXTRA_CONVERSATION_ID, -1L)
        // 消费后清除 extra，避免重复触发
        intent.removeExtra(GreetingNotificationManager.EXTRA_CHARACTER_ID)
        intent.removeExtra(GreetingNotificationManager.EXTRA_CONVERSATION_ID)
        lifecycleScope.launch {
            app.container.settingsRepository.setActiveCharacter(charId)
            if (convId > 0) {
                app.container.settingsRepository.setActiveConversation(charId, convId)
            }
        }
    }

    /**
     * 请求当前分辨率下刷新率最高的显示模式（如 120Hz）。
     *
     * Android 默认可能跑 60Hz，导致 IME 上移 / 转场等动画帧率受限。设置
     * [android.view.WindowManager.LayoutParams.preferredDisplayModeId] 为最高刷新率模式后，
     * 支持的设备会启用高刷新率，动画即可跑满帧；不支持 120Hz 的设备取到的最高模式即当前模式，跳过。
     * 仅筛同分辨率模式，避免触发分辨率切换。
     */
    private fun requestHighRefreshRate() {
        // decorView 在 onResume 才 attach 到 display，onCreate 里取不到 -> 用 WindowManager.defaultDisplay
        // （API 30 起标记 deprecated，但 onCreate 阶段仍是最可靠拿到 Display 的方式，单屏手机即本屏）。
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.modeId == current.modeId) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }
}
