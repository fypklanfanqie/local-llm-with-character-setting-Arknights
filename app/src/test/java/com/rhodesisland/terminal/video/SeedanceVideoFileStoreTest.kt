package com.rhodesisland.terminal.video

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [SeedanceVideoFileStore] 契约测试（Task 6，真实临时目录 File I/O，无 Android）。
 *
 * 覆盖：原子改名产出最终文件 + 哈希、空响应拒绝、非视频魔数拒绝、MIME 提示回退、
 * `.part` 残留绝不视为成品、哈希校验幂等、截断拒绝、磁盘满（改名失败）拒绝。
 */
class SeedanceVideoFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(now: Long = 1_000_000L) = SeedanceVideoFileStore(tmp.root) { now }

    private fun mp4Bytes(payload: ByteArray = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)): ByteArray =
        byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + payload

    private val webmHeader = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    @Test
    fun saveWritesFinalFileWithHash() = runBlocking {
        val s = store()
        val data = mp4Bytes()
        val result = s.save("t1", "video/mp4", data.size.toLong(), ByteArrayInputStream(data))

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(File(file.path).isFile)
        assertTrue(file.path.endsWith("video.mp4"))
        assertEquals(data.size.toLong(), file.byteSize)
        assertEquals("video/mp4", file.mime)
        assertEquals(64, file.sha256.length)
        assertNull(s.findPartFile("t1"))
        assertEquals(file.path, s.findFinalFile("t1")?.absolutePath)
    }

    @Test
    fun saveRejectsEmptyBody() = runBlocking {
        val s = store()
        val result = s.save("t2", "video/mp4", null, ByteArrayInputStream(ByteArray(0)))
        assertTrue(result.isFailure)
        assertNull(s.findFinalFile("t2"))
        assertNull(s.findPartFile("t2"))
    }

    @Test
    fun saveRejectsNonVideoSignature() = runBlocking {
        val s = store()
        val result = s.save("t3", null, null, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertTrue(result.isFailure)
        assertNull(s.findFinalFile("t3"))
    }

    @Test
    fun saveAcceptsMimeHintWhenSignatureUnknown() = runBlocking {
        val s = store()
        // 无 mp4/webm 魔数，但响应声明 video/mp4 -> 回退接受
        val result = s.save("t4", "video/mp4", 4L, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        assertTrue(result.isSuccess)
        assertEquals("video/mp4", result.getOrThrow().mime)
    }

    @Test
    fun partFileIsNeverFinal() = runBlocking {
        val s = store()
        val dir = s.taskDir("t5")
        dir.mkdirs()
        File(dir, "video.mp4.part").writeBytes(mp4Bytes())
        // 只有 .part，无最终成品
        assertNull(s.findFinalFile("t5"))
        assertNotNull(s.findPartFile("t5"))
        assertNull(s.verifyExisting("t5", "whatever"))
    }

    @Test
    fun verifyExistingMatchesHash() = runBlocking {
        val s = store()
        val data = mp4Bytes()
        val saved = s.save("t6", "video/mp4", data.size.toLong(), ByteArrayInputStream(data)).getOrThrow()
        // 哈希匹配 -> 返回元信息
        val verified = s.verifyExisting("t6", saved.sha256)
        assertNotNull(verified)
        assertEquals(saved.sha256, verified!!.sha256)
        // 哈希不匹配 -> null
        assertNull(s.verifyExisting("t6", "deadbeef"))
    }

    @Test
    fun saveRejectsTruncatedContent() = runBlocking {
        val s = store()
        val result = s.save("t7", "video/mp4", 1000L, ByteArrayInputStream(mp4Bytes(byteArrayOf(1, 2))))
        assertTrue(result.isFailure)
        assertNull(s.findFinalFile("t7"))
    }

    @Test
    fun saveFailsWhenTargetUnwritable() = runBlocking {
        val s = store()
        val dir = s.taskDir("t8")
        dir.mkdirs()
        // 预创建同名目录占据 .part 路径 -> 写入失败（模拟磁盘满/占用）
        File(dir, "video.part").mkdirs()
        val result = s.save("t8", "video/mp4", null, ByteArrayInputStream(mp4Bytes()))
        assertTrue(result.isFailure)
        assertNull(s.findFinalFile("t8"))
    }

    @Test
    fun webmSignatureDetected() = runBlocking {
        val s = store()
        val data = webmHeader + byteArrayOf(1, 2, 3, 4, 5, 6)
        val result = s.save("t9", null, null, ByteArrayInputStream(data))
        assertTrue(result.isSuccess)
        assertEquals("video/webm", result.getOrThrow().mime)
        assertTrue(result.getOrThrow().path.endsWith("video.webm"))
    }

    @Test
    fun verifyExistingChecksActualFileBytes() = runBlocking {
        val s = store()
        val dir = s.taskDir("t10")
        dir.mkdirs()
        // 直接放一个非 .part 的「成品」文件，但内容与期望哈希不符 -> 不算数
        File(dir, "video.mp4").writeBytes(mp4Bytes(byteArrayOf(1, 2, 3)))
        val valid = s.save("t10", "video/mp4", null, ByteArrayInputStream(mp4Bytes(byteArrayOf(9)))).getOrThrow()
        assertFalse(valid.sha256.isBlank())
        // save 会覆盖旧无效成品，verifyExisting 以实际字节为准
        assertEquals(valid.sha256, s.verifyExisting("t10", valid.sha256)?.sha256)
    }
}
