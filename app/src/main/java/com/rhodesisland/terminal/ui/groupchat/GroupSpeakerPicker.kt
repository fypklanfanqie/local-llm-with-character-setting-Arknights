package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.config.AppConfig
import kotlin.random.Random

/**
 * 群成员选择（纯函数，JVM 可测）。
 *
 * - [pick]：后台自动聊天的严格 round-robin（与 [com.rhodesisland.terminal.work.GreetingWorker.pickCharacter] 语义一致）。
 * - [resolveReplySpeakers]：用户回合的答复名单——**有 @ 时仅被 @ 成员按提及顺序答复**（定向回答，
 *   不再随机补人）；无 @ 时随机 1..[AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE] 人。
 */
object GroupSpeakerPicker {

    /** 从 [memberIds] 里挑下一位发言者的 id；空集返回 null。 */
    fun pick(memberIds: Set<String>, lastSpeakerId: String?): String? {
        if (memberIds.isEmpty()) return null
        if (memberIds.size == 1) return memberIds.first()
        val sorted = memberIds.sorted() // 固定排序，保证轮询顺序稳定
        val idx = lastSpeakerId?.let { sorted.indexOf(it).takeIf { i -> i >= 0 } }
        return if (idx != null) sorted[(idx + 1) % sorted.size]
        else sorted[Random.nextInt(sorted.size)]
    }

    /**
     * 本轮答复名单（有序，可作发言顺序）：
     *
     * - [mentionIds] 经成员过滤去重后非空 → **仅返回被 @ 的成员**（保持提及顺序）：@ 谁谁答，
     *   其余成员不抢答（定向回答语义）。
     * - 无有效提及 → 从 [memberIds] 随机取 1..[AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE] 人
     *   （cap 封顶于可作答成员数），顺序随机。
     */
    fun resolveReplySpeakers(
        memberIds: Set<String>,
        mentionIds: List<String>,
        random: Random = Random.Default,
    ): List<String> {
        val mentioned = mentionIds.filter { it in memberIds }.distinct()
        if (memberIds.isEmpty()) return emptyList()
        if (mentioned.isNotEmpty()) return mentioned
        val cap = minOf(AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE, memberIds.size)
        val count = random.nextInt(1, cap + 1)
        return memberIds.shuffled(random).take(count)
    }
}
