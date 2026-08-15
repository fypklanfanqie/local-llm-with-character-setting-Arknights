package com.rhodesisland.terminal.video

import com.rhodesisland.terminal.data.model.Character
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * SeedanceReferenceStore 不可变参考图快照 JVM 测试（Task 3）。
 *
 * 无 Android 依赖：假 [ImageProbe] 用纯 JVM 魔数嗅探 + PNG IHDR 解析，
 * 生成带真实尺寸的测试图片字节；Store 的复制 / SHA-256 / 幂等 / 校验逻辑走真实实现。
 * 覆盖：内置立绘复制、自定义 file:// 复制、缺失自定义图、可选背景、MIME/尺寸/宽高比/大小
 * 拒绝、SHA-256、快照后删除来源不影响复制、哈希一致幂等复用、内容不一致拒绝覆盖。
 */
class SeedanceReferenceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ===== 假探测（纯 JVM，与生产 AndroidImageProbe 共享 MIME 语义）=====

    /**
     * 假 [ImageProbe]：魔数嗅探 MIME，解析 PNG IHDR 取宽高，统计字节数。
     * 仅完整支持 PNG 尺寸解析（测试图片一律用 PNG 构造）；其余格式走 lambda probe 覆盖拒绝分支。
     */
    private class FakeProbe : ImageProbe {
        override fun probe(source: ProbeSource): ProbeResult? {
            val bytes = try {
                when (source) {
                    is ProbeSource.FromFile -> if (source.file.isFile) source.file.readBytes() else return null
                    is ProbeSource.FromStream -> source.openStream()?.use { it.readBytes() } ?: return null
                }
            } catch (e: Exception) {
                return null
            }
            if (bytes.isEmpty()) return null
            val mime = sniff(bytes) ?: return null
            val dimensions = if (mime == "image/png") pngDimensions(bytes) else null ?: return null
            return ProbeResult(
                mimeType = mime,
                width = dimensions.first,
                height = dimensions.second,
                byteSize = bytes.size.toLong(),
            )
        }

        private fun sniff(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
            bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
            bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() -> "image/gif"
            bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() && bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() &&
                bytes[8] == 'h'.code.toByte() && bytes[9] == 'e'.code.toByte() -> "image/heic"
            else -> null
        }

        /** 解析 PNG IHDR 宽高（大端，偏移 16/20）。 */
        private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
            fun readIntBE(offset: Int): Int =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            return readIntBE(16) to readIntBE(20)
        }
    }

    // ===== 测试图片构造 =====

    /** 构造最小 PNG 字节（签名 + IHDR 宽高；假探测不校验 CRC，复制层只透传字节）。 */
    private fun pngBytes(width: Int, height: Int, extraBytes: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0, 0, 0, 13))
        out.write("IHDR".toByteArray())
        fun writeIntBE(v: Int) {
            out.write(v ushr 24)
            out.write(v ushr 16)
            out.write(v ushr 8)
            out.write(v)
        }
        writeIntBE(width)
        writeIntBE(height)
        out.write(byteArrayOf(8, 6, 0, 0, 0)) // 位深 8 / RGBA / 压缩 0 / 滤波 0 / 交错 0
        out.write(byteArrayOf(0, 0, 0, 0))    // CRC 占位
        if (extraBytes > 0) out.write(ByteArray(extraBytes))
        return out.toByteArray()
    }

    // ===== 装配与断言助手 =====

    private fun store(
        probe: ImageProbe = FakeProbe(),
        assetBytes: Map<String, ByteArray> = emptyMap(),
    ): SeedanceReferenceStore = SeedanceReferenceStore(
        targetRoot = File(tmp.root, "seedance/tasks"),
        imageProbe = probe,
        openAssetStream = { path -> assetBytes[path]?.let { ByteArrayInputStream(it) } },
    )

    private fun builtInCharacter() = Character(
        id = "neighbor", name = "邻居", code = "", role = "邻居", race = "",
        systemPrompt = "",
    )

    private fun customCharacter(image: String) = Character(
        id = "custom-1", name = "自定义", code = "", role = "", race = "",
        image = image, systemPrompt = "", isCustom = true,
    )

    private fun refsDir(taskUuid: String) =
        File(File(tmp.root, "seedance/tasks"), "$taskUuid/references")

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun assertFailure(result: Result<*>, containsText: String) {
        assertTrue("期望失败，实际 $result", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败原因「$message」应包含「$containsText」", message.contains(containsText))
    }

    // ===== 内置立绘（assets 流）=====

    @Test
    fun builtInAsset_isCopiedIntoTaskReferences() = runBlocking {
        val bytes = pngBytes(900, 1200)
        val result = store(assetBytes = mapOf("characters/neighbor.png" to bytes))
            .snapshot("task-1", builtInCharacter(), "characters/neighbor.png", null)

        assertTrue(result.isSuccess)
        val snap = result.getOrThrow()
        val file = File(snap.characterPath)
        assertEquals(File(refsDir("task-1"), "character.png").absolutePath, file.absolutePath)
        assertTrue(file.isFile)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("image/png", snap.characterMime)
        assertEquals(sha256Of(file), snap.characterSha256)
        assertNull(snap.backgroundPath)
        assertNull(snap.backgroundMime)
        assertNull(snap.backgroundSha256)
    }

    @Test
    fun builtInAsset_missingFailsWithChineseMessage() = runBlocking {
        val result = store().snapshot("task-1", builtInCharacter(), "characters/missing.png", null)
        assertFailure(result, "立绘")
        assertTrue(!refsDir("task-1").exists())
    }

    @Test
    fun builtInAsset_blankPathFails() = runBlocking {
        val result = store().snapshot("task-1", builtInCharacter(), null, null)
        assertFailure(result, "立绘")
    }

    // ===== 自定义角色（file:// 内部路径）=====

    @Test
    fun customFileImage_isCopiedIntoTaskReferences() = runBlocking {
        val bytes = pngBytes(800, 800)
        val source = File(tmp.root, "portrait.png").apply { writeBytes(bytes) }
        val result = store().snapshot("task-2", customCharacter("file://" + source.absolutePath), null, null)

        assertTrue(result.isSuccess)
        val snap = result.getOrThrow()
        assertEquals(File(refsDir("task-2"), "character.png").absolutePath, snap.characterPath)
        assertArrayEquals(bytes, File(snap.characterPath).readBytes())
    }

    @Test
    fun customCharacter_blankImageFailsWithChineseMessage() = runBlocking {
        val result = store().snapshot("task-3", customCharacter(""), null, null)
        assertFailure(result, "立绘")
    }

    @Test
    fun customCharacter_missingImageFileFails() = runBlocking {
        val result = store().snapshot("task-3", customCharacter("file:///nonexistent/portrait.png"), null, null)
        assertFailure(result, "立绘")
        assertTrue(!refsDir("task-3").exists())
    }

    // ===== 可选背景 =====

    @Test
    fun optionalBackground_isCopiedIntoTaskReferences() = runBlocking {
        val charBytes = pngBytes(900, 1200)
        val bgBytes = pngBytes(1600, 900)
        val bgFile = File(tmp.root, "bg.png").apply { writeBytes(bgBytes) }
        val result = store(assetBytes = mapOf("characters/neighbor.png" to charBytes))
            .snapshot("task-4", builtInCharacter(), "characters/neighbor.png", bgFile.absolutePath)

        assertTrue(result.isSuccess)
        val snap = result.getOrThrow()
        val bgCopy = File(snap.backgroundPath!!)
        assertEquals(File(refsDir("task-4"), "background.png").absolutePath, bgCopy.absolutePath)
        assertArrayEquals(bgBytes, bgCopy.readBytes())
        assertEquals("image/png", snap.backgroundMime)
        assertEquals(sha256Of(bgCopy), snap.backgroundSha256)
    }

    @Test
    fun blankBackground_isIgnored() = runBlocking {
        val result = store(assetBytes = mapOf("characters/neighbor.png" to pngBytes(900, 1200)))
            .snapshot("task-5", builtInCharacter(), "characters/neighbor.png", "")
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().backgroundPath)
    }

    @Test
    fun missingBackgroundFile_fails() = runBlocking {
        val result = store(assetBytes = mapOf("characters/neighbor.png" to pngBytes(900, 1200)))
            .snapshot("task-6", builtInCharacter(), "characters/neighbor.png", "/nonexistent/bg.png")
        assertFailure(result, "背景图")
    }

    // ===== 校验拒绝 =====

    @Test
    fun unsupportedMime_isRejected() = runBlocking {
        // HEIC：官方 V1 支持子集外，识别后必须由校验层拒绝
        val heicProbe = ImageProbe { ProbeResult("image/heic", 800, 800, 10_000) }
        val result = store(probe = heicProbe, assetBytes = mapOf("characters/neighbor.webp" to pngBytes(900, 1200)))
            .snapshot("task-7", builtInCharacter(), "characters/neighbor.webp", null)
        assertFailure(result, "格式不支持")
    }

    @Test
    fun smallSide_notGreaterThan300_isRejected() = runBlocking {
        // 短边 100：单边像素约束先于宽高比检查
        val result = store(assetBytes = mapOf("characters/neighbor.png" to pngBytes(100, 500)))
            .snapshot("task-8", builtInCharacter(), "characters/neighbor.png", null)
        assertFailure(result, "像素")
    }

    @Test
    fun largeSide_notLessThan6000_isRejected() = runBlocking {
        val result = store(assetBytes = mapOf("characters/neighbor.png" to pngBytes(7000, 800)))
            .snapshot("task-9", builtInCharacter(), "characters/neighbor.png", null)
        assertFailure(result, "像素")
    }

    @Test
    fun aspectRatioOutOfRange_isRejected() = runBlocking {
        // 400x3000：单边合规但宽高比 0.133 < 0.4
        val result = store(assetBytes = mapOf("characters/neighbor.png" to pngBytes(400, 3000)))
            .snapshot("task-10", builtInCharacter(), "characters/neighbor.png", null)
        assertFailure(result, "宽高比")
    }

    @Test
    fun oversizedImage_isRejected() = runBlocking {
        // 探测字节数 ≥ 30MB 时复制前拒绝（尾部填充模拟大文件）
        val big = pngBytes(900, 1200, extraBytes = 30 * 1024 * 1024)
        val result = store(assetBytes = mapOf("characters/neighbor.png" to big))
            .snapshot("task-11", builtInCharacter(), "characters/neighbor.png", null)
        assertFailure(result, "30MB")
    }

    // ===== SHA-256 与不可变复制 =====

    @Test
    fun sha256_matchesStandardDigest() = runBlocking {
        val bytes = pngBytes(640, 640)
        val result = store(assetBytes = mapOf("characters/neighbor.png" to bytes))
            .snapshot("task-12", builtInCharacter(), "characters/neighbor.png", null)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expected, result.getOrThrow().characterSha256)
    }

    @Test
    fun sourceDeletionAfterSnapshot_keepsImmutableCopy() = runBlocking {
        val bytes = pngBytes(800, 800)
        val source = File(tmp.root, "portrait.png").apply { writeBytes(bytes) }
        val result = store().snapshot("task-13", customCharacter("file://" + source.absolutePath), null, null)
        assertTrue(result.isSuccess)
        val snap = result.getOrThrow()

        assertTrue(source.delete())
        val copy = File(snap.characterPath)
        assertTrue(copy.isFile)
        assertArrayEquals(bytes, copy.readBytes())
        assertEquals(sha256Of(copy), snap.characterSha256)
    }

    @Test
    fun resnapshotWithMatchingHash_reusesExistingFile() = runBlocking {
        val bytes = pngBytes(800, 800)
        val source = File(tmp.root, "portrait.png").apply { writeBytes(bytes) }
        val target = store()
        val first = target.snapshot("task-14", customCharacter("file://" + source.absolutePath), null, null)
        assertTrue(first.isSuccess)
        val recorded = File(first.getOrThrow().characterPath).readBytes()

        val second = target.snapshot("task-14", customCharacter("file://" + source.absolutePath), null, null)
        assertTrue(second.isSuccess)
        assertEquals(first.getOrThrow().characterSha256, second.getOrThrow().characterSha256)
        assertEquals(first.getOrThrow().characterPath, second.getOrThrow().characterPath)
        assertArrayEquals(recorded, File(second.getOrThrow().characterPath).readBytes())
    }

    @Test
    fun resnapshotWithDifferentContent_failsInsteadOfSilentOverwrite() = runBlocking {
        val bytesA = pngBytes(800, 800)
        val bytesB = pngBytes(810, 810)
        val sourceA = File(tmp.root, "a.png").apply { writeBytes(bytesA) }
        val sourceB = File(tmp.root, "b.png").apply { writeBytes(bytesB) }
        val target = store()
        val first = target.snapshot("task-15", customCharacter("file://" + sourceA.absolutePath), null, null)
        assertTrue(first.isSuccess)

        val second = target.snapshot("task-15", customCharacter("file://" + sourceB.absolutePath), null, null)
        assertFailure(second, "不一致")
        assertArrayEquals(bytesA, File(first.getOrThrow().characterPath).readBytes())
    }

    // ===== validateReferenceImage 边界（直接单测）=====

    @Test
    fun validate_boundaryValues() {
        // 恰好通过：短边 > 300、长边 < 6000、宽高比 1.0
        assertNull(validateReferenceImage(ProbeResult("image/jpeg", 400, 400, 1024), "角色立绘图片"))
        // 短边恰好 300：拒绝（须大于 300）
        assertNotNull(validateReferenceImage(ProbeResult("image/jpeg", 300, 600, 1024), "角色立绘图片"))
        // 长边恰好 6000：拒绝（须小于 6000）
        assertNotNull(validateReferenceImage(ProbeResult("image/jpeg", 600, 6000, 1024), "角色立绘图片"))
        // 宽高比恰好 0.4 / 2.5：通过（含端点）
        assertNull(validateReferenceImage(ProbeResult("image/jpeg", 320, 800, 1024), "角色立绘图片"))
        assertNull(validateReferenceImage(ProbeResult("image/jpeg", 800, 320, 1024), "角色立绘图片"))
        // 宽高比越过端点：拒绝
        assertNotNull(validateReferenceImage(ProbeResult("image/jpeg", 320, 803, 1024), "角色立绘图片"))
        // 大小恰好 30MB：拒绝（须小于 30MB）
        assertNotNull(validateReferenceImage(ProbeResult("image/jpeg", 800, 800, 30L * 1024 * 1024), "角色立绘图片"))
        // heic/heif：官方 V1 支持子集外，拒绝
        assertNotNull(validateReferenceImage(ProbeResult("image/heic", 800, 800, 1024), "角色立绘图片"))
        assertNotNull(validateReferenceImage(ProbeResult("image/heif", 800, 800, 1024), "角色立绘图片"))
    }
}
