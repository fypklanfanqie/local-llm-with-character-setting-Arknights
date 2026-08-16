package com.rhodesisland.terminal.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * 博士头像本地存储（「设置 → 我的形象」）。
 *
 * 与 [CharacterImageStore]（角色立绘，多文件）不同：博士头像**同一时刻仅一张**，存于
 * `filesDir/user_profile/`，文件名固定前缀 `avatar.{ext}`；替换语义与
 * [com.rhodesisland.terminal.video.SeedanceSceneStore] 一致——新图先完整写入 `.tmp`，
 * 成功后才删除旧图并原子改名，失败时旧头像保持有效。
 */
object UserProfileImageStore {

    private const val DIR_NAME = "user_profile"

    /** 头像文件名前缀（扩展名随源图 MIME）。 */
    const val FILE_PREFIX = "avatar"

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 当前已安装的头像文件（忽略残留 tmp）；不存在返回 null。 */
    fun currentFile(context: Context): File? = dir(context).listFiles()
        ?.firstOrNull { it.isFile && it.name.startsWith("$FILE_PREFIX.") && !it.name.endsWith(".tmp") }

    /**
     * 安装新头像：content URI -> 复制到 tmp -> 删除旧图 -> 原子改名。
     * 成功返回新头像的 `file://` URI 字符串（与 [CharacterImageStore] 一致，Coil 可直接渲染）；
     * 失败返回 null（旧头像不受影响）。调用方应在 IO 调度器上执行。
     */
    fun save(context: Context, sourceUri: Uri): String? {
        return try {
            val d = dir(context).apply { if (!exists()) mkdirs() }
            val ext = guessExtension(context, sourceUri)
            val target = File(d, "$FILE_PREFIX.$ext")
            val tmp = File(d, "$FILE_PREFIX.$ext.tmp")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: run { tmp.delete(); return null }
            if (tmp.length() == 0L) {
                tmp.delete()
                return null
            }
            d.listFiles()
                ?.filter { it.isFile && it.name.startsWith("$FILE_PREFIX.") && !it.name.endsWith(".tmp") && it.name != target.name }
                ?.forEach { runCatching { it.delete() } }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                null
            } else {
                Uri.fromFile(target).toString()
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 删除当前头像（含残留 tmp）。 */
    fun remove(context: Context) {
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("$FILE_PREFIX.") }
            ?.forEach { runCatching { it.delete() } }
    }

    /** 从 DISPLAY_NAME / MIME 推断扩展名，无法判断时兜底 jpg（与 CharacterImageStore 同策略）。 */
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