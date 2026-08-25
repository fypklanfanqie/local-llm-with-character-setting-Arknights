package com.rhodesisland.terminal.data.remote

/** 分类后的直连 LLM 失败类型；不携带供应商原始错误文本。 */
enum class DirectLlmFailure {
    HTTP,
    NETWORK,
    EMPTY_RESPONSE,
}

/**
 * 直连 LLM 的结构化异常。
 *
 * [message] 始终是固定安全文案；[technicalMessage] 只供日志/诊断使用，UI 不得展示。
 */
class DirectLlmException(
    val failure: DirectLlmFailure,
    val statusCode: Int? = null,
    val technicalMessage: String? = null,
    cause: Throwable? = null,
) : Exception(
    when (failure) {
        DirectLlmFailure.HTTP -> "云端请求失败"
        DirectLlmFailure.NETWORK -> "网络连接失败"
        DirectLlmFailure.EMPTY_RESPONSE -> "云端返回为空"
    },
    cause,
)
