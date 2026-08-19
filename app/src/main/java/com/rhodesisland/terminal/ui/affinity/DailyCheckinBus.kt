package com.rhodesisland.terminal.ui.affinity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用首次打开当天尚未签到时，由 [com.rhodesisland.terminal.RhodesApp] 发出一次请求；
 * 导航层消费后弹出签到窗口。使用递增 nonce，关闭弹窗不会在同次进程存活期间反复弹出。
 */
object DailyCheckinBus {
    private val _requests = MutableStateFlow(0L)
    val requests: StateFlow<Long> = _requests

    fun request() {
        _requests.value += 1
    }
}
