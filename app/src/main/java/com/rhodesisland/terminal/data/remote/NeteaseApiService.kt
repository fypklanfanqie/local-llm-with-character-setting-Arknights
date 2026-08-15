package com.rhodesisland.terminal.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网易云音乐开放接口（无需登录，直接 HTTP 请求）。
 *
 * 用于在不内置版权资源的前提下，为网易云外链曲目实时获取：
 * - 专辑封面：song/detail 的 album.picUrl
 * - 歌词：song/lyric 的 lrc.lyric（标准 LRC 文本）
 *
 * 注意：本地曲目（assets）无 neteaseId，不会调用本服务。
 */
object NeteaseApiService {

    private const val BASE = "https://music.163.com/api"

    /** 获取专辑封面 URL，失败返回 null */
    suspend fun fetchCover(neteaseId: Long): String? = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE/song/detail/?id=$neteaseId&ids=[$neteaseId]").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://music.163.com/")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(text).jsonObject
            val songs = root["songs"]?.jsonArray ?: return@withContext null
            if (songs.isEmpty()) return@withContext null
            val album = songs[0].jsonObject["album"]?.jsonObject ?: return@withContext null
            album["picUrl"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 获取 LRC 歌词文本，失败返回 null */
    suspend fun fetchLyric(neteaseId: Long): String? = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE/song/lyric?id=$neteaseId&lv=1&kv=1&tv=-1").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://music.163.com/")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(text).jsonObject
            val lrc = root["lrc"]?.jsonObject ?: return@withContext null
            lrc["lyric"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 搜索网易云歌曲（无需登录），返回 id/歌名/歌手，用于拼接外链播放。 */
    suspend fun search(keyword: String): List<NeteaseSong> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val conn = (URL("$BASE/search/get/web?s=$encoded&type=1&offset=0&limit=30&total=true").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://music.163.com/")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(text).jsonObject
            val songs = root["result"]?.jsonObject?.get("songs")?.jsonArray ?: return@withContext emptyList()
            songs.mapNotNull { s ->
                val obj = s.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: ""
                val artists = obj["artists"]?.jsonArray ?: obj["ar"]?.jsonArray
                val artist = artists?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                NeteaseSong(id, name, artist)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}

data class NeteaseSong(val id: Long, val name: String, val artist: String)
