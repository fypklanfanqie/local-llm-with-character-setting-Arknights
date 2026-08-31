package com.rhodesisland.terminal.ui.chat

/**
 * 对话列表自动滚动策略（纯函数 + 不可变 [State]，无 Android/Compose 依赖，可 JVM 单测）。
 *
 * 「用户接管优先」语义：
 * - 初始/切换会话：自动跟随底部；
 * - 用户位于底部附近（像素阈值内）时持续跟随流式输出；
 * - 用户上滑浏览历史离开底部后：暂停自动跟随，显示「回到底部」按钮；
 * - 用户回到底部或点击按钮后恢复跟随。
 *
 * 调用方（[ChatMessageList]）用 `mutableStateOf` 持有 [State]，每次转移函数返回新 State，
 * 从而让 Compose 观察到按钮显隐变化。判定「接近底部」由调用方用**像素距离**计算
 * （末项 bottom 与 viewportEnd 之差），而非末项是否可见——单个超高思考消息「可见」并不代表
 * 用户看到的是其底部，这正是旧实现把长思考内容钉在顶部、无法下滑查看的根因。
 */
object ChatAutoScrollPolicy {

    data class State(
        /** 是否应随流式内容增长自动滚动到底部。 */
        val followBottom: Boolean = true,
        /** 是否显示「回到底部」按钮（仅当离开底部暂停跟随且下方确有内容时）。 */
        val showReturnToBottom: Boolean = false,
        /**
         * 跟随锚点：最近一次「确认在底部」时的列表总项数。
         * null = 尚未确认过（视为已锚定）。跟随滚动前的[shouldFollowBottom]校验用它判定
         * 「旧末项是否仍在视口内」——内容自然增长时必然成立，用户上翻后必然不成立，
         * 由此消除「滚动落定 -> 策略更新」竞态窗口内的误跟随（快速上翻被瞬间拉回底部）。
         */
        val followAnchorTotal: Int? = null,
    )

    /** 会话切换/新建：重置为自动跟随并隐藏按钮。 */
    fun onConversationChanged(state: State): State = State(followBottom = true, showReturnToBottom = false)

    /** 点击「回到底部」：恢复跟随并隐藏按钮（实际滚动由 UI 执行），重置锚点等待重新确认。 */
    fun onReturnToBottom(state: State): State = State(followBottom = true, showReturnToBottom = false)

    /**
     * 布局静止后的位置回调（拖拽/程序滚动进行中不调用）。
     *
     * @param isNearBottom 末项 bottom 距 viewport 底部在阈值内（或不可再向下滚动）。
     * @param totalItems 当前列表总项数；确认在底部时记为跟随锚点（[State.followAnchorTotal]）。
     */
    fun onScrollSettled(state: State, isNearBottom: Boolean, totalItems: Int): State =
        if (isNearBottom) {
            State(followBottom = true, showReturnToBottom = false, followAnchorTotal = totalItems)
        } else {
            State(followBottom = false, showReturnToBottom = true, followAnchorTotal = state.followAnchorTotal)
        }

    /**
     * 内容增长后是否应执行跟随滚动（在 [State.followBottom] 允许的前提下做二次校验）。
     *
     * - 锚点为 null：视为已锚定（跟随前从未有过 settle 机会，如刚切会话）。
     * - 锚点有效：视口内必须仍能看到「锚定时的旧末项」（lastVisibleIndex >= 锚点-1）。
     *   用户翻上去后旧末项已滚出视口 → 不跟随；内容增长时旧末项仍在 → 跟随。
     */
    fun shouldFollowBottom(
        state: State,
        totalItems: Int,
        lastVisibleIndex: Int,
    ): Boolean {
        if (!state.followBottom) return false
        val anchor = state.followAnchorTotal ?: return true
        if (totalItems <= 0) return false
        // 列表被清空重置（如切换会话）：锚点失效，视为已锚定
        if (anchor > totalItems) return true
        return lastVisibleIndex >= anchor - 1
    }
}
