package com.rhodesisland.terminal.util

import java.io.File
import java.security.MessageDigest

/**
 * MNN `tmp_path` 临时目录管家（JVM 纯逻辑，可单测）。
 *
 * `libMNN.so` 的 `tmp_path`（llm.cpp setRuntimeHint）激活三件事：
 * - `use_mmap=true` 真正生效：权重变换结果写入 `tmp_path`（EXTERNAL_WEIGHT_DIR），
 *   成为可被系统按需逐出的 file-backed 页（驻留 RAM 下降、减少 LMK 杀）；
 * - `use_cached_mmap`（引擎默认 true）得偿所愿：`<tmp_path>/0_0_0_0_sync.static` 缓存
 *   已变换权重，二次加载跳过在线权重变换（冷启动/换会话重载显著变快）；
 * - 非 CPU 后端（OpenCL）的 tuned-kernel 缓存从相对 CWD 的 `./mnn_cachefile.bin`
 *   （Android 上不可写）回升到 `tmp_path/mnn_cachefile.bin`（可写的崩溃修复，见项目记忆）。
 *
 * 目录命名 `<cacheDir>/mnn_tmp_<sha256(modelConfigPath)[:8]>`，与
 * [com.rhodesisland.terminal.llm.profile.InferenceProfileResolver] 的
 * `runtimeTmpDir` 共享同一 [tmpDirFor]——删除/驱逐与加载用同一把尺子。
 *
 * 磁盘账本：每份 sync.static ≈ 对应模型大小。由 [sweep] 在加载前按「未安装优先 + LRU 驱逐
 * 到预算」维护；设置页清缓存也会一并清掉（下次加载自动重建，仅多一次权重变换耗时）。
 */
object MnnTmpDirJanitor {

    /** tmp 目录前缀（目录名 = 前缀 + 模型 config 路径哈希前 8 位）。 */
    const val TMP_DIR_PREFIX = "mnn_tmp_"

    /** 磁盘资格门槛的固定余量：除模型权重 1.5 倍外额外交付给变换临时文件的空闲额度。 */
    const val MIN_FREE_HEADROOM_BYTES = 512L * 1024 * 1024

    /** 预算下限（无已装模型 tmp 目录时兜底）：一份该量级模型 + 余量。 */
    const val DEFAULT_BUDGET_FLOOR_BYTES = 1L * 1024 * 1024 * 1024

    /** 预算斜高：在最大已装模型一份之上额外保留的空闲额度。 */
    const val DEFAULT_BUDGET_SLACK_BYTES = 1L * 1024 * 1024 * 1024

    private const val SHA_TAG_LENGTH = 8

    /** 某模型的 tmp 目录（按 config.json 绝对路径哈希命名，与 resolver 的 runtimeTmpDir 同一命名）。 */
    fun tmpDirFor(cacheDir: File, modelConfigPath: String): File =
        File(cacheDir, TMP_DIR_PREFIX + sha256Hex(modelConfigPath).take(SHA_TAG_LENGTH))

    /**
     * 磁盘资格判定：空闲（availableBytes）是否足够承担该模型的变换写入。
     * 不满足时调用方应**省略** `tmp_path` 键（回到全内存驻留现状，安全降级），
     * 而不是写一个会被 mmap 打爆磁盘的小分区。
     * @param modelWeightBytes 权重工作集大小；0/负值视为「未知」，按最小模型处理。
     */
    fun eligibleFor(modelWeightBytes: Long, freeBytes: Long): Boolean =
        freeBytes >= (modelWeightBytes.coerceAtLeast(0L) * 1.5).let { if (it < Long.MAX_VALUE - MIN_FREE_HEADROOM_BYTES) it + MIN_FREE_HEADROOM_BYTES else it }

    /**
     * 默认预算：已装模型中**最大一份** tmp 目录大小 + [DEFAULT_BUDGET_SLACK_BYTES] 余量；
     * 无已装模型 tmp 目录时用 [DEFAULT_BUDGET_FLOOR_BYTES]。
     * 语义：至少保留一份最大活跃模型的权重缓存（冷启动收益优先），其余可让位。
     */
    fun defaultBudgetBytes(cacheDir: File, installedModelHashes: Set<String>): Long {
        val largestInstalled = cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith(TMP_DIR_PREFIX) }
            ?.asSequence()
            ?.filter { it.name.substringAfter(TMP_DIR_PREFIX) in installedModelHashes }
            ?.maxOfOrNull { dirSizeOf(it) }
            ?: 0L
        return if (largestInstalled > 0L) {
            largestInstalled + DEFAULT_BUDGET_SLACK_BYTES
        } else {
            DEFAULT_BUDGET_FLOOR_BYTES
        }
    }

    /**
     * 清扫：先删未安装模型的 tmp 目录，再按 LRU（lastModified 最旧优先）驱逐到预算内。
     * @return 被删目录列表（调用方可记日志）。
     * @param budgetBytes 预算；[Long.MAX_VALUE] 表示「只清未安装，不匀预算」。
     */
    fun sweep(cacheDir: File, installedModelHashes: Set<String>, budgetBytes: Long): List<File> {
        val entries = (cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith(TMP_DIR_PREFIX) } ?: emptyArray())
            .map { it to dirSizeOf(it) }
        if (entries.isEmpty()) return emptyList()

        var total = entries.sumOf { it.second }
        val removed = mutableListOf<File>()
        // 未安装优先（承载方值耗尽先清杂物），同组内最旧优先（LRU）。
        val ordered = entries.sortedWith(
            compareBy<Pair<File, Long>> { (dir, _) -> if (dir.name.substringAfter(TMP_DIR_PREFIX) in installedModelHashes) 1 else 0 }
                .thenBy { (dir, _) -> dir.lastModified() },
        )
        for ((dir, size) in ordered) {
            val installed = dir.name.substringAfter(TMP_DIR_PREFIX) in installedModelHashes
            if (!installed || total > budgetBytes) {
                if (dir.deleteRecursively()) {
                    removed += dir
                    total -= size
                }
            }
        }
        return removed
    }

    /** 目录下全部文件字节和（含子目录；异常视为 0）。 */
    fun dirSizeOf(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        0L
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}