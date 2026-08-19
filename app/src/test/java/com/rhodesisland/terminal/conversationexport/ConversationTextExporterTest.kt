package com.rhodesisland.terminal.conversationexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTextExporterTest {

    @Test
    fun renderIncludesEveryMessageInChronologicalOrder() {
        val document = ConversationExportDocument(
            title = "雨夜行动",
            ownerName = "阿米娅",
            createdAt = 1_720_000_000_000L,
            exportedAt = 1_720_000_120_000L,
            messages = listOf(
                ConversationExportMessage(1_720_000_001_000L, "博士", "今晚辛苦了。"),
                ConversationExportMessage(
                    1_720_000_002_000L,
                    "阿米娅",
                    "博士也请早点休息。",
                    listOf("附件：行动记录.pdf"),
                ),
            ),
        )

        val text = ConversationTextExporter.render(document)

        assertTrue(text.indexOf("博士\n今晚辛苦了。") < text.indexOf("阿米娅\n博士也请早点休息。"))
        assertTrue(text.contains("附件：行动记录.pdf"))
        assertTrue(text.contains("会话：雨夜行动"))
    }

    @Test
    fun suggestedNameSanitizesProviderUnsafeCharacters() {
        assertEquals(
            "罗德岛通讯记录_阿米娅_行动_报告_20240819_193640",
            suggestedExportBaseName("阿米娅", "行动:报告?", 1_724_096_200_000L),
        )
    }
}
