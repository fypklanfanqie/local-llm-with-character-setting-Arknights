package com.rhodesisland.terminal.work

/**
 * 主动问候提示词构建（纯函数，JVM 可测）。
 *
 * 时间上下文只注入**低基数时段词**（清晨/上午/…/晚上），绝不注入 HH:mm 数字时间：
 * 分钟级变化会让 system prompt 每次不同，云端 prompt 前缀缓存无法跨请求复用；
 * 时段词在同一时段内字节稳定，问候请求可命中「角色设定 + 用户档案」的缓存前缀。
 */
object GreetingPromptBuilder {

    /**
     * 小时 -> 时段词。与旧 GreetingWorker 划分一致：
     * 5-7 清晨、8-10 上午、11-13 中午、14-17 下午、18-21 傍晚、其余晚上。
     */
    fun periodName(hour: Int): String = when (hour) {
        in 5..7 -> "清晨"
        in 8..10 -> "上午"
        in 11..13 -> "中午"
        in 14..17 -> "下午"
        in 18..21 -> "傍晚"
        else -> "晚上"
    }

    /**
     * 构建「主动发消息」系统附加指令。同一小时内任意调用结果逐字节一致
     * （无分钟/秒级时间），保证上游可复用前缀缓存。
     */
    fun buildTimeDirective(hour: Int): String = buildString {
        val period = periodName(hour)
        append("\n\n[系统附加指令] 现在请你主动给用户发一条消息。当前是")
        append(period).append("。")
        append("\n要求：")
        append("\n- 完全符合你的人设、性格与说话风格")
        append("\n- 可以是打招呼（早安/晚安等）、问候关心、或主动开启一个话题")
        append("\n- 自然简短，像真人随手发的一条消息（1-3 句）")
        append("\n- 只输出消息内容本身，不要加角色名前缀、引号或任何解释")
    }
}
