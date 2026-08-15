package com.rhodesisland.terminal.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KV-head-aware 内存公式测试（Task 13 Step 1）。 */
class LlmMemoryEstimatorTest {

    @Test
    fun kvFormulaUsesGqaHeadsAndHeadDim() {
        // context × layers × 2 × kvHeads × headDim × bytes(2)
        val bytes = LlmMemoryEstimator.kvCacheBytes(
            contextTokens = 4096, layerCount = 28, kvHeads = 4, headDim = 128,
        )
        assertEquals(4096L * 28 * 2 * 4 * 128 * 2, bytes)
    }

    @Test
    fun gqaIsSmallerThanFullHiddenSizeEstimate() {
        // 等价 MHA（kvHeads = hiddenSize/headDim = 4096/128 = 32）-> full 公式。
        val full = LlmMemoryEstimator.kvCacheBytesFullHidden(4096, 28, hiddenSize = 4096)
        val gqa = LlmMemoryEstimator.kvCacheBytes(4096, 28, kvHeads = 4, headDim = 128)

        assertTrue("GQA($gqa) 应显著小于 full($full)", gqa * 8 == full)
    }

    @Test
    fun fullHiddenFallbackMatchesMhaWhenHeadsMatch() {
        val mha = LlmMemoryEstimator.kvCacheBytes(4096, 28, kvHeads = 32, headDim = 128)
        assertEquals(
            LlmMemoryEstimator.kvCacheBytesFullHidden(4096, 28, hiddenSize = 4096),
            mha,
        )
    }
}
