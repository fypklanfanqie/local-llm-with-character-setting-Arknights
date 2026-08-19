package com.rhodesisland.terminal.affinity

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** 礼物图片复制到应用私有目录；历史记录持有副本路径，不依赖原相册 URI。 */
object GiftImageStore {
    private const val DIRECTORY = "gift_images"

    fun save(context: Context, sourceUri: Uri): String? {
        return try {
            val directory = File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }
            val extension = extensionOf(context, sourceUri)
            val destination = File(directory, "gift_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use(input::copyTo)
            } ?: return null
            Uri.fromFile(destination).toString()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun deleteDefinitionImage(context: Context, path: String?) {
        if (path.isNullOrBlank() || !path.startsWith("file:")) return
        val file = runCatching { File(Uri.parse(path).path ?: return) }.getOrNull() ?: return
        val directory = File(context.filesDir, DIRECTORY)
        val belongsToStore = runCatching { file.canonicalPath.startsWith(directory.canonicalPath) }.getOrDefault(false)
        if (belongsToStore) runCatching { file.delete() }
    }

    private fun extensionOf(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        name?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?.let { return it }
        return when (context.contentResolver.getType(uri)?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }
}
