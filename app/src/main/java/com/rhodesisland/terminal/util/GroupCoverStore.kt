package com.rhodesisland.terminal.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * 群封面本地存储（多群聊）：从相册选择封面复制到 `filesDir/group_covers/`，
 * 以 `file://` URI 写入群会话的 coverImagePath（与 [CharacterImageStore] 语义一致）。
 */
object GroupCoverStore {

    private const val DIR = "group_covers"

    /** 复制选中的图片到内部存储，返回 `file://` URI 字符串；失败返回 null。调用方应在 IO 调度器上执行。 */
    fun save(context: Context, sourceUri: Uri): String? {
        return try {
            val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
            val ext = guessExtension(context, sourceUri)
            val dest = File(dir, "cover_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (dest.length() == 0L) {
                dest.delete()
                return null
            }
            Uri.fromFile(dest).toString()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 删除本 store 管理的封面文件（目录前缀校验，与 CharacterImageStore 相同）。 */
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