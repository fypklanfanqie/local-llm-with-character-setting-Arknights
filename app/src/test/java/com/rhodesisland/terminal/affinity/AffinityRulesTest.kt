package com.rhodesisland.terminal.affinity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AffinityRulesTest {

    @Test
    fun affinityIsClampedToIndependentCharacterRange() {
        assertEquals(200f, clampAffinity(250f))
        assertEquals(0f, clampAffinity(-1f))
        assertEquals(100.5f, clampAffinity(100.5f))
    }

    @Test
    fun giftPriceMapsToConfiguredAffinityTier() {
        assertEquals(1f, affinityGainForGiftPrice(5_000))
        assertEquals(1f, affinityGainForGiftPrice(9_999))
        assertEquals(2f, affinityGainForGiftPrice(10_000))
        assertEquals(2f, affinityGainForGiftPrice(14_999))
        assertEquals(3f, affinityGainForGiftPrice(15_000))
        assertEquals(3f, affinityGainForGiftPrice(20_000))
        assertNull(affinityGainForGiftPrice(4_999))
        assertNull(affinityGainForGiftPrice(20_001))
    }

    @Test
    fun crossedThresholdsUnlockEachUnclaimedStageOnce() {
        assertEquals(listOf(50, 100), crossedAffinityThresholds(49.5f, 101f, emptySet()))
        assertEquals(listOf(150), crossedAffinityThresholds(100f, 200f, setOf(50, 100, 200)))
    }
}
