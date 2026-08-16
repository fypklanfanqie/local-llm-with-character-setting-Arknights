package com.rhodesisland.terminal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 存储统计纯函数测试：字节格式化与递归目录大小（缺失目录按 0）。
 */
class AppStorageUsageTest {

    @Test
    fun formatBytes_sizes() {
        assertEquals("0 B", AppStorageUsage.formatBytes(0))
        assertEquals("512 B", AppStorageUsage.formatBytes(512))
        assertEquals("1.0 KB", AppStorageUsage.formatBytes(1024))
        assertEquals("1.5 MB", AppStorageUsage.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", AppStorageUsage.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun dirSize_recursiveAndMissing() {
        val root = Files.createTempDirectory("storage-usage-test").toFile()
        try {
            File(root, "a.txt").writeText("12345") // 5 bytes
            val sub = File(root, "sub").apply { mkdirs() }
            File(sub, "b.txt").writeText("1234567890") // 10 bytes
            assertEquals(15L, AppStorageUsage.dirSize(root))
            assertEquals(0L, AppStorageUsage.dirSize(File(root, "missing")))
            assertTrue(root.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}