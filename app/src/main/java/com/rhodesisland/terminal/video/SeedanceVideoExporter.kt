package com.rhodesisland.terminal.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.rhodesisland.terminal.data.model.SeedanceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 视频导出目标（Task 8）。
 * - [MediaStoreMovies]：Android 10+（API >= 29），MediaStore.Video 免权限写公共相册；
 * - [CreateDocument]：Android 7–9（API 24–28），SAF ACTION_CREATE_DOCUMENT 由用户选择保存位置。
 */
sealed interface VideoExportTarget {
    data object MediaStoreMovies : VideoExportTarget
    data object CreateDocument : VideoExportTarget
}

/**
 * 按系统版本选择导出目标。
 *
 * 边界：API 29（Android 10）为 MediaStore 免权限写相册的最低版本；minSdk 24 恒走
 * [VideoExportTarget.CreateDocument]。纯逻辑，供 [SeedanceExportPolicyTest] 断言。
 */
fun exportTargetForSdk(sdkInt: Int): VideoExportTarget =
    if (sdkInt >= Build.VERSION_CODES.Q) VideoExportTarget.MediaStoreMovies
    else VideoExportTarget.CreateDocument

/**
 * 把内部归档视频导出到用户可见位置（Task 8）。
 *
 * 只读 Seedance 任务归档成品（[SeedanceVideo.localVideoPath]），写入外部：
 * - [exportToMediaStore]：Android 10+ 公共相册 `Movies/RhodesIslandTerminal`；
 * - [exportToUri]：把内部文件流式写入 SAF 返回的 [Uri]（Android 7–9 用户选择位置）。
 *
 * 不申请任何存储权限（MediaStore IS_PENDING 原子发布；SAF 由系统授权）。
 */
class SeedanceVideoExporter(
    private val context: Context,
) {

    /**
     * Android 10+：MediaStore.Video 写入公共相册。
     *
     * `RELATIVE_PATH=Movies/RhodesIslandTerminal` + `IS_PENDING=1→0` 原子发布：
     * 写入完成后才把行对媒体库可见；失败则删除半成品行，不在相册残留损坏文件。
     */
    suspend fun exportToMediaStore(video: SeedanceVideo): Result<Uri> = withContext(Dispatchers.IO) {
        val source = localFile(video) ?: return@withContext Result.failure(
            IllegalStateException("视频文件尚未就绪")
        )
        val mime = video.videoMime ?: "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, suggestedVideoFileName(video))
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RhodesIslandTerminal")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return@withContext Result.failure(IOException("无法创建相册条目"))
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法打开相册输出流")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            Result.success(uri)
        } catch (e: Exception) {
            // 失败清理半成品：不向相册暴露损坏文件
            runCatching { resolver.delete(uri, null, null) }
            Result.failure(e)
        }
    }

    /**
     * 把内部文件流式写入 SAF 目标 [destination]（Android 7–9 用户选择的位置）。
     */
    suspend fun exportToUri(video: SeedanceVideo, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val source = localFile(video) ?: return@withContext Result.failure(
            IllegalStateException("视频文件尚未就绪")
        )
        try {
            context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法打开保存目标")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun localFile(video: SeedanceVideo): File? {
        val path = video.localVideoPath ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }
}

/**
 * 建议导出文件名：`RhodesIslandTerminal_{taskUuid前8位}_{任务id}.{ext}`。
 * MediaStore DISPLAY_NAME 与 CreateDocument 建议名共用，保证两者命名一致。
 */
internal fun suggestedVideoFileName(video: SeedanceVideo): String {
    val ext = when (video.videoMime) {
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }
    return "RhodesIslandTerminal_${video.taskUuid.take(8)}_${video.id}.$ext"
}
