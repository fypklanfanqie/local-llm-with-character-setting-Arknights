package com.rhodesisland.terminal.provider

import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.provider.cloud.CloudChatProvider
import com.rhodesisland.terminal.provider.local.LocalChatProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Chat Provider 管理器
 *
 * 根据设置选择当前活跃的 Provider（云端 / 本地）。
 * 聊天页面通过此管理器获取 Provider，不直接接触具体实现。
 */
class ChatProviderManager(
    private val cloudProvider: CloudChatProvider,
    private val localProvider: LocalChatProvider,
    private val settings: SettingsRepository,
    /** 切离本地（本地 -> 云端）时触发：释放本地已加载模型（deferred-safe）。 */
    private val onSwitchAwayFromLocal: () -> Unit = {},
) {

    val activeProviderType: Flow<ChatProviderType> = settings.activeProvider

    /** 获取当前活跃 Provider */
    suspend fun getActiveProvider(): ChatProvider {
        return when (settings.getActiveProviderNow()) {
            ChatProviderType.LOCAL -> localProvider
            ChatProviderType.CLOUD -> cloudProvider
        }
    }

    /** 切换 Provider 类型 */
    suspend fun switchProvider(type: ChatProviderType) {
        val previous = settings.getActiveProviderNow()
        settings.setActiveProvider(type)
        // 切离本地（切到云端）后本地模型不应继续驻留（数 GB 权重/KV 常驻浪费内存）；
        // 释放为 deferred-safe：推理进行中延迟到 JNI 返回后释放。
        if (previous == ChatProviderType.LOCAL && type != ChatProviderType.LOCAL) {
            onSwitchAwayFromLocal()
        }
    }

    /** 取消所有 Provider 的当前推理 */
    fun cancelAll() {
        cloudProvider.cancel()
        localProvider.cancel()
    }
}
