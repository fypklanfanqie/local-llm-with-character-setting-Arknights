package com.rhodesisland.terminal.download

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 大文件分片合并工具（移植自 MNN `FileSplitter.kt`，Gson -> kotlinx.serialization）。
 *
 * MNN 的 `.mnn` 模型仓库中，过大的权重文件（`llm.mnn.weight`）会被切分成
 * `llm.mnn.weight.part1`、`.part2`、…，并随包附带 `splits_info.json` 描述切分信息。
 * 下载完所有分片后调用 [mergeAllSplitFiles] 即可按序拼接还原原文件并删除分片。
 *
 * 合并前会逐分片校验大小，合并后校验总大小，任一不一致即删除产物返回失败，
 * 避免产出损坏的权重文件导致模型加载崩溃。
 */
object FileSplitter {
    private const val TAG = "FileSplitter"

    @Serializable
    data class SplitInfo(
        val originalFileName: String = "",
        val originalFileSize: Long = 0L,
        val chunkSize: Long = 0L,
        val totalChunks: Int = 0,
        val chunks: List<ChunkInfo> = emptyList(),
    )

    @Serializable
    data class ChunkInfo(
        val chunkIndex: Int = 0,
        val chunkFileName: String = "",
        val chunkSize: Long = 0L,
        val checksum: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 模型目录是否含 `splits_info.json`（即存在待合并的分片） */
    fun needsMerging(modelDir: File): Boolean =
        File(modelDir, "splits_info.json").exists()

    /** 读取 `splits_info.json`；不存在返回 null */
    fun loadSplitInfo(splitInfoFile: File): SplitInfo? {
        if (!splitInfoFile.exists()) return null
        return try {
            json.decodeFromString(SplitInfo.serializer(), splitInfoFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load split info from ${splitInfoFile.absolutePath}", e)
            null
        }
    }

    /**
     * 合并模型目录下所有分片。无 `splits_info.json` 视为无需合并（返回 true）。
     * @return 全部合并成功（或本就无需合并）返回 true；任一失败返回 false
     */
    fun mergeAllSplitFiles(modelDir: File): Boolean {
        val splitInfo = loadSplitInfo(File(modelDir, "splits_info.json"))
            ?: return true // 无切分信息，无需合并

        Log.i(TAG, "合并分片: ${modelDir.absolutePath} (${splitInfo.totalChunks} 段, 目标 ${splitInfo.originalFileSize} 字节)")

        // 收集需要还原的原始文件名（去 .partN 后缀）
        val filesToMerge = linkedSetOf<String>()
        for (chunk in splitInfo.chunks) {
            val original = chunk.chunkFileName.replace(Regex("\\.part\\d+$"), "")
            filesToMerge.add(original)
        }

        var allMerged = true
        for (originalName in filesToMerge) {
            val outputFile = File(modelDir, originalName)
            // 已存在且大小正确则跳过（断点续传 / 重复合并幂等）
            if (outputFile.exists() && outputFile.length() == splitInfo.originalFileSize) {
                Log.i(TAG, "$originalName 已存在且大小正确，跳过")
                continue
            }
            if (outputFile.exists()) {
                Log.w(TAG, "$originalName 大小不符(${outputFile.length()})，重新合并")
                outputFile.delete()
            }

            val merged = mergeFiles(splitInfo, modelDir, outputFile)
            if (!merged) {
                Log.e(TAG, "合并失败: $originalName")
                allMerged = false
            } else if (outputFile.length() != splitInfo.originalFileSize) {
                Log.e(TAG, "合并后大小不符: $originalName (${outputFile.length()})")
                outputFile.delete()
                allMerged = false
            } else {
                Log.i(TAG, "合并完成: $originalName (${outputFile.length()} 字节)")
                deletePartFiles(modelDir, originalName, splitInfo)
            }
        }

        // 全部合并成功后删除 splits_info.json
        if (allMerged) {
            runCatching { File(modelDir, "splits_info.json").delete() }
        }
        return allMerged
    }

    /** 按序拼接一个原始文件的所有分片；合并前逐分片校验大小 */
    private fun mergeFiles(splitInfo: SplitInfo, chunksDir: File, outputFile: File): Boolean {
        if (!chunksDir.exists()) return false
        val sortedChunks = splitInfo.chunks.sortedBy { it.chunkIndex }

        // 预校验所有分片存在且大小匹配；有 checksum 时再做 SHA-256 校验（Task 12 Step 3）。
        var totalExpected = 0L
        for (chunk in sortedChunks) {
            val chunkFile = File(chunksDir, chunk.chunkFileName)
            if (!chunkFile.exists()) {
                Log.e(TAG, "分片缺失: ${chunk.chunkFileName}")
                return false
            }
            if (chunkFile.length() != chunk.chunkSize) {
                Log.e(TAG, "分片大小不符: ${chunk.chunkFileName} (期望 ${chunk.chunkSize}, 实际 ${chunkFile.length()})")
                return false
            }
            val expected = chunk.checksum
            if (!expected.isNullOrBlank()) {
                val actual = sha256(chunkFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Log.e(TAG, "分片校验和不符: ${chunk.chunkFileName} (期望 $expected, 实际 $actual)")
                    return false
                }
            }
            totalExpected += chunk.chunkSize
        }
        if (totalExpected != splitInfo.originalFileSize) {
            Log.e(TAG, "分片总大小($totalExpected) != 原始大小(${splitInfo.originalFileSize})")
            return false
        }

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()
            FileOutputStream(outputFile).use { out ->
                for (chunk in sortedChunks) {
                    FileInputStream(File(chunksDir, chunk.chunkFileName)).use { inn ->
                        val buffer = ByteArray(8192)
                        var n: Int
                        while (inn.read(buffer).also { n = it } != -1) {
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            outputFile.length() == splitInfo.originalFileSize
        } catch (e: IOException) {
            Log.e(TAG, "合并 IO 失败: ${outputFile.name}", e)
            if (outputFile.exists()) outputFile.delete()
            false
        }
    }

    /** 合并成功后删除对应的 .partN 分片文件 */
    private fun deletePartFiles(modelDir: File, originalFileName: String, splitInfo: SplitInfo) {
        val merged = File(modelDir, originalFileName)
        if (!merged.exists() || merged.length() != splitInfo.originalFileSize) {
            Log.e(TAG, "合并文件校验未通过，不删除分片: $originalFileName")
            return
        }
        for (chunk in splitInfo.chunks) {
            if (chunk.chunkFileName.startsWith(originalFileName) &&
                chunk.chunkFileName.matches(Regex(".*\\.part\\d+$"))
            ) {
                runCatching { File(modelDir, chunk.chunkFileName).delete() }
            }
        }
    }
    /** 文件 SHA-256（hex 小写）；用于合并前分片校验（Task 12 Step 3）。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(65536)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

}
