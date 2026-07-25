package com.rhodesisland.terminal.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * 自定义角色立绘本地存储
 *
 * 用户从相册选择的立绘会被复制到应用内部存储（filesDir/character_images/），
 * 以 `file://` URI 形式写入 [com.rhodesisland.terminal.data.model.Character.image]，
 * 供 Coil 的 SubcomposeAsyncImage 直接渲染。
 *
 * 采用「复制到内部存储」而非直接持有 content URI：
 * - content URI 跨进程重建后可能丢权限，或源图被删后立绘失效；
 * - 内部存储归 app 所有，持久稳定，不依赖相册源文件存在；
 * - PickVisualMedia 仅授予临时读权限，复制完成后即不再需要。
 */
object CharacterImageStore {

    private const val DIR = "character_images"

    /**
     * 复制选中的图片到内部存储，返回 `file://` URI 字符串；失败返回 null。
     * 调用方应在 IO 调度器上执行。
     */
    fun save(context: Context, sourceUri: Uri): String? {
        return try {
            val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
            val ext = guessExtension(context, sourceUri)
            // 用时间戳命名，与 character id 解耦：重命名角色不影响立绘
            val dest = File(dir, "portrait_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(dest).toString()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 删除本地立绘文件。仅当属于本 store 管理的 character_images 目录时才删除，
     * 网络 URL / content URI / 空串一律安全跳过。
     */
    fun delete(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        if (!uriString.startsWith("file:")) return
        val path = runCatching { Uri.parse(uriString).path }.getOrNull() ?: return
        val file = File(path)
        val dir = File(context.filesDir, DIR)
        val inStore = runCatching {
            file.canonicalPath.startsWith(dir.canonicalPath)
        }.getOrDefault(false)
        if (inStore && file.exists()) {
            runCatching { file.delete() }
        }
    }

    /** 从 DISPLAY_NAME / MIME 推断扩展名，无法判断时兜底 jpg。 */
    private fun guessExtension(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
            ?.takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }
            ?.let { return it }

        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when (mime?.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }
    }
}
