package com.rhodesisland.terminal.util

import com.rhodesisland.terminal.data.remote.DirectLlmException
import com.rhodesisland.terminal.data.remote.DirectLlmFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserErrorMessageTest {

    @Test
    fun cancellationExceptionIsRethrownUnchanged() {
        val cancellation = CancellationException("internal cancellation")
        try {
            cancellation.toUserErrorMessage()
            throw AssertionError("CancellationException must be rethrown")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun directHttpFailureDoesNotExposeTechnicalDetails() {
        val error = DirectLlmException(
            failure = DirectLlmFailure.HTTP,
            statusCode = 401,
            technicalMessage = "secret body LogID=abc https://private.example",
        )

        val message = error.toUserErrorMessage()

        assertEquals("云端 API Key 无效或未授权", message)
        assertTrue(message.none { it.isDigit() })
        assertTrue(!message.contains("secret"))
        assertTrue(!message.contains("LogID"))
        assertTrue(!message.contains("http"))
    }

    @Test
    fun networkFailureUsesFixedMessage() {
        val error = DirectLlmException(
            failure = DirectLlmFailure.NETWORK,
            technicalMessage = "socket secret body",
        )

        assertEquals("网络连接失败，请检查网络后重试", error.toUserErrorMessage())
    }
}
