package com.rhodesisland.terminal.util

import com.rhodesisland.terminal.data.remote.DirectLlmException
import com.rhodesisland.terminal.data.remote.DirectLlmFailure
import com.rhodesisland.terminal.data.remote.SeedanceApiException
import com.rhodesisland.terminal.data.remote.SeedanceError
import com.rhodesisland.terminal.llm.MemoryAdmissionException
import com.rhodesisland.terminal.llm.PromptAdmissionException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 把技术异常转换为固定、简短的用户文案。
 *
 * 这是唯一允许进入 UI/Toast/Snackbar/持久化错误状态的通用边界；日志仍应记录原异常。
 * [CancellationException] 必须原样传播，调用方不能把取消显示成失败。
 */
fun Throwable.toUserErrorMessage(): String {
    if (this is CancellationException) throw this
    return when (this) {
        is SeedanceApiException -> seedanceUserErrorMessage(classification)
        is DirectLlmException -> when (failure) {
            DirectLlmFailure.HTTP -> when (statusCode) {
                401, 403 -> "云端 API Key 无效或未授权"
                408, 429 -> "云端服务暂时繁忙，请稍后重试"
                in 500..599 -> "云端服务暂时不可用，请稍后重试"
                else -> "云端请求失败，请检查配置后重试"
            }
            DirectLlmFailure.NETWORK -> "网络连接失败，请检查网络后重试"
            DirectLlmFailure.EMPTY_RESPONSE -> "云端未返回有效内容，请稍后重试"
        }
        is MemoryAdmissionException -> "设备可用内存不足，请关闭其他应用或改用更小模型"
        is PromptAdmissionException -> "对话内容过长，请缩短消息后重试"
        is IllegalStateException -> when {
            message?.contains("未选择本地模型") == true -> "请先在模型管理页下载并选择本地模型"
            message?.contains("模型文件未找到") == true -> "本地模型文件未找到，请重新下载模型"
            message?.contains("模型包校验失败") == true -> "本地模型校验失败，请重新下载模型"
            message?.contains("本地推理后端暂不可用") == true -> "本地推理后端暂不可用，请稍后重试"
            else -> "操作失败，请稍后重试"
        }
        is IOException -> "网络连接失败，请检查网络后重试"
        else -> "操作失败，请稍后重试"
    }
}

/** Seedance 分类到固定中文文案；不读取异常 message、URL 或远端错误码。 */
fun seedanceUserErrorMessage(classification: SeedanceError): String = when (classification) {
    SeedanceError.SENSITIVE_CONTENT -> "视频内容未通过审核，请修改描述后重试"
    SeedanceError.QUOTA_EXCEEDED -> "视频服务额度不足或已达上限"
    SeedanceError.AUTH -> "Seedance API Key 无效或未授权"
    SeedanceError.INVALID_PARAMETER -> "视频生成参数不合法，请检查设置"
    SeedanceError.BAD_ENDPOINT -> "Seedance 服务地址或路径不正确"
    SeedanceError.NOT_FOUND -> "视频模型或任务不存在"
    SeedanceError.MODEL_NOT_OPEN -> "视频模型尚未开通"
    SeedanceError.TRANSIENT_429_5XX -> "视频服务暂时繁忙，请稍后重试"
    SeedanceError.AMBIGUOUS_TRANSPORT -> "网络异常，暂时无法确认视频任务状态"
    SeedanceError.OTHER -> "视频生成失败，请稍后重试"
}

/** 安全转换并保留取消语义，适合 catch/onFailure 边界调用。 */
fun userErrorMessageOrThrow(error: Throwable): String = error.toUserErrorMessage()
