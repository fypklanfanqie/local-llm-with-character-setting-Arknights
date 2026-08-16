package com.rhodesisland.terminal

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.rhodesisland.terminal.notification.GreetingNotificationManager
import com.rhodesisland.terminal.notification.GroupChatNotificationManager
import com.rhodesisland.terminal.ui.LoadingScreen
import com.rhodesisland.terminal.ui.glass.GlassBackdrop
import com.rhodesisland.terminal.ui.glass.MeshBackground
import com.rhodesisland.terminal.ui.groupchat.GroupNavigationBus
import com.rhodesisland.terminal.ui.navigation.AppNavGraph
import com.rhodesisland.terminal.ui.theme.ChatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 本 Activity 注入给 [CpuBoostController] 的 sustained setter；onDestroy 按引用相等清除，
     *  防止 Application 单例长期持有旧 Activity 的 Window（配置变更重建时的内存泄漏）。 */
    private var sustainedModeSetter: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as RhodesApp

        // PRTS 深色主题：本应用固定深色科幻风，忽略系统/设置的主题模式（themeMode 设置保留但不再生效）。
        val initialDarkTheme = true

        // 沉浸式全屏：内容延伸到状态栏 / 导航栏背后，系统栏透明化。
        // 初始样式根据当前主题偏好设定；后续 setContent 中 ChatTheme 的 SideEffect 会继续同步。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transparent = android.graphics.Color.TRANSPARENT
            enableEdgeToEdge(
                statusBarStyle = if (initialDarkTheme) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                },
                navigationBarStyle = if (initialDarkTheme) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                },
            )
        } else {
            // API 24-28（含华为 EMUI 9 等魔改 ROM）：enableEdgeToEdge 不可用。
            // 手动透明化状态栏 / 导航栏，图标颜色跟随当前主题偏好。
            @Suppress("DEPRECATION")
            runCatching {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !initialDarkTheme
                isAppearanceLightNavigationBars = !initialDarkTheme
            }
        }
        // 适配高刷新率（120Hz）：请求当前分辨率下刷新率最高的显示模式，让键盘上移/转场等动画跑满帧。
        // 系统在支持的设备上会启用高刷新率；不支持则 no-op（取到当前模式即跳过）。
        requestHighRefreshRate()

        // 角色问候通知点按跳转：把目标角色/会话写入设置，ChatViewModel 的 collector 自动切换。
        // 冷启动在 setContent 前写入，配合启动 Loading 画面让 DataStore 写入完成，避免先闪默认角色。
        // 返回是否来自问候通知：若是则卡片流首页直接落到该角色的聊天页，而不是停在卡片流。
        val initialChatOpen = handleGreetingIntent(intent, app)
        // 群聊通知点按：经 GroupNavigationBus 请求跳转（冷启动 + 运行中统一消费，AppNavGraph 处理）。
        handleGroupIntent(intent)

        // CPU 提频（非 root 路线，Task 8）：sustained mode 改为**生成级**——仅 MAXIMUM_SPEED 本地推理
        // 期间经 CpuBoostController 开启，finally/close 恢复；Balanced 永不开启。此处只注入 window setter。
        val setter: (Boolean) -> Unit = { enabled ->
            runCatching { window.setSustainedPerformanceMode(enabled) }
                .onFailure { android.util.Log.w("MainActivity", "setSustainedPerformanceMode($enabled) failed: ${it.message}") }
                .onSuccess { android.util.Log.i("MainActivity", "SustainedPerformanceMode=$enabled") }
        }
        sustainedModeSetter = setter
        app.container.cpuBoostController.sustainedModeSetter = setter

        setContent {
            // PRTS 深色主题：固定深色（themeMode 设置保留但不再生效）。
            val darkTheme = true
            // GlassBackdrop 提供真实背景模糊背板，供所有玻璃面板采样。
            ChatTheme(darkTheme = darkTheme) {
                GlassBackdrop(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    MeshBackground(Modifier.fillMaxSize())
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent,
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
                                AppNavGraph(container = app.container, initialChatOpen = initialChatOpen)
                            }
                        }
                    }
                }
            }
        }
    }
}

    override fun onResume() {
        super.onResume()
        // 从后台/分屏返回时重新请求高刷：部分设备在窗口切走期间会把显示模式降回 60Hz。
        requestHighRefreshRate()
    }

    override fun onDestroy() {
        // 清除本 Activity 注入的 sustained setter：按引用相等清除，配置变更重建时不会误清新 Activity
        // 的注入（旧 Activity onDestroy 晚于新 Activity onCreate）。避免 Application 单例持有旧 Window。
        (application as RhodesApp).container.cpuBoostController.clearSustainedModeSetter(sustainedModeSetter)
        sustainedModeSetter = null
        super.onDestroy()
        // 仅在真正退出（isFinishing）时释放音频播放器；配置变更（旋转等）会重建 Activity，
        // 此时 isFinishing=false，不释放以避免中断后台音乐。AudioManager.release 会置空
        // bgmPlayer/voicePlayer，下次使用时按需重建，故释放后可安全重入。
        if (isFinishing) {
            (application as RhodesApp).container.audioManager.release()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGreetingIntent(intent, application as RhodesApp)
        handleGroupIntent(intent)
    }

    /**
     * 群聊通知点按：识别 extra 并请求跳转到群聊（经 [GroupNavigationBus]，由 AppNavGraph 消费）。
     * 消费后清除 extra 避免重复触发。
     */
    private fun handleGroupIntent(intent: Intent?) {
        val convId = intent?.getLongExtra(GroupChatNotificationManager.EXTRA_GROUP_CONVERSATION_ID, -1L) ?: return
        if (convId <= 0L) return
        intent.removeExtra(GroupChatNotificationManager.EXTRA_GROUP_CONVERSATION_ID)
        GroupNavigationBus.requestOpen(convId)
    }

    /**
     * 角色问候通知点按跳转：从通知 PendingIntent 的 extra 取目标角色/会话，写入设置。
     * ChatViewModel 的 activeCharacter / activeConversations collector 自动切换到对应会话。
     */
    private fun handleGreetingIntent(intent: Intent?, app: RhodesApp): Boolean {
        val charId = intent?.getStringExtra(GreetingNotificationManager.EXTRA_CHARACTER_ID) ?: return false
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
        return true
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
        // 筛同分辨率模式，避免触发分辨率切换；取最高刷新率（120Hz / 90Hz / 144Hz 视设备而定）。
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.modeId != current.modeId) {
            // API 23+：preferredDisplayModeId 把显示模式强制切到最高刷新率（主机制）；
            // preferredRefreshRate 作为补充刷新率提示（API 30 起标记 deprecated 但仍生效）。
            // 二者叠加后，Compose 帧时钟按显示刷新率跑满帧（配合持续动画不会掉回 60Hz）。
            @Suppress("DEPRECATION")
            runCatching {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = best.modeId
                    preferredRefreshRate = best.refreshRate
                }
            }.onFailure {
                android.util.Log.w("MainActivity", "requestHighRefreshRate failed: ${it.message}")
            }
        }
    }
}
