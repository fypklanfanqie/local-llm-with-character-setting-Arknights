package com.rhodesisland.terminal

import android.app.Application
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.notification.AppLifecycleObserver
import com.rhodesisland.terminal.notification.GreetingNotificationManager
import com.rhodesisland.terminal.service.InferenceForegroundService
import com.rhodesisland.terminal.work.GreetingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局 Application 入口
 * 初始化 AppContainer（手动 DI 容器）
 */
class RhodesApp : Application() {
    lateinit var container: AppContainer
        private set

    /** 应用级协程作用域：用于启动时触发角色问候后台调度（不阻塞 onCreate）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 角色问候：通知 channel + 前后台观察 + 确保后台调度链存活
        GreetingNotificationManager.createChannel(this)
        InferenceForegroundService.createChannel(this)
        AppLifecycleObserver.register(this)
        appScope.launch {
            GreetingScheduler.ensureScheduled(this@RhodesApp, container.settingsRepository)
        }
    }
}
