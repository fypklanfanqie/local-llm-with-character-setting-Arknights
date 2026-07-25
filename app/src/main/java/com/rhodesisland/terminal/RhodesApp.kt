package com.rhodesisland.terminal

import android.app.Application
import com.rhodesisland.terminal.data.local.AppDatabase

/**
 * 全局 Application 入口
 * 初始化 AppContainer（手动 DI 容器）
 */
class RhodesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
