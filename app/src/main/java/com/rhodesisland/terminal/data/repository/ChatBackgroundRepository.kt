package com.rhodesisland.terminal.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rhodesisland.terminal.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** 通讯界面背景配置：是否启用自定义 + 自定义图片内部存储路径列表（有序）。 */
data class ChatBackgroundConfig(
    val enabled: Boolean,
    val paths: List<String>,
)

/**
 * 通讯界面背景仓库
 *
 * 内置 PRTS 背景轮播来自 [AssetRepository]（assets / CDN）；用户可在设置里添加最多
 * [MAX_BACKGROUNDS] 张自定义背景。所选 content URI 复制到 app 内部存储
 * （`filesDir/chat_backgrounds/`），仅存绝对路径，**不依赖 SAF 持久化 URI 权限**：
 * 持久权限在部分 OEM / 云相册源上不可靠，源 App 重装或撤销即失效；内部拷贝保证
 * 背景始终可用、离线可用、可删除清理。
 *
 * 生效规则：`enabled && paths 非空` -> 轮播自定义；否则 -> 内置 PRTS 轮播（行为不变）。
 */
class ChatBackgroundRepository(
    private val context: Context,
    private val assetRepository: AssetRepository,
    private val settingsStore: SettingsStore,
) {
    companion object {
        /** 自定义背景图片上限。 */
        const val MAX_BACKGROUNDS = 20
    }

    /** 当前背景配置（开关 + 路径列表）。 */
    val config: Flow<ChatBackgroundConfig> =
        combine(settingsStore.chatBgEnabled, settingsStore.chatBgPaths) { enabled, paths ->
            ChatBackgroundConfig(enabled, paths)
        }

    suspend fun getConfigNow(): ChatBackgroundConfig = config.first()

    suspend fun setEnabled(enabled: Boolean) = settingsStore.setChatBgEnabled(enabled)

    /**
     * 返回当前应轮播的背景 URL 列表：
     * - 自定义启用且非空 -> 自定义文件绝对路径（Coil 渲染时转 [File]）；
     * - 否则 -> 内置背景 URL（来自 [AssetRepository.getBackground]，过滤缺失项）。
     */
    fun effectiveUrls(config: ChatBackgroundConfig): List<String> =
        if (config.enabled && config.paths.isNotEmpty()) config.paths
        else (0 until assetRepository.backgroundCount)
            .map { assetRepository.getBackground(it) }
            .filter { it.isNotBlank() }

    /**
     * 复制所选 content URI 到内部存储并追加到自定义列表，自动遵守 [MAX_BACKGROUNDS] 上限
     * （超出部分截断，不报错）。返回实际新增的路径（可能少于入参 uris）。
     */
    suspend fun addUris(uris: List<Uri>): List<String> {
        if (uris.isEmpty()) return emptyList()
        val current = settingsStore.chatBgPaths.first()
        val room = MAX_BACKGROUNDS - current.size
        if (room <= 0) return emptyList()
        val added = copyToInternal(uris.take(room))
        if (added.isNotEmpty()) {
            settingsStore.updateChatBgPaths { it + added }
        }
        return added
    }

    /** 删除指定自定义背景文件并从列表移除。 */
    suspend fun removePath(path: String) {
        settingsStore.updateChatBgPaths { it.filter { p -> p != path } }
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** 删除全部自定义背景文件并清空列表（开关不动）。 */
    suspend fun clearAll() {
        val current = settingsStore.chatBgPaths.first()
        settingsStore.setChatBgPaths(emptyList())
        current.forEach { runCatching { File(it).takeIf { f -> f.exists() }?.delete() } }
    }

    /** IO 线程拷贝选中的 URI 到内部存储目录，返回成功写入的绝对路径（空文件丢弃）。 */
    private suspend fun copyToInternal(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "chat_backgrounds").apply { mkdirs() }
        uris.mapNotNull { uri ->
            runCatching {
                val ext = guessExtension(uri) ?: "jpg"
                val dest = File(dir, "bg_${System.nanoTime()}.${ext}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                if (dest.length() == 0L) {
                    dest.delete()
                    null
                } else {
                    dest.absolutePath
                }
            }.getOrNull()
        }
    }

    /** 推断图片扩展名：显示名后缀 -> MIME 子类型 -> null。 */
    private fun guessExtension(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val name = c.getString(0)
                    name.substringAfterLast('.', missingDelimiterValue = "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { return it.lowercase() }
                }
            }
        }
        runCatching {
            context.contentResolver.getType(uri)?.substringAfter('/')?.let { return it.lowercase() }
        }
        return null
    }
}
