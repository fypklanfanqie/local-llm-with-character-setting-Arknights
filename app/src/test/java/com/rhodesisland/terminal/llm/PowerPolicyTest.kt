package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.profile.PowerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PowerPolicy 驱动提频的纯决策测试（Task 8 Step 2）。 */
class PowerPolicyTest {

    @Test
    fun aggressiveModeUsesShorterTargetWorkDuration() {
        val balancedTarget = CpuBoostController.targetDurationNs(PowerPolicy(
            cpuThreads = 4, lookahead = false, sustainedMode = false, aggressiveHint = false,
        ))
        val aggressiveTarget = CpuBoostController.targetDurationNs(PowerPolicy(
            cpuThreads = 4, lookahead = false, sustainedMode = true, aggressiveHint = true,
        ))

        assertTrue("极速应请求更激进（更短）目标时长", aggressiveTarget < balancedTarget)
    }

    @Test
    fun sustainedOnlyEnabledWhenPolicyRequestsIt() {
        assertFalse(CpuBoostController.shouldEnableSustained(PowerPolicy(
            cpuThreads = 4, lookahead = false, sustainedMode = false, aggressiveHint = false,
        )))
        assertTrue(CpuBoostController.shouldEnableSustained(PowerPolicy(
            cpuThreads = 4, lookahead = false, sustainedMode = true, aggressiveHint = true,
        )))
    }

    @Test
    fun targetDurationCompanionConstantsAreValid() {
        // 温和目标 = 16ms（~62 tok/s 的 work cycle）；激进目标更短以请求更高频率。
        assertEquals(16_000_000L, CpuBoostController.TARGET_WORK_DURATION_NS)
        assertTrue(CpuBoostController.AGGRESSIVE_TARGET_WORK_DURATION_NS < CpuBoostController.TARGET_WORK_DURATION_NS)
    }
}
