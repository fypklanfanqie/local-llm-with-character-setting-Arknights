package com.rhodesisland.terminal.llm.benchmark

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推理选项认证存储测试（Task 6）。
 *
 * [InferenceCertificationStore] 绑定 Android Context，无法纯 JVM 实例化——与 BackendHealthStoreTest
 * 的纯函数模式一致：这里覆盖 companion 的纯逻辑（certKey 稳定性/敏感度、map 编解码=存储 map 存取
 * 语义、@Serializable round-trip）。DataStore 真实 I/O 由 instrumentation 覆盖（Task 8 设备验证）。
 */
class InferenceCertificationStoreTest {

    private fun options(
        device: String = "device-a",
        model: String = "model-a",
        variant: String = "CPU_OPTIMIZED",
        build: String = "build-1",
        commit: String = "abc123",
        lookahead: Boolean = true,
        step: Int = 2,
        configHash: String? = "cfg",
        certifiedAt: Long = 1000L,
    ) = CertifiedInferenceOptions(
        deviceFingerprint = device,
        modelFingerprint = model,
        variant = variant,
        nativeBuildId = build,
        mnnCommit = commit,
        lookahead = lookahead,
        decodeStepTokens = step,
        certifiedConfigHash = configHash,
        certifiedAtElapsedMs = certifiedAt,
    )

    @Test
    fun certKeyIsStableForIdenticalInputs() {
        val a = InferenceCertificationStore.certKey("d", "m", "CPU_OPTIMIZED", "b", "c")
        val b = InferenceCertificationStore.certKey("d", "m", "CPU_OPTIMIZED", "b", "c")

        assertEquals(a, b)
    }

    @Test
    fun certKeyIsSensitiveToEveryIdentityComponent() {
        val base = InferenceCertificationStore.certKey("d", "m", "CPU_OPTIMIZED", "b", "c")
        // 任一分量变化 -> 新键（旧认证自然失效）。
        assertNotEquals(base, InferenceCertificationStore.certKey("d2", "m", "CPU_OPTIMIZED", "b", "c"))
        assertNotEquals(base, InferenceCertificationStore.certKey("d", "m2", "CPU_OPTIMIZED", "b", "c"))
        assertNotEquals(base, InferenceCertificationStore.certKey("d", "m", "OPENCL", "b", "c"))
        assertNotEquals(base, InferenceCertificationStore.certKey("d", "m", "CPU_OPTIMIZED", "b2", "c"))
        assertNotEquals(base, InferenceCertificationStore.certKey("d", "m", "CPU_OPTIMIZED", "b", "c2"))
    }

    @Test
    fun certKeyIs16HexChars() {
        val key = InferenceCertificationStore.certKey("d", "m", "CPU_OPTIMIZED", "b", "c")

        assertEquals(16, key.length)
        assertTrue("certKey 应为 hex：$key", key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun certKeyFromOptionsMatchesFieldForm() {
        val opt = options()
        assertEquals(
            InferenceCertificationStore.certKey(opt),
            InferenceCertificationStore.certKey(opt.deviceFingerprint, opt.modelFingerprint, opt.variant, opt.nativeBuildId, opt.mnnCommit),
        )
    }

    @Test
    fun serializationRoundTripPreservesRecord() {
        val opt = options()
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(CertifiedInferenceOptions.serializer(), opt)
        val restored = json.decodeFromString(CertifiedInferenceOptions.serializer(), encoded)

        assertEquals("round-trip 应逐字段一致", opt, restored)
    }

    @Test
    fun serializationToleratesMissingNewFields() {
        // @Serializable 兼容：旧记录缺新字段时按默认值解析（ignoreUnknownKeys + 默认值）。
        val oldShape = """{"deviceFingerprint":"d","modelFingerprint":"m","variant":"CPU_OPTIMIZED",
            "nativeBuildId":"b","mnnCommit":"c","lookahead":true}"""
        val restored = Json { ignoreUnknownKeys = true }
            .decodeFromString(CertifiedInferenceOptions.serializer(), oldShape)

        assertEquals("缺字段应取默认 decodeStepTokens=1", 1, restored.decodeStepTokens)
        assertEquals("缺字段应取默认 certifiedConfigHash=null", null, restored.certifiedConfigHash)
        assertEquals("缺字段应取默认 certifiedAtElapsedMs=0", 0L, restored.certifiedAtElapsedMs)
    }

    @Test
    fun wave3FieldsRoundTripAndOldRecordsDefaultToBaseline() {
        // Wave 3 新字段（attentionMode/dynamicOption）：显式值 round-trip 逐字段一致。
        val certified = CertifiedInferenceOptions(
            deviceFingerprint = "d", modelFingerprint = "m",
            variant = "CPU_OPTIMIZED", nativeBuildId = "b", mnnCommit = "c",
            lookahead = false, decodeStepTokens = 1,
            attentionMode = 14, dynamicOption = 0,
        )
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString(
            CertifiedInferenceOptions.serializer(),
            json.encodeToString(CertifiedInferenceOptions.serializer(), certified),
        )
        assertEquals(certified, restored)

        // 旧记录（无 Wave 3 字段）解析到基线默认：8/0——语义与历史行为完全一致。
        val oldShape = """{"deviceFingerprint":"d","modelFingerprint":"m","variant":"CPU_OPTIMIZED",
            "nativeBuildId":"b","mnnCommit":"c","lookahead":true,"decodeStepTokens":2}"""
        val legacy = Json { ignoreUnknownKeys = true }
            .decodeFromString(CertifiedInferenceOptions.serializer(), oldShape)
        assertEquals(8, legacy.attentionMode)
        assertEquals(0, legacy.dynamicOption)
    }

    @Test
    fun toCertifiedOptionsCarriesWave3Fields() {
        val case = InferenceBenchmarkCase(
            scenario = com.rhodesisland.terminal.llm.benchmark.InferenceBenchmarkScenario.FIXED_DECODE,
            quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF,
            deviceFingerprint = "d",
            modelFingerprint = "m",
            configHash = "cfg",
        )
        val cert = InferenceCertificationStore.toCertifiedOptions(
            case = case,
            decision = PromotionDecision.Promote,
            nativeBuildId = "b",
            mnnCommit = "c",
            decodeStepTokens = 1,
            lookaheadEvidence = false,
            attentionMode = 14,
            dynamicOption = 0,
            configHash = null,
            nowElapsedMs = 42L,
        )
        assertTrue(cert != null)
        assertEquals(14, cert!!.attentionMode)
        assertEquals(0, cert.dynamicOption)
        assertEquals(false, cert.lookahead) // KV 量化认证不得误留 lookahead=true
    }

    // ===== map 存取语义（纯逻辑；DataStore edit 仅做 decode -> put -> encode，见类实现）=====

    @Test
    fun blankOrCorruptRecordDecodesToEmptyMap() {
        assertTrue(InferenceCertificationStore.decodeRecords("").isEmpty())
        assertTrue(InferenceCertificationStore.decodeRecords("  ").isEmpty())
        assertTrue(InferenceCertificationStore.decodeRecords("{not json}").isEmpty())
    }

    @Test
    fun encodeDecodeRoundTripPreservesAllRecords() {
        val a = options()
        val b = options(device = "device-b", model = "model-b")
        val records = mapOf(
            InferenceCertificationStore.certKey(a) to a,
            InferenceCertificationStore.certKey(b) to b,
        )

        val restored = InferenceCertificationStore.decodeRecords(InferenceCertificationStore.encodeRecords(records))

        assertEquals("map round-trip 应保持全部记录", records, restored)
    }

    @Test
    fun getSemanticsAfterSaveEncodingResolvesByKey() {
        // save() 的落盘内容 = encodeRecords(records)；get(key) = decodeRecords(raw)[key]。
        val opt = options()
        val key = InferenceCertificationStore.certKey(opt)
        val raw = InferenceCertificationStore.encodeRecords(mapOf(key to opt))

        assertEquals("按 certKey 应取回该组合的认证", opt, InferenceCertificationStore.decodeRecords(raw)[key])
        // 其它键不串扰。
        assertTrue(InferenceCertificationStore.decodeRecords(raw).getOrDefault("deadbeef", null) == null)
    }
}
