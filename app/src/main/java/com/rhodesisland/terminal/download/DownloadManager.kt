package com.rhodesisland.terminal.download

import android.content.Context
import android.util.Log
import com.rhodesisland.terminal.data.model.DownloadState
import com.rhodesisland.terminal.data.model.ModelInfo
import com.rhodesisland.terminal.provider.local.ModelPathResolver
import com.rhodesisland.terminal.util.MnnTmpDirJanitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 模型下载管理器（MNN 多文件目录）
 *
 * 功能：
 * - 下载 / 暂停 / 恢复 / 删除
 * - 后台下载
 * - 断点续传（HTTP Range）
 * - 自动重试
 * - 分片合并
 *
 * 下载目录：Android/data/<package>/files/models/<id>/
 */
class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private const val MAX_RETRY = 3
        private const val CHUNK_SIZE = 8192L

        /** 大模型阈值（字节）：超过此大小的模型权重与图分离，llm.mnn.weight 必须存在。
         *  500MB 以下的小模型权重可能内嵌于 llm.mnn，允许缺失仅告警。 */
        private const val WEIGHT_FILE_REQUIRED_THRESHOLD = 500L * 1024 * 1024

        /** 目录总大小校验容差：实际/期望比值低于此值判定下载不完整（HTTP 截断）。 */
        private const val SIZE_TOLERANCE_LOW = 0.9
        /** 目录总大小校验容差：实际/期望比值高于此值判定异常（多下载了无关文件）。 */
        private const val SIZE_TOLERANCE_HIGH = 1.1

        /** HF 仓库里无需下载的辅助文件（非模型本体） */
        private val SKIP_FILES = setOf(
            ".gitattributes", ".gitignore", "README.md", "README", "LICENSE",
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .eventListenerFactory(EventListener.Factory { EventListener.NONE })
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 各模型下载状态流 */
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    /** 下载任务句柄（用于暂停/取消）-- 并发安全 */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val pauseFlags = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 当前活跃的 OkHttp Call，用于 pause/delete 时真正中断阻塞式 source.read()。
     *  仅靠 Job.cancel() 无法打断 native 阻塞 IO，必须 call.cancel() 关闭底层连接。 */
    private val calls = java.util.concurrent.ConcurrentHashMap<String, Call>()

    /** 标记模型为已安装（扫描到本地文件时调用） */
    fun markInstalled(modelId: String, path: String) {
        updateState(modelId, DownloadState.Completed(path))
    }

    fun getState(modelId: String): DownloadState =
        _states.value[modelId] ?: DownloadState.NotDownloaded

    private fun updateState(modelId: String, state: DownloadState) {
        _states.update { it + (modelId to state) }
    }

    /**
     * 开始下载
     */
    fun startDownload(model: ModelInfo) {
        if (jobs[model.id]?.isActive == true) return
        pauseFlags[model.id] = false

        val job = scope.launch {
            var retry = 0
            while (retry < MAX_RETRY && pauseFlags[model.id] != true) {
                try {
                    // MNN 模型为多文件目录 + 分片合并，共用暂停/取消/状态机制。
                    downloadMnnModel(model)
                    return@launch
                } catch (e: CancellationException) {
                    // 协程被取消（pause 或 delete 触发），不重试
                    throw e
                } catch (e: Exception) {
                    if (pauseFlags[model.id] == true || !currentCoroutineContext().isActive) return@launch
                    retry++
                    Log.w(TAG, "Download ${model.id} failed (attempt $retry): ${e.message}")
                    if (retry >= MAX_RETRY) {
                        updateState(model.id, DownloadState.Failed("下载失败，请检查网络后重试"))
                        return@launch
                    }
                    delay(2000L * retry)
                }
            }
        }
        jobs[model.id] = job
        // 下载结束（成功/失败/取消）后清理任务句柄与暂停标志，避免进程生命周期内 map 无界增长
        // （固定 13 模型目录内无害，但自定义/动态 ID 时结构性累积）。用 invokeOnCompletion 而非
        // 在 coroutine 内 finally：处理「协程先于 jobs 赋值完成」的竞态——已在完成时立即执行。
        job.invokeOnCompletion {
            jobs.remove(model.id)
            pauseFlags.remove(model.id)
            calls.remove(model.id)
        }
    }

    // ===== MNN 多文件目录下载 =====

    /** HuggingFace 仓库文件列表 API 响应（仅取 siblings[].rfilename） */
    @Serializable
    private data class HfModelInfo(val siblings: List<HfSibling> = emptyList())

    @Serializable
    private data class HfSibling(val rfilename: String = "")

    /** ModelScope 仓库文件列表 API 响应（国内可访问；取 Data.Files[].Path，过滤 Type=="tree" 目录） */
    @Serializable
    private data class MsRepoFiles(@SerialName("Data") val data: MsRepoData? = null)

    @Serializable
    private data class MsRepoData(@SerialName("Files") val files: List<MsRepoFile>? = null)

    @Serializable
    private data class MsRepoFile(@SerialName("Path") val path: String = "", @SerialName("Type") val type: String = "")

    private val mnnJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 下载 MNN 模型整个仓库到 `<models>/<id>/`，完成后合并分片并校验。 */
    private suspend fun downloadMnnModel(model: ModelInfo) {
        val dir = ModelPathResolver.getModelDir(context, model.id)
        if (!dir.exists()) dir.mkdirs()
        val total = model.size

        val files = listMnnRepoFiles(model)
        if (files.isEmpty()) throw Exception("无法获取 MNN 模型文件列表: ${model.repo}")
        Log.i(TAG, "MNN 模型 ${model.id}: ${files.size} 个文件 -> ${dir.absolutePath}")

        updateState(model.id, DownloadState.Downloading(0L, total))
        // 重试/续传时：把已有部分文件字节计入初始进度，避免进度清零让用户误以为重新下载。
        val existingBytes = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        if (existingBytes > 0L) {
            updateState(model.id, DownloadState.Downloading(existingBytes.coerceAtMost(total), total))
        }
        var aggregate = 0L
        for (file in files) {
            if (pauseFlags[model.id] == true || !currentCoroutineContext().isActive) return
            val target = File(dir, file)
            target.parentFile?.mkdirs()
            val urls = buildMnnFileUrls(model, file)
            var ok = false
            var was404 = false
            var lastErr: Exception? = null
            for (url in urls) {
                try {
                    ok = downloadMnnFile(url, target, model.id, aggregate, total)
                    if (ok) break
                    if (pauseFlags[model.id] == true || !currentCoroutineContext().isActive) return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.message?.contains(" 404") == true) was404 = true
                    lastErr = e
                    continue
                }
            }
            if (!ok) {
                if (was404) {
                    // 可选文件缺失（如某些模型无独立 embeddings/tokenizer），跳过
                    Log.w(TAG, "MNN 文件缺失(404)，跳过: $file")
                } else {
                    throw lastErr ?: Exception("下载失败: $file")
                }
            }
            aggregate += target.length()
            updateState(model.id, DownloadState.Downloading(aggregate, total))
        }

        finishMnnDownload(model, dir)
    }

    /**
     * 下载 MNN 仓库中的单个文件（支持断点续传）。
     * - 416：文件已完整（Range 越界），跳过。
     * - 206：服务端支持 Range，从 [startBytes] 续传。
     * - 200：不支持 Range，从头重写。
     * 进度按 [aggregateBefore] + 本文件已写字节累加进总进度 [total]。
     */
    private suspend fun downloadMnnFile(
        url: String,
        target: File,
        modelId: String,
        aggregateBefore: Long,
        total: Long,
    ): Boolean {
        val startBytes = if (target.exists()) target.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "RhodesIslandTerminal/1.0")
        if (startBytes > 0) builder.header("Range", "bytes=$startBytes-")
        val call = client.newCall(builder.build())
        calls[modelId] = call
        val response = call.execute()
        try {
            if (response.code == 416) return true // 文件已完整
            if (!response.isSuccessful) throw Exception("下载请求失败")
            val body = response.body ?: throw Exception("响应体为空")
            val supportRange = response.code == 206
            val currentStart = if (supportRange) startBytes else 0L
            if (!supportRange && target.exists()) target.delete()
            // 期望字节数：本次响应应写入的字节（206=剩余部分；200=全文）。用于检测服务器提前
            // 断连导致的文件截断（OkHttp source.read() 返回 -1 会误判为完成）。
            val expectedBytes = response.header("Content-Length")?.toLongOrNull()

            val raf = RandomAccessFile(target, "rw")
            var writtenBytes = 0L
            try {
                raf.seek(currentStart)
                val source = body.byteStream()
                val buffer = ByteArray(CHUNK_SIZE.toInt())
                var currentBytes = currentStart
                var lastReport = System.currentTimeMillis()
                while (true) {
                    if (!currentCoroutineContext().isActive || pauseFlags[modelId] == true) return false
                    val read = try {
                        source.read(buffer)
                    } catch (e: IOException) {
                        if (!currentCoroutineContext().isActive) return false
                        throw e
                    }
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    currentBytes += read
                    writtenBytes += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 200) {
                        updateState(modelId, DownloadState.Downloading(aggregateBefore + currentBytes, total))
                        lastReport = now
                    }
                }
            } finally {
                raf.close()
            }
            // 服务器提前断连：实际写入 < Content-Length -> 文件截断。抛错让上层（重试/切镜像）
            // 续传该文件，而不是把残缺文件当完整 -> 末尾大小校验失败 -> 引导用户删除重下。
            if (expectedBytes != null && writtenBytes < expectedBytes) {
                throw Exception("下载不完整：${target.name} 截断（${writtenBytes} / ${expectedBytes} 字节）")
            }
        } finally {
            response.close()
            calls.remove(modelId)
        }
        return true
    }

    /** 构造 MNN 单文件的多镜像下载地址。source-major 排序：先 ModelScope（国内，命中 MNN/<id>），
     *  再 hf-mirror（国内，命中 taobao-mnn/<id>），最后 HF 原站（非国内兜底）。国内用户由此完全不
     *  触碰被墙的 huggingface.co，且可靠的 ModelScope MNN/ 命中靠前，减少无效 404。 */
    private fun buildMnnFileUrls(model: ModelInfo, file: String): List<String> {
        val list = mutableListOf<String>()
        val repos = (listOf(model.repo) + model.altRepos)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        val f = file.trimStart('/')
        for (repo in repos) {
            list.add("https://www.modelscope.cn/models/$repo/resolve/master/$f")
        }
        for (repo in repos) {
            list.add("https://hf-mirror.com/$repo/resolve/main/$f")
        }
        for (repo in repos) {
            list.add("https://huggingface.co/$repo/resolve/main/$f")
        }
        return list.distinct()
    }

    /** 枚举 MNN 仓库文件列表。按国内可访问性优先：ModelScope API -> hf-mirror API -> HuggingFace API，
     *  均失败才回退内置文件集。国内 huggingface.co 被墙，必须优先用 ModelScope/hf-mirror，否则永远
     *  回退硬编码列表（漏 visual.mnn / 文件名对不上如 tokenizer.mtok、embeddings_int4.bin）导致下载不完整。 */
    private fun listMnnRepoFiles(model: ModelInfo): List<String> {
        val repos = (listOf(model.repo) + model.altRepos)
            .map { it.trim().trimStart('/') }
            .filter { it.isNotBlank() }
            .distinct()

        // 1) ModelScope 文件列表 API（国内可访问；命中 MNN/<id>）
        for (repo in repos) {
            val files = tryModelscopeFileList(repo)
            if (files.isNotEmpty()) {
                Log.i(TAG, "MNN 文件列表(ModelScope $repo): ${files.size} 个")
                return files
            }
        }
        // 2) hf-mirror 文件列表 API（国内可访问；命中 taobao-mnn/<id>）
        for (repo in repos) {
            val files = tryHfFileList("https://hf-mirror.com/api/models/$repo")
            if (files.isNotEmpty()) {
                Log.i(TAG, "MNN 文件列表(hf-mirror $repo): ${files.size} 个")
                return files
            }
        }
        // 3) HuggingFace 原站 API（非国内用户兜底；国内被墙会超时失败）
        for (repo in repos) {
            val files = tryHfFileList("https://huggingface.co/api/models/$repo")
            if (files.isNotEmpty()) {
                Log.i(TAG, "MNN 文件列表(HF $repo): ${files.size} 个")
                return files
            }
        }
        // 4) 兜底：核心文件集（所有 API 不可达时；分片模型此路径会因缺 weight 失败校验，提示重试）
        Log.w(TAG, "MNN 文件列表 API 全部失败，使用内置文件集兜底")
        return listOf(
            "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
            "embeddings_bf16.bin", "tokenizer.txt", "splits_info.json",
        )
    }

    /** ModelScope 文件列表 API（国内可访问）。返回过滤后的文件相对路径；失败/空返回空表。 */
    private fun tryModelscopeFileList(repo: String): List<String> = try {
        client.newCall(
            Request.Builder().url("https://www.modelscope.cn/api/v1/models/$repo/repo/files").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) emptyList()
            else {
                val body = resp.body?.string() ?: ""
                runCatching {
                    mnnJson.decodeFromString(MsRepoFiles.serializer(), body)
                        .data?.files
                        ?.filter { it.type != "tree" && it.path.isNotBlank() && it.path !in SKIP_FILES && !it.path.startsWith(".git") }
                        ?.map { it.path }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "ModelScope 文件列表失败 $repo: ${e.message}")
        emptyList()
    }

    /** HuggingFace 兼容文件列表 API（hf-mirror / huggingface.co）。返回过滤后的文件相对路径；失败/空返回空表。 */
    private fun tryHfFileList(apiUrl: String): List<String> = try {
        client.newCall(Request.Builder().url(apiUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) emptyList()
            else {
                val body = resp.body?.string() ?: ""
                runCatching {
                    mnnJson.decodeFromString(HfModelInfo.serializer(), body)
                        .siblings.map { it.rfilename }
                        .filter { it.isNotBlank() && it !in SKIP_FILES && !it.startsWith(".git") }
                }.getOrDefault(emptyList())
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "HF 文件列表失败 $apiUrl: ${e.message}")
        emptyList()
    }

    /**
     * MNN 下载完成：合并分片 + 完整性校验 + 标记完成。
     *
     * 校验链（四层，任一失败标记 Failed 阻止加载损坏文件导致 native hang/crash）：
     *  1. 分片合并校验（[FileSplitter] 内部已有逐分片大小 + 合并后大小校验）
     *  2. 必需文件检查：config.json + llm.mnn 必须存在；大模型(>500MB)的 llm.mnn.weight 必须存在
     *     （taobao-mnn 大模型权重与图分离，缺 weight 加载必崩在 PipelineModule::load，try/catch 拦不住 SIGSEGV。
     *      小模型 ≤500MB 可能权重内嵌于 llm.mnn，允许缺失仅告警）
     *  3. 目录总大小比对：实际目录大小 vs [ModelInfo.size]，差异超过 10% 判定下载不完整
     *     （检测 HTTP 提前断连导致文件截断——OkHttp 的 source.read() 返回 -1 即认为下载完成，
     *      但服务器提前关闭连接时文件可能残缺，无此校验会被标记 Completed → MNN 加载时 hang/崩溃）
     *  4. SHA256 校验（可选，[ModelInfo.sha256] 非空时对主权重文件做严格校验）
     */
    private fun finishMnnDownload(model: ModelInfo, dir: File) {
        updateState(model.id, DownloadState.Verifying(0f))

        // 1. 分片合并
        if (FileSplitter.needsMerging(dir)) {
            updateState(model.id, DownloadState.Verifying(0.3f))
            val merged = FileSplitter.mergeAllSplitFiles(dir)
            if (!merged) {
                updateState(model.id, DownloadState.Failed("模型分片合并失败，文件可能损坏，请删除后重新下载"))
                return
            }
        }

        // 2. 必需文件检查
        val config = File(dir, ModelPathResolver.MNN_CONFIG_FILE)
        val llm = File(dir, ModelPathResolver.MNN_MODEL_FILE)
        if (!config.exists() || !llm.exists()) {
            updateState(model.id, DownloadState.Failed("模型文件不完整：缺 config.json 或 llm.mnn，请删除后重新下载"))
            return
        }
        val weightFile = File(dir, "llm.mnn.weight")
        // 大模型(>500MB)权重与图分离，缺失则加载必崩；小模型可能内嵌，允许缺失仅告警
        if (!weightFile.exists() && model.size > WEIGHT_FILE_REQUIRED_THRESHOLD) {
            updateState(model.id, DownloadState.Failed("模型权重文件 llm.mnn.weight 缺失，请删除后重新下载"))
            return
        }
        if (!weightFile.exists()) {
            Log.w(TAG, "MNN 模型 ${model.id} 缺 llm.mnn.weight（小模型可能内嵌；若加载崩溃请重新下载）")
        }

        // 3. 目录总大小校验：检测 HTTP 截断导致的不完整下载
        updateState(model.id, DownloadState.Verifying(0.6f))
        val actualSize = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val expectedSize = model.size
        if (expectedSize > 0) {
            val ratio = actualSize.toDouble() / expectedSize.toDouble()
            if (ratio < SIZE_TOLERANCE_LOW || ratio > SIZE_TOLERANCE_HIGH) {
                val actMb = actualSize / (1024 * 1024)
                val expMb = expectedSize / (1024 * 1024)
                updateState(model.id, DownloadState.Failed(
                    "模型文件大小校验失败：期望约 ${expMb}MB，实际 ${actMb}MB（偏差 ${((ratio - 1) * 100).toInt()}%，" +
                        "文件可能下载不完整），请删除后重新下载"))
                return
            }
        }

        // 4. SHA256 校验（可选，ModelInfo.sha256 非空时执行）
        if (model.sha256.isNotBlank()) {
            updateState(model.id, DownloadState.Verifying(0.85f))
            val hashTarget = if (weightFile.exists()) weightFile else llm
            val actualHash = sha256OfFile(hashTarget)
            if (!actualHash.equals(model.sha256, ignoreCase = true)) {
                updateState(model.id, DownloadState.Failed(
                    "SHA256 校验失败：文件已损坏（期望 ${model.sha256.take(12)}…，实际 ${actualHash.take(12)}…），请删除后重新下载"))
                return
            }
            Log.i(TAG, "MNN 模型 ${model.id} SHA256 校验通过")
        }

        updateState(model.id, DownloadState.Completed(config.absolutePath))
    }

    /** 计算文件 SHA-256 哈希（十六进制小写串）。流式读取，支持大文件。 */
    private fun sha256OfFile(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var n: Int
            while (stream.read(buffer).also { n = it } != -1) {
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e(TAG, "SHA256 计算失败: ${file.name}", e)
        ""
    }

    /** 暂停下载：先置状态，再 call.cancel() 中断阻塞 read，最后取消协程。
     *  必须调用 call.cancel()，否则 source.read() 不响应协程取消，旧任务会继续写入。 */
    fun pause(modelId: String) {
        pauseFlags[modelId] = true
        updateState(modelId, DownloadState.Paused)
        calls[modelId]?.cancel()
        jobs[modelId]?.cancel()
    }

    /** 恢复下载 */
    fun resume(model: ModelInfo) {
        pauseFlags[model.id] = false
        startDownload(model)
    }

    /** 删除已下载 MNN 模型（整个目录） */
    fun delete(modelId: String) {
        pauseFlags[modelId] = true
        calls[modelId]?.cancel() // 中断可能正在阻塞的 source.read()
        jobs[modelId]?.cancel()

        // MNN：整个目录
        val dir = ModelPathResolver.getModelDir(context, modelId)
        if (dir.exists()) dir.deleteRecursively()

        // 该模型的 mnn_tmp_* 权重缓存（sync.static ≈ 模型大小）：随模型删除一并清，
        // 避免孤儿缓存无限累积。目录命名与 resolver 加载同源（MnnTmpDirJanitor.tmpDirFor）。
        ModelPathResolver.getConfigPath(context, modelId)?.let { configPath ->
            val tmpDir = MnnTmpDirJanitor.tmpDirFor(context.cacheDir, configPath)
            if (tmpDir.exists() && tmpDir.deleteRecursively()) {
                Log.i(TAG, "删除模型同时清理 tmp_path 缓存: ${tmpDir.name}")
            }
        }

        updateState(modelId, DownloadState.NotDownloaded)
    }

    /** 扫描 models 目录，返回已安装 MNN 模型 ID 集合（目录含 config.json + llm.mnn） */
    fun scanInstalledModels(): Set<String> {
        val dir = ModelPathResolver.getModelsDirectory(context)
        val ids = mutableSetOf<String>()
        // MNN 目录（含 config.json + llm.mnn）
        dir.listFiles { f -> f.isDirectory }
            ?.forEach { d ->
                if (File(d, ModelPathResolver.MNN_CONFIG_FILE).exists() &&
                    File(d, ModelPathResolver.MNN_MODEL_FILE).exists()
                ) {
                    ids.add(d.name)
                }
            }
        return ids
    }
}
