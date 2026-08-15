package com.rhodesisland.terminal.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 已落盘成品视频文件元信息。
 */
data class SeedanceVideoFile(
    val path: String,
    val mime: String,
    val byteSize: Long,
    val sha256: String,
)

/** 成品文件名：`video.{ext}`（仅识别受支持的视频扩展名）。 */
private val FINAL_VIDEO_NAME = Regex("""video\.(mp4|webm|mov|mkv|avi)""")

/** 残留 .part 超过该时长视为可清理的废弃文件。 */
private const val STALE_PART_MILLIS = 60 * 60_000L

/**
 * Seedance 成品视频文件存储（纯 JVM 可测，真实 File I/O）。
 *
 * 下载语义：写入 `.part` -> 校验非空 / 完整长度 / 视频魔数或 MIME -> 边写边算 SHA-256 ->
 * 原子改名为 `video.{ext}` -> 返回 [SeedanceVideoFile]。任何失败都清理 `.part` 并
 * `Result.failure`（磁盘满/网络截断/非视频内容等）。
 *
 * 幂等性：[findFinalFile] 只识别非 `.part`/`.tmp` 的最终文件；[verifyExisting] 在任务
 * 已带 SHA-256 时校验既有成品，匹配则直接复用（重复 Worker 不重下）。
 *
 * 测试装配：构造器注入 [targetRoot] 与 [clock]，真实临时目录跑 File I/O。
 */
class SeedanceVideoFileStore(
    private val targetRoot: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 任务专属目录。 */
    fun taskDir(taskUuid: String): File = File(targetRoot, taskUuid)

    /** 既有最终成品文件（非 .part/.tmp），不存在返回 null。 */
    fun findFinalFile(taskUuid: String): File? = taskDir(taskUuid).listFiles()?.firstOrNull { file ->
        file.isFile && FINAL_VIDEO_NAME.matches(file.name)
    }

    /** 残留 .part 文件，不存在返回 null。 */
    fun findPartFile(taskUuid: String): File? = taskDir(taskUuid).listFiles()?.firstOrNull { file ->
        file.isFile && file.name.endsWith(".part")
    }

    /**
     * 将 [source] 保存为任务成品视频。
     *
     * @param mimeHint      响应 Content-Type（用于魔数无法识别时的回退）。
     * @param contentLength 响应 Content-Length（非空且实际字节更少时判为截断）。
     */
    suspend fun save(
        taskUuid: String,
        mimeHint: String?,
        contentLength: Long?,
        source: InputStream,
    ): Result<SeedanceVideoFile> = withContext(Dispatchers.IO) {
        val dir = taskDir(taskUuid)
        val tmp = File(dir, "video.part")
        try {
            dir.mkdirs()
            cleanStaleParts(dir)

            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(12)
            var headerLen = 0
            var total = 0L
            source.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (headerLen < header.size) {
                            val copy = minOf(n, header.size - headerLen)
                            System.arraycopy(buffer, 0, header, headerLen, copy)
                            headerLen += copy
                        }
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        total += n
                    }
                }
            }

            if (total == 0L) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("视频下载内容为空"))
            }
            if (contentLength != null && contentLength > 0 && total < contentLength) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("视频下载不完整（$total/$contentLength）"))
            }
            val format = resolveVideoFormat(mimeHint, header.copyOf(headerLen))
                ?: run {
                    tmp.delete()
                    return@withContext Result.failure(IllegalStateException("下载内容不是受支持的视频格式"))
                }

            val final = File(dir, "video.${format.ext}")
            // 旧成品若为普通文件则覆盖（无效残留）；若为目录则保留，改名会失败 -> 磁盘满语义。
            if (final.isFile) final.delete()
            if (!tmp.renameTo(final)) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("视频保存失败（磁盘可能已满）"))
            }
            Result.success(SeedanceVideoFile(final.absolutePath, format.mime, total, digest.digest().toHex()))
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Result.failure(IllegalStateException("视频保存失败：${e.message ?: "未知错误"}"))
        }
    }

    /**
     * 校验既有成品文件：存在且 SHA-256 与 [expectedSha256] 一致才返回其元信息，否则 null。
     * 仅凭 `.part` 残留绝不视为成品（最终文件须经原子改名）。
     */
    fun verifyExisting(taskUuid: String, expectedSha256: String): SeedanceVideoFile? {
        val file = findFinalFile(taskUuid) ?: return null
        val sha = runCatching { sha256Of(file) }.getOrNull() ?: return null
        if (sha != expectedSha256) return null
        return SeedanceVideoFile(file.absolutePath, mimeForExtension(file.extension), file.length(), sha)
    }

    private fun cleanStaleParts(dir: File) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") && clock() - it.lastModified() > STALE_PART_MILLIS }
            ?.forEach { runCatching { it.delete() } }
    }

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
        return digest.digest().toHex()
    }

    /** 魔数嗅探优先（mp4/webm），回退到响应 MIME（video/ 前缀）。 */
    private fun resolveVideoFormat(mimeHint: String?, header: ByteArray): VideoFormat? {
        val sniffed = when {
            header.size >= 12 &&
                header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() -> "video/mp4"
            header.size >= 4 &&
                header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "video/webm"
            else -> null
        }
        val mime = sniffed ?: mimeHint?.substringBefore(';')?.trim()?.takeIf { it.startsWith("video/") }
        return mime?.let { VideoFormat(it, extForMime(it)) }
    }

    private fun extForMime(mime: String): String = when (mime) {
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }

    private fun mimeForExtension(ext: String): String = when (ext) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }

    private data class VideoFormat(val mime: String, val ext: String)
}

/** 字节数组 SHA-256 -> 小写十六进制。 */
private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }
