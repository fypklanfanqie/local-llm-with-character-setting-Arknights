package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.config.AppConfig
import kotlin.random.Random

/**
 * 群成员选择（纯函数，JVM 可测）。
 *
 * - [pick]：后台自动聊天的严格 round-robin（与 [com.rhodesisland.terminal.work.GreetingWorker.pickCharacter] 语义一致）。
 * - [randomReplyCount]/[pickRandom]：用户回合的**随机多人答复**——无 @ 时随机 1..[AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE]
 *   人；有 @ 时被提及成员全部答复并占用名额、其余随机补齐。
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
     * 本轮答复人数：无 @ 随机 1..cap；有 @ 时 [mentionCount] 个被提及成员必定回 + 随机 0..(cap-mentionCount) 人加入。
     * 上限 [AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE]，封顶可作答的成员数。
     */
    fun randomReplyCount(
        memberCount: Int,
        mentionCount: Int,
        random: Random = Random.Default,
    ): Int {
        if (memberCount <= 0) return 0
        val cap = minOf(AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE, memberCount)
        val mentions = mentionCount.coerceAtMost(cap)
        if (mentions >= cap) return cap
        return if (mentions > 0) {
            mentions + random.nextInt(cap - mentions + 1)
        } else {
            random.nextInt(1, cap + 1)
        }
    }

    /**
     * 挑本轮发言序列（长度 [count]）：[first]（@ 到的成员，按消息中出现顺序，去重）打头，
     * 其余成员 shuffled 补齐；不足时返回可返回的最长序列。
     */
    fun pickRandom(
        memberIds: Set<String>,
        first: List<String>,
        count: Int,
        random: Random = Random.Default,
    ): List<String> {
        if (memberIds.isEmpty() || count <= 0) return emptyList()
        val result = mutableListOf<String>()
        first.forEach { id ->
            if (id in memberIds && id !in result) result.add(id)
        }
        if (result.size >= count) return result.take(count)
        val rest = memberIds.filter { it !in result }.shuffled(random)
        result.addAll(rest.take(count - result.size))
        return result
    }
}