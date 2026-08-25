package com.rhodesisland.terminal.ui.settings

import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeTargetFilterTest {
    private fun char(id: String, name: String, code: String) = Character(
        id = id, name = name, code = code, role = "干员", race = "", systemPrompt = "",
    )

    @Test
    fun characterSearchMatchesNameCodeOrId() {
        val chars = listOf(char("amiya-id", "阿米娅", "AMY"), char("texas-id", "德克萨斯", "TEX"))
        assertEquals(listOf("amiya-id"), filterCharacters(chars, "amy").map { it.id })
        assertEquals(listOf("texas-id"), filterCharacters(chars, "德克").map { it.id })
        assertEquals(listOf("amiya-id"), filterCharacters(chars, "amiya-id").map { it.id })
    }

    @Test
    fun groupSearchMatchesTitleOrId() {
        val groups = listOf(
            Conversation(1, "group_chat", "罗德岛", 0, 0, isGroup = true),
            Conversation(2, "group_chat", "企鹅物流", 0, 0, isGroup = true),
        )
        assertEquals(listOf(2L), filterGroups(groups, "企鹅").map { it.id })
        assertEquals(listOf(1L), filterGroups(groups, "1").map { it.id })
    }

    @Test
    fun missingSelectedIdsAreKeptVisibleAndSearchDoesNotDropThem() {
        val rows = mergeMissingScopeIds(
            available = listOf("a", "b"),
            selected = setOf("a", "deleted"),
            query = "b",
        )
        assertEquals(listOf("deleted", "b"), rows)
    }
}
