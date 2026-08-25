package com.rhodesisland.terminal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** MnnTmpDirJanitor（tmp_path 磁盘账本）单测：命名/资格/预算/清扫顺序。 */
class MnnTmpDirJanitorTest {

    private fun writeFile(dir: File, name: String, size: Int): File {
        val f = File(dir, name)
        f.writeBytes(ByteArray(size))
        return f
    }

    // ===== 命名 =====

    @Test
    fun tmpDirNameIsDeterministicAndDistinctPerPath() {
        val root = createTempDir()
        val a = MnnTmpDirJanitor.tmpDirFor(root, root.absolutePath + "/m/alpha/config.json")
        val a2 = MnnTmpDirJanitor.tmpDirFor(root, root.absolutePath + "/m/alpha/config.json")
        val b = MnnTmpDirJanitor.tmpDirFor(root, root.absolutePath + "/m/beta/config.json")
        assertEquals(a, a2)
        assertFalse("不同 config 路径应落不同目录", a == b)
        assertTrue(a.name.startsWith(MnnTmpDirJanitor.TMP_DIR_PREFIX))
        assertTrue(a.parentFile == root)
    }

    // ===== 资格 =====

    @Test
    fun eligibilityRequiresModelTimes1_5PlusHeadroom() {
        val model = 1_000L * 1024 * 1024 // 1 GiB 模型
        val need = (model * 1.5).toLong() + MnnTmpDirJanitor.MIN_FREE_HEADROOM_BYTES
        assertTrue(MnnTmpDirJanitor.eligibleFor(model, freeBytes = need))
        assertFalse(MnnTmpDirJanitor.eligibleFor(model, freeBytes = need - 1))
        assertFalse("极端不足应拒绝", MnnTmpDirJanitor.eligibleFor(model, freeBytes = 0L))
    }

    @Test
    fun unknownWeightStillRequiresHeadroomFloor() {
        // 未知权重按 0 处理：仍需固定余量门槛（避免小分区打爆）。
        assertFalse(MnnTmpDirJanitor.eligibleFor(modelWeightBytes = 0L, freeBytes = MnnTmpDirJanitor.MIN_FREE_HEADROOM_BYTES - 1))
        assertTrue(MnnTmpDirJanitor.eligibleFor(modelWeightBytes = 0L, freeBytes = MnnTmpDirJanitor.MIN_FREE_HEADROOM_BYTES))
    }

    // ===== 预算 =====

    @Test
    fun defaultBudgetKeepsOneLargestInstalledCopyPlusSlack() {
        val root = createTempDir()
        val installed = File(root, "mnn_tmp_inst1").apply { mkdirs() }
        writeFile(installed, "sync.static", 200)
        File(root, "mnn_tmp_inst2").apply { mkdirs() }
        val budget = MnnTmpDirJanitor.defaultBudgetBytes(root, setOf("inst1", "inst2"))
        assertTrue("预算应 ≥ 最大已装一份 + 余量", budget >= 200L + MnnTmpDirJanitor.DEFAULT_BUDGET_SLACK_BYTES)
    }

    @Test
    fun defaultBudgetFallsBackToFloorWhenNoInstalledDirs() {
        val root = createTempDir()
        assertEquals(
            MnnTmpDirJanitor.DEFAULT_BUDGET_FLOOR_BYTES,
            MnnTmpDirJanitor.defaultBudgetBytes(root, emptySet()),
        )
    }

    // ===== 清扫 =====

    @Test
    fun sweepRemovesOrphansThenLruEvictsToBudget() {
        val root = createTempDir()
        val orphan = File(root, "mnn_tmp_orphan").apply { mkdirs() }
        writeFile(orphan, "sync.static", 100)
        val oldInstalled = File(root, "mnn_tmp_bbb").apply { mkdirs() }
        writeFile(oldInstalled, "sync.static", 200)
        oldInstalled.setLastModified(1_000_000_000_000L)
        val newInstalled = File(root, "mnn_tmp_ccc").apply { mkdirs() }
        writeFile(newInstalled, "sync.static", 150)
        newInstalled.setLastModified(1_500_000_000_000L)

        val removed = MnnTmpDirJanitor.sweep(root, setOf("bbb", "ccc"), budgetBytes = 200L)

        assertEquals("孤儿优先清，其次 LRU 逐最旧已装", setOf("mnn_tmp_orphan", "mnn_tmp_bbb"), removed.map { it.name }.toSet())
        assertFalse(orphan.exists())
        assertFalse(oldInstalled.exists())
        assertTrue("最新的已装缓存应保留", newInstalled.exists())
    }

    @Test
    fun highBudgetKeepsInstalledButStillClearsOrphans() {
        val root = createTempDir()
        val orphan = File(root, "mnn_tmp_orphan").apply { mkdirs() }
        writeFile(orphan, "sync.static", 100)
        val installed = File(root, "mnn_tmp_keep").apply { mkdirs() }
        writeFile(installed, "sync.static", 200)

        val removed = MnnTmpDirJanitor.sweep(root, setOf("keep"), budgetBytes = Long.MAX_VALUE)

        assertEquals(setOf("mnn_tmp_orphan"), removed.map { it.name }.toSet())
        assertTrue(installed.exists())
    }

    @Test
    fun sweepNoopWhenNothingToDelete() {
        val root = createTempDir()
        val installed = File(root, "mnn_tmp_keep").apply { mkdirs() }
        writeFile(installed, "sync.static", 100)
        val removed = MnnTmpDirJanitor.sweep(root, setOf("keep"), budgetBytes = Long.MAX_VALUE)
        assertTrue(removed.isEmpty())
        assertTrue(installed.exists())
    }
}