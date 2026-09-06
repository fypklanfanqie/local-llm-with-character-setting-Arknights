package com.rhodesisland.terminal.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaTaskApi] 契约测试：任务制媒体 API（提交信封 + 任务状态顶层字段）的解析。
 */
class MediaTaskApiTest {

    @Test
    fun parseSubmit_success_returnsTaskId() {
        val body = """{"msg":"任务创建成功","code":200,"data":{"task_id":128013819,"task_ids":[128013819]}}"""
        assertEquals("128013819", MediaTaskApi.parseSubmitResponse(body))
    }

    @Test
    fun parseSubmit_nonZeroCode_throwsWithMsg() {
        val body = """{"code":402,"msg":"余额不足","data":null}"""
        val e = runCatching { MediaTaskApi.parseSubmitResponse(body) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e?.message?.contains("402") == true)
        assertTrue(e?.message?.contains("余额不足") == true)
    }

    @Test
    fun parseSubmit_errorObjectStyle_throws() {
        // 认证类错误可能是 OpenAI 风格 error 对象
        val body = """{"error":{"message":"无效密钥","type":"authentication_error"}}"""
        val e = runCatching { MediaTaskApi.parseSubmitResponse(body) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun parseTaskStatus_success() {
        val body = """
            {"task_id":12345,"model":"tt-image-2","state":"success","status":"生成完成",
             "progress":"100%","is_final":true,"result_url":"https://cdn.example.com/a.png",
             "result_type":"image","cost":0.0386,"error":null}
        """.trimIndent()
        val s = MediaTaskApi.parseTaskStatus(body)
        assertTrue(s.isFinal)
        assertEquals("https://cdn.example.com/a.png", s.resultUrl)
        assertEquals("image", s.resultType)
        assertNull(s.error)
    }

    @Test
    fun parseTaskStatus_running_notFinal() {
        val body = """{"state":"running","progress":"0%","is_final":false,"cost":0.0386,"error":null}"""
        val s = MediaTaskApi.parseTaskStatus(body)
        assertFalse(s.isFinal)
        assertNull(s.resultUrl)
        assertNull(s.error)
    }

    @Test
    fun parseTaskStatus_failedTask_reportsError() {
        val body = """{"state":"failed","status":"生成失败","is_final":true,"error":"内容安全拦截","cost":0}"""
        val s = MediaTaskApi.parseTaskStatus(body)
        assertTrue(s.isFinal)
        assertTrue(s.error?.contains("内容安全拦截") == true)
    }

    @Test
    fun parseTaskStatus_failedWithoutErrorText_fallsBackToState() {
        val body = """{"state":"failed","is_final":true,"error":null}"""
        val s = MediaTaskApi.parseTaskStatus(body)
        assertTrue(s.isFinal)
        assertTrue(s.error?.contains("失败") == true)
    }

    @Test
    fun parseTaskStatus_gatewayErrorObject_throwsForTransientRetry() {
        // 文档 tip 11：非 200 或响应含 error 对象（网关 502/503）应视为瞬时故障
        val body = """{"error":{"message":"Bad Gateway","type":"gateway_error"}}"""
        val e = runCatching { MediaTaskApi.parseTaskStatus(body) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun parseTaskStatus_nonJson_throws() {
        val e = runCatching { MediaTaskApi.parseTaskStatus("<html>502</html>") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
