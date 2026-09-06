package com.rhodesisland.terminal.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MomentImageEndpoints] 契约测试：生图端点候选（漏 /v1 自动补试、完整端点直填、Responses 兜底）。
 */
class MomentImageEndpointsTest {

    @Test
    fun chat_rootBaseUrl_appendsV1Candidate() {
        // 最常见笔误：根域名漏 /v1 —— 第一个候选照拼，第二个补 /v1 重试
        assertEquals(
            listOf(
                "https://xuseny.online/chat/completions",
                "https://xuseny.online/v1/chat/completions",
            ),
            MomentImageEndpoints.chatCandidates("https://xuseny.online"),
        )
    }

    @Test
    fun chat_withV1_singleCandidate() {
        assertEquals(
            listOf("https://relay.example.com/v1/chat/completions"),
            MomentImageEndpoints.chatCandidates("https://relay.example.com/v1"),
        )
    }

    @Test
    fun chat_fullEndpointPassedThrough() {
        assertEquals(
            listOf("https://relay.example.com/v1/chat/completions"),
            MomentImageEndpoints.chatCandidates("https://relay.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun chat_pathWithVersion_notDuplicated() {
        // 自定义路径含版本段（/v2）时不再补 /v1
        assertEquals(
            listOf("https://relay.example.com/v2/chat/completions"),
            MomentImageEndpoints.chatCandidates("https://relay.example.com/v2"),
        )
    }

    @Test
    fun responses_rootBaseUrl_insertsV1() {
        assertEquals(
            listOf("https://xuseny.online/v1/responses"),
            MomentImageEndpoints.responsesCandidates("https://xuseny.online"),
        )
    }

    @Test
    fun responses_fromFullChatEndpoint_stripsChatSuffix() {
        assertEquals(
            listOf("https://relay.example.com/v1/responses"),
            MomentImageEndpoints.responsesCandidates("https://relay.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun responses_withV1_keepsRoot() {
        assertEquals(
            listOf("https://relay.example.com/v1/responses"),
            MomentImageEndpoints.responsesCandidates("https://relay.example.com/v1"),
        )
    }

    @Test
    fun mediaTask_rootBaseUrl_triesV1ThenApiV1() {
        // lk888 类平台路径是 /api/v1；根域名时先试 OpenAI 风格 /v1 再试 /api/v1
        assertEquals(
            listOf(
                "https://api.lk888.ai/v1/media/generate",
                "https://api.lk888.ai/api/v1/media/generate",
            ),
            MomentImageEndpoints.mediaTaskCandidates("https://api.lk888.ai"),
        )
    }

    @Test
    fun mediaTask_apiV1BaseUrl_singleCandidate() {
        assertEquals(
            listOf("https://api.lk888.ai/api/v1/media/generate"),
            MomentImageEndpoints.mediaTaskCandidates("https://api.lk888.ai/api/v1"),
        )
    }

    @Test
    fun mediaTask_fromFullChatEndpoint_stripsChatSuffix() {
        assertEquals(
            listOf("https://relay.example.com/v1/media/generate"),
            MomentImageEndpoints.mediaTaskCandidates("https://relay.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun taskStatusEndpoint_derivedFromSubmitEndpoint() {
        assertEquals(
            "https://api.lk888.ai/api/v1/skills/task-status",
            MomentImageEndpoints.taskStatusEndpoint("https://api.lk888.ai/api/v1/media/generate"),
        )
        assertEquals(
            "https://relay.example.com/v1/skills/task-status",
            MomentImageEndpoints.taskStatusEndpoint("https://relay.example.com/v1/media/generate"),
        )
    }
}
