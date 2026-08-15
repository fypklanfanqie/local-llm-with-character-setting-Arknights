package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.profile.ResidencyPolicy
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模型驻留虚拟时间测试（Task 14 Step 1）。 */
class ModelResidencyControllerTest {

    @Test
    fun balancedReleasesAfterGraceInBackground() = runTest {
        var released = 0
        val c = controller({ released++ }, ResidencyPolicy(15_000L), this)

        c.onAppForegroundChanged(false)
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals("后台 15s 后应释放", 1, released)
    }

    @Test
    fun foregroundReturnBeforeGraceCancelsRelease() = runTest {
        var released = 0
        val c = controller({ released++ }, ResidencyPolicy(15_000L), this)

        c.onAppForegroundChanged(false)
        advanceTimeBy(5_000L)
        c.onAppForegroundChanged(true) // 宽限内返回
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals("宽限内返回不应释放", 0, released)
    }

    @Test
    fun maximumSpeedRetainsLonger() = runTest {
        var released = 0
        val c = controller({ released++ }, ResidencyPolicy(60_000L), this)

        c.onAppForegroundChanged(false)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("30s < 60s 宽限，不应释放", 0, released)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, released)
    }

    @Test
    fun generationActivePreventsRelease() = runTest {
        var released = 0
        val c = controller({ released++ }, ResidencyPolicy(15_000L), this)

        c.onGenerationStateChanged(true)
        c.onAppForegroundChanged(false)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals("生成期间不释放", 0, released)
    }

    @Test
    fun trimCriticalReleasesImmediately() = runTest {
        var released = 0
        val c = controller({ released++ }, ResidencyPolicy(60_000L), this)

        c.onAppForegroundChanged(false)
        c.onTrimMemory(immediate = true)
        runCurrent()

        assertEquals("trim critical 立即释放", 1, released)
    }

    @Test
    fun providerSwitchToCloudSchedulesRelease() = runTest {
        var released = 0
        val c = controller({ released++ }, ResidencyPolicy(15_000L), this)

        c.onAppForegroundChanged(false)
        c.onProviderChanged(providerStaysLocal = false)
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(1, released)
    }

    private fun controller(
        releaseAll: suspend () -> Unit,
        policy: ResidencyPolicy,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = ModelResidencyController(
        releaseAll = releaseAll,
        balancedKeepAliveMs = 15_000L,
        maxSpeedKeepAliveMs = 60_000L,
        scope = scope,
    ).also { it.onModelChanged(policy) }
}
