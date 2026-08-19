package com.rhodesisland.terminal.conversationexport

import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationImageLayoutTest {

    @Test
    fun longImageOverSafetyLimitIsRejected() {
        val document = ConversationExportDocument(
            title = "超长会话",
            ownerName = "阿米娅",
            createdAt = 0,
            exportedAt = 0,
            messages = List(1_000) { index ->
                ConversationExportMessage(index.toLong(), "博士", "长消息 ".repeat(120))
            },
        )

        val error = runCatching {
            ConversationImageLayout.plan(document, ConversationImageMode.LONG_IMAGE)
        }.exceptionOrNull()

        assertTrue(error is LongImageTooTallException)
    }

    @Test
    fun paginatedModeCreatesMoreThanOnePageForLongConversation() {
        val document = ConversationExportDocument(
            title = "分页会话",
            ownerName = "阿米娅",
            createdAt = 0,
            exportedAt = 0,
            messages = List(100) { index ->
                ConversationExportMessage(index.toLong(), "博士", "测试消息 ".repeat(20))
            },
        )

        val plan = ConversationImageLayout.plan(document, ConversationImageMode.PAGINATED)

        assertTrue(plan.pageCount >= 2)
    }
}
