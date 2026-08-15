package com.rhodesisland.terminal.llm.profile

import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.benchmark.CertifiedInferenceOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 生成每轮不可变的 [ResolvedInferencePlan]（Task 7）。
 *
 * 关键职责：
 * - 由模式/后端偏好/设备与健康信息解析**有序后端尝试链**（QNN 永不进 AUTO）；
 * - 为每个 [BackendAttempt] 生成规范化 native set_config JSON（键排序），并计算
 *   [BackendAttempt.loadConfigHash] 作为唯一模型重载指纹；
 * - 产出流式/功耗/驻留策略与全部安全降级原因（类型化 [DowngradeReason]）。
 *
 * 热/内存降级不能被 MAXIMUM_SPEED 绕过：CPU 线程数一律取热准入值，模式只影响
 * sustained/性能提示/批处理阈值等非安全键。
 *
 * Task 6 认证门禁：lookahead 与多 token 步进不是用户无条件配置——只有
 * [com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore] 认证了该
 * device+model+variant+native 组合的基准收益才启用（lookahead 还需用户请求）；未认证一律回落
 * 安全默认（lookahead=false、decodeStepTokens=1），CPU 线程数保持热准入（线程认证不在本任务范围）。
 *
 * @param cacheDir 应用私有缓存目录（Context.cacheDir）；运行时缓存按模型指纹命名写入，
 *                 不再写入下载模型目录。
 * @param modelPath MNN 模型 `config.json` 绝对路径；用于 cache 命名空间与负载指纹。
 */
class InferenceProfileResolver(
    private val cacheDir: File,
    private val modelPath: String,
) {

    /**
     * @param openclHealth OpenCL 健康状态（Task 9，来自 BackendHealthStore）：PROBE_OK/MODEL_OK
     *        可进链；UNKNOWN 需先探测（Task 10），不入链；COOLDOWN/CRASH_BLACKLISTED 不入链。
     * @param thermalAdmittedThreads 热准入后的 CPU 线程数（min(用户, 大核, 温控上限)），
     *        由调用方已算好，MAXIMUM_SPEED 不能绕过。
     * @param certifiedOptions 该组合（device+model+variant+native）的基准认证（Task 6，
     *        [com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore]）；null=未认证。
     *        用户 lookahead 请求只是使用既有认证的许可——认证缺失/无 lookahead 证据/变体不匹配时
     *        native config 回落 lookahead=false 并记 [DowngradeReason.LOOKAHEAD_UNCERTIFIED]；
     *        多 token 步进（decodeStepTokens）同理：仅认证了步进收益的组合才 >1，否则恒 1。
     */
    fun resolve(
        mode: InferencePerformanceMode,
        backendPreference: BackendPreference,
        contextTokens: Int,
        maxOutputTokens: Int,
        thermalAdmittedThreads: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        openclHealth: OpenClHealthState,
        // 模型大小策略（Task 15）：AUTO 仅对 GPU_ELIGIBLE（总参数量 >7B）加入 GPU attempt；
        // 显式 MNN_GPU 忽略本字段（用户显式选择优先）。调用方（Provider/基准）负责在
        // 探测前用同一分类决定是否需要 OpenCL 健康探测。
        modelClass: AutoBackendModelClass,
        certifiedOptions: CertifiedInferenceOptions? = null,
    ): ResolvedInferencePlan {
        val downgrades = mutableListOf<DowngradeReason>()
        // Task 6：lookahead 门禁——用户请求只是使用既有认证的许可，不是无条件 native 配置：
        // 该组合（device+model+CPU 变体+native）有 lookahead 基准认证才启用，否则回落 false。
        val cert = certifiedOptions
        val effectiveLookahead = lookahead && cert != null && cert.lookahead &&
            cert.matchesCpuVariant()
        if (lookahead && !effectiveLookahead) {
            downgrades += DowngradeReason.LOOKAHEAD_UNCERTIFIED
        }
        // Task 6：多 token 步进门禁——无认证默认 1（native 逐 token，Task 1 clamp [1,4]）；
        // 仅当该组合认证了步进收益（decodeStepTokens>1）且变体匹配时才 >1。
        // Task 6 review M-2：生效值 coerceIn(1,4) 纵深防御——损坏记录（如 step=99）不再直传 native
        // （native 已有 clamp [1,4]，此为 Kotlin 侧双保险）。
        val effectiveStep = if (cert != null && cert.decodeStepTokens > 1 &&
            cert.matchesCpuVariant()
        ) cert.decodeStepTokens.coerceIn(1, 4) else 1

        // 尝试链：QNN 永不进 AUTO；标准版显式选 NPU 也解析为 CPU（保留已存设置但标不支持）。
        val openclEligible = openclHealth == OpenClHealthState.PROBE_OK ||
            openclHealth == OpenClHealthState.MODEL_OK
        val attempts = buildList {
            val cpu = thermalAdmittedThreads.coerceAtLeast(1)
            when (backendPreference) {
                // AUTO：仅对「总参数量严格 >7B」（[modelClass] == GPU_ELIGIBLE）在 OpenCL 健康时
                // 加入 GPU attempt；<=7B 或参数未知一律 CPU，并记类型化原因。显式 MNN_GPU 不受门槛
                // 限制（见下）。GPU_ELIGIBLE 但 OpenCL 不健康时静默走 CPU（与历史一致：用户未显式
                // 请求 GPU，探测/健康由 Provider 侧负责，不额外提示）。
                BackendPreference.AUTO -> {
                    when {
                        modelClass == AutoBackendModelClass.GPU_ELIGIBLE && openclEligible ->
                            add(attempt(BackendType.MNN_GPU, RuntimeVariant.OPENCL, 68, contextTokens, lookahead = false, temperature, topP, repeatPenalty))
                        modelClass == AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD ->
                            downgrades += DowngradeReason.AUTO_MODEL_AT_OR_BELOW_7B_CPU
                        modelClass == AutoBackendModelClass.CPU_UNKNOWN_PARAMETERS ->
                            downgrades += DowngradeReason.AUTO_MODEL_PARAMETERS_UNKNOWN_CPU
                    }
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                }
                // 显式 MNN_GPU：任何模型都可在 OpenCL 健康时尝试 GPU；失败由 BackendManager 回退 CPU。
                BackendPreference.MNN_GPU -> {
                    if (openclEligible) {
                        add(attempt(BackendType.MNN_GPU, RuntimeVariant.OPENCL, 68, contextTokens, lookahead = false, temperature, topP, repeatPenalty))
                    } else if (openclHealth != OpenClHealthState.UNKNOWN) {
                        downgrades += DowngradeReason.OPENCL_UNHEALTHY
                    }
                    // Task 6：CPU 两个变体统一使用门禁后的 effectiveLookahead——认证记录于
                    // CPU_OPTIMIZED（基准变体），CPU_COMPATIBILITY 是极少运行的兜底 attempt，
                    // 沿用同一认证配置（与既有「用户 lookahead 同时作用于两个 CPU 变体」一致）。
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                }
                BackendPreference.MNN_CPU -> {
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                }
                BackendPreference.MNN_NPU -> {
                    // 标准构建不含 QNN 运行时：保留设置但解析为 CPU，显式降级原因（Task 11）。
                    downgrades += DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
                }
            }
        }

        // effectiveMode：热/内存受限时即使请求 MAXIMUM_SPEED 也回落 BALANCED（安全键不可绕过）。
        // Task 7 阶段调用方只传入已热准入的线程；此处保留模式，实际热降级由后续任务执行。
        val effectiveMode = mode

        return ResolvedInferencePlan(
            requestedMode = mode,
            effectiveMode = effectiveMode,
            contextTokens = contextTokens,
            maxOutputTokens = maxOutputTokens,
            streamPolicy = when (mode) {
                InferencePerformanceMode.BALANCED -> StreamPolicy(batchMaxBytes = 256, batchMaxMs = 16)
                InferencePerformanceMode.MAXIMUM_SPEED -> StreamPolicy(batchMaxBytes = 512, batchMaxMs = 32)
            },
            powerPolicy = PowerPolicy(
                cpuThreads = thermalAdmittedThreads.coerceAtLeast(1),
                // Task 6：lookahead 同步为门禁后的 effectiveLookahead（与 native config 一致）——
                // 用户请求只是使用认证的许可；无认证时即使 MAXIMUM_SPEED 也不开启。
                lookahead = effectiveLookahead,
                sustainedMode = mode == InferencePerformanceMode.MAXIMUM_SPEED,
                aggressiveHint = mode == InferencePerformanceMode.MAXIMUM_SPEED,
            ),
            residencyPolicy = ResidencyPolicy(
                keepAliveMs = when (mode) {
                    InferencePerformanceMode.BALANCED -> 15_000L
                    InferencePerformanceMode.MAXIMUM_SPEED -> 60_000L
                },
            ),
            attempts = attempts,
            downgradeReasons = downgrades,
            // Task 6：多 token 步进门禁后的生效步长（BackendManager 生成期透传 native）。
            decodeStepTokens = effectiveStep,
        )
    }

    /**
     * 认证组合是否匹配当前 CPU 变体。
     *
     * 认证记录于基准变体 [RuntimeVariant.CPU_OPTIMIZED]（[toCertifiedOptions] 由 CPU 象限推导）；
     * lookahead / 多 token 步进只对 CPU 有意义，且门禁要求组合完整匹配——变体不一致的认证
     * （如 GPU 象限认证）不构成 CPU 组合的证据。
     */
    private fun CertifiedInferenceOptions.matchesCpuVariant(): Boolean =
        variant == RuntimeVariant.CPU_OPTIMIZED.name

    private fun attempt(
        backend: BackendType,
        variant: RuntimeVariant,
        threadNum: Int,
        contextTokens: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
    ): BackendAttempt {
        val backendType = when (backend) {
            BackendType.MNN_CPU -> "cpu"
            BackendType.MNN_GPU -> "opencl"
            BackendType.MNN_NPU -> "qnn"
        }
        val json = buildAttemptNativeConfig(
            variant = variant,
            backendType = backendType,
            threadNum = threadNum,
            cachePath = runtimeCacheFile.absolutePath,
            contextTokens = contextTokens,
            lookahead = lookahead,
            temperature = temperature,
            topP = topP,
            repeatPenalty = repeatPenalty,
        )
        return BackendAttempt(
            backend = backend,
            variant = variant,
            nativeConfigJson = json,
            loadConfigHash = loadConfigHash(json),
            requiresProbe = variant == RuntimeVariant.OPENCL,
        )
    }

    private val runtimeCacheFile: File by lazy {
        File(cacheDir, "mnn_cache_${sha256(modelPath).take(8)}.bin")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val HASH_HEX_LENGTH = 16

        /**
         * 生成规范化 native set_config JSON（键排序，供 JNI 原样透传给 Llm::set_config）。
         *
         * 安全通用键固定：use_mmap/reuse_kv/attention_mode=8/dynamic_option=0/mixed_samplers(penalty)。
         * CPU_OPTIMIZED 用 low precision/memory + Power_High；CPU_COMPATIBILITY 用保守
         * normal/normal + Power_Normal（不依赖省略字段继承未知模型默认）；OPENCL 保持 68 编码。
         */
        fun buildAttemptNativeConfig(
            variant: RuntimeVariant,
            backendType: String,
            threadNum: Int,
            cachePath: String,
            contextTokens: Int,
            lookahead: Boolean,
            temperature: Float,
            topP: Float,
            repeatPenalty: Float,
        ): String {
            val optimized = variant == RuntimeVariant.CPU_OPTIMIZED
            val isOpenCl = variant == RuntimeVariant.OPENCL
            val config = buildJsonObject {
                put("schemaVersion", SCHEMA_VERSION)
                put("backend_type", backendType)
                put("thread_num", threadNum)
                put("cache_path", cachePath)
                // precision/memory：CPU_OPTIMIZED=low/low；CPU_COMPATIBILITY=normal/normal；OpenCL=low/low。
                put("precision", if (optimized || isOpenCl) "low" else "normal")
                put("memory", if (optimized || isOpenCl) "low" else "normal")
                put("use_mmap", true)
                put("reuse_kv", true)
                put("attention_mode", 8)
                put("dynamic_option", 0)
                put("temperature", temperature)
                put("topP", topP)
                put("repetition_penalty", repeatPenalty)
                put(
                    "mixed_samplers",
                    buildJsonArray {
                        add(JsonPrimitive("penalty"))
                        add(JsonPrimitive("topK"))
                        add(JsonPrimitive("tfs"))
                        add(JsonPrimitive("typical"))
                        add(JsonPrimitive("topP"))
                        add(JsonPrimitive("min_p"))
                        add(JsonPrimitive("temperature"))
                    },
                )
                // kv_max_length 仅写入 CPU 分支：OpenCL 的 KV 分配行为封装在 pinned libMNN.so 内、
                // 未经设备矩阵验证，盲目加入可能破坏 GPU 加载或（更可能）不被 native 消费——
                // 既不改变 loadConfigHash 也不降低实际 GPU KV 分配。故 OpenCL 分支保守地**不设**
                // kv_max_length：内存准入的 GPU 侧不假设「降 context 即降 GPU KV 分配」，仅在 CPU
                // 路径保证 context 生效。若未来在真机矩阵验证 native 支持，再统一接入并纳入 hash。
                if (backendType == "cpu") {
                    // 功耗：CPU_OPTIMIZED=high（大核调度）；CPU_COMPATIBILITY=normal（保守）。
                    put("power", if (optimized) "high" else "normal")
                    if (contextTokens > 0) put("kv_max_length", contextTokens)
                    if (lookahead) {
                        put("speculative_type", "lookahead")
                        put("ngram_match_maxlen", 4)
                        put("draft_predict_length", 5)
                    }
                }
            }
            return canonicalJsonString(config)
        }

        /** 键递归排序的规范化 JSON 字符串；同一语义配置恒产生同一字节序列。 */
        fun canonicalJsonString(root: JsonObject): String {
            fun canon(element: JsonElement): JsonElement = when (element) {
                is JsonObject -> JsonObject(
                    element.entries.sortedBy { it.key }.associate { (k, v) -> k to canon(v) },
                )
                is JsonArray -> element  // 数组元素序确定性（resolver 固定构建），无需重建
                else -> element
            }
            return (canon(root) as JsonObject).toString()
        }

        /** loadConfigHash：规范化配置 JSON 的 SHA-256 前 16 位 hex，作为唯一重载指纹。 */
        fun loadConfigHash(canonicalJson: String): String =
            sha256(canonicalJson).take(HASH_HEX_LENGTH)

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
