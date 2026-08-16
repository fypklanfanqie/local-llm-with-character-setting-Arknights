package com.rhodesisland.terminal.ui.groupchat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.model.Conversation
import com.rhodesisland.terminal.util.MarkdownParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 群列表行：群会话 + 最后一条消息预览（内容/时间）。 */
data class GroupListRow(
    val group: Conversation,
    val preview: String?,
    val previewTime: Long?,
)

data class GroupListUiState(
    val rows: List<GroupListRow> = emptyList(),
)

/**
 * 微信式群聊列表 ViewModel：观察全部群会话（多群聊），每行附带最后一条消息预览。
 * 新建/删除群由 [GroupChatRepository] 落库，群列表 Flow 自动刷新。
 */
class GroupListViewModel(
    application: Application,
    val container: AppContainer,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GroupListUiState())
    val uiState: StateFlow<GroupListUiState> = _uiState

    init {
        viewModelScope.launch {
            try {
                container.groupChatRepository.observeGroups().collect { groups ->
                    val rows = groups.map { g ->
                        val preview = container.groupChatRepository.lastMessagePreview(g.id)
                        GroupListRow(
                            group = g,
                            preview = preview?.let { MarkdownParser.stripThink(it.first).trim().take(40) },
                            previewTime = preview?.second,
                        )
                    }
                    _uiState.update { it.copy(rows = rows) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "群列表 flow 异常", e)
            }
        }
    }

    /** 新建群聊（封面已在调用方落盘或为 null）；成功回调新群 id。 */
    fun createGroup(name: String, coverPath: String?, memberIds: List<String>, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = container.groupChatRepository.createGroup(name, coverPath, memberIds)
            onCreated(id)
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            container.groupChatRepository.deleteGroup(id)
        }
    }

    companion object {
        private const val TAG = "GroupListViewModel"
    }
}