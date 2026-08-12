package com.lxmusic.tv.network

import android.util.Log
import com.lxmusic.tv.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lxmusic.tv.util.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * QQ音乐API
 * 参考洛雪音乐mobile版实现
 * 直接HTTP调用QQ音乐搜索API，无需JS引擎
 */
class QQMusicApi(
    private val httpClient: HttpClient = HttpClient()
) {
    companion object {
        private const val TAG = "QQMusicApi"
        // 老版搜索接口（musicu.fcg 的 DoSearchForQQMusicDesktop 已失效返回 code=2001，此接口实测可用）
        private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val PLAY_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        // 大封面：800x800（播放页大图更清晰；列表小图由 RemoteImage 子采样，不影响内存）
        private const val COVER_URL_PREFIX = "https://y.qq.com/music/photo_new/T002R800x800M000"
        // QQ 接口风控要求携带 Referer，否则返回空结果
        private val QQ_HEADERS = mapOf(
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com"
        )
    }

    /**
     * 搜索音乐
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param limit 每页数量
     * @return 搜索结果
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): QQMusicSearchResult = withContext(Dispatchers.IO) {
        try {
            val url = "$SEARCH_URL?w=${URLEncoder.encode(keyword, "UTF-8")}&format=json&p=$page&n=$limit&cr=1"

            Log.d(TAG, "搜索请求: $keyword, page=$page, limit=$limit")

            val response = httpClient.get(url, headers = QQ_HEADERS)
            if (!response.isSuccess) {
                Log.e(TAG, "搜索请求失败: ${response.code} ${response.message}")
                return@withContext QQMusicSearchResult(
                    list = emptyList(),
                    total = 0,
                    page = page,
                    allPage = 0
                )
            }

            val json = parseToObj(response.body)
            val data = json.optJSONObject("data") ?: JsonObject(emptyMap())
            val song = data.optJSONObject("song") ?: JsonObject(emptyMap())
            val list = song.optJSONArray("list") ?: JsonArray(emptyList())

            val songs = mutableListOf<QQMusicSong>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val parsed = parseSong(item)
                if (parsed != null) {
                    songs.add(parsed)
                }
            }

            // 获取总数
            val total = song.optInt("totalnum", 0)
            val allPage = if (total > 0) (total + limit - 1) / limit else 0

            Log.d(TAG, "搜索完成: ${songs.size} 首歌曲, 总计 $total 首")

            QQMusicSearchResult(
                list = songs,
                total = total,
                page = page,
                allPage = allPage
            )
        } catch (e: Exception) {
            Log.e(TAG, "搜索异常", e)
            QQMusicSearchResult(
                list = emptyList(),
                total = 0,
                page = page,
                allPage = 0
            )
        }
    }

    /**
     * 获取音乐播放URL
     * @param songMid QQ音乐歌曲mid
     * @param mediaMid 媒体mid
     * @return 播放URL
     */
    suspend fun getMusicUrl(songMid: String, mediaMid: String): String? = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("req_0", JSONObject().apply {
                    put("module", "vkey.GetVkeyServer")
                    put("method", "CgiGetVkey")
                    put("param", JSONObject().apply {
                        put("guid", "0")
                        // 注意: Android 的 org.json.JSONObject 没有 put(String, Collection) 重载，
                        // 必须用 JSONArray 包装，否则运行时 NoSuchMethodError 闪退
                        put("songmid", JSONArray(listOf(songMid)))
                        put("songtype", JSONArray(listOf(0)))
                        put("uin", "0")
                        // 实测（2026-08-05）：必须用 loginflag=1，旧参数 loginst 已被 QQ 忽略，
                        // 会导致 purl 恒为空、内置 QQ 播放全部失败（免费歌也拿不到 URL）
                        put("loginflag", 1)
                        put("platform", "20")
                    })
                })
                put("comm", JSONObject().apply {
                    put("uin", 0)
                    put("format", "json")
                    put("ct", 24)
                    put("cv", 0)
                })
            }

            val url = "$PLAY_URL?data=${URLEncoder.encode(requestBody.toString(), "UTF-8")}"
            
            val response = httpClient.get(url, headers = QQ_HEADERS)
            if (response.isSuccess) {
                val json = parseToObj(response.body)
                val req0 = json.optJSONObject("req_0") ?: JsonObject(emptyMap())
                val data = req0.optJSONObject("data") ?: JsonObject(emptyMap())
                val midurlinfo = data.optJSONArray("midurlinfo") ?: JsonArray(emptyList())
                
                if (midurlinfo.length() > 0) {
                    val info = midurlinfo.getJSONObject(0)
                    val purl = info.optString("purl", "")
                    
                    if (purl.isNotEmpty()) {
                        val sip = data.optJSONArray("sip")?.optString(0) ?: ""
                        "$sip$purl"
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取播放URL失败", e)
            null
        }
    }

    /**
     * 获取歌曲封面图片URL
     * @param albumMid 专辑mid
     * @return 封面URL
     */
    suspend fun getPicUrl(albumMid: String): String? = withContext(Dispatchers.IO) {
        try {
            if (albumMid.isNotEmpty()) {
                "$COVER_URL_PREFIX${albumMid}.jpg"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取歌词（2.8 增加翻译：接口返回含 trans 字段，构造统一 JSON {lyric, tlyric} 供解析）
     * @param songMid QQ音乐歌曲mid
     * @return JSON 字符串 {lyric, tlyric}（洛雪歌词协议字段名）；失败返回 null
     */
    suspend fun getLyric(songMid: String): String? = withContext(Dispatchers.IO) {
        try {
            // 老版歌词接口（实测可用，nobase64=1 直接返回明文 LRC，lyric + trans 翻译字段）
            // musicu.fcg 的 GetPlaySongLyric 已失效（返回 code=500003）
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=$songMid&format=json&nobase64=1"

            val response = httpClient.get(url, headers = QQ_HEADERS)
            if (response.isSuccess) {
                val json = parseToObj(response.body)
                if (json.optInt("retcode", -1) == 0) {
                    val lyric = json.optStr("lyric").orEmpty()
                    val trans = json.optStr("trans").orEmpty()
                    // 构造统一 JSON（构造请求体用 org.json，无重复 key 安全）
                    org.json.JSONObject()
                        .put("lyric", lyric)
                        .apply { if (trans.isNotEmpty()) put("tlyric", trans) }
                        .toString()
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取歌词失败", e)
            null
        }
    }

    /**
     * 解析搜索结果中的单首歌曲
     * 对应 client_search_cp 接口返回的字段
     */
    private fun parseSong(item: JsonObject): QQMusicSong? {
        return try {
            val songMid = item.optString("songmid", "")
            if (songMid.isEmpty()) return null

            val name = item.optString("songname", "")
            val singerList = item.optJSONArray("singer") ?: JsonArray(emptyList())
            val artist = if (singerList.length() > 0) {
                singerList.getJSONObject(0).optString("name", "")
            } else {
                ""
            }

            val albumMid = item.optString("albummid", "")
            val album = item.optString("albumname", "")
            val duration = item.optLong("interval", 0) * 1000 // 转毫秒

            // 封面 URL: T002R800x800M000{albummid}.jpg（大封面，列表由 RemoteImage 子采样）
            val picUrl = if (albumMid.isNotEmpty()) "$COVER_URL_PREFIX${albumMid}.jpg" else ""

            // 获取媒体mid（老接口两个字段都可能有）
            val mediaMid = item.optString("media_mid", "")
                .ifEmpty { item.optString("strMediaMid", "") }

            // 解析音质信息
            val types = parseQualityInfo(item)

            QQMusicSong(
                songMid = songMid,
                name = name,
                artist = artist,
                album = album,
                albumMid = albumMid,
                mediaMid = mediaMid,
                picUrl = picUrl,
                duration = duration,
                source = "tx",
                types = types
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析歌曲信息失败", e)
            null
        }
    }

    /**
     * 解析音质信息（client_search_cp 接口的 sizeXXX 字段）
     */
    private fun parseQualityInfo(item: JsonObject): List<QQMusicSongType> {
        val types = mutableListOf<QQMusicSongType>()

        // 检查128k音质
        if (item.has("size128")) {
            types.add(QQMusicSongType(type = "128k", fileSize = item.optLong("size128", 0).toString()))
        }

        // 检查320k音质
        if (item.has("size320")) {
            types.add(QQMusicSongType(type = "320k", fileSize = item.optLong("size320", 0).toString()))
        }

        // 检查flac音质
        if (item.has("sizeflac")) {
            types.add(QQMusicSongType(type = "flac", fileSize = item.optLong("sizeflac", 0).toString()))
        }

        // 检查ogg音质
        if (item.has("sizeogg")) {
            types.add(QQMusicSongType(type = "ogg", fileSize = item.optLong("sizeogg", 0).toString()))
        }

        // 默认128k
        if (types.isEmpty()) {
            types.add(QQMusicSongType(type = "128k", fileSize = "0"))
        }

        return types
    }

    /**
     * 将QQMusicSong转换为应用内Song数据模型
     */
    fun toSong(qqMusicSong: QQMusicSong): Song {
        // id 拼接 mediaMid（格式: songMid_mediaMid），播放时需要两者才能获取播放URL
        val id = if (qqMusicSong.mediaMid.isNotEmpty()) {
            "${qqMusicSong.songMid}_${qqMusicSong.mediaMid}"
        } else {
            qqMusicSong.songMid
        }
        return Song(
            id = id,
            name = qqMusicSong.name,
            singer = qqMusicSong.artist,
            albumName = qqMusicSong.album,
            albumId = qqMusicSong.albumMid,
            picUrl = qqMusicSong.picUrl.ifEmpty { null },
            duration = qqMusicSong.duration,
            platform = MusicPlatform.TX,
            quality = qqMusicSong.types.mapNotNull { type ->
                when (type.type) {
                    "128k" -> AudioQuality.QUALITY_128K
                    "320k" -> AudioQuality.QUALITY_320K
                    "flac" -> AudioQuality.FLAC
                    else -> null
                }
            }.ifEmpty { listOf(AudioQuality.QUALITY_128K, AudioQuality.QUALITY_320K) }
        )
    }
}

/**
 * QQ音乐搜索结果
 */
data class QQMusicSearchResult(
    val list: List<QQMusicSong>,
    val total: Int,
    val page: Int,
    val allPage: Int
)

/**
 * QQ音乐歌曲信息
 */
data class QQMusicSong(
    val songMid: String,
    val name: String,
    val artist: String,
    val album: String,
    val albumMid: String,
    val mediaMid: String,
    val picUrl: String = "",
    val duration: Long,
    val source: String,
    val types: List<QQMusicSongType>
)

/**
 * QQ音乐歌曲音质信息
 */
data class QQMusicSongType(
    val type: String,
    val fileSize: String
)