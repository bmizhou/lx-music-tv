package com.lxmusic.tv.network

import android.util.Log
import com.lxmusic.tv.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lxmusic.tv.util.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 酷我音乐API
 * 参考洛雪音乐mobile版 src/utils/musicSdk/kw/musicSearch.js 实现
 * 直接HTTP调用酷我搜索API，无需JS引擎
 */
class KuwoApi(
    private val httpClient: HttpClient = HttpClient()
) {
    companion object {
        private const val TAG = "KuwoApi"
        private const val SEARCH_URL = "http://search.kuwo.cn/r.s"
        private const val MUSIC_INFO_URL = "http://www.kuwo.cn/api/www/music/musicInfo"
        private const val PLAY_URL_PREFIX = "http://antiserver.kuwo.cn/anti.s"
        private const val COVER_URL_PREFIX = "http://img.kwimg.com/kuwoimg/albumcover/"
    }

    // N_MINFO 中提取音质信息的正则
    private val mInfoPattern = Pattern.compile("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")

    /**
     * 搜索音乐
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param limit 每页数量
     * @return 搜索结果
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): KuwoSearchResult = withContext(Dispatchers.IO) {
        try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = "$SEARCH_URL?client=kt" +
                    "&all=$encodedKeyword" +
                    "&pn=${page - 1}" +
                    "&rn=$limit" +
                    "&uid=794762570" +
                    "&ver=kwplayer_ar_9.2.2.1" +
                    "&vipver=1" +
                    "&show_copyright_off=1" +
                    "&newver=1" +
                    "&ft=music" +
                    "&cluster=0" +
                    "&strategy=2012" +
                    "&encoding=utf8" +
                    "&rformat=json" +
                    "&vermerge=1" +
                    "&mobi=1" +
                    "&issubtitle=1"

            Log.d(TAG, "搜索请求: $keyword, page=$page, limit=$limit")

            val response = httpClient.get(url)
            if (!response.isSuccess) {
                Log.e(TAG, "搜索请求失败: ${response.code} ${response.message}")
                return@withContext KuwoSearchResult(
                    list = emptyList(),
                    total = 0,
                    page = page,
                    allPage = 0
                )
            }

            val json = parseToObj(response.body)
            val total = json.optString("TOTAL", "0").toIntOrNull() ?: 0
            val abslist = json.optJSONArray("abslist") ?: JsonArray(emptyList())

            val songs = mutableListOf<KuwoSong>()
            for (i in 0 until abslist.length()) {
                val item = abslist.getJSONObject(i)
                val song = parseSong(item)
                if (song != null) {
                    songs.add(song)
                }
            }

            val allPage = if (total > 0) (total + limit - 1) / limit else 0

            Log.d(TAG, "搜索完成: ${songs.size} 首歌曲, 总计 $total 首")

            KuwoSearchResult(
                list = songs,
                total = total,
                page = page,
                allPage = allPage
            )
        } catch (e: Exception) {
            Log.e(TAG, "搜索异常", e)
            KuwoSearchResult(
                list = emptyList(),
                total = 0,
                page = page,
                allPage = 0
            )
        }
    }

    /**
     * 获取音乐播放URL
     * @param songId 酷我歌曲ID
     * @param quality 音质: 128k, 320k, flac, flac24bit
     * @return 播放URL
     */
    suspend fun getMusicUrl(songId: String, quality: String = "320k"): String? = withContext(Dispatchers.IO) {
        try {
            val br = when (quality) {
                "128k" -> "128"
                "320k" -> "320"
                "flac" -> "flac"
                "flac24bit" -> "2000"
                else -> "320"
            }

            val url = "$PLAY_URL_PREFIX?type=convert_url3" +
                    "&rid=MUSIC_${songId}" +
                    "&br=$br" +
                    "&fmt=mp3" +
                    "&pos=0" +
                    "&checkStatus=str" +
                    "&t=${System.currentTimeMillis() / 1000}"

            val response = httpClient.get(url)
            if (response.isSuccess && response.body.isNotEmpty()) {
                // 响应可能是直接URL或JSON
                val body = response.body.trim()
                if (body.startsWith("http")) {
                    body
                } else {
                    try {
                        val json = parseToObj(body)
                        json.optStr("url")
                    } catch (e: Exception) {
                        null
                    }
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
     * 获取歌曲详细信息
     * @param songId 酷我歌曲ID
     * @return 歌曲详情
     */
    suspend fun getMusicInfo(songId: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val url = "$MUSIC_INFO_URL?mid=$songId"
            val response = httpClient.get(url)
            if (response.isSuccess) {
                val json = parseToObj(response.body)
                if (json.optInt("code") == 200) {
                    json.optJSONObject("data")
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取歌曲信息失败", e)
            null
        }
    }

    /**
     * 获取歌曲封面图片URL
     * @param songId 酷我歌曲ID
     * @return 封面URL
     */
    suspend fun getPicUrl(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val info = getMusicInfo(songId)
            info?.optStr("pic") ?: info?.optStr("albumpic")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取歌词
     * @param songId 酷我歌曲ID（搜索为 MUSICRID 去前缀的新 id；浏览页歌单/排行可能给老 id）
     * @param songName 歌名（可选）：id 查不到歌词时用歌名+歌手搜索定位新 id 再查
     * @param artist 歌手名（可选）
     * @return LRC格式歌词
     */
    suspend fun getLyric(songId: String, songName: String? = null, artist: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 主查：songinfoandlrc 按 id 查（接口偶发失败，内部已重试）
            fetchLrcById(songId)?.let { return@withContext it }

            // 2. id 查不到（浏览页老 id 常见）→ 用歌名+歌手搜索定位，逐个试新 id
            if (!songName.isNullOrBlank()) {
                val keyword = if (!artist.isNullOrBlank()) "$artist $songName" else songName
                val found = search(keyword, page = 1, limit = 10)
                for (s in found.list) {
                    if (s.songId.isNotEmpty() && s.songId != songId) {
                        fetchLrcById(s.songId)?.let { return@withContext it }
                    }
                }
            }

            // 3. 备用方案：直接请求LRC文件
            val fallbackUrl = "http://lyric.kuwo.cn/lr/lyric/$songId"
            val fallbackResponse = httpClient.get(fallbackUrl)
            if (fallbackResponse.isSuccess && fallbackResponse.body.startsWith("[")) {
                return@withContext fallbackResponse.body
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取歌词失败", e)
            null
        }
    }

    /**
     * 通过 songinfoandlrc 按 id 查 LRC（重试 1 次，接口节点偶发返回"音乐查询失败"）
     * 响应结构 {"data":{"lrclist":[{time:"秒", lineLyric:"..."}]}}，data 是对象、无 status 字段
     */
    private suspend fun fetchLrcById(songId: String): String? {
        repeat(2) {
            try {
                // m.kuwo.cn 的 songinfoandlrc：仅 HTTP 可用（HTTPS 返回"音乐查询失败"）
                val url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$songId"
                val response = httpClient.get(url)
                if (response.isSuccess) {
                    val json = parseToObj(response.body)
                    val lrcList = json.optJSONObject("data")?.optJSONArray("lrclist")
                    if (lrcList != null && lrcList.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until lrcList.length()) {
                            val line = lrcList.getJSONObject(i)
                            val time = line.optString("time", "0").toDoubleOrNull() ?: 0.0
                            val min = (time / 60).toInt()
                            val sec = time % 60
                            sb.appendLine(String.format("[%02d:%05.2f]%s", min, sec, line.optString("lineLyric", "")))
                        }
                        if (sb.isNotBlank()) return sb.toString()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询歌词失败: $songId", e)
            }
        }
        return null
    }

    /**
     * 解析搜索结果中的单首歌曲
     */
    private fun parseSong(item: JsonObject): KuwoSong? {
        return try {
            val musicRid = item.optString("MUSICRID", "")
            val songId = musicRid.replace("MUSIC_", "")
            if (songId.isEmpty()) return null

            val name = decodeHtml(item.optString("SONGNAME", ""))
            val artist = decodeHtml(item.optString("ARTIST", ""))
            val album = decodeHtml(item.optString("ALBUM", ""))
            val albumId = item.optString("ALBUMID", "")
            val duration = item.optLong("DURATION", 0) * 1000 // 转毫秒

            // 解析封面：优先 hts_MVPIC（完整 HTTPS MV 图，324 宽），回退 star/albumcover 专辑封面
            val picUrl = buildString {
                val mvPic = item.optString("hts_MVPIC", "")
                if (mvPic.isNotBlank()) {
                    append(mvPic)
                } else {
                    val albumPic = item.optString("web_albumpic_short", "")
                    if (albumPic.isNotBlank()) {
                        append("https://img1.kuwo.cn/star/albumcover/$albumPic")
                    }
                }
            }.ifBlank { null }

            // 解析可用音质
            val nMinfo = item.optString("N_MINFO", "")
            val types = parseQualityInfo(nMinfo)

            KuwoSong(
                songId = songId,
                name = name,
                artist = artist,
                album = album,
                albumId = albumId,
                duration = duration,
                picUrl = picUrl,
                source = "kw",
                types = types
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析歌曲信息失败", e)
            null
        }
    }

    /**
     * 解析N_MINFO字段中的音质信息
     * 格式: level:XX,bitrate:XXXX,format:XXX,size:XX.XX;level:XX,...
     */
    private fun parseQualityInfo(nMinfo: String): List<KuwoSongType> {
        if (nMinfo.isEmpty()) return emptyList()

        val types = mutableListOf<KuwoSongType>()
        val parts = nMinfo.split(";")

        for (part in parts) {
            val matcher = mInfoPattern.matcher(part)
            if (matcher.find()) {
                val bitrate = matcher.group(2) ?: continue
                val size = matcher.group(4) ?: ""

                val type = when (bitrate) {
                    "4000" -> "flac24bit"
                    "2000" -> "flac"
                    "320" -> "320k"
                    "128" -> "128k"
                    else -> continue
                }
                types.add(KuwoSongType(type = type, fileSize = size))
            }
        }

        return types
    }

    /**
     * 解码HTML实体
     */
    private fun decodeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    /**
     * 将KuwoSong转换为应用内Song数据模型
     */
    fun toSong(kuwoSong: KuwoSong): Song {
        return Song(
            id = kuwoSong.songId,
            name = kuwoSong.name,
            singer = kuwoSong.artist,
            albumName = kuwoSong.album,
            albumId = kuwoSong.albumId,
            picUrl = kuwoSong.picUrl,
            duration = kuwoSong.duration,
            platform = MusicPlatform.KW,
            quality = kuwoSong.types.mapNotNull { type ->
                when (type.type) {
                    "128k" -> AudioQuality.QUALITY_128K
                    "320k" -> AudioQuality.QUALITY_320K
                    "flac" -> AudioQuality.FLAC
                    "flac24bit" -> AudioQuality.FLAC_24BIT
                    else -> null
                }
            }.ifEmpty { listOf(AudioQuality.QUALITY_128K, AudioQuality.QUALITY_320K) }
        )
    }
}

/**
 * 酷我搜索结果
 */
data class KuwoSearchResult(
    val list: List<KuwoSong>,
    val total: Int,
    val page: Int,
    val allPage: Int
)

/**
 * 酷我歌曲信息
 */
data class KuwoSong(
    val songId: String,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val duration: Long,
    val picUrl: String? = null,
    val source: String,
    val types: List<KuwoSongType>
)

/**
 * 酷我歌曲音质信息
 */
data class KuwoSongType(
    val type: String,
    val fileSize: String
)
