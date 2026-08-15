package com.rhodesisland.terminal.llm.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 指纹规范化/失效测试（Task 9 Step 1）。 */
class FingerprintTest {

    private val base = mapOf(
        "manufacturer" to "Xiaomi",
        "model" to "2312DRA50C",
        "osFingerprint" to "Xiaomi/xxx:14/UP1A/1:user",
        "soc" to "SM8550",
        "openclDriver" to "v2.2.0 (Adreno)",
        "mnnCommit" to "af0142bcc7b76b7a5128373e285683dc04f55f69",
        "nativeBuildId" to "batch-2026-08-09",
        "policySchema" to "1",
        "abi" to "arm64-v8a",
    )

    @Test
    fun hashIsIndependentOfMapIterationOrder() {
        val reversed = base.entries.toList().reversed().toMap()

        assertEquals(
            DeviceRuntimeFingerprint.canonicalHash(base),
            DeviceRuntimeFingerprint.canonicalHash(reversed),
        )
    }

    @Test
    fun osFingerprintChangeInvalidates() {
        val changed = base + ("osFingerprint" to "Xiaomi/xxx:14/UP1A/2:user")
        assertNotEquals(
            DeviceRuntimeFingerprint.compute(base),
            DeviceRuntimeFingerprint.compute(changed),
        )
    }

    @Test
    fun openclDriverChangeInvalidates() {
        val changed = base + ("openclDriver" to "v2.1.0 (Adreno)")
        assertNotEquals(
            DeviceRuntimeFingerprint.compute(base),
            DeviceRuntimeFingerprint.compute(changed),
        )
    }

    @Test
    fun modelFingerprintChangeInvalidates() {
        val modelA = mapOf(
            "config.json" to "hashA",
            "llm.mnn" to "hashW",
            "tokenizer.txt" to "hashT",
        )
        val modelB = modelA + ("llm.mnn" to "hashW2")

        assertNotEquals(
            DeviceRuntimeFingerprint.computeModel(modelA),
            DeviceRuntimeFingerprint.computeModel(modelB),
        )
        assertEquals(
            DeviceRuntimeFingerprint.computeModel(modelA),
            DeviceRuntimeFingerprint.computeModel(modelA),
        )
    }
}
