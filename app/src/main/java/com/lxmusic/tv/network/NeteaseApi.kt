package com.lxmusic.tv.network

import android.util.Base64
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
import java.security.MessageDigest

/**
 * 网易云音乐API
 * 参考洛雪音乐mobile版实现
 * 直接HTTP调用网易云音乐搜索API，无需JS引擎
 */
class NeteaseApi(
    private val httpClient: HttpClient = HttpClient()
) {
    companion object {
        private const val TAG = "NeteaseApi"
        private const val SEARCH_URL = "https://music.163.com/api/search/get"
        // 网易云 weapi 加密搜索接口（明文 api/search/get 已不再返回封面 picUrl，游客态即可搜）
        private const val WY_WEAPI_SEARCH = "https://music.163.com/weapi/search/get"
        private const val PLAY_URL = "https://music.163.com/song/media/outer/url"
        private const val COVER_URL_PREFIX = "https://p1.music.126.net/"
        // weapi 请求头（与 BrowseDataService 一致）
        private val WY_WEAPI_HEADERS = mapOf(
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com/"
        )
    }

    /**
     * 搜索音乐（对外入口）
     * 优先走 weapi /weapi/search/get（明文 api/search/get 已不再返回封面 picUrl），
     * weapi 失败或返回空时回退明文接口兜底。
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): NeteaseSearchResult = withContext(Dispatchers.IO) {
        // 优先 weapi（返回 al.picUrl 封面）
        try {
            val weapiResult = searchWeapi(keyword, page, limit)
            if (weapiResult.list.isNotEmpty()) {
                return@withContext weapiResult
            }
        } catch (e: Exception) {
            Log.w(TAG, "weapi 搜索失败，回退明文接口", e)
        }
        // 回退明文搜索（封面可能为空）
        searchPlaintext(keyword, page, limit)
    }

    /**
     * 明文搜索（api/search/get）
     * 注意：网易云已不再在此接口返回 album.picUrl（恒为空），仅作兜底。
     */
    private suspend fun searchPlaintext(keyword: String, page: Int = 1, limit: Int = 30): NeteaseSearchResult = withContext(Dispatchers.IO) {
        try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = "$SEARCH_URL?csrf_token=&type=1&offset=${(page - 1) * limit}&total=true&limit=$limit"
            
            val body = "s=$encodedKeyword&type=1&offset=${(page - 1) * limit}&total=true&limit=$limit"
            
            Log.d(TAG, "搜索请求: $keyword, page=$page, limit=$limit")

            val response = httpClient.post(
                url,
                body = body,
                contentType = "application/x-www-form-urlencoded",
                headers = mapOf(
                    "Referer" to "https://music.163.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            if (!response.isSuccess) {
                Log.e(TAG, "搜索请求失败: ${response.code} ${response.message}")
                return@withContext NeteaseSearchResult(
                    list = emptyList(),
                    total = 0,
                    page = page,
                    allPage = 0
                )
            }

            val json = parseToObj(response.body)
            val result = json.optJSONObject("result") ?: JsonObject(emptyMap())
            val songs = result.optJSONArray("songs") ?: JsonArray(emptyList())

            val songList = mutableListOf<NeteaseSong>()
            for (i in 0 until songs.length()) {
                val item = songs.getJSONObject(i)
                val song = parseSong(item)
                if (song != null) {
                    songList.add(song)
                }
            }

            // 获取总数
            val total = result.optInt("songCount", 0)
            val allPage = if (total > 0) (total + limit - 1) / limit else 0

            Log.d(TAG, "搜索完成: ${songList.size} 首歌曲, 总计 $total 首")

            NeteaseSearchResult(
                list = songList,
                total = total,
                page = page,
                allPage = allPage
            )
        } catch (e: Exception) {
            Log.e(TAG, "搜索异常", e)
            NeteaseSearchResult(
                list = emptyList(),
                total = 0,
                page = page,
                allPage = 0
            )
        }
    }

    /**
     * 获取音乐播放URL
     * @param songId 网易云歌曲ID
     * @return 播放URL
     */
    suspend fun getMusicUrl(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$PLAY_URL?id=$songId"
            
            val response = httpClient.get(url)
            if (response.isSuccess) {
                // 网易云直接返回重定向URL
                val finalUrl = response.headers["Location"] ?: url
                finalUrl
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
     * @param picUrl 封面图片URL
     * @return 封面URL
     */
    suspend fun getPicUrl(picUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            if (picUrl.isNotEmpty()) {
                // 网易云封面 URL 支持 ?param=宽x高 动态缩放；默认 300x300 太小，升级 800
                if (picUrl.contains("param=")) {
                    picUrl.replace(Regex("param=\\d+y\\d+"), "param=800y800")
                } else {
                    "$picUrl?param=800y800"
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取歌词（2.8 增加翻译：tv=1 拉取 tlyric，返回统一 JSON {lyric, tlyric} 供 MusicSearchService 解析）
     * @param songId 网易云歌曲ID
     * @return JSON 字符串 {lyric, tlyric}（洛雪歌词协议字段名）；失败返回 null
     */
    suspend fun getLyric(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            // lv=1 原文 + tv=1 翻译（实测可用）
            val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&tv=1"

            val response = httpClient.get(url)
            if (response.isSuccess) {
                val json = parseToObj(response.body)
                val lrc = json.optJSONObject("lrc")?.optStr("lyric").orEmpty()
                val tlyric = json.optJSONObject("tlyric")?.optStr("lyric").orEmpty()
                // 构造统一 JSON（构造请求体用 org.json，无重复 key 安全）
                org.json.JSONObject()
                    .put("lyric", lrc)
                    .apply { if (tlyric.isNotEmpty()) put("tlyric", tlyric) }
                    .toString()
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
     */
    private fun parseSong(item: JsonObject): NeteaseSong? {
        return try {
            val id = item.optLong("id", 0).toString()
            if (id.isEmpty() || id == "0") return null

            val name = item.optString("name", "")
            val artists = item.optJSONArray("artists") ?: JsonArray(emptyList())
            val artist = if (artists.length() > 0) {
                artists.getJSONObject(0).optString("name", "")
            } else {
                ""
            }

            val album = item.optJSONObject("album") ?: JsonObject(emptyMap())
            val albumId = album.optString("id", "")
            val albumName = album.optString("name", "")
            // 明文接口 album.picUrl 已为空，尝试用 picId 还原封面直链兜底；picUrl 存在时升级动态缩放参数到 800
            val rawPicUrl = album.optString("picUrl", "")
            val picUrl = if (rawPicUrl.isNotEmpty()) {
                if (rawPicUrl.contains("param=")) rawPicUrl.replace(Regex("param=\\d+y\\d+"), "param=800y800")
                else "$rawPicUrl?param=800y800"
            } else buildWyCoverUrl(album.optString("picId", ""))
            val duration = item.optLong("duration", 0)

            // 解析音质信息
            val types = parseQualityInfo(item)

            NeteaseSong(
                songId = id,
                name = name,
                artist = artist,
                album = albumName,
                albumId = albumId,
                picUrl = picUrl,
                duration = duration,
                source = "wy",
                types = types
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析歌曲信息失败", e)
            null
        }
    }

    /**
     * weapi 加密搜索（cloudsearch）
     * 返回结构与明文不同：单曲列表在 result.songs[]，每首含
     * - id：歌曲ID
     * - name：歌名
     * - ar[]：歌手数组（取 ar[0].name）
     * - al：专辑对象（al.name / al.picUrl / al.id）—— 封面在这里
     * - dt：时长（毫秒）
     */
    private suspend fun searchWeapi(keyword: String, page: Int, limit: Int): NeteaseSearchResult {
        val json = JSONObject().apply {
            put("s", keyword)
            put("type", 1) // 1 = 单曲
            put("offset", (page - 1) * limit)
            put("limit", limit)
            put("total", true)
        }.toString()

        val (params, encSecKey) = NeteaseWeApi.encrypt(json)
        val response = httpClient.postForm(
            WY_WEAPI_SEARCH,
            mapOf("params" to params, "encSecKey" to encSecKey),
            headers = WY_WEAPI_HEADERS
        )

        if (!response.isSuccess) {
            Log.e(TAG, "weapi 搜索请求失败: ${response.code} ${response.message}")
            return NeteaseSearchResult(emptyList(), 0, page, 0)
        }

        val jsonObj = parseToObj(response.body)
        val result = jsonObj.optJSONObject("result") ?: JsonObject(emptyMap())
        val songs = result.optJSONArray("songs") ?: JsonArray(emptyList())

        val songList = mutableListOf<NeteaseSong>()
        for (i in 0 until songs.length()) {
            val item = songs.getJSONObject(i)
            val song = parseSongWeapi(item)
            if (song != null) {
                songList.add(song)
            }
        }

        val total = result.optInt("songCount", 0)
        val allPage = if (total > 0) (total + limit - 1) / limit else 0

        Log.d(TAG, "weapi 搜索完成: ${songList.size} 首, 总计 $total")
        return NeteaseSearchResult(
            list = songList,
            total = total,
            page = page,
            allPage = allPage
        )
    }

    /**
     * 解析 weapi 搜索（/weapi/search/get）结果中的单首歌曲。
     * 该接口返回结构与明文 api/search/get 一致：artists[] / album{} / duration，
     * 封面只在 album.picId 里（album.picUrl 为空），需用 buildWyCoverUrl 还原直链。
     */
    private fun parseSongWeapi(item: JsonObject): NeteaseSong? {
        return try {
            val id = item.optLong("id", 0).toString()
            if (id.isEmpty() || id == "0") return null

            val name = item.optString("name", "")
            val artists = item.optJSONArray("artists") ?: JsonArray(emptyList())
            val artist = if (artists.length() > 0) {
                artists.getJSONObject(0).optString("name", "")
            } else {
                ""
            }

            val album = item.optJSONObject("album") ?: JsonObject(emptyMap())
            val albumId = album.optString("id", "")
            val albumName = album.optString("name", "")
            // 封面：weapi 搜索仅给 picId，按网易云图片 token 算法还原直链
            val picUrl = buildWyCoverUrl(album.optString("picId", ""))
            val duration = item.optLong("duration", 0)

            // 解析音质信息
            val types = parseQualityInfo(item)

            NeteaseSong(
                songId = id,
                name = name,
                artist = artist,
                album = albumName,
                albumId = albumId,
                picUrl = picUrl,
                duration = duration,
                source = "wy",
                types = types
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 weapi 歌曲信息失败", e)
            null
        }
    }

    /**
     * 由网易云封面ID(picId)构造封面直链。
     * 网易云图片 CDN 做了 token 防护：token = base64url( md5( xor(picId字符串, MAGIC) ) )。
     * 明文/weapi 搜索接口只返回 picId（album.picUrl 已为空），必须用此方法还原封面地址。
     */
    private fun buildWyCoverUrl(picId: String?): String {
        if (picId.isNullOrBlank()) return ""
        val magic = "3go8&\$8*3*3h0k(2)2"
        val xored = picId.mapIndexed { i, c ->
            (c.code xor magic[i % magic.length].code).toChar()
        }.joinToString("")
        val md5 = MessageDigest.getInstance("MD5").digest(xored.toByteArray(Charsets.UTF_8))
        val token = Base64.encodeToString(md5, Base64.NO_WRAP)
            .replace("/", "_")
            .replace("+", "-")
        // 加尺寸参数：默认 300x300 太小，播放页大封面会模糊；800x800 更清晰
        return "https://p1.music.126.net/$token/$picId.jpg?param=800y800"
    }

    /**
     * 解析音质信息
     */
    private fun parseQualityInfo(item: JsonObject): List<NeteaseSongType> {
        val types = mutableListOf<NeteaseSongType>()

        // 检查音质信息
        val privilege = item.optJSONObject("privilege") ?: JsonObject(emptyMap())
        val maxBrLevel = privilege.optInt("maxBrLevel", 0)

        if (maxBrLevel >= 320000) {
            types.add(NeteaseSongType(type = "320k", fileSize = ""))
        }

        if (maxBrLevel >= 999000) {
            types.add(NeteaseSongType(type = "flac", fileSize = ""))
        }

        // 默认128k
        if (types.isEmpty()) {
            types.add(NeteaseSongType(type = "128k", fileSize = ""))
        }

        return types
    }

    /**
     * 将NeteaseSong转换为应用内Song数据模型
     */
    fun toSong(neteaseSong: NeteaseSong): Song {
        return Song(
            id = neteaseSong.songId,
            name = neteaseSong.name,
            singer = neteaseSong.artist,
            albumName = neteaseSong.album,
            albumId = neteaseSong.albumId,
            picUrl = neteaseSong.picUrl,
            duration = neteaseSong.duration,
            platform = MusicPlatform.WY,
            quality = neteaseSong.types.mapNotNull { type ->
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
 * 网易云搜索结果
 */
data class NeteaseSearchResult(
    val list: List<NeteaseSong>,
    val total: Int,
    val page: Int,
    val allPage: Int
)

/**
 * 网易云歌曲信息
 */
data class NeteaseSong(
    val songId: String,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val picUrl: String,
    val duration: Long,
    val source: String,
    val types: List<NeteaseSongType>
)

/**
 * 网易云歌曲音质信息
 */
data class NeteaseSongType(
    val type: String,
    val fileSize: String
)