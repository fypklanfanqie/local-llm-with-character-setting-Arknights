package com.rhodesisland.terminal.llm.profile

import com.rhodesisland.terminal.data.model.AutoBackendModelClass
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.benchmark.CertifiedInferenceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wave 1（tmp_path 激活）解析器单测：tmp_path 存在/确定性/按模型区分；cache_path 死键绝迹；
 * 磁盘资格不过时键字节级缺失；同输入双实例 hash 一致。
 */
class InferenceProfileResolverTest {

    private fun resolver(dir: File, modelPath: String, eligible: (File) -> Boolean = { true }) =
        InferenceProfileResolver(dir, modelPath, eligible)

    private fun cpuOptimizedJson(r: InferenceProfileResolver, temperature: Float = 0.8f): String =
        r.resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_CPU,
            contextTokens = 4096,
            maxOutputTokens = 2048,
            thermalAdmittedThreads = 4,
            lookahead = false,
            temperature = temperature,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.UNKNOWN,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
        ).attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.nativeConfigJson

    private fun cpuOptimizedHash(r: InferenceProfileResolver): String =
        r.resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_CPU,
            contextTokens = 4096,
            maxOutputTokens = 2048,
            thermalAdmittedThreads = 4,
            lookahead = false,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.UNKNOWN,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
        ).attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.loadConfigHash

    @Test
    fun tmpPathEmittedWhenEligibleAndDeadCachePathAbsent() {
        val dir = createTempDir()
        val json = cpuOptimizedJson(resolver(dir, dir.absolutePath + "/m/config.json"))
        assertTrue("应携带 tmp_path（激活 use_mmap 权重落盘 + use_cached_mmap 快启）", json.contains("\"tmp_path\":"))
        assertFalse("不应携带已废弃的 cache_path 死键（当前引擎无消费点）", json.contains("cache_path"))
    }

    @Test
    fun tmpPathDeterministicAndDistinctPerModel() {
        val dir = createTempDir()
        val modelA = dir.absolutePath + "/m/alpha/config.json"
        val modelB = dir.absolutePath + "/m/beta/config.json"
        val a1 = cpuOptimizedJson(resolver(dir, modelA))
        val a2 = cpuOptimizedJson(resolver(dir, modelA))
        val b = cpuOptimizedJson(resolver(dir, modelB))
        assertEquals("同模型跨实例应稳定", a1, a2)
        assertTrue("不同模型应落到不同 tmp 目录", a1 != b)
    }

    @Test
    fun tmpPathKeyAbsentWhenIneligible() {
        val dir = createTempDir()
        val json = cpuOptimizedJson(resolver(dir, dir.absolutePath + "/m/config.json", eligible = { false }))
        assertFalse("tmp_path 键应整体消失", json.contains("tmp_path"))
        assertFalse("不得以空串形式占位", json.contains("\"tmp_path\":\"\""))
        // 其余安全键不受影响
        assertTrue(json.contains("\"use_mmap\":true"))
    }

    @Test
    fun identicalInputsProduceIdenticalLoadHash() {
        val dir = createTempDir()
        val modelPath = dir.absolutePath + "/m/config.json"
        assertEquals(cpuOptimizedHash(resolver(dir, modelPath)), cpuOptimizedHash(resolver(dir, modelPath)))
    }

    // ===== Wave 3：认证制 attention_mode / dynamic_option 门禁 =====

    private fun certified(attentionMode: Int, dynamicOption: Int = 0): CertifiedInferenceOptions =
        CertifiedInferenceOptions(
            deviceFingerprint = "d",
            modelFingerprint = "m",
            variant = RuntimeVariant.CPU_OPTIMIZED.name,
            nativeBuildId = "b",
            mnnCommit = "c",
            attentionMode = attentionMode,
            dynamicOption = dynamicOption,
        )

    /** 取指定变体 attempt 的 JSON。 */
    private fun jsonOfVariant(r: InferenceProfileResolver, variant: RuntimeVariant, cert: CertifiedInferenceOptions? = null): String =
        r.resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_CPU,
            contextTokens = 4096,
            maxOutputTokens = 2048,
            thermalAdmittedThreads = 4,
            lookahead = false,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.UNKNOWN,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
            certifiedOptions = cert,
        ).attempts.first { it.variant == variant }.nativeConfigJson

    @Test
    fun certifiedKvQuantAppliesToOptimizedOnly() {
        val dir = createTempDir()
        val r = resolver(dir, dir.absolutePath + "/m/config.json")
        val optimized = jsonOfVariant(r, RuntimeVariant.CPU_OPTIMIZED, certified(attentionMode = 14))
        val compat = jsonOfVariant(r, RuntimeVariant.CPU_COMPATIBILITY, certified(attentionMode = 14))

        assertTrue("CPU_OPTIMIZED 应携带认证的 KV-TQ4 档位", optimized.contains("\"attention_mode\":14"))
        assertTrue("CPU_COMPATIBILITY 必须恒基线（保守兜底）", compat.contains("\"attention_mode\":8"))
    }

    @Test
    fun uncertifiedOrOutOfWorkingListFallsBackToBaseline() {
        val dir = createTempDir()
        val modelPath = dir.absolutePath + "/m/config.json"
        // 无认证 -> 基线
        assertEquals(
            "\"attention_mode\":8",
            jsonOfVariant(resolver(dir, modelPath), RuntimeVariant.CPU_OPTIMIZED)
                .substringAfter("\"attention_mode\":").substringBefore(",").let { "\"attention_mode\":$it" },
        )
        // 白名单外（如历史事故档 10）-> 基线
        val rogue = jsonOfVariant(
            resolver(dir, modelPath), RuntimeVariant.CPU_OPTIMIZED, certified(attentionMode = 10),
        )
        assertTrue(rogue.contains("\"attention_mode\":8"))

        // dynamic 白名单外（如 2）-> 基线 0
        val rogueDyn = jsonOfVariant(
            resolver(dir, modelPath), RuntimeVariant.CPU_OPTIMIZED, certified(attentionMode = 9, dynamicOption = 2),
        )
        assertTrue(rogueDyn.contains("\"dynamic_option\":0"))
    }

    @Test
    fun certifiedDynamicHighBitAppliesWhenWhitelisted() {
        val dir = createTempDir()
        val r = resolver(dir, dir.absolutePath + "/m/config.json")
        val json = jsonOfVariant(r, RuntimeVariant.CPU_OPTIMIZED, certified(attentionMode = 8, dynamicOption = 8))
        assertTrue(json.contains("\"dynamic_option\":8"))
    }

    @Test
    fun gpuVariantAttemptStaysBaselineEvenWithCert() {
        val dir = createTempDir()
        val r = resolver(dir, dir.absolutePath + "/m/config.json")
        val plan = r.resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_GPU,
            contextTokens = 4096,
            maxOutputTokens = 2048,
            thermalAdmittedThreads = 4,
            lookahead = false,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.PROBE_OK,
            modelClass = AutoBackendModelClass.GPU_ELIGIBLE,
            certifiedOptions = certified(attentionMode = 14),
        )
        val opencl = plan.attempts.first { it.variant == RuntimeVariant.OPENCL }.nativeConfigJson
        assertTrue("OpenCL attempt 恒基线 8/0（GPU 路径 KV 语义未验证）", opencl.contains("\"attention_mode\":8"))
    }

    // ===== Wave 2：采样热重建能力门禁 =====

    private fun hotUpdateResolver(dir: File, modelPath: String) =
        InferenceProfileResolver(dir, modelPath, tmpPathEligible = { true }, samplerHotUpdateCapable = true)

    @Test
    fun legacyModeKeepsSamplingScalarsAndHashSensitiveToTemperature() {
        val dir = createTempDir()
        val modelPath = dir.absolutePath + "/m/config.json"
        val json = cpuOptimizedJson(resolver(dir, modelPath))
        assertTrue("legacy 模式标量必须在 load 配置内", json.contains("\"temperature\":"))
        assertTrue(json.contains("\"topP\":"))
        assertTrue(json.contains("\"repetition_penalty\":"))

        // 温度变化 -> hash 变化（legacy 调参=重载，现状行为）
        val r1 = InferenceProfileResolver(dir, modelPath)
        val h1 = cpuOptimizedHash(r1)
        val r2 = InferenceProfileResolver(dir, modelPath)
        val plan2 = r2.resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_CPU,
            contextTokens = 4096, maxOutputTokens = 2048,
            thermalAdmittedThreads = 4, lookahead = false,
            temperature = 1.0f, topP = 0.9f, repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.UNKNOWN,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
        )
        assertNotEquals(h1, plan2.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.loadConfigHash)
    }

    @Test
    fun hotUpdateModeOmitsScalarsAndHashStableAcrossTemperature() {
        val dir = createTempDir()
        val modelPath = dir.absolutePath + "/m/config.json"
        val json = cpuOptimizedJson(hotUpdateResolver(dir, modelPath))
        assertFalse("hot-update 模式温度标量必须省略（经每轮 set_config 生效）", json.contains("\"temperature\":"))
        assertFalse(json.contains("\"repetition_penalty\":"))
        // 结构键保留
        assertTrue("mixed_samplers 结构键两种模式都保留", json.contains("mixed_samplers"))

        // 温度变化 -> hash 不变（调参不再触发整模重载）
        val h1 = cpuOptimizedHash(hotUpdateResolver(dir, modelPath))
        val r2 = hotUpdateResolver(dir, modelPath)
        val plan2 = r2.resolve(
            mode = InferencePerformanceMode.BALANCED,
            backendPreference = BackendPreference.MNN_CPU,
            contextTokens = 4096, maxOutputTokens = 2048,
            thermalAdmittedThreads = 4, lookahead = false,
            temperature = 1.0f, topP = 0.9f, repeatPenalty = 1.2f,
            openclHealth = OpenClHealthState.UNKNOWN,
            modelClass = AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
        )
        assertEquals(
            "hot-update 下温度变化不得改变 loadConfigHash",
            h1,
            plan2.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.loadConfigHash,
        )
    }
}