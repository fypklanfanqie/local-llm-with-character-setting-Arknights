package com.rhodesisland.terminal.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 模型包完整性校验测试（Task 12 Step 1）。 */
class ModelBundleValidatorTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempDir()
    }

    private fun file(rel: String, content: String = "x") {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun validMinimal() {
        file("config.json", "{\"model_path\":\"llm.mnn\"}")
        file("llm.mnn")
        file("llm.mnn.weight")
        file("tokenizer.txt")
    }

    @Test
    fun validMinimalTextOnlyBundlePasses() {
        validMinimal()
        val r = ModelBundleValidator.validate(root)

        assertTrue("errors=$r", r.valid)
        assertTrue(r.modelFingerprint.isNotBlank())
        // 必需文件 = 默认集（llm.mnn/weight/tokenizer）+ config 引用（model_path=llm.mnn）。
        assertTrue(r.requiredFiles.contains("llm.mnn"))
        assertTrue(r.requiredFiles.contains("llm.mnn.weight"))
        assertTrue(r.requiredFiles.contains("tokenizer.txt"))
    }

    @Test
    fun missingTokenizerIsInvalid() {
        validMinimal()
        File(root, "tokenizer.txt").delete()

        val r = ModelBundleValidator.validate(root)

        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("tokenizer.txt") })
    }

    @Test
    fun missingExternalWeightIsInvalid() {
        validMinimal()
        File(root, "llm.mnn.weight").delete()

        val r = ModelBundleValidator.validate(root)

        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("llm.mnn.weight") })
    }

    @Test
    fun zeroByteRequiredFileIsInvalid() {
        validMinimal()
        File(root, "tokenizer.txt").writeText("")

        val r = ModelBundleValidator.validate(root)

        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("为空") })
    }

    @Test
    fun malformedConfigJsonIsInvalid() {
        file("config.json", "{not valid json")
        file("llm.mnn"); file("llm.mnn.weight"); file("tokenizer.txt")

        val r = ModelBundleValidator.validate(root)

        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("不是合法 JSON") })
    }

    @Test
    fun pathTraversalInConfigIsRejected() {
        file("config.json", "{\"model_path\":\"../../evil.mnn\"}")
        file("llm.mnn"); file("llm.mnn.weight"); file("tokenizer.txt")

        val r = ModelBundleValidator.validate(root)

        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("逃逸") })
    }

    @Test
    fun partialPartFilesRemainAreWarningNotHardFailure() {
        validMinimal()
        file("llm.mnn.weight.part1")

        val r = ModelBundleValidator.validate(root)

        assertTrue("残留分片只是警告", r.valid)
        assertTrue(r.warnings.any { it.contains("分片") })
    }

    @Test
    fun optionalMultimodalAbsenceIsTolerated() {
        validMinimal()
        file("config.json", "{\"model_path\":\"llm.mnn\",\"visual_path\":\"visual.bin\"}")
        // visual.bin 缺失：多模态可选文件，缺失应告警而非错误。
        val r = ModelBundleValidator.validate(root)

        assertTrue("多模态可选缺失应容忍: $r", r.valid)
        assertTrue(r.warnings.any { it.contains("visual.bin") })
    }
}
