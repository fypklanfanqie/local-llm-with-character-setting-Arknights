package com.rhodesisland.terminal.ui.chat

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.Characters
import com.rhodesisland.terminal.data.model.*
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.repository.ConversationRepository
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

    init {
        // 监听活跃角色变化：加载角色信息。
        // 只绑定 activeCharacter（不绑会话映射），避免 setActiveConversation 触发重复 loadCharacter / 重播语音。
        viewModelScope.launch {
            container.settingsRepository.activeCharacter.collect { charId ->
                streamingJob?.cancel()
                container.chatProviderManager.cancelAll()
                loadCharacter(charId)
            }
        }
        // 监听角色 + 活跃会话映射：确定该角色的活跃会话；无（或已被删除）则自动新建「新对话」。
        // setActiveConversation 会引发 combine 重发，但届时会话已存在 -> 走 if 分支，幂等无环。
        viewModelScope.launch {
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
        }
        // 监听活跃会话 + 聊天记录（flatMapLatest 保证会话切换时取消旧订阅，避免历史串台）
        viewModelScope.launch {
            _activeConversationId
                .flatMapLatest { id ->
                    if (id != null) container.chatRepository.getHistoryFlow(id)
                    else flowOf(emptyList())
                }
                .collect { history ->
                    latestHistory = history
                    renderMessages(history)
                }
        }
        // 监听当前角色的会话列表（供抽屉展示 + 同步当前会话标题）
        viewModelScope.launch {
            container.settingsRepository.activeCharacter
                .flatMapLatest { charId -> container.conversationRepository.observeByCharacter(charId) }
                .collect { list ->
                    _uiState.update { it.copy(conversations = list) }
                    syncActiveMeta()
                }
        }
        // 监听活跃会话变化 -> 同步标题/高亮（切换/新建/删除后立即生效）
        viewModelScope.launch {
            _activeConversationId.collect { syncActiveMeta() }
        }
        // 监听 Provider 类型变化
        viewModelScope.launch {
            container.chatProviderManager.activeProviderType.collect { type ->
                _uiState.update { it.copy(activeProvider = type) }
            }
        }
        // 监听 TTS 语言
        viewModelScope.launch {
            container.settingsRepository.ttsLanguage.collect { lang ->
                _uiState.update { it.copy(ttsLanguage = lang) }
            }
        }
        // 监听深度思考开关：更新 UI 态并重渲染已有消息（show/hide 思考过程）
        viewModelScope.launch {
            container.settingsRepository.deepThinking.collect { enabled ->
                _uiState.update { it.copy(deepThinkingEnabled = enabled) }
                renderMessages(latestHistory)
            }
        }
    }

    /** 从会话列表 + 当前活跃 id 同步 uiState 的 activeConversationId / activeConversationTitle */
    private fun syncActiveMeta() {
        val list = _uiState.value.conversations
        val id = _activeConversationId.value
        val active = list.firstOrNull { it.id == id }
        _uiState.update {
            it.copy(
                activeConversationId = id,
                activeConversationTitle = active?.title ?: ConversationRepository.DEFAULT_TITLE,
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

    private fun renderMessages(history: List<ChatMessage>) {
        // 流式输出期间，历史 Flow 刷新不应抹掉正在生成的 streaming 消息，否则会闪烁
        // 仅在当前确有流式生成（isStreaming=true）时保留 streaming 气泡；切会话/角色时
        // isStreaming 已同步置 false，避免旧会话的 streaming 气泡在历史 Flow 先于 streamingJob
        // finally 触发时被错误拼进新会话列表。
        val streaming = if (_uiState.value.isStreaming) {
            _uiState.value.messages.firstOrNull { it.id == "streaming" }
        } else null
        if (history.isEmpty()) {
            val msgs = if (streaming != null) listOf(streaming) else emptyList()
            _uiState.update { it.copy(messages = msgs, showWelcome = streaming == null) }
            return
        }
        val showThink = _uiState.value.deepThinkingEnabled
        val messages = history.mapIndexed { idx, msg ->
            val src = if (showThink) msg.content else MarkdownParser.stripThink(msg.content)
            val segments = MarkdownParser.parseWithThink(src, isStreaming = false)
            DisplayMessage(
                // 用 timestamp+idx 生成稳定 id，避免清屏重发后同槽位折叠状态串到新代码块
                id = "msg-${msg.timestamp}-$idx",
                role = msg.role,
                content = msg.content,
                segments = segments,
                sender = if (msg.role == "user") "DOCTOR // YOU" else (_uiState.value.characterName.ifEmpty { "OPERATOR" }),
                images = msg.images,
                files = msg.files,
            )
        }
        val finalMessages = if (streaming != null) messages + streaming else messages
        _uiState.update { it.copy(messages = finalMessages, showWelcome = false) }
    }

    // ===== 会话管理 =====

    /** 新建会话并切换为活跃。清空输入/附件，历史自然为空。 */
    fun newConversation() {
        viewModelScope.launch {
            val charId = _uiState.value.characterId
            streamingJob?.cancel()
            container.chatProviderManager.cancelAll()
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
            val charId = _uiState.value.characterId
            container.settingsRepository.setActiveConversation(charId, id)
            _activeConversationId.value = id
            _uiState.update { it.copy(showConversationSheet = false, isStreaming = false, showTyping = false) }
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
            container.chatProviderManager.switchProvider(type)
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
        val convId = _activeConversationId.value ?: return

        _uiState.update {
            it.copy(inputText = "", uploadedImages = emptyList(), uploadedFiles = emptyList(), isStreaming = true, showTyping = true, showWelcome = false)
        }

        streamingJob = viewModelScope.launch {
            // 性能浮窗日志终态：默认「已停止」（取消路径），成功/出错时覆盖。
            // 须在 try 外声明，catch/finally 才可见（try 块内声明的局部变量不对 catch/finally 可见）。
            var termReason = "已停止"
            var userMsgId = 0L   // 已落库用户消息 id；发送失败时 catch 据此回滚删除
            try {
                val char = container.characterRepository.getNow(charId)
                    ?: throw Exception("角色不存在")
                // 存储用户消息
                val displayText = text.ifEmpty {
                    when {
                        images.isNotEmpty() -> "[图片]"
                        files.isNotEmpty() -> "[文件]"
                        else -> "[附件]"
                    }
                }
                val userMessage = ChatMessage(
                    role = "user",
                    content = displayText,
                    images = images,
                    files = files,
                    fileNames = files.map { it.name },
                )
                userMsgId = container.chatRepository.addMessage(charId, convId, userMessage)

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
                        resolveMultimodalMessage(msg)
                    } else {
                        msg
                    }
                }
                val isCloudProvider = container.settingsRepository.getActiveProviderNow() == ChatProviderType.CLOUD
                val apiMessages = buildList {
                    add(ChatMessage(role = "system", content = char.systemPrompt))
                    addAll(resolvedHistory.map {
                        // 云端历史含 <think>（注入的推理），回传前剥离（reasoning 不应回传给对话商）
                        val c = if (isCloudProvider) MarkdownParser.stripThink(it.content) else it.content
                        ChatMessage(role = it.role, content = c, multimodalImages = it.multimodalImages)
                    })
                }

                // 性能浮窗：记录生成起点，重置速率（Token 速率由下方回调实时更新，不依赖 500ms 刷新）
                container.performanceCollector.updateTokenRate(0f)
                container.performanceCollector.updateLog("生成中…")
                val genStart = SystemClock.elapsedRealtime()
                var genTokens = 0
                // 流式 UI 节流：见 STREAM_THROTTLE_MS。lastStreamRenderMs 在同一条消息的串行回调内访问，
                // onChunk 由 nativeGenerateStream 同步回调（同一 IO 线程、一次一个），无需同步。
                var lastStreamRenderMs = 0L

                // 调用 Provider
                val showThink = _uiState.value.deepThinkingEnabled
                val provider = container.chatProviderManager.getActiveProvider()
                val response = provider.chat(apiMessages) { accumulated ->
                    // 性能浮窗：按 chunk 近似计数 token，实时更新 tok/s 与日志
                    genTokens++
                    val elapsedSec = (SystemClock.elapsedRealtime() - genStart) / 1000f
                    container.performanceCollector.updateTokenRate(if (elapsedSec > 0) genTokens / elapsedSec else 0f)
                    container.performanceCollector.updateLog("已生成 $genTokens tokens")
                    // 节流：仅首块或距上次重渲染 >= STREAM_THROTTLE_MS 时才重解析 Markdown + 重组列表。
                    // 末块若被跳过，下方完成路径会用完整 response 覆盖并落库，不会丢字。
                    val now = SystemClock.elapsedRealtime()
                    if (genTokens == 1 || now - lastStreamRenderMs >= STREAM_THROTTLE_MS) {
                        lastStreamRenderMs = now
                        // 流式更新
                        val streamingMsg = DisplayMessage(
                            id = "streaming",
                            role = "streaming",
                            content = accumulated,
                            segments = if (showThink) MarkdownParser.parseWithThink(accumulated, isStreaming = true)
                                else MarkdownParser.parseWithThink(MarkdownParser.stripThink(accumulated), isStreaming = true),
                            sender = state.characterName.ifEmpty { "OPERATOR" },
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

                // 流式完成 -> 移除临时消息，存储正式回复
                termReason = "完成: $genTokens tokens"
                val assistantMessage = ChatMessage(role = "assistant", content = response)
                container.chatRepository.addMessage(charId, convId, assistantMessage)
                // 刷新会话 updatedAt，把它顶到列表最前
                container.conversationRepository.touch(convId)

                _uiState.update { s ->
                    val msgs = s.messages.filterNot { it.id == "streaming" }.toMutableList()
                    s.copy(messages = msgs, isStreaming = false, showTyping = false)
                }
            } catch (e: CancellationException) {
                // 取消（切角色/切会话）必须传播，不能当普通错误处理，否则破坏结构化并发
                throw e
            } catch (e: Exception) {
                termReason = "出错: ${e.message ?: "请求失败"}"
                // 回滚：删除已落库的用户消息（无对应回复，避免孤儿），恢复输入框内容，
                // 让用户可直接重试而无需重输（重发产生新消息，不会重复）。
                if (userMsgId != 0L) runCatching { container.chatRepository.deleteMessage(userMsgId) }
                _uiState.update { s ->
                    val msgs = s.messages.filterNot { it.id == "streaming" }.toMutableList()
                    s.copy(
                        messages = msgs,
                        isStreaming = false,
                        showTyping = false,
                        errorMessage = e.message ?: "请求失败",
                        inputText = text,
                        uploadedImages = images,
                        uploadedFiles = files,
                    )
                }
            } finally {
                // 取消路径下 catch 会 rethrow 跳过清理，用 finally 兜底重置流式状态，
                // 确保切角色/切会话后发送按钮不再禁用、无残留 streaming 消息
                // 性能浮窗：写入终态日志（完成/出错/已停止），停生成后速率归零
                container.performanceCollector.updateLog(termReason)
                container.performanceCollector.updateTokenRate(0f)
                _uiState.update { s ->
                    if (s.isStreaming || s.messages.any { it.id == "streaming" }) {
                        s.copy(
                            messages = s.messages.filterNot { it.id == "streaming" },
                            isStreaming = false,
                            showTyping = false,
                        )
                    } else s
                }
            }
        }
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
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

                val speakText = if (lang == TtsLanguage.JA) {
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
}
