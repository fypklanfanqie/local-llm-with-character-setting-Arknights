package com.rhodesisland.terminal.ui.groupchat

/**
 * 群聊界面可见性跟踪（供后台 Worker 通知抑制）：
 * 用户正停留在群聊页时，成员发言已通过 Room Flow 实时冒泡，无需再弹通知（类微信）。
 * 由 [GroupChatScreen] 的 DisposableEffect 置位。
 */
object GroupScreenTracker {
    @Volatile
    var isVisible: Boolean = false
}