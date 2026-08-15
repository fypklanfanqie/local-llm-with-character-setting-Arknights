package com.rhodesisland.terminal.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTest {

    private fun model(totalParamsB: Float?): ModelInfo =
        ModelInfo(id = "m", name = "m", size = 1L, totalParamsB = totalParamsB)

    @Test
    fun strictlyBelowSevenUsesCpu() {
        assertEquals(AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, model(6.99f).autoBackendModelClass)
        assertEquals(AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, model(0.8f).autoBackendModelClass)
        assertEquals(AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, model(4.0f).autoBackendModelClass)
    }

    @Test
    fun exactlySevenUsesCpu() {
        // 严格「>7B」才开 GPU：恰好 7.0B 走 CPU。
        assertEquals(AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD, model(7.0f).autoBackendModelClass)
    }

    @Test
    fun strictlyAboveSevenUsesGpu() {
        assertEquals(AutoBackendModelClass.GPU_ELIGIBLE, model(7.01f).autoBackendModelClass)
        assertEquals(AutoBackendModelClass.GPU_ELIGIBLE, model(8.0f).autoBackendModelClass)
        assertEquals(AutoBackendModelClass.GPU_ELIGIBLE, model(9.0f).autoBackendModelClass)
        assertEquals(AutoBackendModelClass.GPU_ELIGIBLE, model(35.0f).autoBackendModelClass)
    }

    @Test
    fun unknownParametersUseCpu() {
        assertEquals(AutoBackendModelClass.CPU_UNKNOWN_PARAMETERS, model(null).autoBackendModelClass)
    }

    @Test
    fun thresholdConstantIsSeven() {
        assertEquals(7.0f, ModelInfo.AUTO_GPU_THRESHOLD_B, 0.0f)
    }

    @Test
    fun allBuiltinModelsHaveKnownPositiveParams() {
        DEFAULT_MNN_MODELS.forEach { m ->
            assertTrue(
                "内置模型 ${m.id} 缺 totalParamsB（应填总参数量）",
                m.totalParamsB != null && m.totalParamsB!! > 0f,
            )
        }
    }

    @Test
    fun builtinRoutingClassification() {
        val byId = DEFAULT_MNN_MODELS.associateBy { it.id }
        assertEquals(
            AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
            byId.getValue("Qwen3.5-2B-MNN").autoBackendModelClass,
        )
        assertEquals(
            AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
            byId.getValue("Qwen3.5-4B-MNN").autoBackendModelClass,
        )
        // 7B 模型严格不满足 >7B，AUTO 下走 CPU。
        assertEquals(
            AutoBackendModelClass.CPU_BELOW_OR_EQUAL_THRESHOLD,
            byId.getValue("DeepSeek-R1-7B-Qwen-MNN").autoBackendModelClass,
        )
        assertEquals(
            AutoBackendModelClass.GPU_ELIGIBLE,
            byId.getValue("DeepSeek-R1-0528-Qwen3-8B-MNN").autoBackendModelClass,
        )
        assertEquals(
            AutoBackendModelClass.GPU_ELIGIBLE,
            byId.getValue("Qwen3.5-9B-MNN").autoBackendModelClass,
        )
        // MoE 按总参数量 35B 判定，允许 GPU。
        assertEquals(
            AutoBackendModelClass.GPU_ELIGIBLE,
            byId.getValue("Qwen3.5-35B-A3B-MNN").autoBackendModelClass,
        )
    }
}
