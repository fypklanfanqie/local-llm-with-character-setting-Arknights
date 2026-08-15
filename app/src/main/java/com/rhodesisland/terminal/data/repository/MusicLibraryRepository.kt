package com.rhodesisland.terminal.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rhodesisland.terminal.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音乐库仓库：持久化播放列表（本地导入 + 用户添加的在线曲）的唯一来源。
 *
 * 本地导入走 SAF 文件选择器，所选 content URI **拷贝**到 App 内部存储
 * （`filesDir/music/`），仅存绝对路径，**不依赖 SAF 持久化 URI 权限**：
 * 持久权限在部分 OEM / 云盘源上不可靠，源 App 重装或撤销即失效；内部拷贝保证
 * 曲目始终可用、离线可播、可删除清理。与 [ChatBackgroundRepository] 同一策略。
 */
class MusicLibraryRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        /** 播放列表曲目上限。 */
        const val MAX_TRACKS = 100
    }

    /** 播放列表（持久化），供 UI collectAsState 使用。 */
    val playlist: Flow<List<BgmTrack>> = settingsStore.musicPlaylist

    /**
     * 拷贝所选音频 content URI 到内部存储并追加到播放列表。
     * 按曲名（忽略大小写）去重，遵守 [MAX_TRACKS] 上限。返回实际新增的曲目。
     */
    suspend fun addLocalUris(uris: List<Uri>): List<BgmTrack> {
        if (uris.isEmpty()) return emptyList()
        val current = runCatching { settingsStore.musicPlaylist.first() }.getOrDefault(emptyList())
        val room = MAX_TRACKS - current.size
        if (room <= 0) return emptyList()
        val added = copyToInternal(uris.take(room), current.map { it.name.lowercase() }.toMutableSet())
        if (added.isNotEmpty()) {
            settingsStore.updateMusicPlaylist { it + added }
        }
        return added
    }

    /** 追加在线搜索曲目（按 key 去重）。网易云 URL 与 neteaseId 保留 → 封面/歌词仍可拉取。 */
    suspend fun addOnlineTrack(track: BgmTrack) {
        settingsStore.updateMusicPlaylist { list ->
            if (list.any { it.key == track.key }) list else list + track
        }
    }

    /** 从列表移除；若 file 属于 `filesDir/music/` 则一并删除该文件。 */
    suspend fun removeTrack(track: BgmTrack) {
        settingsStore.updateMusicPlaylist { it.filterNot { t -> t.key == track.key } }
        val musicDir = File(context.filesDir, "music").absolutePath
        if (track.file.startsWith(musicDir)) {
            runCatching { File(track.file).takeIf { f -> f.exists() }?.delete() }
        }
    }

    /** IO 线程拷贝选中音频到内部存储，返回成功写入的 BgmTrack 列表（空文件/同名去重丢弃）。 */
    private suspend fun copyToInternal(uris: List<Uri>, seenNames: MutableSet<String>): List<BgmTrack> =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "music").apply { mkdirs() }
            uris.mapNotNull { uri ->
                runCatching {
                    val displayName = queryDisplayName(uri) ?: return@runCatching null
                    val name = displayName.substringBeforeLast('.', displayName)
                        .ifBlank { displayName }
                    val nameKey = name.lowercase()
                    if (nameKey in seenNames) return@runCatching null
                    val ext = displayName.substringAfterLast('.', "mp3").takeIf { it.isNotBlank() }?.lowercase() ?: "mp3"
                    val dest = File(dir, "music_${System.nanoTime()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    if (dest.length() == 0L) {
                        dest.delete()
                        null
                    } else {
                        seenNames.add(nameKey)
                        BgmTrack(
                            file = dest.absolutePath,
                            name = name,
                            key = "local_${System.nanoTime()}",
                            neteaseId = null,
                        )
                    }
                }.getOrNull()
            }
        }

    /** 从 content URI 读取显示名（DISPLAY_NAME），读不到返回 null。 */
    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
}
