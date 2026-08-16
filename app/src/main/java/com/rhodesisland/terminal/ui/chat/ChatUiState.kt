package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.AttachedFile
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.model.TtsLanguage

/**
 * 聊天页 UI 状态
 * 对应小程序 pages/chat/chat.js 的 data
 * 已删除 isFreeMode / credits / adLoaded 字段
 */
data class ChatUiState(
    val characterId: String = "",
    val characterName: String = "",
    val characterRole: String = "",
    val characterImage: String = "",
    val watermarkName: String = "",
    /** 博士头像（设置「我的形象」；空=monogram「我」）。 */
    val userImage: String = "",
    val ttsEnabled: Boolean = false,
    val messages: List<DisplayMessage> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    /** 用户已请求停止当前生成（Task 7）：true 时输入栏显示「正在停止」并禁用重复停止。 */
    val stopRequested: Boolean = false,
    /** 当前生成请求序号（Task 7）：防止迟到的 finally 清除新一轮流式状态。 */
    val activeGenerationId: Long? = null,
    val showTyping: Boolean = false,
    val showWelcome: Boolean = true,
    val subtitleJp: String = "",
    val subtitleCn: String = "",
    val ttsLanguage: TtsLanguage = TtsLanguage.ZH,
    val ttsPlayingIndex: Int = -1,
    val ttsLoadingIndex: Int = -1,
    val ttsSubtitleJp: String = "",
    val ttsSubtitleCn: String = "",
    val showSwitchSubtitle: Boolean = false,
    val uploadedImages: List<String> = emptyList(),
    val uploadedFiles: List<AttachedFile> = emptyList(),
    val activeProvider: ChatProviderType = ChatProviderType.CLOUD,
    /** 深度思考模式开关：控制推理过程是否生成与展示（本地 + 云端通用） */
    val deepThinkingEnabled: Boolean = false,
    val errorMessage: String? = null,
    // ===== 会话管理 =====
    /** 当前角色的全部会话（最近活跃在前），供底部抽屉展示 */
    val conversations: List<Conversation> = emptyList(),
    /** 当前活跃会话 id；null 表示尚未确定 */
    val activeConversationId: Long? = null,
    /** 当前活跃会话标题（工具栏「会话」按钮显示用） */
    val activeConversationTitle: String = "",
    /** 当前活跃会话的 Seedance 自动视频开关（Task 7，新会话默认 false）。 */
    val activeConversationAutoVideoEnabled: Boolean = false,
    /** 是否展开对话管理抽屉 */
    val showConversationSheet: Boolean = false,
)
