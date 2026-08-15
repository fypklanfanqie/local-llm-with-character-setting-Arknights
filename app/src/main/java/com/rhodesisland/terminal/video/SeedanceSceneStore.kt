package com.rhodesisland.terminal.video

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Seedance 全局背景图仓库。
 *
 * 用户可在「Seedance 对话视频」设置里选一张背景图（可选）。与通讯聊天背景（多张轮播）不同，
 * Seedance 背景是**一张全局图**，保存于 `filesDir/seedance_scene/`，文件名固定前缀
 * `background.{ext}`（扩展名随 MIME），同一时刻仅一张。所选 content URI 经 [ImageProbe]
 * 按 Seedance 参考图官方约束（[validateReferenceImage]）校验后复制到内部存储，
 * 路径持久化在 [com.rhodesisland.terminal.data.model.SeedanceConfig.backgroundImagePath]
 * （由设置页经 SettingsRepository.setSeedanceConfig 写回）。
 *
 * 替换语义：新图先完整写入 `background.{ext}.tmp`，成功后才删除旧图并原子改名——
 * 复制/校验失败时旧背景保持有效，**绝不先删旧图再写新图**。
 */
class SeedanceSceneStore(
    private val context: Context,
    private val imageProbe: ImageProbe = AndroidImageProbe(),
) {

    companion object {
        /** 全局背景目录名（filesDir 下）。 */
        const val DIR_NAME = "seedance_scene"

        /** 全局背景文件名前缀（扩展名随 MIME）。 */
        const val FILE_PREFIX = "background"
    }

    private val sceneDir: File get() = File(context.filesDir, DIR_NAME)

    /** 当前已安装的背景文件（忽略残留 tmp）；不存在返回 null。 */
    fun currentFile(): File? = sceneDir.listFiles()
        ?.firstOrNull { it.isFile && it.name.startsWith("$FILE_PREFIX.") && !it.name.endsWith(".tmp") }

    /**
     * 安装新背景：content URI -> 探测校验 -> 完整复制到 tmp -> 删除旧图 -> 原子改名。
     * 成功返回新背景绝对路径；失败返回 Result.failure（中文原因），旧图不受影响。
     */
    suspend fun install(sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        fun openSource(): InputStream? = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (e: Exception) {
            null
        }

        val probe = try {
            imageProbe.probe(ProbeSource.FromStream { openSource() })
        } catch (e: Exception) {
            null
        } ?: return@withContext Result.failure(IllegalStateException("背景图无法读取（文件可能已删除或损坏）"))

        validateReferenceImage(probe, "背景图")?.let { message ->
            return@withContext Result.failure(IllegalStateException(message))
        }
        val ext = SUPPORTED_SEEDANCE_MIME_EXT.getValue(probe.mimeType)

        return@withContext try {
            val dir = sceneDir.apply { mkdirs() }
            val target = File(dir, "$FILE_PREFIX.$ext")
            val tmp = File(dir, "$FILE_PREFIX.$ext.tmp")
            openSource()?.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                ?: return@withContext Result.failure(IllegalStateException("背景图读取失败"))
            if (tmp.length() == 0L) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("背景图为空文件"))
            }
            // 新图已完整落盘：删除其它扩展名的旧图，再把新图改名到目标（同名旧图被原子替换）。
            sceneDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("$FILE_PREFIX.") && !it.name.endsWith(".tmp") && it.name != target.name }
                ?.forEach { runCatching { it.delete() } }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("背景图保存失败"))
            }
            Result.success(target.absolutePath)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("背景图保存失败：${e.message ?: "未知错误"}"))
        }
    }

    /** 删除全局背景文件（含残留 tmp）。 */
    suspend fun remove() = withContext(Dispatchers.IO) {
        sceneDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$FILE_PREFIX.") }
            ?.forEach { runCatching { it.delete() } }
    }
}
