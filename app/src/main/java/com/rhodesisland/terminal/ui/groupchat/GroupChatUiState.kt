package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.DisplayMessage

/**
 * 群聊页 UI 状态。独立于单角色 [com.rhodesisland.terminal.ui.chat.ChatUiState]：
 * 群聊逐条按 [DisplayMessage.characterId] 解析发言人，故状态携带 [members] + [memberImages] 供列表查头像/名字。
 */
data class GroupChatUiState(
    /** 群聊会话 id（null = 尚未解析）。 */
    val conversationId: Long? = null,
    /** 群名称（来自群会话行 title）。 */
    val groupName: String = "群聊",
    /** 群封面 file:// 路径（空=未设置，用成员头像拼图占位）。 */
    val groupCoverPath: String = "",
    /** 群成员角色 id 列表（按设置顺序）。 */
    val memberIds: List<String> = emptyList(),
    /** 已解析的群成员（过滤掉已删除的自定义角色）。 */
    val members: List<Character> = emptyList(),
    /** 成员 id -> 立绘 URL（列表/成员条/流式气泡按条定位头像）。 */
    val memberImages: Map<String, String> = emptyMap(),
    /** 博士头像路径（设置「我的形象」；空=用 monogram「我」占位）。 */
    val userImage: String = "",
    val messages: List<DisplayMessage> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val showTyping: Boolean = false,
    /** 正在「输入中」的成员 id（typing 气泡头像）。 */
    val typingCharacterId: String? = null,
    val errorMessage: String? = null,
    val activeProvider: ChatProviderType = ChatProviderType.CLOUD,
    val groupEnabled: Boolean = false,
    val autoChatEnabled: Boolean = true,
    val isCloud: Boolean = true,
    val showWelcome: Boolean = true,
)