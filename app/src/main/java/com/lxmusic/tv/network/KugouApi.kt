package com.lxmusic.tv.network

import android.util.Log
import com.lxmusic.tv.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lxmusic.tv.util.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 酷狗音乐API
 * 参考洛雪音乐mobile版实现
 * 直接HTTP调用酷狗搜索API，无需JS引擎
 */
class KugouApi(
    private val httpClient: HttpClient = HttpClient()
) {
    companion object {
        private const val TAG = "KugouApi"
        private const val SEARCH_URL = "http://mobilecdn.kugou.com/api/v3/search/song"
        // trackercdn v2：key = md5(hash + "kgcloudv2")，参考洛雪 kg.js（实测可用，返回 url 数组）
        private const val TRACKER_CDN_URL = "https://trackercdn.kugou.com/i/v2/"
        private const val COVER_URL_PREFIX = "http://imge.kugou.com/albumcover/"
    }

    /**
     * 搜索音乐
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param limit 每页数量
     * @return 搜索结果
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): KugouSearchResult = withContext(Dispatchers.IO) {
        try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = "$SEARCH_URL?format=json" +
                    "&keyword=$encodedKeyword" +
                    "&page=$page" +
                    "&pagesize=$limit" +
                    "&showtype=1"

            Log.d(TAG, "搜索请求: $keyword, page=$page, limit=$limit")

            val response = httpClient.get(url)
            if (!response.isSuccess) {
                Log.e(TAG, "搜索请求失败: ${response.code} ${response.message}")
                return@withContext KugouSearchResult(
                    list = emptyList(),
                    total = 0,
                    page = page,
                    allPage = 0
                )
            }

            val json = parseToObj(response.body)
            val data = json.optJSONObject("data") ?: JsonObject(emptyMap())
            val total = data.optInt("total", 0)
            val info = data.optJSONArray("info") ?: JsonArray(emptyList())

            val songs = mutableListOf<KugouSong>()
            for (i in 0 until info.length()) {
                val item = info.getJSONObject(i)
                val song = parseSong(item)
                if (song != null) {
                    songs.add(song)
                }
            }

            val allPage = if (total > 0) (total + limit - 1) / limit else 0

            Log.d(TAG, "搜索完成: ${songs.size} 首歌曲, 总计 $total 首")

            KugouSearchResult(
                list = songs,
                total = total,
                page = page,
                allPage = allPage
            )
        } catch (e: Exception) {
            Log.e(TAG, "搜索异常", e)
            KugouSearchResult(
                list = emptyList(),
                total = 0,
                page = page,
                allPage = 0
            )
        }
    }

    /**
     * 获取音乐播放URL
     * @param hash 酷狗歌曲hash
     * @param albumId 专辑ID
     * @return 播放URL
     */
    suspend fun getMusicUrl(hash: String, albumId: String): String? = withContext(Dispatchers.IO) {
        try {
            // 旧接口 www.kugou.com/yy/index.php?r=play/getdata 已失效（play_url 恒为空，2026-08-05 实测）
            // 改用 trackercdn v2（洛雪 kg.js 同款）：key = md5(hash + "kgcloudv2")，返回 url 数组（多镜像）
            val key = md5Hex(hash + "kgcloudv2")
            val url = "$TRACKER_CDN_URL?cmd=25" +
                    "&hash=$hash" +
                    "&key=$key" +
                    "&pid=1" +
                    "&behavior=play" +
                    "&appid=1014" +
                    "&appver=10000" +
                    "&version=8192" +
                    "&format=mp3"

            val response = httpClient.get(url, mapOf("Referer" to "http://www.kugou.com/"))
            if (response.isSuccess) {
                val json = parseToObj(response.body)
                // url 可能是字符串或数组（多镜像），取第一个可用
                val urlVal = json.opt("url") ?: return@withContext null
                when (urlVal) {
                    is String -> urlVal.ifEmpty { null }
                    is JsonArray -> (0 until urlVal.length())
                        .map { urlVal.optString(it) }
                        .firstOrNull { it.isNotEmpty() }
                    else -> null
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
     * MD5 十六进制（trackercdn 签名用）
     */
    private fun md5Hex(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 获取歌曲封面图片URL
     * @param hash 酷狗歌曲hash
     * @return 封面URL
     */
    suspend fun getPicUrl(hash: String): String? = withContext(Dispatchers.IO) {
        try {
            // 封面URL格式: http://imge.kugou.com/albumcover/{hash}.jpg
            "$COVER_URL_PREFIX${hash}.jpg"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取歌词（lyrics.kugou.com 搜索 → 下载，content 为 base64 编码的 LRC）
     * 旧接口 www.kugou.com/yy/index.php?r=play/getdata 已失效（返回空 data）
     * @param hash 酷狗歌曲hash
     * @param albumId 专辑ID（id 格式 hash_albumId，可空）
     * @param keyword 歌名（可选，用于提高候选匹配准确率）
     * @return LRC格式歌词
     */
    suspend fun getLyric(hash: String, albumId: String? = null, keyword: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 搜索歌词候选
            val keywordParam = keyword?.takeIf { it.isNotBlank() }?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                    "&keyword=$keywordParam" +
                    "&hash=$hash" +
                    (albumId?.takeIf { it.isNotBlank() }?.let { "&album_audio_id=$it" } ?: "")
            val searchResponse = httpClient.get(searchUrl)
            if (!searchResponse.isSuccess) return@withContext null
            val searchJson = parseToObj(searchResponse.body)
            val candidates = searchJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return@withContext null
            val first = candidates.getJSONObject(0)
            val lyricId = first.optString("id", "")
            val accessKey = first.optString("accesskey", "")
            if (lyricId.isEmpty() || accessKey.isEmpty()) return@withContext null

            // 2. 下载 LRC（content 字段是 base64 编码的 LRC 文本）
            val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc" +
                    "&id=$lyricId&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val downloadResponse = httpClient.get(downloadUrl)
            if (!downloadResponse.isSuccess) return@withContext null
            val downloadJson = parseToObj(downloadResponse.body)
            val content = downloadJson.optString("content", "")
            if (content.isEmpty()) return@withContext null
            val lrcBytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            String(lrcBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "获取歌词失败", e)
            null
        }
    }

    /**
     * 解析搜索结果中的单首歌曲
     * 酷狗 v3 接口字段: songname(歌名), singername(歌手), duration(秒), album_id, album_name
     * 注意不要用 fileName（接口返回的是全小写 filename），否则歌名为空
     */
    private fun parseSong(item: JsonObject): KugouSong? {
        return try {
            val hash = item.optString("hash", "")
            if (hash.isEmpty()) return null

            val name = item.optString("songname", "")
            val artist = item.optString("singername", "")
            val duration = item.optLong("duration", 0) * 1000 // 转毫秒
            val albumId = item.optString("album_id", "")

            // 兜底：从 filename（如 "歌手 - 歌名"）解析
            val fileName = item.optString("filename", "")
            val fallbackName = if (name.isBlank() && fileName.isNotBlank()) {
                val parts = fileName.split(" - ", limit = 2)
                if (parts.size > 1) parts[1].trim() else fileName
            } else name
            val fallbackArtist = if (artist.isBlank() && fileName.isNotBlank()) {
                fileName.substringBefore(" - ").trim()
            } else artist

            // 封面 URL: trans_param.union_cover 是模板（{size} 替换为 300）
            val unionCover = item.optJSONObject("trans_param")?.optString("union_cover", "") ?: ""
            val picUrl = if (unionCover.isNotBlank()) {
                unionCover.replace("{size}", "1000")
            } else ""

            // 解析音质信息
            val types = parseQualityInfo(item)

            KugouSong(
                hash = hash,
                name = fallbackName,
                artist = fallbackArtist,
                albumId = albumId,
                picUrl = picUrl,
                duration = duration,
                source = "kg",
                types = types
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析歌曲信息失败", e)
            null
        }
    }

    /**
     * 解析音质信息
     */
    private fun parseQualityInfo(item: JsonObject): List<KugouSongType> {
        val types = mutableListOf<KugouSongType>()

        // 检查320k音质
        if (item.optInt("is_320", 0) == 1) {
            types.add(KugouSongType(type = "320k", fileSize = ""))
        }

        // 检查flac音质
        if (item.optInt("is_lossless", 0) == 1) {
            types.add(KugouSongType(type = "flac", fileSize = ""))
        }

        // 默认128k
        if (types.isEmpty()) {
            types.add(KugouSongType(type = "128k", fileSize = ""))
        }

        return types
    }

    /**
     * 将KugouSong转换为应用内Song数据模型
     */
    fun toSong(kugouSong: KugouSong): Song {
        // id 拼接 albumId（格式: hash_albumId），播放时需要两者才能获取播放URL
        val id = if (!kugouSong.albumId.isNullOrBlank()) {
            "${kugouSong.hash}_${kugouSong.albumId}"
        } else {
            kugouSong.hash
        }
        return Song(
            id = id,
            name = kugouSong.name,
            singer = kugouSong.artist,
            albumName = null,
            albumId = kugouSong.albumId,
            picUrl = kugouSong.picUrl.ifEmpty { null },
            duration = kugouSong.duration,
            platform = MusicPlatform.KG,
            quality = kugouSong.types.mapNotNull { type ->
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
 * 酷狗搜索结果
 */
data class KugouSearchResult(
    val list: List<KugouSong>,
    val total: Int,
    val page: Int,
    val allPage: Int
)

/**
 * 酷狗歌曲信息
 */
data class KugouSong(
    val hash: String,
    val name: String,
    val artist: String,
    val albumId: String,
    val picUrl: String = "",
    val duration: Long,
    val source: String,
    val types: List<KugouSongType>
)

/**
 * 酷狗歌曲音质信息
 */
data class KugouSongType(
    val type: String,
    val fileSize: String
)