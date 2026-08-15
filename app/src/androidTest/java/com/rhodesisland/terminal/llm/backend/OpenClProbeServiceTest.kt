package com.rhodesisland.terminal.llm.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OpenCL 探测协调器测试（Task 10 Step 5）。
 *
 * 用注入的 fake probe/clock 验证成功、普通失败、超时与进程死亡判定；不依赖真实 OpenCL 设备。
 * 真实设备身份/输出校验由 CI 上的真机 instrumentation 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class OpenClProbeServiceTest {

    @Test
    fun successResultReturnedImmediately() = runBlocking {
        var launched = false
        val runner = OpenClProbeRunner(
            launchProbe = { launched = true },
            readResult = {
                OpenClProbeResult(success = true, vendor = "Qualcomm", device = "Adreno 740", driver = "v2.2.0", durationMs = 3)
            },
            clock = { 0L },
        )

        val result = runner.runProbe()

        assertTrue(launched)
        assertTrue(result.success)
        assertEquals("Qualcomm", result.vendor)
        assertEquals("Adreno 740", result.device)
    }

    @Test
    fun ordinaryFailureMapsFailureCode() = runBlocking {
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_NO_DEVICE) },
            clock = { 0L },
        )

        val result = runner.runProbe()

        assertFalse(result.success)
        assertEquals(OpenClProbeResult.FAILURE_NO_DEVICE, result.failureCode)
    }

    @Test
    fun timeoutWhenNoResultArrives() = runBlocking {
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { null },
            clock = { Long.MAX_VALUE },  // 立即超时
        )

        val result = runner.runProbe()

        assertFalse(result.success)
        assertEquals(OpenClProbeResult.FAILURE_TIMEOUT, result.failureCode)
    }

    @Test
    fun malformedResultMapsToProcessDeath() = runBlocking {
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_PROCESS_DEATH) },
            clock = { 0L },
        )

        val result = runner.runProbe()

        assertEquals(OpenClProbeResult.FAILURE_PROCESS_DEATH, result.failureCode)
    }

    // ===== Task 3：coordinator + 真实 BackendHealthStore（DataStore）链路 =====
    // fake probe（不依赖真实 OpenCL 设备），验证「探测结果 -> 持久健康记录 -> 再决策」闭环。
    // 每次用唯一指纹键，避免跨测试/跨运行残留污染。

    @Test
    fun probeSuccessPersistsProbeOkThroughRealStore() = runBlocking {
        val store = BackendHealthStore(ApplicationProvider.getApplicationContext())
        store.resetAll()
        val device = "test-dev-${System.nanoTime()}"
        val model = "test-model-${System.nanoTime()}"
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { OpenClProbeResult(success = true, vendor = "fake", device = "fake", durationMs = 1) },
            clock = { 0L },
        )
        val coordinator = BackendHealthCoordinator(store, device, model, runner, clock = { 0L })

        val state = coordinator.runProbeIfNeeded(model)
        assertEquals(OpenClHealthState.PROBE_OK, state)

        val key = BackendHealthStore.keyFor(device, model, BackendType.MNN_GPU, RuntimeVariant.OPENCL)
        assertEquals("探测成功应持久化 PROBE_OK", HealthState.PROBE_OK, store.get(key)?.state)

        // 再决策：直接 PROBE_OK，无需重复探测（决策不含启动探测进程）。
        val decision = coordinator.resolve(model)
        assertEquals(OpenClHealthState.PROBE_OK, decision.state)
        assertFalse(decision.probeRequired)
    }

    @Test
    fun probeFailurePersistsCooldownThroughRealStore() = runBlocking {
        val store = BackendHealthStore(ApplicationProvider.getApplicationContext())
        store.resetAll()
        val device = "test-dev-${System.nanoTime()}"
        val model = "test-model-${System.nanoTime()}"
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_NO_DEVICE) },
            clock = { 0L },
        )
        val coordinator = BackendHealthCoordinator(store, device, model, runner, clock = { 0L })

        val state = coordinator.runProbeIfNeeded(model)
        assertEquals(OpenClHealthState.COOLDOWN, state)

        val key = BackendHealthStore.keyFor(device, model, BackendType.MNN_GPU, RuntimeVariant.OPENCL)
        val record = store.get(key)
        assertEquals("探测失败应记 PROBE 类别", HealthFailureClass.PROBE, record?.failureClass)
        assertEquals(HealthState.COOLDOWN, record?.state)
    }
}
