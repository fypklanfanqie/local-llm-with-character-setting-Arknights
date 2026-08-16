package com.rhodesisland.terminal.ui.chat

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.AssetPaths
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.*
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.repository.AutoVideoOutboxDraft
import com.rhodesisland.terminal.data.repository.ChatCompletionRepository
import com.rhodesisland.terminal.data.repository.ConversationRepository
import com.rhodesisland.terminal.provider.local.LocalChatProvider
import com.rhodesisland.terminal.util.MarkdownParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** 流式 UI 重渲染最小间隔（ms）。本地小模型 token 速率可达 30–50 tok/s、回复长，逐 token 重解析
 *  Markdown + 重组消息列表是 O(n²)，长回复明显卡顿；限制到 ~30fps。首块不节流，末块由完成路径
 *  用完整 response 覆盖并落库，故节流不会丢字。 */
private const val STREAM_THROTTLE_MS = 30L

/**
 * 聊天页 ViewModel
 *
 * 对应小程序 pages/chat/chat.js
 * 已删除：initCredits / watchAd / isFreeMode / 积分检查
 *
 * 会话模型：每个角色可有多个会话（Conversation），每个会话独立历史与模型上下文。
 * 切换角色时恢复该角色上次活跃的会话；无活跃会话则自动新建「新对话」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    val container: AppContainer,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    /** 当前流式推理 Job：切换角色/会话时取消，避免回复写入错误会话或残留孤儿消息 */
    private var streamingJob: Job? = null

    /** 角色切换字幕定时器：切换时取消上一次，避免提前关掉新角色字幕 */
    private var subtitleJob: Job? = null

    /** 当前 TTS 任务（加载+启动播放）。新点按会先取消它，避免在翻译/加载窗口内连点启动两个 TTS、状态串台。 */
    private var ttsJob: Job? = null

    /** 当前活跃会话 id。null 表示尚未确定（首次启动/被删空），由 init 中的 combine 自动创建。 */
    private val _activeConversationId = MutableStateFlow<Long?>(null)

    /** 最近一次历史快照，供深度思考开关切换时重渲染（show/hide 思考过程）。 */
    private var latestHistory: List<ChatMessage> = emptyList()

    /** 最近一次会话内 Seedance 视频快照，供深度思考开关切换时重渲染（否则视频卡会被空列表冲掉）。 */
    private var latestVideos: List<SeedanceVideo> = emptyList()

    /**
     * 乐观完成消息：assistant 已落库但 Room Flow 尚未回填该行的临时桥。
     * Room 快照包含同一 databaseId 或切走会话时才清除（见 [renderMessages]），
     * 防止延迟 Flow 覆盖刚展示的完成回复（修复首轮回答消失）。
     */
    private var pendingFinal: PendingFinal? = null

    /** 生成请求自增序号（Task 7）：与 uiState.activeGenerationId 配对，防止迟到 finally 串台。 */
    private var generationCounter = 0L

    /** 当前生成已累积的原始流式文本（Task 7）：用户显式停止/云端 IOException 时用于保留部分输出。 */
    @Volatile
    private var latestAccumulated: String = ""

    /** 助手回复 + 自动视频 outbox 同事务落库（Task 7）：进程在回复保存后死亡也不漏自动视频。 */
    private val chatCompletionRepository = ChatCompletionRepository(container.database)

    init {
        // 监听活跃角色变化：加载角色信息。
        // 只绑定 activeCharacter（不绑会话映射），避免 setActiveConversation 触发重复 loadCharacter / 重播语音。
        viewModelScope.launch {
            try {
                container.settingsRepository.activeCharacter.collect { charId ->
                    streamingJob?.cancel()
                    container.chatProviderManager.cancelAll()
                    pendingFinal = null
                    loadCharacter(charId)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "activeCharacter flow 异常", e)
                _uiState.update { it.copy(errorMessage = "角色数据加载失败：${e.message}", showWelcome = false) }
            }
        }
        // 监听角色 + 活跃会话映射：确定该角色的活跃会话；无（或已被删除）则自动新建「新对话」。
        // setActiveConversation 会引发 combine 重发，但届时会话已存在 -> 走 if 分支，幂等无环。
        viewModelScope.launch {
            try {
                container.settingsRepository.activeCharacter
                    .combine(container.settingsRepository.activeConversations) { charId, map -> charId to map[charId] }
                    .distinctUntilChanged()
                    .collect { (charId, convId) ->
                        val id = convId
                        if (id != null && container.conversationRepository.getById(id) != null) {
                            _activeConversationId.value = id
                        } else {
                            val newId = container.conversationRepository.create(charId)
                            container.settingsRepository.setActiveConversation(charId, newId)
                            _activeConversationId.value = newId
                        }
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "活跃会话 flow 异常", e)
                _uiState.update { it.copy(errorMessage = "会话数据加载失败：${e.message}", showWelcome = false) }
            }
        }
        // 监听活跃会话 + 聊天记录 + 会话内 Seedance 视频（flatMapLatest 保证会话切换时取消旧订阅，
        // 避免历史/视频串台）。视频状态/提示词/路径仅用于展示层，绝不进入 LLM 历史。
        viewModelScope.launch {
            try {
                _activeConversationId
                    .flatMapLatest { id ->
                        if (id != null) {
                            combine(
                                container.chatRepository.getHistoryFlow(id),
                                container.seedanceVideoRepository.observeForConversation(id),
                            ) { history, videos -> history to videos }
                        } else {
                            flowOf(emptyList<ChatMessage>() to emptyList<SeedanceVideo>())
                        }
                    }
                    .collect { (history, videos) ->
                        latestHistory = history
                        latestVideos = videos
                        renderMessages(history, videos)
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "聊天记录 flow 异常", e)
                _uiState.update { it.copy(errorMessage = "聊天记录加载失败：${e.message}", showWelcome = false) }
            }
        }
        // 监听当前角色的会话列表（供抽屉展示 + 同步当前会话标题）
        viewModelScope.launch {
            try {
                container.settingsRepository.activeCharacter
                    .flatMapLatest { charId -> container.conversationRepository.observeByCharacter(charId) }
                    .collect { list ->
                        _uiState.update { it.copy(conversations = list) }
                        syncActiveMeta()
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "会话列表 flow 异常", e)
                _uiState.update { it.copy(errorMessage = "会话列表加载失败：${e.message}") }
            }
        }
        // 监听活跃会话变化 -> 同步标题/高亮（切换/新建/删除后立即生效）
        viewModelScope.launch {
            try {
                _activeConversationId.collect { syncActiveMeta() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "activeConversationId flow 异常", e)
            }
        }
        // 监听 Provider 类型变化
        viewModelScope.launch {
            try {
                container.chatProviderManager.activeProviderType.collect { type ->
                    _uiState.update { it.copy(activeProvider = type) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "providerType flow 异常", e)
                _uiState.update { it.copy(errorMessage = "Provider 切换失败：${e.message}") }
            }
        }
        // 监听 TTS 语言
        viewModelScope.launch {
            try {
                container.settingsRepository.ttsLanguage.collect { lang ->
                    _uiState.update { it.copy(ttsLanguage = lang) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "ttsLanguage flow 异常", e)
            }
        }
        // 监听深度思考开关：更新 UI 态并重渲染已有消息（show/hide 思考过程）
        viewModelScope.launch {
            try {
                container.settingsRepository.deepThinking.collect { enabled ->
                    _uiState.update { it.copy(deepThinkingEnabled = enabled) }
                    renderMessages(latestHistory, videos = latestVideos)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "deepThinking flow 异常", e)
            }
        }
        // 博士档案（我的形象）-> 头像（用户气泡显示）
        viewModelScope.launch {
            container.settingsRepository.userProfile.collect { profile ->
                _uiState.update { it.copy(userImage = profile.avatarPath) }
            }
        }
    }

    /** 从会话列表 + 当前活跃 id 同步 uiState 的 activeConversationId / activeConversationTitle /
     *  activeConversationAutoVideoEnabled */
    private fun syncActiveMeta() {
        val list = _uiState.value.conversations
        val id = _activeConversationId.value
        val active = list.firstOrNull { it.id == id }
        _uiState.update {
            it.copy(
                activeConversationId = id,
                activeConversationTitle = active?.title ?: ConversationRepository.DEFAULT_TITLE,
                activeConversationAutoVideoEnabled = active?.autoVideoEnabled ?: false,
            )
        }
    }

    private suspend fun loadCharacter(characterId: String) {
        val char = container.characterRepository.getNow(characterId)
            ?: container.characterRepository.getNow(Characters.DEFAULT_CHARACTER_ID)
            ?: return
        val imageUrl = if (char.isCustom && char.image.isNotBlank()) {
            char.image
        } else {
            container.assetRepository.getPicture(characterId)
        }

        _uiState.update {
            it.copy(
                characterId = characterId,
                characterName = char.name,
                characterRole = char.role,
                characterImage = imageUrl,
                watermarkName = char.watermarkName,
                // 自定义角色也允许 TTS（朗读按钮显示），音色由角色音色映射按 characterId 选取
                ttsEnabled = char.ttsEnabled || char.isCustom,
                subtitleJp = char.voiceLines?.jp ?: "",
                subtitleCn = char.voiceLines?.cn ?: "",
            )
        }

        // 切换角色时短暂显示双语字幕（3.5s 后自动隐藏）
        val hasSwitchSub = !char.voiceLines?.jp.isNullOrEmpty() || !char.voiceLines?.cn.isNullOrEmpty()
        if (hasSwitchSub) {
            _uiState.update { it.copy(showSwitchSubtitle = true) }
            // 取消上一次未到期的字幕定时器，避免快速切换时旧定时器提前关掉新角色字幕
            subtitleJob?.cancel()
            subtitleJob = viewModelScope.launch {
                kotlinx.coroutines.delay(3500)
                _uiState.update { it.copy(showSwitchSubtitle = false) }
            }
        }
    }

    private fun renderMessages(history: List<ChatMessage>, videos: List<SeedanceVideo> = emptyList()) {
        // 统一走 ChatTimelineReconciler：Room 快照 + 流式气泡 + 乐观完成消息协调渲染。
        // - 流式输出期间保留 streaming 气泡（仅 isStreaming=true 时，避免切会话/角色后旧气泡串台）；
        // - Room 以行 ID 确认完成消息前保留 pendingFinal，杜绝延迟 Flow 覆盖（回答消失）；
        // - 跨会话 pending 丢弃并清除；
        // - 会话内 Seedance 视频按 sourceAssistantMessageId 附加到助手消息（仅展示层）。
        val streaming = if (_uiState.value.isStreaming) {
            _uiState.value.messages.firstOrNull { it.id == "streaming" }
        } else null
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = _activeConversationId.value,
            pendingFinal = pendingFinal,
            streaming = streaming,
            showThink = _uiState.value.deepThinkingEnabled,
            characterName = _uiState.value.characterName,
            videos = videos,
        )
        if (result.pendingResolved) pendingFinal = null
        // 欢迎页仅在「没有任何可显示消息」时出现：用 result.messages 而非 reconciler 的
        // history/streaming/pending 组合判定，防止本地首答完成后（乐观消息已入列表但 Room
        // 旧快照尚未回填的窗口内）showWelcome 残留 true，把刚生成的回答遮成欢迎页。
        _uiState.update { it.copy(messages = result.messages, showWelcome = result.messages.isEmpty()) }
    }

    // ===== 会话管理 =====

    /** 新建会话并切换为活跃。清空输入/附件，历史自然为空。 */
    fun newConversation() {
        viewModelScope.launch {
            val charId = _uiState.value.characterId
            streamingJob?.cancel()
            container.chatProviderManager.cancelAll()
            pendingFinal = null
            val newId = container.conversationRepository.create(charId)
            container.settingsRepository.setActiveConversation(charId, newId)
            _activeConversationId.value = newId
            _uiState.update {
                it.copy(
                    inputText = "",
                    uploadedImages = emptyList(),
                    uploadedFiles = emptyList(),
                    showConversationSheet = false,
                    isStreaming = false,
                    showTyping = false,
                    stopRequested = false,
                    activeGenerationId = null,
                )
            }
        }
    }

    /** 切换到指定会话。 */
    fun switchConversation(id: Long) {
        if (id == _activeConversationId.value) {
            toggleConversationSheet(false)
            return
        }
        viewModelScope.launch {
            streamingJob?.cancel()
            container.chatProviderManager.cancelAll()
            pendingFinal = null
            val charId = _uiState.value.characterId
            container.settingsRepository.setActiveConversation(charId, id)
            _activeConversationId.value = id
            _uiState.update {
                it.copy(
                    showConversationSheet = false,
                    isStreaming = false,
                    showTyping = false,
                    stopRequested = false,
                    activeGenerationId = null,
                )
            }
        }
    }

    /**
     * 删除指定会话（连同其全部消息）。
     * 若删的是当前活跃会话，自动切到最近的另一个；一条不剩则清活跃记录，由 combine 自动新建。
     */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val charId = _uiState.value.characterId
            container.conversationRepository.delete(id)
            // 删除的若是当前活跃会话（或其 pending 所属会话），同步清理，防止残留串台。
            if (id == _activeConversationId.value || pendingFinal?.conversationId == id) {
                pendingFinal = null
            }
            if (id == _activeConversationId.value) {
                val remaining = container.conversationRepository.listByCharacter(charId)
                val target = remaining.firstOrNull()
                if (target != null) {
                    container.settingsRepository.setActiveConversation(charId, target.id)
                    _activeConversationId.value = target.id
                } else {
                    container.settingsRepository.clearActiveConversation(charId)
                    _activeConversationId.value = null
                }
            }
        }
    }

    /** 重命名会话。空标题回退为默认「新对话」。 */
    fun renameConversation(id: Long, title: String) {
        val t = title.trim().ifBlank { ConversationRepository.DEFAULT_TITLE }
        viewModelScope.launch {
            container.conversationRepository.rename(id, t)
            // observeByCharacter Flow 会刷新列表 -> syncActiveMeta 更新标题
        }
    }

    fun toggleConversationSheet(open: Boolean) {
        _uiState.update { it.copy(showConversationSheet = open) }
    }

    /**
     * 开启/关闭当前会话的 Seedance 自动视频（Task 7）。
     *
     * 关闭直接生效；开启时先做准入检查（Seedance API Key 非空、角色立绘存在），
     * 不满足则提示并**不**落库开启（保留原开关值）。Provider == LOCAL 时由 UI 层禁用
     * （显示「仅云端可用」，不清空已存储的开关值），此处不拦截。
     */
    fun setAutoVideoEnabled(conversationId: Long, enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { container.conversationRepository.setAutoVideoEnabled(conversationId, false) }
            return
        }
        viewModelScope.launch {
            val seedance = container.settingsRepository.getSeedanceConfigNow()
            if (seedance.apiKey.isBlank()) {
                _uiState.update {
                    it.copy(errorMessage = "未配置 Seedance API Key，请先到「设置」中配置后再开启自动视频")
                }
                return@launch
            }
            val charId = _uiState.value.characterId
            val char = container.characterRepository.getNow(charId)
            val hasCharacterImage = if (char == null) false
            else if (char.isCustom) char.image.isNotBlank()
            else AssetPaths.PICTURES[charId] != null
            if (!hasCharacterImage) {
                _uiState.update {
                    it.copy(errorMessage = "该角色未设置立绘图片，请先到角色页配置后再开启自动视频")
                }
                return@launch
            }
            container.conversationRepository.setAutoVideoEnabled(conversationId, true)
        }
    }

    /** 取消排队中的 Seedance 视频任务（仅 QUEUED 可发起；结果以服务端状态为准）。 */
    fun cancelVideoTask(taskId: Long) {
        viewModelScope.launch {
            val claimed = container.seedanceVideoRepository.claim(
                taskId,
                SeedanceVideoState.QUEUED,
                SeedanceVideoState.CANCEL_REQUESTED,
            )
            if (claimed) {
                container.seedanceVideoScheduler.enqueue(taskId)
            }
        }
    }

    /**
     * 重试失败/过期的 Seedance 视频任务（Task 7）。
     *
     * 按当前状态映射回可被 Worker 自动认领的入口状态后重新入队：
     * FAILED_SNAPSHOT -> SNAPSHOT_PENDING；FAILED_PROMPT/CONFIG_CHANGED -> PROMPT_PENDING；
     * FAILED_SUBMISSION/FAILED_REMOTE/EXPIRED -> SUBMISSION_PENDING；FAILED_QUERY -> QUEUED（继续查询）；
     * FAILED_DOWNLOAD -> DOWNLOAD_PENDING。费用性重试（FAILED_REMOTE/EXPIRED/歧义 FAILED_SUBMISSION）
     * 由视频卡先弹确认对话框，用户确认后才调用本方法。
     *
     * 手动重试走 [com.rhodesisland.terminal.data.model.prepareRetry]：仅当回入口状态
     * SUBMISSION_PENDING（重新生成）才归档当前 remoteTaskId 进 previousRemoteTasksJson 并
     * generationAttempt += 1；继续查询/重新下载复用同一 remoteTaskId 不归档不加次数。
     * 所有手动重试都重置自动退避与费用确认标记，再回到入口状态。
     */
    fun retryVideoTask(taskId: Long) {
        viewModelScope.launch {
            val video = container.seedanceVideoRepository.getById(taskId) ?: return@launch
            val entry = retryEntryStateOf(video.state) ?: return@launch
            container.seedanceVideoRepository.update(
                video.prepareRetry(entry).copy(
                    state = entry,
                    errorStage = null,
                    errorCode = null,
                    errorMessage = null,
                    retryDisposition = null,
                    nextRetryAt = null,
                ),
            )
            container.seedanceVideoScheduler.enqueue(taskId)
        }
    }

    /** 失败/过期状态 -> 可自动认领的入口状态（纯函数；非可重试状态返回 null）。 */
    private fun retryEntryStateOf(state: SeedanceVideoState): SeedanceVideoState? = when (state) {
        SeedanceVideoState.FAILED_SNAPSHOT -> SeedanceVideoState.SNAPSHOT_PENDING
        SeedanceVideoState.FAILED_PROMPT,
        SeedanceVideoState.FAILED_PROMPT_CONFIG_CHANGED -> SeedanceVideoState.PROMPT_PENDING
        SeedanceVideoState.FAILED_SUBMISSION,
        SeedanceVideoState.FAILED_REMOTE,
        SeedanceVideoState.EXPIRED -> SeedanceVideoState.SUBMISSION_PENDING
        SeedanceVideoState.FAILED_QUERY -> SeedanceVideoState.QUEUED
        SeedanceVideoState.FAILED_DOWNLOAD -> SeedanceVideoState.DOWNLOAD_PENDING
        else -> null
    }

    // ===== 输入 / 附件 =====

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun addImage(uri: String) {
        _uiState.update {
            if (it.uploadedImages.size >= 3) it
            else it.copy(uploadedImages = it.uploadedImages + uri)
        }
    }

    fun removeImage(index: Int) {
        _uiState.update { state ->
            val current = state.uploadedImages.toMutableList()
            if (index in current.indices) current.removeAt(index)
            state.copy(uploadedImages = current)
        }
    }

    fun addFile(uri: String, name: String) {
        _uiState.update {
            if (it.uploadedFiles.size >= 3) it
            else it.copy(uploadedFiles = it.uploadedFiles + AttachedFile(path = uri, name = name))
        }
    }

    fun removeFile(index: Int) {
        _uiState.update { state ->
            val current = state.uploadedFiles.toMutableList()
            if (index in current.indices) current.removeAt(index)
            state.copy(uploadedFiles = current)
        }
    }

    fun toggleTtsLanguage() {
        viewModelScope.launch {
            val current = container.settingsRepository.getTtsLanguageNow()
            val newLang = if (current == TtsLanguage.ZH) TtsLanguage.JA else TtsLanguage.ZH
            container.settingsRepository.setTtsLanguage(newLang)
        }
    }

    fun switchProvider(type: ChatProviderType) {
        viewModelScope.launch {
            // 与 switchConversation/newConversation 一致：切换前取消当前推理，避免旧 provider
            // 的 onToken 回调继续更新 UI 造成状态混乱。
            streamingJob?.cancel()
            container.chatProviderManager.cancelAll()
            pendingFinal = null
            container.chatProviderManager.switchProvider(type)
            _uiState.update { s ->
                s.copy(
                    isStreaming = false,
                    showTyping = false,
                    stopRequested = false,
                    activeGenerationId = null,
                    messages = s.messages.filterNot { it.id == "streaming" },
                )
            }
        }
    }

    /** 切换深度思考模式（本地 + 云端通用）。Flow 收集器负责更新 UI 态与重渲染。 */
    fun toggleDeepThinking() {
        viewModelScope.launch {
            val enabled = !container.settingsRepository.getDeepThinkingNow()
            container.settingsRepository.setDeepThinking(enabled)
        }
    }

    /**
     * 请求停止当前生成（Task 7）。
     *
     * 不取消 streamingJob（否则 CancellationException 会走回滚路径、丢弃部分文本），而是：
     * 1. 置 stopRequested=true（UI 显示「正在停止」、禁用重复点击）；
     * 2. 调 [ChatProviderManager.cancelAll]（LocalChatProvider 经 ExecutionControl 发布 USER_CANCEL，
     *    MnnBackend 每 token 检测 abort；云端则取消 HTTP 调用）。
     *
     * prefill 是阻塞调用，无法即时中断——点击后可能等待 prefill 返回才真正停止，UI 必须保持「正在停止」。
     * 停止完成后由 sendMessage 正常收尾：保留部分输出并标记停止状态。
     */
    fun stopGeneration() {
        val state = _uiState.value
        if (!state.isStreaming || state.stopRequested) return
        _uiState.update { it.copy(stopRequested = true) }
        container.chatProviderManager.cancelAll()
    }

    /**
     * 发送消息
     * 已删除付费积分检查逻辑
     * 历史取当前活跃会话的最近 N 条作为模型上下文（每会话上下文互不污染）。
     */
    fun sendMessage() {
        val state = _uiState.value
        if (state.isStreaming) return

        val text = state.inputText.trim()
        val images = state.uploadedImages.toList()
        val files = state.uploadedFiles.toList()
        if (text.isEmpty() && images.isEmpty() && files.isEmpty()) return

        val charId = state.characterId
        val convId = _activeConversationId.value
        if (convId == null) {
            // 活跃会话尚未初始化完成（首屏竞态）：给出可见错误，而非让发送按钮静默无反应。
            _uiState.update { it.copy(errorMessage = "会话尚未就绪，请稍候再试") }
            return
        }

        // Task 7：为本次生成分配唯一请求序号，防止迟到 finally 清掉新一轮流式状态。
        val generationId = ++generationCounter
        latestAccumulated = ""
        _uiState.update {
            it.copy(
                inputText = "",
                uploadedImages = emptyList(),
                uploadedFiles = emptyList(),
                isStreaming = true,
                showTyping = true,
                showWelcome = false,
                stopRequested = false,
                activeGenerationId = generationId,
            )
        }

        streamingJob = viewModelScope.launch {
            // 性能浮窗日志终态：默认「已停止」（取消路径），成功/出错时覆盖。
            // 须在 try 外声明，catch/finally 才可见（try 块内声明的局部变量不对 catch/finally 可见）。
            var termReason = "已停止"
            var userMsgId = 0L   // 已落库用户消息 id；发送失败时 catch 据此回滚删除
            var userDisplayText = ""  // 用户消息展示文本（自动视频 outbox 的用户文本快照；try 外声明供 catch 可见）
            // Task 7：发送起点捕获的自动视频快照与角色来源（try 外声明，catch 的停止路径也可安全传参；
            // 停止路径 shouldCreateAutoVideo 恒为 false，不会实际创建 outbox）。
            var autoVideoSnapshot: AutoVideoTriggerSnapshot? = null
            var autoCharacter: Character? = null
            var autoCharacterImageSource: String? = null
            try {
                val char = container.characterRepository.getNow(charId)
                    ?: throw Exception("角色不存在")
                autoCharacter = char
                // Task 7：发送起点捕获自动视频触发快照（Provider/会话开关/API 配置/Seedance 配置/
                // 角色图来源）。生成期间切换 Provider、开关或配置均不影响本次判定；
                // 视频生成独立于 streamingJob/isStreaming，不阻塞下一轮对话。
                val autoProvider = container.settingsRepository.getActiveProviderNow()
                val autoEnabled = _uiState.value.conversations.firstOrNull { it.id == convId }?.autoVideoEnabled ?: false
                val autoApiConfig = container.settingsRepository.getApiConfigNow()
                val autoSeedanceConfig = container.settingsRepository.getSeedanceConfigNow()
                autoCharacterImageSource =
                    if (char.isCustom) char.image.takeIf { it.isNotBlank() } else AssetPaths.PICTURES[char.id]
                // 存储用户消息
                val displayText = text.ifEmpty {
                    when {
                        images.isNotEmpty() -> "[图片]"
                        files.isNotEmpty() -> "[文件]"
                        else -> "[附件]"
                    }
                }
                userDisplayText = displayText
                val userMessage = ChatMessage(
                    role = "user",
                    content = displayText,
                    images = images,
                    files = files,
                    fileNames = files.map { it.name },
                )
                userMsgId = container.chatRepository.addMessage(charId, convId, userMessage)
                autoVideoSnapshot = AutoVideoTriggerSnapshot(
                    provider = autoProvider,
                    enabled = autoEnabled,
                    userMessageId = userMsgId,
                    apiConfig = autoApiConfig,
                    seedanceConfig = autoSeedanceConfig,
                )

                // 自动生成会话标题：首条用户消息后，若标题仍是默认值，用消息摘要命名
                if (state.activeConversationTitle == ConversationRepository.DEFAULT_TITLE) {
                    val summary = displayText.take(20).replace("\n", " ").trim()
                    if (summary.isNotEmpty()) {
                        container.conversationRepository.rename(convId, summary)
                    }
                }

                // 构建 API 消息（含图片多模态 / 文档文本提取）
                val history = container.chatRepository.getHistory(convId).takeLast(AppConfig.MAX_CONTEXT_MESSAGES)
                val resolvedHistory = history.map { msg ->
                    if (msg.role == "user" && (msg.images.isNotEmpty() || msg.files.isNotEmpty())) {
                        // 仅本次发送的消息严格解析（可操作错误照常上抛让用户看到）；
                        // 历史消息的附件解析失败（旧会话带图/文件后切换了非多模态模型等）静默降级为
                        // 纯文本，绝不误报「需多模态模型」、绝不阻塞本次发送。
                        if (msg.databaseId == userMsgId) {
                            resolveMultimodalMessage(msg)
                        } else {
                            resolveHistoryAttachmentLenient(msg)
                        }
                    } else {
                        msg
                    }
                }
                val isCloudProvider = container.settingsRepository.getActiveProviderNow() == ChatProviderType.CLOUD
                // 博士档案（人设/关系）注入 system：云端与本地共用同一消息列表，一处注入两端生效
                val userDirective = container.settingsRepository.getUserProfileNow().toDirectiveText()
                val apiMessages = buildList {
                    add(ChatMessage(role = "system", content = char.systemPrompt + userDirective))
                    addAll(resolvedHistory.map {
                        if (isCloudProvider) {
                            // 云端历史含 <think>（注入的推理），回传前剥离（reasoning 不应回传给对话商）。
                            ChatMessage(
                                role = it.role,
                                content = MarkdownParser.stripThink(it.content),
                                multimodalImages = it.multimodalImages,
                            )
                        } else {
                            // 本地先保留 content + modelContent 原始双字段交给 PromptWindowPlanner：token 估算
                            // 必须使用 modelContent ?: content；选好窗口后 LocalChatProvider 才把模型可见文本
                            // 映射到 content，避免规划前丢失真实原文长度并破坏 KV 前缀解释。
                            it
                        }
                    })
                }

                // 性能浮窗：重置速率与日志。实时 Token 速率由浮窗读 MnnBackend 原子快照（native tps），
                // 不再按流式 chunk 近似计数（Task 4：批处理后 chunk 数≠token 数）。
                container.performanceCollector.updateTokenRate(0f)
                container.performanceCollector.updateLog("生成中…")
                // 流式 UI 节流：见 STREAM_THROTTLE_MS。onChunk 由 LocalStreamRenderPump 渲染协程
                // （节流放行）与 finish（同步终帧）串行调用，lastStreamRenderMs 无需同步。
                var lastStreamRenderMs = 0L

                // 调用 Provider
                val showThink = _uiState.value.deepThinkingEnabled
                val onChunk: (String) -> Unit = { accumulated ->
                    // Task 7：先记录权威累积文本（停止时落库内容不受渲染节流影响），再节流渲染。
                    latestAccumulated = accumulated
                    // 节流：仅首块或距上次重渲染 >= STREAM_THROTTLE_MS 时才重解析 Markdown + 重组列表。
                    // 末块若被跳过，下方完成路径会用完整 displayResponse 覆盖并落库，不会丢字。
                    val now = SystemClock.elapsedRealtime()
                    if (lastStreamRenderMs == 0L || now - lastStreamRenderMs >= STREAM_THROTTLE_MS) {
                        lastStreamRenderMs = now
                        // 流式更新
                        val streamingMsg = DisplayMessage(
                            id = "streaming",
                            role = "streaming",
                            content = accumulated,
                            segments = if (showThink) MarkdownParser.parseWithThink(accumulated, isStreaming = true)
                                else MarkdownParser.parseWithThink(MarkdownParser.stripThink(accumulated), isStreaming = true),
                            sender = state.characterName.ifEmpty { "AI" },
                            isStreaming = true,
                        )
                        _uiState.update { s ->
                            val msgs = s.messages.toMutableList()
                            val existingIdx = msgs.indexOfFirst { it.id == "streaming" }
                            if (existingIdx >= 0) msgs[existingIdx] = streamingMsg
                            else msgs.add(streamingMsg)
                            s.copy(messages = msgs, showTyping = false)
                        }
                    }
                }
                val provider = container.chatProviderManager.getActiveProvider()
                // 本地：走 chatTyped 取展示文本 + 模型原始文本（modelContent）；云端：返回展示文本，modelContent=null。
                val displayResponse: String
                val modelText: String?
                var generatedTokens = 0   // native 实测生成 token 数（仅本地有意义；云端 0）
                var localCompletionReason: com.rhodesisland.terminal.llm.metrics.CompletionReason? = null
                if (provider is LocalChatProvider) {
                    val localResult = provider.chatTyped(apiMessages, onChunk)
                    displayResponse = localResult.displayText
                    modelText = localResult.modelText
                    generatedTokens = localResult.generation?.generatedTokens ?: 0
                    localCompletionReason = localResult.generation?.completionReason
                } else {
                    displayResponse = provider.chat(apiMessages, onChunk)
                    modelText = null
                }

                // 流式完成 -> 移除临时 streaming 消息，落库持久化；明确区分 timeout/max-token/用户停止。
                // token 数取 native 实测 generatedTokens（批处理后回调数≠token 数）。
                val stoppedByUser = _uiState.value.stopRequested &&
                    _uiState.value.activeGenerationId == generationId
                termReason = when {
                    stoppedByUser -> "已停止（保留部分输出）"
                    localCompletionReason == com.rhodesisland.terminal.llm.metrics.CompletionReason.TIMEOUT -> "生成超时"
                    localCompletionReason == com.rhodesisland.terminal.llm.metrics.CompletionReason.MAX_TOKENS -> "达到生成上限"
                    else -> "完成: $generatedTokens tokens"
                }
                val completionState = if (stoppedByUser) {
                    stoppedCompletionState(displayResponse)
                } else {
                    MessageCompletionState.COMPLETE
                }
                // 统一完成/停止落库：插入 assistant（+自动视频 outbox 同事务）、touch、乐观显示、清理流式状态。
                finalizeAssistant(
                    charId = charId,
                    convId = convId,
                    senderName = state.characterName.ifEmpty { "AI" },
                    displayResponse = displayResponse,
                    modelText = modelText,
                    completionState = completionState,
                    autoVideoSnapshot = autoVideoSnapshot,
                    character = autoCharacter,
                    userText = userDisplayText,
                    characterImageSource = autoCharacterImageSource,
                )
            } catch (e: CancellationException) {
                // 取消（切角色/切会话）必须传播，不能当普通错误处理，否则破坏结构化并发
                throw e
            } catch (e: Exception) {
                val stoppedByUser = _uiState.value.stopRequested &&
                    _uiState.value.activeGenerationId == generationId
                if (stoppedByUser) {
                    // 云端显式停止：HTTP 调用被取消表现为 IOException。此时不是错误——保留部分输出，
                    // 不删除已落库的用户消息，不展示错误横幅。
                    termReason = "已停止（保留部分输出）"
                    val partial = latestAccumulated
                    finalizeAssistant(
                        charId = charId,
                        convId = convId,
                        senderName = state.characterName.ifEmpty { "AI" },
                        displayResponse = partial,
                        modelText = null,
                        completionState = stoppedCompletionState(partial),
                        autoVideoSnapshot = autoVideoSnapshot,
                        character = autoCharacter,
                        userText = userDisplayText,
                        characterImageSource = autoCharacterImageSource,
                    )
                } else {
                    termReason = "出错: ${e.message ?: "请求失败"}"
                    // 回滚：删除已落库的用户消息（无对应回复，避免孤儿），恢复输入框内容，
                    // 让用户可直接重试而无需重输（重发产生新消息，不会重复）。
                    if (userMsgId != 0L) runCatching { container.chatRepository.deleteMessage(userMsgId) }
                    pendingFinal = null
                    _uiState.update { s ->
                        val msgs = s.messages.filterNot { it.id == "streaming" }.toMutableList()
                        s.copy(
                            messages = msgs,
                            isStreaming = false,
                            showTyping = false,
                            stopRequested = false,
                            activeGenerationId = null,
                            errorMessage = e.message ?: "请求失败",
                            inputText = text,
                            uploadedImages = images,
                            uploadedFiles = files,
                        )
                    }
                }
            } finally {
                // 取消路径下 catch 会 rethrow 跳过清理，用 finally 兜底重置流式状态，
                // 确保切角色/切会话后发送按钮不再禁用、无残留 streaming 消息。
                // Task 7：只有 finally 的 generationId 等于当前 activeGenerationId 才清理，
                // 防止旧请求迟到的 finally 抹掉新一轮请求的状态（切会话/发新消息后）。
                // 性能浮窗：写入终态日志（完成/出错/已停止），停生成后速率归零。
                container.performanceCollector.updateLog(termReason)
                container.performanceCollector.updateTokenRate(0f)
                if (_uiState.value.activeGenerationId == generationId) {
                    _uiState.update { s ->
                        if (s.isStreaming || s.messages.any { it.id == "streaming" }) {
                            s.copy(
                                messages = s.messages.filterNot { it.id == "streaming" },
                                isStreaming = false,
                                showTyping = false,
                                stopRequested = false,
                                activeGenerationId = null,
                            )
                        } else s
                    }
                }
            }
        }
    }

    /**
     * 用户停止后的完成状态（Task 7）。
     *
     * 优先按展示文本（含 `<think>` 折叠装饰）判断：思考模型「未闭合思考」会被 renderLocalThink 包装成
     * `<think>...`，因此能正确得到 STOPPED_BEFORE_FINAL；闭合思考后有正文则得到 STOPPED_PARTIAL。
     * 仅当没有任何真实输出（latestAccumulated 为空，如 prefill 阶段被停止、非思考模型展示为占位文案）时，
     * 回退原始文本判空，得到「尚未生成最终答案」。
     */
    private fun stoppedCompletionState(displayResponse: String): MessageCompletionState {
        val inspected = if (latestAccumulated.isBlank()) latestAccumulated else displayResponse
        return StoppedOutputInspector.inspect(inspected)
    }

    /**
     * 统一完成/用户停止落库（Task 7）：
     * - 助手消息与自动视频 outbox **同事务**落库（[ChatCompletionRepository.finalizeAssistant]），
     *   进程在回复保存后死亡也不漏自动视频；事务提交后在工作管理器入队视频任务；
     * - 以返回的**行 ID** 构造乐观完成消息（key=`msg-$assistantRowId`），与 Room 回填 key 一致，
     *   并登记 pendingFinal（renderMessages 在 Room 确认前保留、确认后只显示一次，杜绝回答消失竞态）；
     * - 替换 streaming 气泡、清理 isStreaming/stopRequested/activeGenerationId。
     *
     * [autoVideoSnapshot]/[character]/[characterImageSource] 为发送起点捕获值；停止路径
     * [shouldCreateAutoVideo] 恒为 false，不会实际创建 outbox。
     */
    private suspend fun finalizeAssistant(
        charId: String,
        convId: Long,
        senderName: String,
        displayResponse: String,
        modelText: String?,
        completionState: MessageCompletionState,
        autoVideoSnapshot: AutoVideoTriggerSnapshot?,
        character: Character?,
        userText: String,
        characterImageSource: String?,
    ) {
        val assistantMessage = ChatMessage(
            role = "assistant",
            content = displayResponse,
            modelContent = modelText,
            completionState = completionState,
        )
        val outbox = buildAutoVideoOutbox(
            snapshot = autoVideoSnapshot,
            assistant = assistantMessage,
            conversationId = convId,
            character = character,
            userText = userText,
            characterImageSource = characterImageSource,
        )
        val finalized = chatCompletionRepository.finalizeAssistant(charId, convId, assistantMessage, outbox)
        val assistantRowId = finalized.assistantMessageId
        // 事务外入队（WorkManager KEEP 语义）：进程死亡由启动恢复 [recoverPending] 兜底。
        val videoTaskId = finalized.videoTaskId
        if (videoTaskId != null) {
            runCatching { container.seedanceVideoScheduler.enqueue(videoTaskId) }
        }
        // 刷新会话 updatedAt，把它顶到列表最前
        container.conversationRepository.touch(convId)

        val finalShowThink = _uiState.value.deepThinkingEnabled
        val assistantSrc = if (finalShowThink) displayResponse else MarkdownParser.stripThink(displayResponse)
        val assistantDisplay = DisplayMessage(
            id = "msg-$assistantRowId",
            role = "assistant",
            content = displayResponse,
            segments = MarkdownParser.parseWithThink(assistantSrc, isStreaming = false),
            sender = senderName,
            completionState = completionState,
            databaseId = assistantRowId,
        )
        pendingFinal = PendingFinal(
            conversationId = convId,
            databaseId = assistantRowId,
            message = assistantDisplay,
        )
        _uiState.update { s ->
            val msgs = s.messages.toMutableList()
            val streamIdx = msgs.indexOfFirst { it.id == "streaming" }
            // 若 Room 回填已先于乐观替换到达（msgs 已含同 id 的 assistant 行），
            // 只移除 streaming，绝不重复添加；否则以乐观消息替换 streaming。
            val alreadyRendered = msgs.any { it.id == assistantDisplay.id }
            if (alreadyRendered) {
                if (streamIdx >= 0) msgs.removeAt(streamIdx)
            } else {
                if (streamIdx >= 0) msgs[streamIdx] = assistantDisplay
                else msgs.add(assistantDisplay)
            }
            s.copy(
                messages = msgs,
                isStreaming = false,
                showTyping = false,
                stopRequested = false,
                activeGenerationId = null,
            )
        }
    }

    /**
     * 构建自动视频 outbox 草稿（Task 7）。
     *
     * 触发条件由 [shouldCreateAutoVideo] 纯策略判定；不满足时返回 null（仅普通落库助手消息）。
     * 快照来自发送起点捕获，任务不随源头变化而漂移；[characterImageSource] 为角色立绘来源
     * （内置角色 assets 相对路径 / 自定义角色 `file://` 内部路径，与
     * [com.rhodesisland.terminal.video.SeedanceReferenceStore] 的读取方式一致）。
     */
    private fun buildAutoVideoOutbox(
        snapshot: AutoVideoTriggerSnapshot?,
        assistant: ChatMessage,
        conversationId: Long,
        character: Character?,
        userText: String,
        characterImageSource: String?,
    ): AutoVideoOutboxDraft? {
        if (snapshot == null || character == null) return null
        if (!shouldCreateAutoVideo(snapshot, assistant)) return null
        return AutoVideoOutboxDraft(
            taskUuid = "auto-${snapshot.userMessageId}-${System.currentTimeMillis()}",
            triggerType = "auto",
            sourceConversationId = conversationId,
            sourceUserMessageId = snapshot.userMessageId,
            characterIdSnapshot = character.id,
            characterNameSnapshot = character.name,
            characterRoleSnapshot = character.role,
            characterSystemPromptSnapshot = character.systemPrompt,
            userTextSnapshot = userText,
            assistantTextSnapshot = assistant.content,
            sceneDescriptionSnapshot = snapshot.seedanceConfig.sceneDescription,
            promptBaseUrlSnapshot = snapshot.apiConfig.baseUrl,
            promptModelSnapshot = snapshot.apiConfig.model,
            characterImageSourceSnapshot = characterImageSource.orEmpty(),
            backgroundImageSourceSnapshot = snapshot.seedanceConfig.backgroundImagePath,
            modelVariant = snapshot.seedanceConfig.variant,
            resolution = snapshot.seedanceConfig.resolution,
            ratio = snapshot.seedanceConfig.ratio,
            durationSeconds = snapshot.seedanceConfig.durationSeconds,
            generateAudio = snapshot.seedanceConfig.generateAudio,
            watermark = snapshot.seedanceConfig.watermark,
        )
    }

    /**
     * 解析用户消息中的附件，转为可发送给 API 的内容（直连对话商，不经代理）：
     * - 图片：多模态模型直接以 base64 image_url 透传；非多模态模型无法识图（直连后无服务端兜底）-> 抛清晰错误。
     * - 文档：PDF 逐页渲染送多模态模型提取文字；纯文本直接读；Office 报错引导转 PDF。
     * 过期的 content URI 会被静默跳过（瞬时失败）；格式/多模态/缺 Key 等可操作错误上抛。
     */
    private suspend fun resolveMultimodalMessage(msg: ChatMessage): ChatMessage {
        val ctx = getApplication<android.app.Application>()
        val apiConfig = container.settingsRepository.getApiConfigNow()
        // 本地模型不具备多模态能力；仅云端 + 多模态模型才直传图片
        val activeType = container.chatProviderManager.activeProviderType.first()
        val isMultimodal = activeType != ChatProviderType.LOCAL &&
            container.documentRepository.isMultimodalModel(apiConfig.model)

        val base64Images = mutableListOf<String>()
        for (uri in msg.images) {
            container.documentRepository.uriToBase64(ctx, uri)?.let { base64Images.add(it) }
        }
        val extra = StringBuilder()

        // 文档 -> 提取文本（直连对话商）
        for (file in msg.files) {
            val extracted = try {
                container.documentRepository.extractDocumentText(ctx, file.path, file.name, apiConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 可操作错误（格式不支持 / 需多模态 / 缺 Key）上抛让用户可见；其余瞬时失败静默跳过
                if (e.message?.let { m -> listOf("暂不支持", "多模态", "API Key").any { it in m } } == true) throw e
                null
            }
            if (!extracted.isNullOrBlank()) {
                extra.append("\n[文档：").append(file.name).append("]\n").append(extracted)
            }
        }

        // 图片 -> 多模态直传；非多模态模型上抛清晰提示（替代原 OCR 兜底，直连后无法实现）
        val multimodalImages = if (isMultimodal) base64Images else emptyList()
        if (!isMultimodal && base64Images.isNotEmpty()) {
            throw Exception("当前模型不支持图片识别，请切换多模态模型（如 GPT-4o / Qwen-VL）")
        }

        val newContent = if (extra.isEmpty()) msg.content else msg.content + extra.toString()
        return msg.copy(content = newContent, multimodalImages = multimodalImages)
    }

    /** 历史消息附件宽容解析：任何失败（取消除外）降级为纯文本，绝不阻塞本次发送。 */
    private suspend fun resolveHistoryAttachmentLenient(msg: ChatMessage): ChatMessage =
        try {
            resolveMultimodalMessage(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg.copy(multimodalImages = emptyList())
        }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * 删除单条消息（用户问题或助手回答）。
     *
     * 仅删除已落库的持久消息（[DisplayMessage.databaseId] 非空）；流式气泡不提供入口。
     * 删除后 Room Flow 自动重渲染；若删除的是乐观完成消息（pendingFinal），先同步清除该
     * pending 并从列表移除，避免 Room 旧快照在删除落库前把消息重新带回。
     * 关联的 Seedance 视频任务保留（其来源快照独立，删除聊天消息不影响已完成视频）。
     */
    fun deleteMessage(databaseId: Long?) {
        val id = databaseId ?: return
        if (id <= 0L) return
        viewModelScope.launch {
            if (_activeConversationId.value == null) return@launch
            // 正在展示的乐观完成消息：先清 pending 并从列表移除，防止 Room 旧快照把它带回来。
            if (pendingFinal?.databaseId == id) {
                pendingFinal = null
                _uiState.update { s ->
                    s.copy(messages = s.messages.filterNot { it.id == "msg-$id" })
                }
            }
            container.chatRepository.deleteMessage(id)
            // 兜底：DB 删除提交后（Room 已不会再回填该行），确保列表中不残留。
            _uiState.update { s ->
                if (s.messages.any { it.id == "msg-$id" }) {
                    s.copy(messages = s.messages.filterNot { it.id == "msg-$id" })
                } else s
            }
        }
    }

    /** TTS 播放 */
    fun playTts(message: DisplayMessage) {
        // 已在加载或播放中：本次点按视为停止（toggle off）。取消进行中的加载任务并停止播放，
        // 避免翻译/加载窗口内连点导致两个 TTS 并发、ttsLoadingIndex / 字幕互相覆盖。
        val active = ttsJob
        if (container.ttsManager.playing || (active != null && active.isActive)) {
            active?.cancel()
            container.ttsManager.stopAll()
            _uiState.update {
                it.copy(
                    ttsLoadingIndex = -1,
                    ttsPlayingIndex = -1,
                    ttsSubtitleJp = "",
                    ttsSubtitleCn = "",
                    showSwitchSubtitle = false,
                )
            }
            return
        }
        ttsJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(ttsLoadingIndex = it.messages.indexOf(message), showSwitchSubtitle = false) }
                val cleanText = container.ttsManager.cleanTtsText(message.content)
                val lang = container.settingsRepository.getTtsLanguageNow()
                val engine = container.settingsRepository.getTtsEngineNow()

                // 日语：仅云端引擎走 LLM 翻译；系统引擎直接朗读原文（设备无日语语音时由引擎报清晰错误），
                // 避免为系统 TTS 白白消耗一次翻译调用。
                val speakText = if (lang == TtsLanguage.JA && engine == TtsEngine.CLOUD) {
                    // 日语 -> 翻译
                    try {
                        translateToJapanese(cleanText)
                    } catch (e: Exception) {
                        cleanText
                    }
                } else {
                    cleanText
                }

                _uiState.update {
                    it.copy(
                        ttsPlayingIndex = it.messages.indexOf(message),
                        ttsSubtitleCn = cleanText,
                        ttsSubtitleJp = if (lang == TtsLanguage.JA) speakText else "",
                    )
                }
                container.ttsManager.speak(speakText, _uiState.value.characterId)
                _uiState.update {
                    it.copy(
                        ttsLoadingIndex = -1,
                        ttsPlayingIndex = -1,
                        ttsSubtitleJp = "",
                        ttsSubtitleCn = "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        ttsLoadingIndex = -1,
                        ttsPlayingIndex = -1,
                        ttsSubtitleJp = "",
                        ttsSubtitleCn = "",
                        errorMessage = "TTS 失败: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun translateToJapanese(text: String): String {
        val apiConfig = container.settingsRepository.getApiConfigNow()
        if (apiConfig.apiKey.isBlank()) return text
        // 直连对话商：用翻译 prompt 调一次非流式 chat，不经代理
        val messages = listOf(
            ChatMessageDto("system", JsonPrimitive("你是专业翻译。将下面的中文翻译成自然日文，仅输出译文，不要解释或加引号。")),
            ChatMessageDto("user", JsonPrimitive(text)),
        )
        val translated = container.directLlmClient.chatOnce(
            baseUrl = apiConfig.baseUrl,
            apiKey = apiConfig.apiKey,
            model = apiConfig.model,
            messages = messages,
        )
        return translated.ifBlank { text }
    }

    override fun onCleared() {
        super.onCleared()
        container.chatProviderManager.cancelAll()
        container.ttsManager.stopAll()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
