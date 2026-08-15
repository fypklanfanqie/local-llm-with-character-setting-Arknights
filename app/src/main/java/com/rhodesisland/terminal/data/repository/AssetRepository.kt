package com.rhodesisland.terminal.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.URLUtil
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.config.AssetPaths
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * 立绘/语音/BGM 资源仓库
 *
 * 由于 Android 无微信云存储 getTempFileURL，资源地址解析策略：
 * 1. 若 AppConfig.ASSET_CDN_BASE 配置了公网 CDN → 拼接 CDN URL（优先，推荐服务器托管）
 * 2. 否则使用本地 assets 文件路径（file:///android_asset/...）
 *
 * 立绘/语音/BGM 文件缺失时不会崩溃：
 * - 图片：返回空串，UI 层用风格化占位图兜底
 * - 音频：返回空串，调用方静默跳过
 */
class AssetRepository(private val context: Context) {

    /** 获取角色立绘 URL（通讯页） */
    fun getPicture(characterId: String): String {
        val relPath = AssetPaths.PICTURES[characterId] ?: return ""
        return resolveAssetUrl(relPath)
    }

    /** 获取角色选择页立绘 URL */
    fun getSelectionPicture(characterId: String): String {
        val relPath = AssetPaths.SELECTION_PICTURES[characterId]
            ?: AssetPaths.PICTURES[characterId]
            ?: return ""
        return resolveAssetUrl(relPath)
    }

    /** 获取角色语音 URL（缺失返回空串，由调用方静默跳过） */
    fun getVoice(characterId: String): String {
        val relPath = AssetPaths.VOICES[characterId] ?: return ""
        return resolveAssetUrl(relPath, scheme = "asset", requireExists = true)
    }

    /**
     * 获取 BGM 列表。
     * - 网易云外链（http/https）：直接作为可播放 URL 返回（ExoPlayer 流式播放）
     * - 本地 assets 曲目（music/...）：转换为 asset:// 协议，并校验文件存在
     */
    fun getBgmList(): List<BgmTrack> {
        return AssetPaths.BGM.mapNotNull { track ->
            when {
                track.path.startsWith("http") -> {
                    // 网易云外链：直接可用，并从 URL 解析 neteaseId 用于拉取封面/歌词
                    BgmTrack(
                        file = track.path,
                        name = track.name,
                        key = track.key,
                        neteaseId = extractNeteaseId(track.path),
                    )
                }
                track.path.isNotBlank() -> {
                    val file = resolveAssetUrl(track.path, scheme = "asset", requireExists = true)
                    if (file.isBlank()) null else BgmTrack(file = file, name = track.name, key = track.key, neteaseId = null)
                }
                else -> null
            }
        }
    }

    /** 从网易云外链中提取歌曲 id：.../outer/url?id=123456 */
    private fun extractNeteaseId(url: String): Long? {
        // 用 Uri 正规解析 query，避免 indexOf("id=") 误匹配 songid= / userid= 等参数名
        val idParam = Uri.parse(url).getQueryParameter("id") ?: return null
        return idParam.takeWhile { it.isDigit() }.toLongOrNull()
    }

    /** 获取背景图 URL */
    fun getBackground(index: Int): String {
        val list = AssetPaths.BACKGROUNDS
        if (index !in list.indices) return ""
        val item = list[index]
        return resolveAssetUrl(item.cloud, scheme = "file", requireExists = false)
    }

    val backgroundCount: Int get() = AssetPaths.BACKGROUNDS.size

    /**
     * 解析相对资源路径为可用 URL。
     * 策略：外链直返 → CDN 拼接 → 本地 assets。
     *
     * 本地 assets 协议前缀：
     * - 图片用 `file:///android_asset/`（Coil 原生支持）
     * - 音频用 `asset:///`（ExoPlayer 内置 AssetDataSource 支持）
     *
     * @param scheme       本地 assets 协议："file"（图片）或 "asset"（音频）
     * @param requireExists 是否要求文件真实存在。
     *   图片传 false（由 UI 的占位图兜底）；
     *   音频/BGM 传 true（文件不存在则返回空串，调用方静默跳过）。
     */
    private fun resolveAssetUrl(
        relativePath: String,
        scheme: String = "file",
        requireExists: Boolean = false,
    ): String {
        // 外链 URL 直接返回
        if (URLUtil.isNetworkUrl(relativePath)) {
            return relativePath
        }
        // 配置了 CDN
        if (AppConfig.ASSET_CDN_BASE.isNotBlank()) {
            return AppConfig.ASSET_CDN_BASE.trimEnd('/') + "/" + relativePath.trimStart('/')
        }
        // 本地 assets
        val localUrl = when (scheme) {
            "asset" -> "asset:///$relativePath"
            else -> "file:///android_asset/$relativePath"
        }
        return if (!requireExists || assetFileExists(relativePath)) {
            localUrl
        } else {
            ""  // 缺失 → 返回空，由调用方处理
        }
    }

    /** 检查 assets 中是否存在指定相对路径的文件 */
    private fun assetFileExists(relativePath: String): Boolean {
        return try {
            val stream = context.assets.open(relativePath)
            stream.close()
            true
        } catch (e: IOException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}

@Serializable
data class BgmTrack(
    val file: String,
    val name: String,
    val key: String,
    val neteaseId: Long? = null,
) {
    /**
     * EP 分类（由 key 前缀派生，用于音乐页 EP 筛选）。
     * 与网页版 musicData.js 的 ep 字段对应：系统 / Y-7 … Y-2 / Y-0～Y-1 / Overseas。
     */
    val ep: String
        get() = when {
            key.startsWith("sys") -> "系统"
            key.startsWith("y7") -> "Y-7"
            key.startsWith("y6") -> "Y-6"
            key.startsWith("y5") -> "Y-5"
            key.startsWith("y4") -> "Y-4"
            key.startsWith("y3") -> "Y-3"
            key.startsWith("y2") -> "Y-2"
            key.startsWith("y01") -> "Y-0～Y-1"
            key.startsWith("os") -> "Overseas"
            else -> "其他"
        }
}
