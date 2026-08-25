package com.rhodesisland.terminal.ui.settings

import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.Conversation
import java.util.Locale

/** 世界书生效范围目标筛选（纯函数，JVM 可测）。 */
internal fun filterCharacters(characters: List<Character>, query: String): List<Character> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return characters
    return characters.filter {
        it.name.lowercase(Locale.ROOT).contains(q) ||
            it.code.lowercase(Locale.ROOT).contains(q) ||
            it.id.lowercase(Locale.ROOT).contains(q)
    }
}

internal fun filterGroups(groups: List<Conversation>, query: String): List<Conversation> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return groups
    return groups.filter {
        it.title.lowercase(Locale.ROOT).contains(q) || it.id.toString().contains(q)
    }
}

/**
 * 过滤后仍保留已选目标：
 * - available 中的已选项若被 query 过滤，按正常搜索语义隐藏；
 * - 已选但已从数据源消失的悬空 ID 永远置顶显示，用户可以显式取消；
 * - 后续接 UI 时用该 id 列表生成 pinned missing 行/available 行。
 */
internal fun mergeMissingScopeIds(
    available: List<String>,
    selected: Set<String>,
    query: String,
): List<String> {
    val q = query.trim().lowercase(Locale.ROOT)
    val missing = selected.filter { it !in available }.sorted()
    val filteredAvailable = available.filter { q.isEmpty() || it.lowercase(Locale.ROOT).contains(q) }
    return missing + filteredAvailable
}
