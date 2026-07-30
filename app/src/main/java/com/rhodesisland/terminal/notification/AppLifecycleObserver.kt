package com.rhodesisland.terminal.notification

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * 进程前后台状态观察：通过 [ProcessLifecycleOwner] 跟踪整个应用是否在前台。
 *
 * 角色问候 [com.rhodesisland.terminal.work.GreetingWorker] 据此抑制通知——用户正停留在
 * 该角色的聊天界面时，主动消息已通过 Room Flow 实时冒泡，无需再弹通知
 * （类微信「正在看该聊天时不弹横幅」）。
 *
 * `onStart` 在任意 Activity 进入前台时触发，`onStop` 在最后一个 Activity 退到后台时触发。
 */
object AppLifecycleObserver : DefaultLifecycleObserver {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun register(@Suppress("UNUSED_PARAMETER") app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
    }
}
