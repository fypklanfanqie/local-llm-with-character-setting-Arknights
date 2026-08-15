package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 热状态转变决策测试（Task 8 Step 1）。纯 JVM：decide 不依赖 Android。 */
class ThermalPolicyTest {

    private fun decide(
        level: ThermalLevel,
        mode: InferencePerformanceMode = InferencePerformanceMode.MAXIMUM_SPEED,
        bigCore: Int = 6,
    ): ThermalDecision = ThermalMonitor.decide(level, mode, bigCore)

    @Test
    fun moderateDowngradesMaximumSpeedToBalancedAndDisablesSustained() {
        val d = decide(ThermalLevel.MODERATE)

        assertEquals(InferencePerformanceMode.BALANCED, d.effectiveMode)
        assertFalse(d.removeBoostNow)
        assertFalse(d.stopNow)
        assertTrue(d.reloadAfterTurn)
        // 下一轮线程降至大核数一半。
        assertEquals(3, d.nextThreadCap)
    }

    @Test
    fun severeRemovesBoostMarksReloadAndCapsNextLoadAtTwo() {
        val d = decide(ThermalLevel.SEVERE, bigCore = 6)

        assertTrue(d.removeBoostNow)
        assertTrue(d.reloadAfterTurn)
        assertEquals(2, d.nextThreadCap)
        assertEquals(InferencePerformanceMode.BALANCED, d.effectiveMode)
        assertFalse(d.stopNow)
    }

    @Test
    fun criticalAndEmergencyRequestStopWithThermalStopWithoutBackendPenalty() {
        for (level in listOf(ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY)) {
            val d = decide(level)
            assertTrue("$level 应请求停止", d.stopNow)
            assertTrue(d.removeBoostNow)
            assertEquals(1, d.nextThreadCap)
            assertEquals(InferencePerformanceMode.BALANCED, d.effectiveMode)
        }
    }

    @Test
    fun noneAndLightKeepRequestedModeAndNoThrottle() {
        for (level in listOf(ThermalLevel.NONE, ThermalLevel.LIGHT)) {
            val d = decide(level, mode = InferencePerformanceMode.MAXIMUM_SPEED)
            assertEquals(InferencePerformanceMode.MAXIMUM_SPEED, d.effectiveMode)
            assertFalse(d.removeBoostNow)
            assertFalse(d.stopNow)
            assertFalse(d.reloadAfterTurn)
            assertEquals(0, d.nextThreadCap) // 0 = 不限制
        }
    }
}
