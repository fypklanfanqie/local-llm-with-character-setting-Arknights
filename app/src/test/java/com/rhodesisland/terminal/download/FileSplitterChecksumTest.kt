package com.rhodesisland.terminal.download

import com.rhodesisland.terminal.download.FileSplitter.ChunkInfo
import com.rhodesisland.terminal.download.FileSplitter.SplitInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 分片校验和测试（Task 12 Step 3）。 */
class FileSplitterChecksumTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempDir()
    }

    @Test
    fun sha256OfKnownContentIsStable() {
        val f = File(root, "a.txt").apply { writeText("hello") }
        // "hello" 的标准 SHA-256。
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            FileSplitter.sha256(f),
        )
    }

    @Test
    fun mergeValidatesChunkChecksumsAndProducesMergedFile() {
        val modelDir = File(root, "model").apply { mkdirs() }
        val original = "0123456789abcdef" // 16 bytes
        val part1 = original.substring(0, 8).toByteArray()
        val part2 = original.substring(8).toByteArray()
        File(modelDir, "weight.bin.part1").writeBytes(part1)
        File(modelDir, "weight.bin.part2").writeBytes(part2)
        val splitInfo = SplitInfo(
            originalFileName = "weight.bin",
            originalFileSize = 16L,
            chunkSize = 8L,
            totalChunks = 2,
            chunks = listOf(
                ChunkInfo(0, "weight.bin.part1", 8L, FileSplitter.sha256(File(modelDir, "weight.bin.part1"))),
                ChunkInfo(1, "weight.bin.part2", 8L, FileSplitter.sha256(File(modelDir, "weight.bin.part2"))),
            ),
        )
        File(modelDir, "splits_info.json").writeText(Json.encodeToString(splitInfo))

        assertTrue(FileSplitter.mergeAllSplitFiles(modelDir))
        assertEquals(original, File(modelDir, "weight.bin").readText())
    }

    @Test
    fun mergeRejectsWrongChunkChecksum() {
        val modelDir = File(root, "model2").apply { mkdirs() }
        val part1 = "abcdefgh".toByteArray()
        File(modelDir, "weight.bin.part1").writeBytes(part1)
        val splitInfo = SplitInfo(
            originalFileName = "weight.bin",
            originalFileSize = 8L,
            chunkSize = 8L,
            totalChunks = 1,
            chunks = listOf(
                ChunkInfo(0, "weight.bin.part1", 8L, "deadbeef"), // 错误 checksum
            ),
        )
        File(modelDir, "splits_info.json").writeText(Json.encodeToString(splitInfo))

        assertFalse(FileSplitter.mergeAllSplitFiles(modelDir))
        assertFalse("校验失败不应产出合并文件", File(modelDir, "weight.bin").exists())
    }
}
