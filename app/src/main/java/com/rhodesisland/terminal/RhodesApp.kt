package com.rhodesisland.terminal

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Process
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.notification.AppLifecycleObserver
import com.rhodesisland.terminal.notification.GreetingNotificationManager
import com.rhodesisland.terminal.notification.GroupChatNotificationManager
import com.rhodesisland.terminal.service.InferenceForegroundService
import com.rhodesisland.terminal.util.PrtsImageLoader
import com.rhodesisland.terminal.work.GreetingScheduler
import com.rhodesisland.terminal.work.GroupChatScheduler
import com.rhodesisland.terminal.ui.affinity.DailyCheckinBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.chatbyyourside.llm.backend.OpenClProbeService
import java.io.File

/**
 * 全局 Application 入口
 * 初始化 AppContainer（手动 DI 容器）
 */
class RhodesApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    /** 应用级协程作用域：用于启动时触发角色问候后台调度（不阻塞 onCreate）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Coil 图片加载器：PRTS 立绘需要浏览器头 + 反热链 cookie（见 PrtsImageLoader）。 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(PrtsImageLoader.okHttpClient)
            .crossfade(true)
            .build()

    // Task 15/16：内存压力释放安全网——系统 trim 到「明确内存紧张」档时释放已加载模型
    // （BackendManager.release 为 deferred-safe：生成中延迟到 JNI 返回后释放）。**不调整后台驻留
    // 时序**：UI_HIDDEN/BACKGROUND 仅表示退到后台、非内存压力，模型仍按现状常驻，避免「过早回收」观感；
    // 仅当系统明确内存紧张（RUNNING_LOW/CRITICAL、后台 MODERATE/COMPLETE、低内存）时让出大模型，缓解 LMK 压力。
    private val memoryPressureCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            if (critical) {
                Log.i(TAG, "系统内存紧张（trim level=$level），释放已加载模型")
                runCatching { container.backendManager.release() }
            }
        }

        override fun onLowMemory() {
            Log.i(TAG, "系统低内存，释放已加载模型")
            runCatching { container.backendManager.release() }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        // :mnn_probe 隔离进程只运行 OpenCL 探测 service，不执行主应用初始化（AppContainer、
        // 通知渠道、问候调度等均与探测无关）。短路可显著加快探测进程启动——否则完整
        // onCreate（含通知/前台观察/后台调度）在冷启动 + 驱动初始化之上叠加延迟，
        // 容易超过 OpenClProbeRunner 的探测超时，导致 probe 失败 -> COOLDOWN -> OpenCL
        // 不入链 -> 用户显式选 GPU 仍回退 CPU。探测所需 libbackend_probe.so 由
        // OpenClProbeService 的 companion init 独立加载，不依赖本 onCreate。
        if (isMnnProbeProcess()) return

        container = AppContainer(this)

        // PRTS 立绘反热链 cookie 预热：一次请求拿到 sec cookie，此后全量干员立绘（网络图）可正常加载。
        appScope.launch(Dispatchers.IO) { PrtsImageLoader.prewarm() }
        // 升级时清一次 Coil 图片缓存：旧版本可能把反热链 HTML 挑战页当成成功响应缓存了，需冲掉。
        clearStaleImageCache()

        // 角色问候：通知 channel + 前后台观察 + 确保后台调度链存活
        GreetingNotificationManager.createChannel(this)
        // 群聊：通知 channel + 确保后台调度链存活（空闲自动聊天）
        GroupChatNotificationManager.createChannel(this)
        // 本地推理保活：前台服务通知渠道（生成期间常驻通知栏，防国产 ROM 杀进程）
        InferenceForegroundService.createChannel(this)
        AppLifecycleObserver.register(this)
        // Task 15/16：前台空闲时只做轻量 OpenCL 探测（绝不自动加载模型/预热）。
        container.startIdleOpenClProbe(appScope)
        // Task 15/16：内存压力安全网（关键 trim/低内存时释放模型；生成中延迟释放）。
        registerComponentCallbacks(memoryPressureCallbacks)
        appScope.launch {
            GreetingScheduler.ensureScheduled(this@RhodesApp, container.settingsRepository)
        }
        // 群聊空闲自动聊天：确保后台调度链存活（关闭/本地时由 ensureScheduled cancel）
        appScope.launch {
            GroupChatScheduler.ensureScheduled(this@RhodesApp, container.settingsRepository)
        }
        appScope.launch {
            if (container.affinityRepository.shouldShowDailyCheckinPrompt()) {
                DailyCheckinBus.request()
            }
        }
        // Task 6：恢复 Seedance 视频流水线（复位进程中断残留的进行中状态 + 重入队可自动认领任务）。幂等，异步。
        appScope.launch {
            container.seedanceVideoScheduler.recoverPending()
        }
    }

    /**
     * 升级后清一次 Coil 磁盘缓存（Coil 默认目录 cacheDir/image_cache）：
     * 旧版本可能把 PRTS 反热链 HTML 挑战页当作成功响应缓存，导致立绘一直解码失败。
     * 按版本号只清一次，避免每次启动都清（浪费）。
     */
    private fun clearStaleImageCache() {
        val prefs = getSharedPreferences("rhodes_app", MODE_PRIVATE)
        val lastClearedV = prefs.getInt("last_image_cache_cleared_v", 0)
        val vc = BuildConfig.VERSION_CODE
        if (lastClearedV < vc) {
            runCatching { File(cacheDir, "image_cache").deleteRecursively() }
            prefs.edit().putInt("last_image_cache_cleared_v", vc).apply()
        }
    }

    companion object {
        private const val TAG = "RhodesApp"
    }

    /** 当前是否运行于 :mnn_probe 隔离进程（OpenCL 探测专用，见 OpenClProbeService）。 */
    private fun isMnnProbeProcess(): Boolean =
        (Process.myProcessName() ?: "").endsWith(":mnn_probe")
}
