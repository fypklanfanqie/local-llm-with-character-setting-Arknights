package com.rhodesisland.terminal.notification

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程前后台状态观察：通过 [ProcessLifecycleOwner] 跟踪整个应用是否在前台。
 *
 * 角色问候 [com.rhodesisland.terminal.work.GreetingWorker] 据此抑制通知——用户正停留在
 * 该角色的聊天界面时，主动消息已通过 Room Flow 实时冒泡，无需再弹通知
 * （类微信「正在看该聊天时不弹横幅」）。
 *
 * `onStart` 在任意 Activity 进入前台时触发，`onStop` 在最后一个 Activity 退到后台时触发。
 *
 * Task 15/16：额外提供前台状态监听（[addForegroundListener]），供空闲轻量 OpenCL 探测
 * （[com.rhodesisland.terminal.llm.backend.IdleOpenClProbeCoordinator]）等旁路逻辑订阅。
 */
object AppLifecycleObserver : DefaultLifecycleObserver {

    private const val TAG = "AppLifecycleObserver"

    @Volatile
    var isForeground: Boolean = false
        private set

    private val foregroundListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun register(@Suppress("UNUSED_PARAMETER") app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** 订阅前后台变化（回调在生命周期线程执行；实现方不得阻塞）。 */
    fun addForegroundListener(listener: (Boolean) -> Unit) {
        foregroundListeners += listener
    }

    /** 取消订阅前后台变化（幂等；未订阅时 no-op）。 */
    fun removeForegroundListener(listener: (Boolean) -> Unit) {
        foregroundListeners -= listener
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        notifyListeners(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        notifyListeners(false)
    }

    private fun notifyListeners(foreground: Boolean) {
        foregroundListeners.forEach { listener ->
            runCatching { listener(foreground) }
                .onFailure { Log.w(TAG, "前台监听回调异常（忽略）: ${it.message}") }
        }
    }
}
