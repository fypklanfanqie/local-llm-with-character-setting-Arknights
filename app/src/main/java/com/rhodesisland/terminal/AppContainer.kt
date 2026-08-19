package com.rhodesisland.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.AssetPaths
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.local.SettingsStore
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.remote.CreateSeedanceTask
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import com.rhodesisland.terminal.data.remote.RetrofitClient
import com.rhodesisland.terminal.data.remote.SeedanceClient
import com.rhodesisland.terminal.data.remote.SeedanceImageContent
import com.rhodesisland.terminal.data.repository.AssetRepository
import com.rhodesisland.terminal.data.repository.CharacterRepository
import com.rhodesisland.terminal.data.repository.ChatBackgroundRepository
import com.rhodesisland.terminal.data.repository.ChatRepository
import com.rhodesisland.terminal.data.repository.ConversationRepository
import com.rhodesisland.terminal.data.repository.DocumentRepository
import com.rhodesisland.terminal.data.repository.GroupChatRepository
import com.rhodesisland.terminal.data.repository.MusicLibraryRepository
import com.rhodesisland.terminal.data.repository.SeedanceVideoRepository
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.conversationexport.ConversationExportService
import com.rhodesisland.terminal.affinity.AffinityRepository
import com.rhodesisland.terminal.affinity.SpecialEventCatalog
import com.rhodesisland.terminal.affinity.SpecialEventConversationCoordinator
import com.rhodesisland.terminal.download.DownloadManager
import com.rhodesisland.terminal.video.DirectLlmSeedancePromptLlm
import com.rhodesisland.terminal.video.SeedanceConversationContextProvider
import com.rhodesisland.terminal.video.SeedanceImageEncoder
import com.rhodesisland.terminal.video.SeedancePipelineCoordinator
import com.rhodesisland.terminal.video.SeedancePromptGenerator
import com.rhodesisland.terminal.video.SeedanceReferenceStore
import com.rhodesisland.terminal.video.SeedanceRepositoryPipelineStore
import com.rhodesisland.terminal.video.SeedanceSnapshotSourceResolver
import com.rhodesisland.terminal.video.SeedanceSnapshotSources
import com.rhodesisland.terminal.video.SeedanceSubmitter
import com.rhodesisland.terminal.video.SeedanceVideoDownload
import com.rhodesisland.terminal.video.SeedanceVideoDownloader
import com.rhodesisland.terminal.video.SeedanceVideoFileStore
import com.rhodesisland.terminal.work.SeedanceVideoScheduler
import com.rhodesisland.terminal.llm.CpuBoostController
import com.rhodesisland.terminal.llm.backend.BackendHealthCoordinator
import com.rhodesisland.terminal.llm.backend.BackendHealthStore
import com.rhodesisland.terminal.llm.backend.BackendManager
import com.rhodesisland.terminal.llm.backend.GpuPreheatCoordinator
import com.rhodesisland.terminal.llm.backend.IdleOpenClProbeCoordinator
import com.rhodesisland.terminal.llm.backend.OpenClProbeRunner
import com.rhodesisland.terminal.notification.AppLifecycleObserver
import com.rhodesisland.terminal.llm.benchmark.DefaultLocalInferenceBenchmarkRunner
import com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore
import com.rhodesisland.terminal.manager.AudioManager
import com.rhodesisland.terminal.manager.ModelManager
import com.rhodesisland.terminal.manager.TtsManager
import com.rhodesisland.terminal.perfmon.PerformanceCollector
import com.rhodesisland.terminal.provider.ChatProviderManager
import com.rhodesisland.terminal.provider.cloud.CloudChatProvider
import com.rhodesisland.terminal.provider.local.LocalChatProvider
import com.rhodesisland.terminal.tts.VolcTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

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
    val affinityRepository: AffinityRepository by lazy { AffinityRepository(database) }
    val specialEventCatalog: SpecialEventCatalog by lazy { SpecialEventCatalog(context) }
    val specialEventConversationCoordinator: SpecialEventConversationCoordinator by lazy {
        SpecialEventConversationCoordinator(
            database = database,
            affinityRepository = affinityRepository,
            conversations = conversationRepository,
            chats = chatRepository,
            settings = settingsRepository,
            catalog = specialEventCatalog,
        )
    }
    val conversationExportService: ConversationExportService by lazy {
        ConversationExportService(conversationRepository, chatRepository, characterRepository)
    }

    // 群聊：复用 conversation + chat_history（哨兵 characterId），仅云端可用。
    val groupChatRepository: GroupChatRepository by lazy {
        GroupChatRepository(conversationRepository, chatRepository)
    }

    // 通讯界面背景：内置 PRTS 轮播 + 用户自定义图片（最多 20 张，复制到内部存储）。
    val chatBackgroundRepository: ChatBackgroundRepository by lazy {
        ChatBackgroundRepository(context, assetRepository, settingsStore)
    }

    // 音乐库：本地导入 + 在线添加曲目的持久化播放列表（文件拷贝到内部存储）。
    val musicLibrary: MusicLibraryRepository by lazy {
        MusicLibraryRepository(context, settingsStore)
    }

    // ===== 网络 API =====
    /** 直连对话商 OpenAI 兼容 API 客户端（云端对话/翻译/文档提取，不经代理） */
    val directLlmClient: DirectLlmClient by lazy { DirectLlmClient(RetrofitClient.streamingClient) }

    // ===== Seedance 视频流水线（Task 6：持久 Worker 状态机 + 内部归档）=====
    // 专用有限超时 OkHttp 客户端（不复用 RetrofitClient.streamingClient——后者无超时）。
    // connect 15s / read 60s / write 60s / call 120s；下载客户端放宽 read 以容纳大文件。
    private val seedanceHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val seedanceDownloadHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build()
    }

    val seedanceVideoRepository: SeedanceVideoRepository by lazy {
        SeedanceVideoRepository(database.seedanceVideoDao())
    }

    val seedanceClient: SeedanceClient by lazy { SeedanceClient(seedanceHttpClient) }

    val seedanceReferenceStore: SeedanceReferenceStore by lazy {
        SeedanceReferenceStore.production(context)
    }

    val seedanceVideoFileStore: SeedanceVideoFileStore by lazy {
        SeedanceVideoFileStore(File(context.filesDir, "seedance/tasks"))
    }

    val seedancePromptGenerator: SeedancePromptGenerator by lazy {
        SeedancePromptGenerator(DirectLlmSeedancePromptLlm(directLlmClient))
    }

    private val seedancePipelineStore: SeedanceRepositoryPipelineStore by lazy {
        SeedanceRepositoryPipelineStore(seedanceVideoRepository)
    }

    val seedancePipelineCoordinator: SeedancePipelineCoordinator by lazy {
        SeedancePipelineCoordinator(
            store = seedancePipelineStore,
            submitter = object : SeedanceSubmitter {
                override suspend fun create(config: SeedanceConfig, request: CreateSeedanceTask) =
                    seedanceClient.createTask(config, request)

                override suspend fun get(config: SeedanceConfig, taskId: String) =
                    seedanceClient.getTask(config, taskId)

                override suspend fun cancel(config: SeedanceConfig, taskId: String) =
                    seedanceClient.cancelQueuedTask(config, taskId)
            },
            promptProvider = seedancePromptGenerator::generate,
            conversationContextProvider = SeedanceConversationContextProvider { conversationId, userText, assistantText, maxTurns ->
                buildSeedanceConversationContext(conversationId, userText, assistantText, maxTurns)
            },
            snapshooter = seedanceReferenceStore::snapshot,
            resolveSnapshotSources = SeedanceSnapshotSourceResolver { task ->
                resolveSeedanceSnapshotSources(task)
            },
            downloadVideo = SeedanceVideoDownloader { url ->
                downloadSeedanceVideo(url)
            },
            fileStore = seedanceVideoFileStore,
            encoder = SeedanceImageEncoder { path, mime, maxBytes ->
                encodeSeedanceImage(path, mime, maxBytes)
            },
            apiConfigProvider = { settingsRepository.getApiConfigNow() },
            seedanceConfigProvider = { settingsRepository.getSeedanceConfigNow() },
        )
    }

    val seedanceVideoScheduler: SeedanceVideoScheduler by lazy {
        SeedanceVideoScheduler(context, seedancePipelineCoordinator, seedancePipelineStore)
    }

    /**
     * 构建提示词生成用的「前情对话」文本：取该会话最近消息（最新在前），
     * 剔除与本次用户发言/角色回复内容相同的两条（即当前这一轮），再取最多 [maxTurns] 条按时间正序拼接。
     * 单条截断 200 字；任何异常向上层抛由协调器降级为空串（绝不阻塞视频流水线）。
     */
    private suspend fun buildSeedanceConversationContext(
        conversationId: Long,
        currentUserText: String,
        currentAssistantText: String,
        maxTurns: Int,
    ): String {
        val messages = chatRepository.getHistory(conversationId)
        val relevant = messages.asReversed() // 最新在前
            .filter { it.content.isNotBlank() }
            .filter { it.content.trim() != currentUserText.trim() && it.content.trim() != currentAssistantText.trim() }
            .take(maxTurns)
            .asReversed() // 恢复时间正序
        return relevant.joinToString("\n") { msg ->
            val speaker = if (msg.role == "user") "用户" else "角色"
            "$speaker：${msg.content.trim().take(200)}"
        }
    }

    /** outbox 来源快照 -> 参考图仓库入参：内置角色用 assets 相对路径，自定义角色用 char.image。 */
    private suspend fun resolveSeedanceSnapshotSources(task: SeedanceVideo): SeedanceSnapshotSources? {
        val character = characterRepository.getNow(task.characterIdSnapshot) ?: return null
        val builtInAssetPath = if (character.isCustom) null else AssetPaths.PICTURES[task.characterIdSnapshot]
        return SeedanceSnapshotSources(
            character = character,
            builtInAssetPath = builtInAssetPath,
            backgroundImagePath = task.backgroundImageSourceSnapshot,
        )
    }

    /**
     * 参考图内部文件 -> base64 图片内容（读取在 Worker 的 IO 线程，不整读入 UI 线程）。
     *
     * [maxBytes] 为单张图片（base64 解码后）字节上限。原图不超限时直接原样编码；
     * 超限时降采样 + JPEG 质量梯度重编码至达标（中转站媒体协议单张 ≤10MB，立绘 PNG 常超限）。
     * 压缩后仍超限则抛异常，由协调器按「参考图缺失或不可读」处理，绝不发送可能被服务端拒绝的超限图片。
     */
    private suspend fun encodeSeedanceImage(path: String, mime: String, maxBytes: Long): SeedanceImageContent =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile) throw IllegalStateException("参考图文件不存在")
            val bytes = file.readBytes()
            if (bytes.size <= maxBytes) {
                return@withContext SeedanceImageContent(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            val compressed = compressImageToFit(file, maxBytes)
                ?: throw IllegalStateException("参考图压缩后仍超过 ${maxBytes / (1024 * 1024)}MB 限制，无法提交")
            SeedanceImageContent("image/jpeg", Base64.encodeToString(compressed, Base64.NO_WRAP))
        }

    /** 降采样 + JPEG 质量梯度压缩，返回不超过 [maxBytes] 的字节；无法达标返回 null。 */
    private fun compressImageToFit(file: File, maxBytes: Long): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 目标长边从 2560 起步逐级减半重试，直到压缩产物达标或尺寸下限 640。
        var targetLongSide = 2560
        while (targetLongSide >= 640) {
            val inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetLongSide)
            val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            try {
                var quality = 90
                while (quality >= 60) {
                    val out = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                    val data = out.toByteArray()
                    if (data.size <= maxBytes) return data
                    quality -= 10
                }
            } finally {
                bitmap.recycle()
            }
            targetLongSide /= 2
        }
        return null
    }

    /** 计算 2 的幂降采样系数，使解码后长边不超过 [targetLongSide]。 */
    private fun computeSampleSize(width: Int, height: Int, targetLongSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= targetLongSide) sample *= 2
        return sample
    }

    /** GET 远端签名 URL，返回未消费的流（失败/非 2xx 返回 null）。 */
    private suspend fun downloadSeedanceVideo(url: String): SeedanceVideoDownload? =
        withContext(Dispatchers.IO) {
            try {
                val response = seedanceDownloadHttpClient.newCall(
                    Request.Builder().url(url).get().build(),
                ).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext null
                }
                val body = response.body
                if (body == null) {
                    response.close()
                    return@withContext null
                }
                SeedanceVideoDownload(
                    mime = body.contentType()?.toString(),
                    contentLength = body.contentLength().takeIf { it >= 0 },
                    stream = body.byteStream(),
                    onClose = { response.close() },
                )
            } catch (e: Exception) {
                null
            }
        }

    // ===== TTS =====
    private val ttsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    val ttsClient: VolcTtsClient by lazy { VolcTtsClient(AppConfig.TTS_DIRECT_URL, ttsHttpClient) }
    val ttsManager: TtsManager by lazy { TtsManager(context, ttsClient, settingsRepository) }

    // ===== 音频 =====
    val audioManager: AudioManager by lazy { AudioManager(context, settingsRepository) }

    // ===== 本地 LLM =====
    // CPU 提频控制器（非 root：PerformanceHintManager hint session + 推理线程高优先级；
    // SustainedPerformanceMode 在 MainActivity 窗口级开启）。enabled 由 LocalChatProvider 同步设置开关，
    // 在 MnnBackend.generateStreamMessages 内包住 nativeGenerateStream 生效。
    val cpuBoostController: CpuBoostController by lazy { CpuBoostController(context) }

    // Task 7：后端健康记录存储（与协调器共享同一实例；设置页「清除后端健康记录」整体重置）。
    val backendHealthStore: BackendHealthStore by lazy { BackendHealthStore(context) }

    // Task 7：推理选项认证存储（lookahead/步进基准证据）。LocalChatProvider 每轮按
    // device+model+variant+native 组合查证；设置页「运行基准并认证」落盘 /「清除实验认证」重置。
    val inferenceCertificationStore: InferenceCertificationStore by lazy {
        InferenceCertificationStore(context)
    }

    // Task 7：本地推理基准运行器（认证闭环用）。热检查由自建 ThermalMonitor 采样驱动
    // （API 29+/PowerManager 缺席时为 no-op，热守卫不拒绝——见 runner KDoc）。
    val benchmarkRunner: DefaultLocalInferenceBenchmarkRunner by lazy {
        DefaultLocalInferenceBenchmarkRunner(context, backendManager, settingsRepository)
    }

    // Task 3：后端健康协调器（OpenCL 探测/健康记录单点）。BackendManager 与 LocalChatProvider 共享
    // 同一实例，避免两套状态。健康键设备指纹 = healthDeviceFingerprintOf（Build/OS/SoC/ABI + 策略，
    // 不含 native 身份——native 重建不改变健康键，旧构建的失败教训仍适用于新构建，final review I2）。
    // 认证键设备指纹用 deviceFingerprintOf（含 native 身份，另经 certKey 显式 native 分量绑定）。
    // modelFingerprint 由调用方按当前模型逐轮传入（模型切换即新键）。
    val backendHealthCoordinator: BackendHealthCoordinator by lazy {
        BackendHealthCoordinator(
            store = backendHealthStore,
            deviceFingerprint = BackendHealthCoordinator.healthDeviceFingerprintOf(),
            probeRunner = OpenClProbeRunner.real(context),
        )
    }

    // 推理后端管理器：MNN CPU / OpenCL GPU / QNN NPU，按偏好选择并支持回退链
    val backendManager: BackendManager by lazy {
        BackendManager(context, cpuBoostController, backendHealthCoordinator)
    }

    // Task 15/16：GPU 完整预热（设置页手动按钮触发；加载当前 >7B 模型 + 极短生成预热 OpenCL）。
    val gpuPreheatCoordinator: GpuPreheatCoordinator by lazy {
        GpuPreheatCoordinator(context, backendManager, backendHealthCoordinator, settingsRepository)
    }

    // Task 15/16：前台空闲时的轻量 OpenCL 探测（只探测，绝不自动加载模型）。
    private var idleOpenClProbeCoordinator: IdleOpenClProbeCoordinator? = null

    /**
     * 启动前台空闲轻量 OpenCL 探测（幂等）：应用进入前台并空闲一段后，仅当「显式 GPU 或
     * AUTO+>7B 模型」且健康记录确需探测时，在隔离进程执行一次轻量探测。绝不加载模型。
     */
    fun startIdleOpenClProbe(appScope: kotlinx.coroutines.CoroutineScope) {
        if (idleOpenClProbeCoordinator != null) return
        val coordinator = IdleOpenClProbeCoordinator(
            scope = appScope,
            context = context,
            healthCoordinator = backendHealthCoordinator,
            settings = settingsRepository,
            isForeground = { AppLifecycleObserver.isForeground },
        )
        idleOpenClProbeCoordinator = coordinator
        AppLifecycleObserver.addForegroundListener { foreground ->
            coordinator.onAppForegroundChanged(foreground)
        }
        // 应用已在前台时补一次初始调度。
        if (AppLifecycleObserver.isForeground) {
            coordinator.onAppForegroundChanged(true)
        }
    }

    // ===== Chat Provider =====
    val cloudChatProvider: CloudChatProvider by lazy {
        CloudChatProvider(directLlmClient, settingsRepository)
    }
    val localChatProvider: LocalChatProvider by lazy {
        LocalChatProvider(
            context,
            backendManager,
            settingsRepository,
            cpuBoostController,
            backendHealthCoordinator,
            inferenceCertificationStore,
        )
    }
    val chatProviderManager: ChatProviderManager by lazy {
        ChatProviderManager(
            cloudChatProvider,
            localChatProvider,
            settingsRepository,
            onSwitchAwayFromLocal = { backendManager.release() },
        )
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
