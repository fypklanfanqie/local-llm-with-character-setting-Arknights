package com.rhodesisland.terminal.llm.backend

import com.rhodesisland.terminal.llm.profile.DeviceRuntimeFingerprint
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackendHealthCoordinator 决策/记录状态机测试（Task 3）。
 *
 * fake store（内存 map）+ fake probe（OpenClProbeRunner 注入）+ 可控 clock，覆盖：
 * 无记录 / PROBE_OK / MODEL_OK / COOLDOWN 过期前后 / CRASH_BLACKLISTED 恒排除 / 探测成功与失败
 * 持久化 / LOAD / GENERATION（重复升 7d）/ MODEL_OK 升级 / 指纹变化失效。
 */
class BackendHealthCoordinatorTest {

    private val hourMs = 60L * 60 * 1000
    private val deviceA = "device-test-a"
    private val modelA = "model-a"
    private val modelB = "model-b"
    private val gpuKeyA = BackendHealthStore.keyFor(deviceA, modelA, BackendType.MNN_GPU, RuntimeVariant.OPENCL)

    /** 内存健康存储替身（BackendHealthStore 绑定 DataStore/Context，无法纯 JVM 实例化）。 */
    private class FakeHealthStore : BackendHealthRecordStore {
        val records = mutableMapOf<BackendHealthKey, HealthRecord>()
        override suspend fun get(key: BackendHealthKey): HealthRecord? = records[key]
        override suspend fun update(key: BackendHealthKey, transform: (HealthRecord?) -> HealthRecord?) {
            val next = transform(records[key])
            if (next != null) records[key] = next else records.remove(key)
        }
    }

    /** fake probe：固定结果；launched 记录探测是否被调用（验证不重复探测 / 不探测路径）。 */
    private class ProbeRecorder {
        var launched = false
        fun runner(result: OpenClProbeResult): OpenClProbeRunner = OpenClProbeRunner(
            launchProbe = { launched = true },
            readResult = { result },
            clock = { 0L },
        )
    }

    private val successProbe = OpenClProbeResult(success = true, vendor = "Qualcomm", device = "Adreno 740")
    private val failureProbe = OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_NO_DEVICE)

    @Test
    fun noRecord_yieldsUnknownWithProbeRequired() = runBlocking {
        val coordinator = BackendHealthCoordinator(FakeHealthStore(), deviceA)

        val decision = coordinator.resolve(modelA)

        assertEquals(OpenClHealthState.UNKNOWN, decision.state)
        assertTrue(decision.probeRequired)
        assertNull(decision.reason)
    }

    @Test
    fun probeOkRecord_yieldsProbeOkDirectlyWithoutReprobe() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.recordOk(HealthState.PROBE_OK)
        val recorder = ProbeRecorder()

        val decision = BackendHealthCoordinator(
            store, deviceA, probeRunner = recorder.runner(successProbe),
        ).resolveForGpu(modelA)

        assertEquals(OpenClHealthState.PROBE_OK, decision.state)
        assertFalse(decision.probeRequired)
        assertFalse("健康记录已 PROBE_OK 时不应重复探测", recorder.launched)
    }

    @Test
    fun modelOkRecord_yieldsModelOkDirectly() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.recordOk(HealthState.MODEL_OK)

        val decision = BackendHealthCoordinator(store, deviceA).resolve(modelA)

        assertEquals(OpenClHealthState.MODEL_OK, decision.state)
        assertFalse(decision.probeRequired)
    }

    @Test
    fun cooldownBeforeExpiry_yieldsCooldownWithoutProbe() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.afterFailure(null, HealthFailureClass.PROBE, nowElapsedMs = 1_000)
        val recorder = ProbeRecorder()

        val decision = BackendHealthCoordinator(
            store, deviceA, probeRunner = recorder.runner(successProbe), clock = { 1_000L },
        ).resolveForGpu(modelA)

        assertEquals(OpenClHealthState.COOLDOWN, decision.state)
        assertFalse(decision.probeRequired)
        assertFalse("冷却期内不应探测", recorder.launched)
    }

    @Test
    fun cooldownAfterExpiry_yieldsUnknownProbeRequiredAndRevalidates() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.afterFailure(null, HealthFailureClass.PROBE, nowElapsedMs = 1_000)
        val recorder = ProbeRecorder()

        // 冷却过期（24h 后）：回 UNKNOWN 重新验证 -> 探测成功 -> PROBE_OK 持久化。
        val decision = BackendHealthCoordinator(
            store, deviceA,
            probeRunner = recorder.runner(successProbe),
            clock = { 1_000 + 24 * hourMs + 1 },
        ).resolveForGpu(modelA)

        assertEquals(OpenClHealthState.PROBE_OK, decision.state)
        assertTrue(recorder.launched)
        assertEquals(HealthState.PROBE_OK, store.records[gpuKeyA]?.state)
    }

    @Test
    fun cooldownExpiredButNoProbeRunner_staysUnknown() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.afterFailure(null, HealthFailureClass.PROBE, nowElapsedMs = 1_000)

        val decision = BackendHealthCoordinator(
            store, deviceA, clock = { 1_000 + 24 * hourMs + 1 },
        ).resolveForGpu(modelA)

        // 无 probeRunner（纯查询）：探测跳过，保持 UNKNOWN -> 计划走 CPU 链。
        assertEquals(OpenClHealthState.UNKNOWN, decision.state)
        assertTrue(decision.probeRequired)
    }

    @Test
    fun blacklisted_yieldsBlacklistedAlwaysAndNeverReprobes() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.afterCrashMarker(nowElapsedMs = 5_000)
        val recorder = ProbeRecorder()

        // 即使时钟推到 Long.MAX_VALUE 也恒排除；探测从不触发。
        val decision = BackendHealthCoordinator(
            store, deviceA,
            probeRunner = recorder.runner(successProbe),
            clock = { Long.MAX_VALUE },
        ).resolveForGpu(modelA)

        assertEquals(OpenClHealthState.CRASH_BLACKLISTED, decision.state)
        assertFalse(decision.probeRequired)
        assertFalse("黑名单恒不探测", recorder.launched)
    }

    @Test
    fun probeSuccess_persistsProbeOk() = runBlocking {
        val store = FakeHealthStore()
        val recorder = ProbeRecorder()

        val state = BackendHealthCoordinator(
            store, deviceA, probeRunner = recorder.runner(successProbe),
        ).runProbeIfNeeded(modelA)

        assertEquals(OpenClHealthState.PROBE_OK, state)
        assertTrue(recorder.launched)
        assertEquals(HealthState.PROBE_OK, store.records[gpuKeyA]?.state)
    }

    @Test
    fun probeFailure_persistsProbeCategoryCooldown() = runBlocking {
        val store = FakeHealthStore()
        val recorder = ProbeRecorder()

        val state = BackendHealthCoordinator(
            store, deviceA, probeRunner = recorder.runner(failureProbe),
        ).runProbeIfNeeded(modelA)

        assertEquals(OpenClHealthState.COOLDOWN, state)
        val record = store.records[gpuKeyA]
        assertEquals(HealthState.COOLDOWN, record?.state)
        assertEquals(HealthFailureClass.PROBE, record?.failureClass)
        assertEquals("clock=0 -> 24h 冷却", 24 * hourMs, record?.cooldownUntilElapsedMs)
    }

    @Test
    fun loadFailure_recordsLoadCategory() = runBlocking {
        val store = FakeHealthStore()

        BackendHealthCoordinator(store, deviceA, clock = { 1_000L })
            .afterLoadFailure(BackendType.MNN_GPU, RuntimeVariant.OPENCL, modelA)

        val record = store.records[gpuKeyA]
        assertEquals(HealthState.COOLDOWN, record?.state)
        assertEquals(HealthFailureClass.LOAD, record?.failureClass)
        assertEquals(1_000 + 24 * hourMs, record?.cooldownUntilElapsedMs)
    }

    @Test
    fun generationFailure_repeatedEscalatesTo7dCooldown() = runBlocking {
        val store = FakeHealthStore()
        var now = 1_000L
        val coordinator = BackendHealthCoordinator(store, deviceA, clock = { now })

        coordinator.afterGenerationFailure(BackendType.MNN_GPU, RuntimeVariant.OPENCL, modelA)
        now = 2_000
        coordinator.afterGenerationFailure(BackendType.MNN_GPU, RuntimeVariant.OPENCL, modelA)

        val record = store.records[gpuKeyA]
        assertEquals(HealthFailureClass.GENERATION, record?.failureClass)
        assertEquals(2, record?.failureCount)
        assertEquals("同类别重复达阈值升 7d", 2_000 + 7L * 24 * hourMs, record?.cooldownUntilElapsedMs)
    }

    @Test
    fun modelOk_upgradesProbeOk() = runBlocking {
        val store = FakeHealthStore()
        val coordinator = BackendHealthCoordinator(store, deviceA)

        coordinator.afterProbeSuccess(modelA)
        assertEquals(HealthState.PROBE_OK, store.records[gpuKeyA]?.state)

        coordinator.markModelOk(BackendType.MNN_GPU, RuntimeVariant.OPENCL, modelA)
        assertEquals(HealthState.MODEL_OK, store.records[gpuKeyA]?.state)
    }

    @Test
    fun fingerprintChange_yieldsFreshUnknownKey() = runBlocking {
        val store = FakeHealthStore()
        store.records[gpuKeyA] = BackendHealthPolicy.recordOk(HealthState.MODEL_OK)
        val coordinator = BackendHealthCoordinator(store, deviceA)

        // 模型指纹变化 -> 新键 -> 旧健康记录不命中。
        val newModel = coordinator.resolve(modelB)
        assertEquals(OpenClHealthState.UNKNOWN, newModel.state)
        assertTrue(newModel.probeRequired)

        // 设备指纹变化同理（黑名单/基准随键自然失效）。
        val newDevice = BackendHealthCoordinator(store, "device-test-b").resolve(modelA)
        assertEquals(OpenClHealthState.UNKNOWN, newDevice.state)
        assertTrue(newDevice.probeRequired)
    }

    @Test
    fun nativeRebuildDoesNotChangeHealthFingerprintKey() {
        // final review I2：健康键语义（BackendHealthStore KDoc）——device+model+backend+variant
        // **不含 native 身份**。native 重建（mnnCommit/nativeBuildId 变化）不得改变健康指纹：
        // 旧构建的失败教训（CRASH_BLACKLISTED/COOLDOWN）在新构建上继续适用，键不变则记录命中。
        // 注意（final re-review）：canonicalHash 哈希**所有非空键**——不能拿
        // canonicalHash(parts) vs canonicalHash(parts + native 键) 做恒等断言（恒不等）；
        // 独立性由结构保证：健康指纹仅由 healthFingerprintParts 推导、其中不含 native 身份。
        val parts = BackendHealthCoordinator.healthFingerprintParts()
        assertFalse("健康指纹不应含 mnnCommit", parts.containsKey("mnnCommit"))
        assertFalse("健康指纹不应含 nativeBuildId", parts.containsKey("nativeBuildId"))

        // 回归守卫：healthDeviceFingerprintOf 恒等于 healthFingerprintParts 的哈希——若未来把
        // mnnCommit/nativeBuildId 加回健康指纹（部件或推导路径），本断言即失败。
        assertEquals(
            "健康指纹应仅由 healthFingerprintParts 推导（不含 native 身份）",
            DeviceRuntimeFingerprint.canonicalHash(parts),
            BackendHealthCoordinator.healthDeviceFingerprintOf(),
        )

        // 认证键口径仍绑定 native 身份：不同 native 身份 -> 不同认证指纹 -> 认证失效（预期）。
        val rebuilt = parts + mapOf("mnnCommit" to "new-commit", "nativeBuildId" to "build-2026-08-10")
        val certParts = parts + mapOf("mnnCommit" to "old-commit", "nativeBuildId" to "build-2026-08-01")
        assertNotEquals(
            "认证设备指纹应随 native 身份变化",
            DeviceRuntimeFingerprint.canonicalHash(rebuilt),
            DeviceRuntimeFingerprint.canonicalHash(certParts),
        )
    }

    @Test
    fun resolveForGpu_withoutProbeRunner_skipsProbeAndStaysUnknown() = runBlocking {
        val coordinator = BackendHealthCoordinator(FakeHealthStore(), deviceA)  // probeRunner = null

        val decision = coordinator.resolveForGpu(modelA)

        assertEquals(OpenClHealthState.UNKNOWN, decision.state)
        assertTrue(decision.probeRequired)
    }
}
