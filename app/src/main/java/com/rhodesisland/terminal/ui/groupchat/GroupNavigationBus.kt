package com.rhodesisland.terminal.ui.groupchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 群聊导航事件总线：群聊通知点按（冷启动 + 运行中 onNewIntent）统一经此请求跳转到指定群的聊天页。
 *
 * [requests] 携带自增 nonce 与目标群 id；[com.rhodesisland.terminal.ui.navigation.AppNavGraph]
 * 观察变化，用 remember 的「已处理 nonce」消费，天然对重组/配置变更幂等。
 */
object GroupNavigationBus {

    data class Request(val nonce: Long, val groupId: Long)

    private val _requests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = _requests

    fun requestOpen(groupId: Long) {
        val current = _requests.value
        _requests.value = Request((current?.nonce ?: 0L) + 1L, groupId)
    }
}