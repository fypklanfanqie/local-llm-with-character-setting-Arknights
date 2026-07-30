package com.rhodesisland.terminal

import android.content.Context
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.local.SettingsStore
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import com.rhodesisland.terminal.data.remote.RetrofitClient
import com.rhodesisland.terminal.data.repository.AssetRepository
import com.rhodesisland.terminal.data.repository.CharacterRepository
import com.rhodesisland.terminal.data.repository.ChatBackgroundRepository
import com.rhodesisland.terminal.data.repository.ChatRepository
import com.rhodesisland.terminal.data.repository.ConversationRepository
import com.rhodesisland.terminal.data.repository.DocumentRepository
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.download.DownloadManager
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.rhodesisland.terminal.manager.AudioManager
import com.rhodesisland.terminal.manager.ModelManager
import com.rhodesisland.terminal.manager.TtsManager
import com.rhodesisland.terminal.perfmon.PerformanceCollector
import com.rhodesisland.terminal.provider.ChatProviderManager
import com.rhodesisland.terminal.provider.cloud.CloudChatProvider
import com.rhodesisland.terminal.provider.local.LocalChatProvider
import com.rhodesisland.terminal.tts.VolcTtsClient

/**
 * 手动 DI 容器
 * 集中管理所有单例依赖
 */
class AppContainer(private val context: Context) {

    // ===== 本地存储 =====
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    // ===== 仓库 =====
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsStore) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(database.conversationDao()) }
    val assetRepository: AssetRepository by lazy { AssetRepository(context) }
    val documentRepository: DocumentRepository by lazy { DocumentRepository(directLlmClient) }
    val characterRepository: CharacterRepository by lazy { CharacterRepository(settingsRepository) }

    // 通讯界面背景：内置 PRTS 轮播 + 用户自定义图片（最多 20 张，复制到内部存储）。
    val chatBackgroundRepository: ChatBackgroundRepository by lazy {
        ChatBackgroundRepository(context, assetRepository, settingsStore)
    }

    // ===== 网络 API =====
    /** 直连对话商 OpenAI 兼容 API 客户端（云端对话/翻译/文档提取，不经代理） */
    val directLlmClient: DirectLlmClient by lazy { DirectLlmClient(RetrofitClient.streamingClient) }

    // ===== TTS =====
    val ttsClient: VolcTtsClient by lazy { VolcTtsClient(AppConfig.TTS_PROXY_URL, RetrofitClient.okHttpClient) }
    val ttsManager: TtsManager by lazy { TtsManager(context, ttsClient, settingsRepository) }

    // ===== 音频 =====
    val audioManager: AudioManager by lazy { AudioManager(context, settingsRepository) }

    // ===== 本地 LLM =====
    // CPU 提频控制器（非 root：PerformanceHintManager hint session + 推理线程高优先级；
    // SustainedPerformanceMode 在 MainActivity 窗口级开启）。enabled 由 LocalChatProvider 同步设置开关，
    // 在 MnnBackend.generateStreamMessages 内包住 nativeGenerateStream 生效。
    val cpuBoostController: CpuBoostController by lazy { CpuBoostController(context) }

    // 推理后端管理器：MNN CPU / OpenCL GPU / QNN NPU，按偏好选择并支持回退链
    val backendManager: BackendManager by lazy { BackendManager(context, cpuBoostController) }

    // ===== Chat Provider =====
    val cloudChatProvider: CloudChatProvider by lazy {
        CloudChatProvider(directLlmClient, settingsRepository)
    }
    val localChatProvider: LocalChatProvider by lazy {
        LocalChatProvider(context, backendManager, settingsRepository, cpuBoostController)
    }
    val chatProviderManager: ChatProviderManager by lazy {
        ChatProviderManager(cloudChatProvider, localChatProvider, settingsRepository)
    }

    // ===== 性能监控浮窗（仅本地聊天界面显示；应用内液态玻璃，见 PerformanceGlassOverlay）=====
    // collector 复用 localChatProvider 已封装的 native 频率/温度读取。浮窗在 ChatScreen 内组合，
    // 直接读 settingsRepository.liquidGlass 获取玻璃开关，无需此处缓存/推送。
    val performanceCollector: PerformanceCollector by lazy {
        PerformanceCollector(context, localChatProvider)
    }

    // ===== 模型管理 =====
    val downloadManager: DownloadManager by lazy { DownloadManager(context) }
    val modelManager: ModelManager by lazy {
        ModelManager(context, downloadManager, settingsRepository, backendManager)
    }
}
