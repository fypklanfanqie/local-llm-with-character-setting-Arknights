package com.rhodesisland.terminal.ui.groupchat

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.repository.GroupChatRepository
import com.rhodesisland.terminal.ui.chat.PendingFinal
import com.rhodesisland.terminal.util.MarkdownParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STREAM_THROTTLE_MS = 30L

/**
 * 群聊 ViewModel（仅云端可用）。
 *
 * 独立于单角色 [com.rhodesisland.terminal.ui.chat.ChatViewModel]：
 * - 群聊会话用自己的 conversationId（哨兵角色 "group_chat"），**绝不**触碰 activeCharacter/activeConversation；
 * - 用户回合**随机 1–[AppConfig.GroupChat.MAX_REPLIES_PER_USER_MESSAGE] 名成员依次流式答复**；
 *   消息中 `@名字` 提及的成员必定答复（排在前面），其余随机加入；后答者能看到先答者的回复；
 * - 无自动视频/附件；发言人逐条由行级 `characterId` 决定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModel(
    application: Application,
    val container: AppContainer,
    private val groupId: Long,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GroupChatUiState())
    val uiState: StateFlow<GroupChatUiState> = _uiState

    private var streamingJob: Job? = null

    /** 乐观完成消息列表：一轮多人答复连续落库，Room Flow 回填滞后时逐条保留（见 GroupChatTimelineReconciler）。 */
    private val pendingFinals = mutableListOf<PendingFinal>()

    private val _conversationId = MutableStateFlow<Long?>(null)

    init {
        // 群会话 id 即入参 groupId（多群聊：每个群一个会话行）
        _conversationId.value = groupId
        // 加载群信息：名称 / 封面 / 成员（成员跟随群行，非设置里的旧选择）
        viewModelScope.launch { reloadGroup() }
        // 群聊配置 -> 仅开关状态 + 是否云端
        viewModelScope.launch {
            try {
                container.settingsRepository.groupChatConfig.collect { cfg ->
                    _uiState.update {
                        it.copy(
                            groupEnabled = cfg.enabled,
                            autoChatEnabled = cfg.autoChat,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "群聊配置 flow 异常", e)
            }
        }
        // Provider 类型
        viewModelScope.launch {
            container.chatProviderManager.activeProviderType.collect { type ->
                _uiState.update { it.copy(activeProvider = type, isCloud = type == ChatProviderType.CLOUD) }
            }
        }
        // 博士档案（我的形象）-> 头像（用户气泡显示）
        viewModelScope.launch {
            container.settingsRepository.userProfile.collect { profile ->
                _uiState.update { it.copy(userImage = profile.avatarPath) }
            }
        }
        // 群聊历史 -> 逐条发言人 reconciliation
        viewModelScope.launch {
            try {
                _conversationId
                    .filterNotNull()
                    .flatMapLatest { id -> container.chatRepository.getHistoryFlow(id) }
                    .collect { history -> renderMessages(history) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "群聊历史 flow 异常", e)
            }
        }
    }

    /** 重新加载群信息（名称/封面/成员）；改名、改封面后调用。 */
    fun refreshGroup() {
        viewModelScope.launch { reloadGroup() }
    }

    private suspend fun reloadGroup() {
        try {
            val group = container.groupChatRepository.getGroup(groupId)
            if (group == null) {
                _uiState.update { it.copy(errorMessage = "群聊不存在（可能已被删除）") }
                return
            }
            val members = group.memberIds
                .take(AppConfig.GroupChat.MAX_MEMBERS)
                .mapNotNull { container.characterRepository.getNow(it) }
            val images = members.associate { c ->
                c.id to (if (c.isCustom && c.image.isNotBlank()) c.image
                else container.assetRepository.getPicture(c.id))
            }
            _uiState.update {
                it.copy(
                    groupName = group.title.ifBlank { GroupChatRepository.GROUP_TITLE },
                    groupCoverPath = group.coverImagePath ?: "",
                    memberIds = members.map { c -> c.id },
                    members = members,
                    memberImages = images,
                    conversationId = groupId,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "群信息加载失败", e)
            _uiState.update { it.copy(errorMessage = "群信息加载失败：${e.message}") }
        }
    }

    private fun renderMessages(history: List<ChatMessage>) {
        val state = _uiState.value
        val streaming = if (state.isStreaming) state.messages.firstOrNull { it.id == "streaming" } else null
        val nameById = state.members.associate { it.id to it.name }
        val result = GroupChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = _conversationId.value,
            pendingFinals = pendingFinals.toList(),
            streaming = streaming,
            speakerNameOf = { id -> id?.let { nameById[it] } ?: GroupChatPromptBuilder.FALLBACK_NAME },
        )
        if (result.resolvedPendingIds.isNotEmpty()) {
            pendingFinals.removeAll { it.databaseId in result.resolvedPendingIds }
        }
        _uiState.update { it.copy(messages = result.messages, showWelcome = result.messages.isEmpty()) }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /** @ 弹窗选中成员后回填输入框：把最后一个 @ 替换为 `@名字`（保留前后已输入的内容）。 */
    fun applyAtMention(name: String) {
        _uiState.update { s ->
            val t = s.inputText
            val next = if (t.contains("@")) {
                val idx = t.lastIndexOf('@')
                t.substring(0, idx) + "@" + name + t.substring(idx + 1)
            } else {
                t + "@" + name
            }
            s.copy(inputText = next)
        }
    }

    /** 直接展示一条错误横幅（@ 弹窗触发但尚未选成员等场景）。 */
    fun notifyError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * 发送用户群聊消息 -> 随机 1..MAX 名成员依次流式答复。
     * `@名字` 提及的成员必定答复（排在最前）；无提及则全随机。仅云端；无附件、无自动视频。
     */
    fun sendMessage() {
        val state = _uiState.value
        if (state.isStreaming) return
        val text = state.inputText.trim()
        if (text.isEmpty()) return
        val convId = _conversationId.value
        if (convId == null) {
            _uiState.update { it.copy(errorMessage = "群聊尚未就绪，请稍候再试") }
            return
        }
        val members = state.members
        if (members.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请先到「设置 → 群聊」选择群成员") }
            return
        }
        if (state.activeProvider != ChatProviderType.CLOUD) {
            _uiState.update { it.copy(errorMessage = "群聊仅云端 AI 可用") }
            return
        }

        _uiState.update {
            it.copy(
                inputText = "",
                isStreaming = true,
                showTyping = true,
                showWelcome = false,
            )
        }

        streamingJob = viewModelScope.launch {
            var userMsgId = 0L
            var repliesOk = 0
            try {
                val profile = container.settingsRepository.getUserProfileNow()
                val memberNames = members.map { it.name }
                val nameToId = members.associate { it.name to it.id }
                val mentionIds = GroupChatPromptBuilder.extractMentions(text, memberNames)
                    .mapNotNull { nameToId[it] }
                val count = GroupSpeakerPicker.randomReplyCount(members.size, mentionIds.size)
                val speakerIds = GroupSpeakerPicker.pickRandom(members.map { it.id }.toSet(), mentionIds, count)
                val speakers = speakerIds.mapNotNull { id -> members.firstOrNull { it.id == id } }
                if (speakers.isEmpty()) throw Exception("请先到「设置 → 群聊」选择群成员")
                _uiState.update { it.copy(typingCharacterId = speakers.first().id) }

                val userMessage = ChatMessage(role = "user", content = text)
                userMsgId = container.groupChatRepository.sendUserMessage(convId, userMessage)
                container.settingsRepository.setGroupLastUserMessageAt(System.currentTimeMillis())

                var history = container.chatRepository.getHistory(convId)
                val provider = container.chatProviderManager.getActiveProvider()
                val mentionIdSet = mentionIds.toSet()

                speakers.forEach { speaker ->
                    val targeted = speaker.id in mentionIdSet
                    val apiMessages = GroupChatPromptBuilder.buildApiMessages(
                        members = members,
                        speaker = speaker,
                        history = history,
                        askUser = false,
                        userPersona = profile.persona,
                        userRelationship = profile.relationship,
                        targeted = targeted,
                    )

                    var lastStreamRenderMs = 0L
                    val onChunk: (String) -> Unit = { accumulated ->
                        val now = SystemClock.elapsedRealtime()
                        if (lastStreamRenderMs == 0L || now - lastStreamRenderMs >= STREAM_THROTTLE_MS) {
                            lastStreamRenderMs = now
                            val streamingMsg = DisplayMessage(
                                id = "streaming",
                                role = "streaming",
                                content = accumulated,
                                segments = MarkdownParser.parseWithThink(MarkdownParser.stripThink(accumulated), isStreaming = true),
                                sender = speaker.name,
                                isStreaming = true,
                                characterId = speaker.id,
                            )
                            _uiState.update { s ->
                                val msgs = s.messages.toMutableList()
                                val idx = msgs.indexOfFirst { it.id == "streaming" }
                                if (idx >= 0) msgs[idx] = streamingMsg else msgs.add(streamingMsg)
                                s.copy(messages = msgs, showTyping = false)
                            }
                        }
                    }

                    val displayResponse = provider.chat(apiMessages, onChunk)
                    val clean = GroupChatPromptBuilder.stripSpeakerPrefix(displayResponse, memberNames)
                    val rowId = container.groupChatRepository.sendMemberMessage(convId, speaker.id, clean)
                    repliesOk++
                    container.settingsRepository.setGroupLastSpeakerId(speaker.id)
                    history = history + ChatMessage(role = "assistant", content = clean, characterId = speaker.id)
                    finalizeReply(convId, speaker, clean, rowId)
                }

                // 全部答复完成：复位流式态，结束输入栏的转圈
                _uiState.update { s ->
                    s.copy(
                        messages = s.messages.filterNot { it.id == "streaming" },
                        isStreaming = false,
                        showTyping = false,
                        typingCharacterId = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (repliesOk == 0) {
                    // 一条回复都没成功：回滚用户消息、恢复输入、报错
                    if (userMsgId != 0L) runCatching { container.chatRepository.deleteMessage(userMsgId) }
                    pendingFinals.clear()
                    _uiState.update { s ->
                        val msgs = s.messages.filterNot { it.id == "streaming" }.toMutableList()
                        s.copy(
                            messages = msgs,
                            isStreaming = false,
                            showTyping = false,
                            errorMessage = e.message ?: "请求失败",
                            inputText = text,
                            typingCharacterId = null,
                        )
                    }
                } else {
                    // 部分答复成功：保留已落库回复，仅清理流式状态
                    _uiState.update { s ->
                        s.copy(
                            messages = s.messages.filterNot { it.id == "streaming" },
                            isStreaming = false,
                            showTyping = false,
                            typingCharacterId = null,
                        )
                    }
                }
            }
        }
    }

    /** 单条答复完成：登记乐观 pending + 用完成消息替换 streaming 气泡。 */
    private fun finalizeReply(convId: Long, speaker: Character, response: String, rowId: Long) {
        val display = DisplayMessage(
            id = "msg-$rowId",
            role = "assistant",
            content = response,
            segments = MarkdownParser.parseWithThink(MarkdownParser.stripThink(response), isStreaming = false),
            sender = speaker.name,
            completionState = com.rhodesisland.terminal.data.model.MessageCompletionState.COMPLETE,
            databaseId = rowId,
            characterId = speaker.id,
        )
        pendingFinals.add(PendingFinal(conversationId = convId, databaseId = rowId, message = display))
        _uiState.update { s ->
            val msgs = s.messages.toMutableList()
            val streamIdx = msgs.indexOfFirst { it.id == "streaming" }
            val alreadyRendered = msgs.any { it.id == display.id }
            if (alreadyRendered) {
                if (streamIdx >= 0) msgs.removeAt(streamIdx)
            } else {
                if (streamIdx >= 0) msgs[streamIdx] = display else msgs.add(display)
            }
            s.copy(messages = msgs)
        }
    }

    fun deleteMessage(databaseId: Long?) {
        val id = databaseId ?: return
        if (id <= 0L) return
        viewModelScope.launch {
            pendingFinals.removeAll { it.databaseId == id }
            container.chatRepository.deleteMessage(id)
            _uiState.update { s ->
                if (s.messages.any { it.id == "msg-$id" }) s.copy(messages = s.messages.filterNot { it.id == "msg-$id" })
                else s
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        container.chatProviderManager.cancelAll()
    }

    companion object {
        private const val TAG = "GroupChatViewModel"
    }
}