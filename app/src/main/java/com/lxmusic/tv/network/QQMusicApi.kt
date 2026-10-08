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
        // 2.9 (参考 lx-music-desktop PR #2848):
        // 将搜索接口从失效风控的 client_search_cp 切换至 PC 端 DoSearchForQQMusicDesktop + musics.fcg(带 zzcSign 签名)
        private const val SEARCH_URL = "https://u.y.qq.com/cgi-bin/musics.fcg"
        private const val PLAY_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        // 大封面：800x800（播放页大图更清晰；列表小图由 RemoteImage 子采样，不影响内存）
        private const val COVER_URL_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R800x800M000"
        // QQ 接口风控要求携带 Referer，否则返回空结果
        private val QQ_HEADERS = mapOf(
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com"
        )
        // 搜索专用请求头（包含桌面端/移动端特定 UA）
        private val SEARCH_HEADERS = mapOf(
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com",
            "User-Agent" to "QQMusic 14090508(android 12)"
        )
    }

    /**
     * 搜索音乐（参考 lx-music-desktop PR #2848: PC端 DoSearchForQQMusicDesktop + musics.fcg(zzcSign)）
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param limit 每页数量
     * @return 搜索结果
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): QQMusicSearchResult = withContext(Dispatchers.IO) {
        try {
            val searchId = QqSignUtil.getSearchId()
            val requestBody = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("_channelid", "0")
                    put("_os_version", "6.2.9200-2")
                    put("ct", "19")
                    put("cv", "2151")
                    put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
                    put("patch", "118")
                    put("psrf_access_token_expiresAt", 0)
                    put("psrf_qqaccess_token", "")
                    put("psrf_qqopenid", "")
                    put("psrf_qqunionid", "")
                    put("tmeAppID", "qqmusic")
                    put("tmeLoginType", 0)
                    put("uin", "0")
                    put("wid", "7223299733393904640")
                })
                put("music.search.SearchCgiService", JSONObject().apply {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicDesktop")
                    put("param", JSONObject().apply {
                        put("grp", 1)
                        put("num_per_page", limit)
                        put("page_num", page)
                        put("query", keyword)
                        put("remoteplace", "txt.newclient.top")
                        put("search_type", 0)
                        put("searchid", searchId)
                    })
                })
            }

            val bodyStr = requestBody.toString()
            val sign = QqSignUtil.zzcSign(bodyStr)
            val url = "$SEARCH_URL?sign=$sign"

            Log.d(TAG, "搜索请求: $keyword, page=$page, limit=$limit, sign=$sign")

            val response = httpClient.post(url, bodyStr, contentType = "application/json", headers = SEARCH_HEADERS)
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
            val rootCode = json.optInt("code", -1)
            // 兼容返回结构：music.search.SearchCgiService 或 req (PR #2848)
            val cgiService = json.optJSONObject("music.search.SearchCgiService") ?: json.optJSONObject("req")
            val cgiCode = cgiService?.optInt("code", -1) ?: -1

            if (rootCode != 0 || cgiCode != 0) {
                Log.e(TAG, "搜索接口返回错误: rootCode=$rootCode, cgiCode=$cgiCode")
                return@withContext QQMusicSearchResult(
                    list = emptyList(),
                    total = 0,
                    page = page,
                    allPage = 0
                )
            }

            val data = cgiService?.optJSONObject("data") ?: JsonObject(emptyMap())
            val body = data.optJSONObject("body") ?: JsonObject(emptyMap())
            // 兼容新结构 body.song.list 与旧结构 body.item_song
            val songObj = body.optJSONObject("song")
            val list = songObj?.optJSONArray("list") ?: body.optJSONArray("item_song") ?: JsonArray(emptyList())

            val songs = mutableListOf<QQMusicSong>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val parsed = parseSong(item)
                if (parsed != null) {
                    songs.add(parsed)
                }
            }

            // 获取总数：兼容 meta.sum 与 meta.estimate_sum
            val meta = data.optJSONObject("meta")
            val total = meta?.optInt("sum", 0).takeIf { (it ?: 0) > 0 }
                ?: meta?.optInt("estimate_sum", 0).takeIf { (it ?: 0) > 0 }
                ?: songs.size
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
     * 获取原文及翻译。PlayLyricInfo 使用数字 songID，先通过歌曲详情解析 songMid。
     * crypt=0、qrc=0 返回 Base64 编码的普通 LRC，无需 QRC 解密。
     * 旧接口通常只有原文，仅作为新接口失败时的兜底。
     * @param songMid QQ音乐歌曲mid
     * @return JSON 字符串 {lyric, tlyric}（洛雪歌词协议字段名）；失败返回 null
     */
    suspend fun getLyric(songMid: String): String? = withContext(Dispatchers.IO) {
        try {
            val detailRequest = JSONObject().apply {
                put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
                put("req", JSONObject().apply {
                    put("module", "music.pf_song_detail_svr")
                    put("method", "get_song_detail_yqq")
                    put("param", JSONObject().put("song_type", 0).put("song_mid", songMid))
                })
            }
            val detailResponse = httpClient.post(PLAY_URL, detailRequest.toString(), headers = QQ_HEADERS)
            if (detailResponse.isSuccess) {
                val detail = parseToObj(detailResponse.body)
                val req = detail.optJSONObject("req")
                val songId = req?.optJSONObject("data")?.optJSONObject("track_info")?.optLong("id", 0) ?: 0
                if (detail.optInt("code", -1) == 0 && req?.optInt("code", -1) == 0 && songId > 0) {
                    val lyricRequest = JSONObject().apply {
                        put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
                        put("req", JSONObject().apply {
                            put("module", "music.musichallSong.PlayLyricInfo")
                            put("method", "GetPlayLyricInfo")
                            put("param", JSONObject().apply {
                                put("format", "json")
                                put("crypt", 0)
                                put("ct", 19)
                                put("cv", 1873)
                                put("interval", 0)
                                put("lrc_t", 0)
                                put("qrc", 0)
                                put("qrc_t", 0)
                                put("roma", 0)
                                put("roma_t", 0)
                                put("songID", songId)
                                put("trans", 1)
                                put("trans_t", 0)
                                put("type", -1)
                            })
                        })
                    }
                    val response = httpClient.post(PLAY_URL, lyricRequest.toString(), headers = QQ_HEADERS)
                    if (response.isSuccess) {
                        QQMusicLyricResponse.parse(response.body)?.let { return@withContext it }
                    }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "获取翻译歌词失败，尝试旧接口: ${e.message}")
        }
        getLegacyLyric(songMid)
    }

    private suspend fun getLegacyLyric(songMid: String): String? {
        try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${URLEncoder.encode(songMid, "UTF-8")}&format=json&nobase64=1"

            val response = httpClient.get(url, headers = QQ_HEADERS)
            return if (response.isSuccess) {
                val json = parseToObj(response.body)
                if (json.optInt("retcode", -1) == 0 && !json.optStr("lyric").isNullOrBlank()) {
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "获取歌词失败", e)
            return null
        }
    }

    /**
     * 解析搜索结果中的单首歌曲
     * 兼容 DoSearchForQQMusicDesktop 与旧 client_search_cp 接口返回的字段
     */
    private fun parseSong(item: JsonObject): QQMusicSong? {
        return try {
            val songMid = item.optString("mid", "").ifEmpty { item.optString("songmid", "") }
            if (songMid.isEmpty()) return null

            val name = item.optString("title", "")
                .ifEmpty { item.optString("name", "") }
                .ifEmpty { item.optString("songname", "") }

            val singerList = item.optJSONArray("singer") ?: JsonArray(emptyList())
            val artist = if (singerList.length() > 0) {
                val names = mutableListOf<String>()
                for (i in 0 until singerList.length()) {
                    val s = singerList.getJSONObject(i).optString("name", "")
                    if (s.isNotEmpty()) names.add(s)
                }
                names.joinToString("、").ifEmpty { item.optString("singerName", "") }
            } else {
                item.optString("singerName", "")
            }

            val albumObj = item.optJSONObject("album")
            val albumMid = albumObj?.optString("mid", "")?.ifEmpty { item.optString("albummid", "") } ?: item.optString("albummid", "")
            val album = albumObj?.optString("title", "")
                ?.ifEmpty { albumObj.optString("name", "") }
                ?.ifEmpty { item.optString("albumname", "") }
                ?: item.optString("albumname", "")

            val fileObj = item.optJSONObject("file")
            val mediaMid = fileObj?.optString("media_mid", "")
                ?.ifEmpty { item.optString("media_mid", "") }
                ?.ifEmpty { item.optString("strMediaMid", "") }
                ?: item.optString("media_mid", "").ifEmpty { item.optString("strMediaMid", "") }

            val duration = item.optLong("interval", 0) * 1000L // 转毫秒

            // 封面 URL: T002R800x800M000{albummid}.jpg（优先专辑封面；无专辑封面时使用首位歌手头像）
            val picUrl = if (albumMid.isNotEmpty() && albumMid != "空") {
                "$COVER_URL_PREFIX${albumMid}.jpg"
            } else {
                val firstSingerMid = if (singerList.length() > 0) singerList.getJSONObject(0).optString("mid", "") else ""
                if (firstSingerMid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T001R500x500M000${firstSingerMid}.jpg" else ""
            }

            // 解析音质信息（兼容 PC 端 file 对象与旧版 sizeXXX 字段）
            val types = parseQualityInfo(item, fileObj)

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
     * 解析音质信息（优先读取新版 file 对象中的 size_xxx 字段，兼容旧版 sizeXXX）
     */
    private fun parseQualityInfo(item: JsonObject, fileObj: JsonObject? = null): List<QQMusicSongType> {
        val types = mutableListOf<QQMusicSongType>()

        if (fileObj != null) {
            val size128 = fileObj.optLong("size_128mp3", 0L)
            if (size128 > 0) types.add(QQMusicSongType(type = "128k", fileSize = size128.toString()))

            val size320 = fileObj.optLong("size_320mp3", 0L)
            if (size320 > 0) types.add(QQMusicSongType(type = "320k", fileSize = size320.toString()))

            val sizeFlac = fileObj.optLong("size_flac", 0L)
            if (sizeFlac > 0) types.add(QQMusicSongType(type = "flac", fileSize = sizeFlac.toString()))

            val sizeHires = fileObj.optLong("size_hires", 0L)
            if (sizeHires > 0) types.add(QQMusicSongType(type = "flac24bit", fileSize = sizeHires.toString()))
        }

        // 旧版字段兜底
        if (types.isEmpty()) {
            if (item.has("size128")) types.add(QQMusicSongType(type = "128k", fileSize = item.optLong("size128", 0L).toString()))
            if (item.has("size320")) types.add(QQMusicSongType(type = "320k", fileSize = item.optLong("size320", 0L).toString()))
            if (item.has("sizeflac")) types.add(QQMusicSongType(type = "flac", fileSize = item.optLong("sizeflac", 0L).toString()))
            if (item.has("sizeogg")) types.add(QQMusicSongType(type = "ogg", fileSize = item.optLong("sizeogg", 0L).toString()))
        }

        // 默认兜底
        if (types.isEmpty()) {
            types.add(QQMusicSongType(type = "128k", fileSize = "0"))
            types.add(QQMusicSongType(type = "320k", fileSize = "0"))
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
            albumName = qqMusicSong.album.ifBlank { null },
            albumId = qqMusicSong.albumMid.ifBlank { null },
            picUrl = qqMusicSong.picUrl.ifEmpty { null },
            duration = qqMusicSong.duration,
            platform = MusicPlatform.TX,
            quality = qqMusicSong.types.mapNotNull { type ->
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
 * QQ 音乐签名与参数工具类（参考 lx-music-desktop PR #2848）
 */
object QqSignUtil {
    private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
    private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val SCRAMBLE_VALUES = intArrayOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179)

    /**
     * 生成请求签名（zzcSign）
     */
    fun zzcSign(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(40)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) hex.append('0')
            hex.append(Integer.toHexString(v))
        }
        val hexStr = hex.toString() // 40 chars

        val part1 = StringBuilder()
        for (idx in PART_1_INDEXES) {
            if (idx in hexStr.indices) {
                part1.append(hexStr[idx])
            }
        }

        val part2 = StringBuilder()
        for (idx in PART_2_INDEXES) {
            if (idx in hexStr.indices) {
                part2.append(hexStr[idx])
            }
        }

        val part3 = ByteArray(SCRAMBLE_VALUES.size)
        for (i in SCRAMBLE_VALUES.indices) {
            val byteVal = hexStr.substring(i * 2, i * 2 + 2).toInt(16)
            part3[i] = (SCRAMBLE_VALUES[i] xor byteVal).toByte()
        }

        val b64 = try {
            android.util.Base64.encodeToString(part3, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(part3)
        }.replace(Regex("[\\\\/+=]"), "")

        return "zzc$part1$b64$part2".lowercase()
    }

    /**
     * PC 客户端版 searchid：32 位大写十六进制 GUID + 5 位补零随机数 = 37 字符
     */
    fun getSearchId(): String {
        val hexChars = "0123456789ABCDEF"
        val sb = StringBuilder(37)
        val rnd = java.util.Random()
        for (i in 0 until 32) {
            sb.append(hexChars[rnd.nextInt(16)])
        }
        val rand5 = rnd.nextInt(100000).toString().padStart(5, '0')
        sb.append(rand5)
        return sb.toString()
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
