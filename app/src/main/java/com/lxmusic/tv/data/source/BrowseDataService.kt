package com.lxmusic.tv.data.source

import android.util.Log
import com.lxmusic.tv.data.model.*
import com.lxmusic.tv.network.HttpClient
import com.lxmusic.tv.network.HttpResponse
import com.lxmusic.tv.network.NeteaseWeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.lxmusic.tv.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 宽松 JSON 解析统一走 [parseToObj] / 扩展函数（见 util/LenientJson.kt），
 * 规避 Android 13+ 系统 org.json 严格模式遇重复 key 抛异常、以及 R8 shrink 剔除宽松版 org.json 的问题。
 */

/**
 * 浏览数据服务（发现/歌单/排行）
 *
 * 按设置的默认音乐平台从内置 API 获取推荐歌单、歌单广场、排行榜数据。
 * 参考洛雪音乐的数据结构：
 * - 发现页：推荐歌单
 * - 歌单页：歌单广场列表
 * - 排行页：排行榜列表
 *
 * 平台支持情况（已实测）：
 * - 网易云：完整（榜单/榜单歌曲/歌单/歌单歌曲/推荐）
 * - QQ音乐：完整（榜单/歌单列表/歌单歌曲/歌单详情，均用极简 UA "Mozilla/5.0" 的 QQ_HEADERS；08-08 实测对照：极简 UA 秒放行约 50ms，完整 Chrome UA 反被挂起）
 * - 酷狗/酷我/咪咕：接口未实现，返回空
 */
class BrowseDataService(
    // 浏览数据（榜单/歌单）接口响应大（如 QQ 榜单一次返回 25 榜+每榜 3 首歌），全局 5s 在 TV 网络下易误伤 → 15s；
    // relaxHostnameVerification=true：mobilecdn.kugou.com 等平台 CDN 域名解析节点证书主机名不匹配
    // （HTTPS 校验失败，实测 mobilecdn 落到 *.cdn.myqcloud.com 证书），需放宽主机名（证书链仍校验）
    private val httpClient: HttpClient = HttpClient(15000, 15000, relaxHostnameVerification = true)
) {
    companion object {
        private const val TAG = "BrowseDataService"
        private const val WY_TOPLIST_URL = "https://music.163.com/api/toplist"
        // ⚠️ 网易云歌单列表：必须用 api/playlist/list（支持 offset 分页，实测 offset 生效）。
        // highquality/list 的 offset 参数实测无效（offset=0/30 返回完全相同列表），会导致歌单广场翻页全是重复，勿再使用。
        private const val WY_PLAYLIST_LIST_URL = "https://music.163.com/api/playlist/list"
        // 网易云 weapi 加密接口（游客态可返回完整歌单与歌曲列表）
        private const val WY_WEAPI_PLAYLIST_DETAIL = "https://music.163.com/weapi/v3/playlist/detail"
        // 歌曲详情（分批按 id 取完整歌曲，c 为 JSON 字符串数组）
        private const val WY_WEAPI_SONG_DETAIL = "https://music.163.com/weapi/v3/song/detail"
        // 榜单列表（weapi 加密版，实测 code 200 返回 63 榜，字段与明文完全一致；
        // 明文 api/toplist 被风控时用它兜底——带签名，风控远低于明文接口）
        private const val WY_WEAPI_TOPLIST = "https://music.163.com/weapi/toplist"
        // weapi 请求公共头（Referer/Origin 必带，否则返回 403）
        private val WY_WEAPI_HEADERS = mapOf(
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com/"
        )
        // weapi 请求尽量模拟浏览器（部分出口对 UA 敏感）
        private val WY_WEAPI_TOP_HEADERS = WY_WEAPI_HEADERS + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // QQ 热门排行榜（topid 为 QQ 音乐公开的榜单 id）
        private val QQ_TOP_IDS = listOf(
            Triple("4", "QQ音乐巅峰榜·热歌", "https://y.qq.com"),
            Triple("26", "飙升榜", "https://y.qq.com"),
            Triple("27", "热歌榜", "https://y.qq.com"),
            Triple("62", "流行指数榜", "https://y.qq.com"),
            Triple("36", "欧美金曲榜", "https://y.qq.com"),
            Triple("28", "新歌榜", "https://y.qq.com")
        )
        // ⚠️ QQ 接口 UA 必须用极简 "Mozilla/5.0"（用户 08-08 实测对照：08-06 版本极简 UA 100% 成功约 50ms；
        // 08-07 误改完整 Chrome UA 后 QQ 歌单失败率高 + 挂起卡住）。与"浏览器 UA 越全越安全"的直觉相反，
        // 腾讯对无 cookie 的 qzone/musicu 接口在极简 UA 下秒放行，完整 Chrome UA 反而被挂起风控。
        private val QQ_HEADERS = mapOf(
            "Referer" to "https://y.qq.com/",
            "User-Agent" to "Mozilla/5.0"
        )

        // 酷我常用榜单（参考 lxserver musicSdk/kw/leaderboard.js boardList）
        private val KW_TOP_IDS = listOf(
            Pair("93", "飙升榜"),
            Pair("16", "热歌榜"),
            Pair("145", "会员榜"),
            Pair("158", "抖音热歌榜"),
            Pair("187", "流行趋势榜"),
            Pair("26", "经典怀旧榜"),
            Pair("104", "华语榜"),
            Pair("182", "粤语榜"),
            Pair("22", "欧美榜"),
            Pair("184", "韩语榜"),
            Pair("183", "日语榜")
        )

        // 咪咕常用榜单（参考 lxserver musicSdk/mg/leaderboard.js boardList）
        private val MG_TOP_IDS = listOf(
            Pair("27553319", "新歌榜"),
            Pair("27186466", "热歌榜"),
            Pair("27553408", "原创榜"),
            Pair("75959118", "音乐风向榜"),
            Pair("76557036", "彩铃分贝榜"),
            Pair("76557745", "会员臻爱榜"),
            Pair("23189800", "港台榜"),
            Pair("23189399", "内地榜"),
            Pair("19190036", "欧美榜"),
            Pair("83176390", "国风金曲榜")
        )
    }

    /**
     * 获取排行榜列表
     * @param offset 分页偏移（每页 30 条）
     */
    suspend fun getRankingList(platform: MusicPlatform, offset: Int = 0): List<BrowseItem> = withContext(Dispatchers.IO) {
        try {
            when (platform) {
                // 网易云/酷狗榜单一次返回全部，用 drop/take 分页
                MusicPlatform.WY -> fetchWyRankings().drop(offset).take(30)
                MusicPlatform.TX -> fetchQqRankingList().drop(offset).take(30)
                MusicPlatform.KG -> fetchKgRankings().drop(offset).take(30)
                MusicPlatform.KW -> fetchKwRankings().drop(offset).take(30)
                MusicPlatform.MG -> fetchMgRankings().drop(offset).take(30)
                else -> emptyList()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取排行榜失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取排行榜/歌单的歌曲列表（网易云榜单和歌单都用 playlist/detail 接口）
     * @param limit 最多返回的歌曲数（默认 100；酷狗热门歌曲场景传 10 取榜单前 10 首）
     */
    suspend fun getRankingSongs(platform: MusicPlatform, id: String, page: Int = 1, pageSize: Int = 30): List<Song> = withContext(Dispatchers.IO) {
        try {
            when (platform) {
                // 网易云排行榜走明文接口（官方榜单完整且快）；歌单歌曲仍走 weapi
                MusicPlatform.WY -> fetchWyRankSongs(id)
                MusicPlatform.TX -> fetchQqTopSongs(id)
                MusicPlatform.KG -> fetchKgRankSongs(id, page, pageSize)
                MusicPlatform.KW -> fetchKwRankSongs(id)
                MusicPlatform.MG -> fetchMgRankSongs(id)
                else -> emptyList()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取榜单歌曲失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取平台热门歌曲（直接返回歌曲列表，而非榜单名列表）
     *
     * 说明：酷狗没有干净的「热门歌曲」独立接口，其排行榜列表接口返回的是一堆榜单名
     * （Top500 / 民谣版 / 飙升版 等），并非歌曲。这里与用户约定：取酷狗主榜单「TOP500」
     * 的前 [limit] 首作为热门歌曲展示（榜单本身 500 首过多，只取前 10 首即可）。
     * 其他平台暂未实现，返回空（调用方按原「排行榜」逻辑展示榜单名）。
     *
     * @param limit 热门歌曲数量（默认 10）
     */
    suspend fun getHotSongs(platform: MusicPlatform, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        if (platform != MusicPlatform.KG) return@withContext emptyList()
        try {
            val ranks = fetchKgRankings()
            if (ranks.isEmpty()) return@withContext emptyList()
            // 优先匹配「TOP500」主榜单；其次「热歌/热榜」；都没有则取第一个榜单
            val top = ranks.firstOrNull { it.name.contains("TOP500", ignoreCase = true) }
                ?: ranks.firstOrNull { it.name.contains("热歌", ignoreCase = true) || it.name.contains("热榜", ignoreCase = true) }
                ?: ranks.first()
            fetchKgRankSongs(top.id, 1, limit)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取酷狗热门歌曲失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取歌单广场列表
     * @param offset 分页偏移（每页 30 条）
     */
    suspend fun getPlaylistList(platform: MusicPlatform, offset: Int = 0): List<BrowseItem> = withContext(Dispatchers.IO) {
        try {
            when (platform) {
                MusicPlatform.WY -> fetchWyPlaylists(offset)
                MusicPlatform.TX -> fetchQqPlaylists(offset)
                MusicPlatform.KG -> fetchKgPlaylists(offset)
                MusicPlatform.KW -> fetchKwPlaylists(offset)
                MusicPlatform.MG -> fetchMgPlaylists(offset)
                else -> emptyList()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取歌单失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取歌单歌曲列表
     */
    suspend fun getPlaylistSongs(platform: MusicPlatform, id: String, page: Int = 1, pageSize: Int = 30): List<Song> = withContext(Dispatchers.IO) {
        try {
            when (platform) {
                MusicPlatform.WY -> fetchWyPlaylistSongs(id)
                // QQ 歌单歌曲（参考 lxserver：fcg_ucc_getcdinfo_byids_cp + new_format=1，用户网络下免登录）
                MusicPlatform.TX -> fetchQqPlaylistSongs(id)
                MusicPlatform.KG -> fetchKgPlaylistSongs(id, page, pageSize)
                MusicPlatform.KW -> fetchKwPlaylistSongs(id)
                MusicPlatform.MG -> fetchMgPlaylistSongs(id)
                else -> emptyList()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取歌单歌曲失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取歌单详情（名称/封面/歌曲数/创建者）
     * 用于 Web 端按歌单链接添加收藏：解析平台+ID 后拉取歌单元数据
     * 平台支持情况（实测）：
     * - 网易云：playlist/detail 返回 name/coverImgUrl/trackCount/creator.nickname
     * - QQ：fcg_ucc_getcdinfo_byids_cp 返回 dissname/logo/songnum/nickname
     * - 酷我：nplserver pl.svc getlistinfo 返回 title/uname/pic
     * - 酷狗/咪咕：接口无歌单名，名称兜底「平台歌单」，歌曲数从 getPlaylistSongs 获取
     */
    suspend fun getPlaylistDetail(platform: MusicPlatform, id: String): Playlist? = withContext(Dispatchers.IO) {
        try {
            when (platform) {
                MusicPlatform.WY -> fetchWyPlaylistDetail(id)
                MusicPlatform.TX -> fetchQqPlaylistDetail(id)
                MusicPlatform.KW -> fetchKwPlaylistDetail(id)
                MusicPlatform.KG -> fetchKgPlaylistDetail(id)
                MusicPlatform.MG -> fetchMgPlaylistDetail(id)
                else -> null
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取歌单详情失败: ${e.message}", e)
            null
        }
    }

    /**
     * 网易云歌单详情（weapi 加密接口，游客态即可返回完整信息）
     * 注意：weapi 返回的歌单字段直接挂在 playlist 下（与明文 api 的 result 不同）
     */
    private suspend fun fetchWyPlaylistDetail(id: String): Playlist? {
        val json = JSONObject().apply {
            put("id", id.toLongOrNull() ?: id)
            put("offset", 0)
            put("limit", 1000)
            put("total", true)
        }.toString()
        val (params, encSecKey) = NeteaseWeApi.encrypt(json)
        val response = httpClient.postForm(
            WY_WEAPI_PLAYLIST_DETAIL,
            mapOf("params" to params, "encSecKey" to encSecKey),
            headers = WY_WEAPI_HEADERS
        )
        if (!response.isSuccess) return null
        val jsonObj = JSONObject(response.body)
        val playlist = jsonObj.optJSONObject("playlist") ?: return null
        val name = playlist.optString("name", "")
        if (name.isBlank()) return null
        return Playlist(
            id = id,
            name = name,
            description = playlist.optString("description", "").ifBlank { null },
            coverUrl = playlist.optString("coverImgUrl", "").ifEmpty { null },
            songCount = playlist.optInt("trackCount", 0),
            platform = MusicPlatform.WY,
            creator = playlist.optJSONObject("creator")?.optString("nickname", "")?.ifBlank { null }
        )
    }

    /**
     * QQ 歌单详情（fcg_ucc_getcdinfo_byids_cp，cdlist[0] 含 dissname/logo/songnum/nickname）
     */
    private suspend fun fetchQqPlaylistDetail(disstid: String): Playlist? {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                "?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$disstid&loginUin=0&hostUin=0" +
                "&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        // 同 fetchQqPlaylistSongs：必须用极简 UA（QQ_HEADERS），完整 Chrome UA 反被挂起（08-08 实测对照）。
        val response = qqGetWithRetry(url, QQ_HEADERS + mapOf(
            "Referer" to "https://y.qq.com/n/yqq/playsquare/$disstid.html"
        ))
        if (!response.isSuccess) return null
        // 宽松解析（lenientJson）：同上规避 R8 下 org.json 严格模式崩溃
        val json = parseToObj(response.body)
        val cd = json.optJSONArray("cdlist")?.optJSONObject(0) ?: return null
        val name = cd.optString("dissname", "")
        if (name.isBlank()) return null
        return Playlist(
            id = disstid,
            name = name,
            description = cd.optString("desc", "").ifBlank { null },
            coverUrl = cd.optString("logo", "").replace("http://", "https://").ifEmpty { null },
            songCount = cd.optInt("songnum", 0),
            platform = MusicPlatform.TX,
            creator = cd.optString("nickname", "").ifBlank { null }
        )
    }

    /**
     * 酷我歌单详情（nplserver pl.svc getlistinfo，title/uname/pic）
     */
    private suspend fun fetchKwPlaylistDetail(id: String): Playlist? {
        var pid = id
        if (id.startsWith("digest-")) {
            val parts = id.removePrefix("digest-").split("__", limit = 2)
            if (parts.size == 2) pid = parts[1]
        }
        val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$pid&pn=0&rn=1" +
                "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "http://www.kuwo.cn/")
        )
        if (!response.isSuccess) return null
        val json = JSONObject(response.body)
        if (json.optString("result", "") != "ok") return null
        val title = json.optString("title", "")
        if (title.isBlank()) return null
        // 封面：pic 为相对路径（如 123/s4s6/1/xxx.jpg），拼域名
        val picRaw = json.optString("pic", "")
        val picUrl = when {
            picRaw.startsWith("http") -> picRaw
            picRaw.isNotBlank() -> "https://img1.kuwo.cn/star/albumcover/1000$picRaw"
            else -> null
        }
        return Playlist(
            id = pid,
            name = title,
            description = null,
            coverUrl = picUrl,
            songCount = json.optInt("total", 0).takeIf { it > 0 } ?: json.optInt("validtotal", 0),
            platform = MusicPlatform.KW,
            creator = json.optString("uname", "").ifBlank { null }
        )
    }

    /**
     * 酷狗歌单详情（页面无歌单名元数据，名称兜底「酷狗歌单」，歌曲数从歌曲列表获取）
     */
    private suspend fun fetchKgPlaylistDetail(id: String): Playlist? {
        val songs = fetchKgPlaylistSongs(id)
        if (songs.isEmpty()) return null
        return Playlist(
            id = id,
            name = "酷狗歌单 $id",
            description = null,
            coverUrl = songs.firstOrNull()?.picUrl,
            songCount = songs.size,
            platform = MusicPlatform.KG,
            creator = null
        )
    }

    /**
     * 咪咕歌单详情（queryMusicListSongs 无歌单名，名称兜底「咪咕歌单」，歌曲数取 totalCount）
     */
    private suspend fun fetchMgPlaylistDetail(id: String): Playlist? {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/user/queryMusicListSongs.do" +
                "?musicListId=$id&pageNo=1&pageSize=1"
        val response = httpClient.get(url, headers = MG_HEADERS)
        if (!response.isSuccess) return null
        val json = JSONObject(response.body)
        if (json.optString("code", "") != "000000") return null
        val total = json.optInt("totalCount", 0)
        if (total <= 0) return null
        val list = json.optJSONArray("list") ?: return null
        val firstPic = if (list.length() > 0) {
            val imgs = list.getJSONObject(0).optJSONArray("albumImgs")
            if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("img", "") else ""
        } else ""
        return Playlist(
            id = id,
            name = "咪咕歌单 $id",
            description = null,
            coverUrl = firstPic.ifEmpty { null },
            songCount = total,
            platform = MusicPlatform.MG,
            creator = null
        )
    }

    /**
     * 获取推荐歌单（发现页）
     * @param offset 分页偏移
     */
    suspend fun getRecommendList(platform: MusicPlatform, offset: Int = 0): List<BrowseItem> = withContext(Dispatchers.IO) {
        try {
            when (platform) {
                MusicPlatform.WY -> fetchWyRecommend(offset)
                MusicPlatform.TX -> fetchQqPlaylists(offset)
                MusicPlatform.KG -> fetchKgPlaylists(offset)
                MusicPlatform.KW -> fetchKwPlaylists(offset)
                MusicPlatform.MG -> fetchMgPlaylists(offset)
                else -> emptyList()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取推荐歌单失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取搜索页热门搜索关键词（仿 lxserver musicSdk 各平台 hotSearch）
     * 返回热词列表（歌手/歌曲混合）；接口不可用返回空列表，由 UI 层兜底内置关键词
     */
    suspend fun getHotSearch(platform: MusicPlatform): List<String> = withContext(Dispatchers.IO) {
        try {
            val result = when (platform) {
                // QQ：c.y.qq.com gethotkey（data.hotkey[].k）
                MusicPlatform.TX -> fetchQqHotSearch()
                // 酷狗：mobilecdn /api/v3/search/hot（data.info[].keyword，过滤带跳转的推广词）
                MusicPlatform.KG -> fetchKgHotSearch()
                // 网易云：POST music.163.com/api/search/hot，body type=1&limit=10（result.hots[].first）
                MusicPlatform.WY -> fetchWyHotSearch()
                // 酷我/咪咕等无专属热搜接口：回退到网易云真实热词，不使用假数据
                else -> fetchWyHotSearch()
            }
            // 主接口返回空（接口失败/限流但未抛异常，如 QQ 频繁切换平台时偶发）时，
            // 回退网易云真实热词，避免 UI 用固定假数据兜底（仅网易云自身为空才返回空）
            if (result.isEmpty() && platform != MusicPlatform.WY) {
                fetchWyHotSearch()
            } else {
                result
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取热门搜索失败($platform): ${e.message}", e)
            // 单平台接口异常时回退网易云真实热词；网易云自身失败时返回空，由 UI 兜底
            try {
                if (platform != MusicPlatform.WY) fetchWyHotSearch() else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * QQ 热搜（gethotkey.fcg，实测可用）
     *
     * 切换平台频繁时 QQ 接口偶发限流返回空，若直接退回网易云热词会让用户误以为
     * 「切了平台却没刷新、还是上一个平台的榜单」。这里做 2 次重试（间隔 250ms），
     * 尽量拿到 QQ 自己的真实热词，减少误以为没切换的错觉。
     */
    private suspend fun fetchQqHotSearch(): List<String> {
        repeat(2) { attempt ->
            val list = fetchQqHotKeyOnce()
            if (list.isNotEmpty()) return list
            if (attempt == 0) delay(250)
        }
        return emptyList()
    }

    /**
     * QQ 热搜单次请求（gethotkey.fcg）
     * 注意：必须带 uin=0&needNewCode=1&platform=yqq.json，否则返回 500
     */
    private suspend fun fetchQqHotKeyOnce(): List<String> {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/gethotkey.fcg" +
                "?format=json&ct=24&qqmusic_ver=1298&new_json=1&remoteplace=txt.yqq.center" +
                "&uin=0&needNewCode=1&platform=yqq.json"
        val response = httpClient.get(url, headers = QQ_HEADERS)
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val hotkey = json.optJSONObject("data")?.optJSONArray("hotkey") ?: return emptyList()
        return buildList {
            for (i in 0 until hotkey.length()) {
                val keyword = hotkey.getJSONObject(i).optString("k", "").trim()
                if (keyword.isNotEmpty()) add(keyword)
            }
        }.distinct()
    }

    /**
     * 酷狗热搜
     * 说明：酷狗 mobilecdn/api/v3/search/hot 接口无论加什么参数，
     * 都只返回 4 个「带 jumpurl 的推广活动词」（独家首发/儿歌大全/动漫/洗脑电音），
     * 没有真正的搜索热词，无法作为热搜数据使用。
     * 因此与 lx-music 对 KG 的处理一致：用酷狗**真实榜单名**作为热搜数据
     * （m.kugou.com/rank/list 返回 rank.list[].rankname，均为真实榜单），不使用假数据兜底。
     */
    private suspend fun fetchKgHotSearch(): List<String> {
        val url = "https://m.kugou.com/rank/list&json=true"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/"),
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val list = json.optJSONObject("rank")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val name = list.getJSONObject(i).optString("rankname", "").trim()
                if (name.isNotEmpty()) add(name)
            }
        }.distinct().take(12)
    }

    /**
     * 网易云热搜（POST /api/search/hot，body type=1&limit=10，实测可用）
     */
    private suspend fun fetchWyHotSearch(): List<String> {
        val response = httpClient.post(
            urlStr = "https://music.163.com/api/search/hot",
            body = "type=1&limit=10",
            contentType = "application/x-www-form-urlencoded",
            headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to "Mozilla/5.0")
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val hots = json.optJSONObject("result")?.optJSONArray("hots") ?: return emptyList()
        return buildList {
            for (i in 0 until hots.length()) {
                val keyword = hots.getJSONObject(i).optString("first", "").trim()
                if (keyword.isNotEmpty()) add(keyword)
            }
        }.distinct()
    }

    // ========== 网易云 ==========

    /**
     * 网易云榜单列表（三级兜底，防接口级风控）
     *
     * 注意：明文 api/toplist 在部分网络/IP 下会被网易云风控返回 403 或空 list
     * （同域的歌单广场接口 highquality/list 风控较松、正常），一旦返回空，原实现
     * 会直接让 UI 显示「该平台暂不支持或暂无数据」。这里按风控从松到严依次尝试：
     * 1) 明文 api/toplist（显式检查 code，避免非 200 的 json 被静默当空）；
     * 2) 结构完全兼容的明文 api/toplist/detail（同为 list[]，id/name/coverImgUrl/trackCount 一致）；
     * 3) weapi 加密版 /weapi/toplist（实测 code 200 返回 63 榜，字段与明文一致；
     *    带签名是网易云客户端真实接口，风控远低于明文，作为最终兜底）。
     */
    private suspend fun fetchWyRankings(): List<BrowseItem> {
        val fromPlain = fetchWyTopListFrom(WY_TOPLIST_URL)
        val fromDetail = if (fromPlain.isEmpty()) fetchWyTopListFrom("$WY_TOPLIST_URL/detail") else fromPlain
        return if (fromDetail.isEmpty()) fetchWyTopListViaWeApi() else fromDetail
    }

    /**
     * weapi 加密版榜单列表兜底（/weapi/toplist）
     */
    private suspend fun fetchWyTopListViaWeApi(): List<BrowseItem> {
        val (params, encSecKey) = NeteaseWeApi.encrypt("{}")
        val response = httpClient.postForm(
            WY_WEAPI_TOPLIST,
            mapOf("params" to params, "encSecKey" to encSecKey),
            headers = WY_WEAPI_TOP_HEADERS
        )
        if (!response.isSuccess) return emptyList()
        // 宽松解析：规避 Android 13+ org.json 严格模式遇重复 key 抛异常（R8 shrink 还会剔除宽松版 org.json）
        val obj = try {
            parseToObj(response.body)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "fetchWyTopListViaWeApi JSON 解析失败", e)
            return emptyList()
        }
        val code = obj.optInt("code", -1)
        if (code != 200) return emptyList()
        // weapi 榜单返回 data.list（与明文 list 结构兼容）；个别场景直接 list，兼容两种
        val list = (obj?.get("data") as? JsonObject)?.get("list") as? JsonArray
            ?: obj?.get("list") as? JsonArray
            ?: return emptyList()
        return buildList {
            for (elem in list) {
                val item = elem as? JsonObject ?: continue
                add(
                    BrowseItem(
                        id = item.optLong("id", 0).toString(),
                        name = item.optStr("name") ?: "",
                        coverUrl = item.optStr("coverImgUrl")?.takeIf { it.isNotEmpty() },
                        songCount = item.optInt("trackCount", 0).takeIf { it > 0 }
                            ?: item.optInt("updateFrequency", 0)
                    )
                )
            }
        }.filter { it.id != "0" && it.name.isNotBlank() }
    }

    /**
     * 从指定 URL 拉取网易云榜单列表（api/toplist 与 api/toplist/detail 共用解析）
     */
    private suspend fun fetchWyTopListFrom(url: String): List<BrowseItem> {
        val response = httpClient.get(
            url,
            headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to "Mozilla/5.0")
        )
        if (!response.isSuccess) return emptyList()
        // 宽松解析：规避 Android 13+ org.json 严格模式遇重复 key 抛异常（网易云 /api/toplist 响应中 updateFrequency 字段重复出现）
        val obj = try {
            parseToObj(response.body)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "fetchWyTopListFrom JSON 解析失败", e)
            return emptyList()
        }
        val code = obj.optInt("code", -1)
        // 明文接口偶发返回非 200（如风控返回的 json），必须显式检查 code 否则会被当空
        if (code != 200) return emptyList()
        val list = obj?.get("list") as? JsonArray ?: return emptyList()
        return buildList {
            for (elem in list) {
                val item = elem as? JsonObject ?: continue
                add(
                    BrowseItem(
                        id = item.optLong("id", 0).toString(),
                        name = item.optStr("name") ?: "",
                        coverUrl = item.optStr("coverImgUrl")?.takeIf { it.isNotEmpty() },
                        // trackCount 在两个接口都有；updateFrequency 在 api/toplist 恒为 null，仅作兜底
                        songCount = item.optInt("trackCount", 0).takeIf { it > 0 }
                            ?: item.optInt("updateFrequency", 0)
                    )
                )
            }
        }.filter { it.id != "0" && it.name.isNotBlank() }
    }

    private suspend fun fetchWyPlaylists(offset: Int = 0): List<BrowseItem> {
        // 歌单广场：api/playlist/list（order=hot 热门排序，支持 limit/offset 分页，实测 offset 生效）
        val url = "$WY_PLAYLIST_LIST_URL?cat=${URLEncoder.encode("全部", "UTF-8")}&order=hot&limit=30&offset=$offset"
        val response = httpClient.get(
            url,
            headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to "Mozilla/5.0")
        )
        if (!response.isSuccess) return emptyList()
        val json = parseToObj(response.body)
        val playlists = json.optJSONArray("playlists") ?: return emptyList()
        return buildList {
            for (i in 0 until playlists.length()) {
                val item = playlists.getJSONObject(i)
                add(
                    BrowseItem(
                        id = item.optLong("id", 0).toString(),
                        name = item.optString("name", ""),
                        coverUrl = item.optString("coverImgUrl", "").ifEmpty { null },
                        songCount = item.optInt("trackCount", 0),
                        description = item.optString("description", "").ifBlank { null }
                    )
                )
            }
        }.filter { it.id != "0" && it.name.isNotBlank() }
    }

    private suspend fun fetchWyRecommend(offset: Int = 0): List<BrowseItem> {
        // 推荐歌单 = 精品歌单分页
        return fetchWyPlaylists(offset)
    }

    /**
     * 网易云歌单歌曲列表（weapi 加密接口，分页拉取完整列表）
     *
     * 明文 api 接口对游客态最多只返回前 10 首且 trackCount 被截成可见数，
     * 这里改用 weapi：先取第一页拿到真实 trackCount，再按 offset 分页直到取完。
     */
    /**
     * 网易云歌单歌曲列表（weapi 加密接口）
     *
     * 注意：weapi/v3/playlist/detail 游客态 tracks 数组只返回前 10 首（offset/limit 被忽略），
     * 但 playlist.trackIds 是全量 id。因此流程为：
     * 1. detail 拿全量 trackIds
     * 2. 将 id 分批（每批 200）调 weapi/v3/song/detail（c 为 JSON 字符串）取完整歌曲
     */
    private suspend fun fetchWyPlaylistSongs(playlistId: String): List<Song> {
        // 1. 拿全量 trackIds
        val json = JSONObject().apply {
            put("id", playlistId.toLongOrNull() ?: playlistId)
            put("offset", 0)
            put("limit", 1000)
            put("total", true)
        }.toString()
        val (params, encSecKey) = NeteaseWeApi.encrypt(json)
        val response = httpClient.postForm(
            WY_WEAPI_PLAYLIST_DETAIL,
            mapOf("params" to params, "encSecKey" to encSecKey),
            headers = WY_WEAPI_HEADERS
        )
        if (!response.isSuccess) return emptyList()
        val jsonObj = parseToObj(response.body)
        val playlist = jsonObj.optJSONObject("playlist") ?: return emptyList()
        val trackIdsJson = playlist.optJSONArray("trackIds") ?: return emptyList()
        val ids = buildList {
            for (i in 0 until trackIdsJson.length()) {
                val id = trackIdsJson.getJSONObject(i).optLong("id", 0)
                if (id != 0L) add(id)
            }
        }
        if (ids.isEmpty()) return emptyList()

        // 2. 分批 song/detail 取完整歌曲
        val all = mutableListOf<Song>()
        for (start in ids.indices step 200) {
            val batch = ids.subList(start, minOf(start + 200, ids.size))
            val cArr = JSONArray().apply { batch.forEach { put(JSONObject().put("id", it)) } }
            val detailBody = JSONObject().apply {
                put("c", cArr.toString())
                put("url_version", 1)
            }.toString()
            val (p2, k2) = NeteaseWeApi.encrypt(detailBody)
            val detailResp = httpClient.postForm(
                WY_WEAPI_SONG_DETAIL,
                mapOf("params" to p2, "encSecKey" to k2),
                headers = WY_WEAPI_HEADERS
            )
            if (!detailResp.isSuccess) continue
            val detailJson = parseToObj(detailResp.body)
            val songs = detailJson.optJSONArray("songs")
            if (songs != null) all.addAll(parseWyTracks(songs))
        }
        return all.filter { it.id != "0" && it.name.isNotBlank() }.distinctBy { it.id }
    }

    /**
     * 解析网易云 tracks 数组为歌曲列表
     * 兼容两套字段：weapi（ar/al/dt）与明文 api（artists/album/duration）
     */
    private fun parseWyTracks(tracks: JsonArray): List<Song> {
        return buildList {
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val id = track.optLong("id", 0).toString()
                if (id == "0") continue
                val name = track.optString("name", "")
                // weapi 接口字段与明文 api 不同：歌手数组=ar、专辑对象=al、时长(毫秒)=dt
                // 同时兼容旧字段名 artists/album/duration，避免部分接口返回结构变化时丢字段
                val artists = track.optJSONArray("ar") ?: track.optJSONArray("artists") ?: JsonArray(emptyList())
                val singer = if (artists.length() > 0) artists.getJSONObject(0).optString("name", "") else ""
                val album = track.optJSONObject("al") ?: track.optJSONObject("album") ?: JsonObject(emptyMap())
                val albumName = album.optString("name", "").ifBlank { null }
                val albumId = album.optLong("id", 0).toString().takeIf { it != "0" }
                // 网易云封面动态缩放参数升级到 800（默认 300 太小，播放页大封面模糊）
                val rawPic = album.optString("picUrl", "")
                val picUrl = if (rawPic.isEmpty()) null else {
                    if (rawPic.contains("param=")) rawPic.replace(Regex("param=\\d+y\\d+"), "param=800y800")
                    else "$rawPic?param=800y800"
                }
                val duration = track.optLong("dt", 0).let { if (it == 0L) track.optLong("duration", 0) else it }
                add(
                    Song(
                        id = id,
                        name = name,
                        singer = singer,
                        albumName = albumName,
                        albumId = albumId,
                        picUrl = picUrl,
                        duration = duration,
                        platform = MusicPlatform.WY
                    )
                )
            }
        }
    }

    /**
     * 网易云排行榜歌曲（明文 api/playlist/detail，加载更快）
     * 官方榜单游客态明文接口返回完整列表（实测热歌榜 200 首、新歌榜 100 首），无需 weapi 加密。
     * 普通用户歌单明文只返回前 10 首，仍走 weapi 的 fetchWyPlaylistSongs。
     */
    private suspend fun fetchWyRankSongs(playlistId: String): List<Song> {
        val url = "https://music.163.com/api/playlist/detail?id=$playlistId"
        val response = httpClient.get(
            url,
            headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to "Mozilla/5.0")
        )
        if (!response.isSuccess) return emptyList()
        // 宽松解析：规避 Android 13+ org.json 严格模式遇重复 key 抛异常
        val json = parseToObj(response.body)
        if (json.optInt("code", -1) != 200) return emptyList()
        val tracks = json.optJSONObject("result")?.optJSONArray("tracks") ?: return emptyList()
        return parseWyTracks(tracks)
    }

    // ========== QQ音乐 ==========

    /**
     * QQ 接口指数退避重试的等待间隔（毫秒）：第 1→2 次、第 2→3 次。
     * 配合 [qqGetWithRetry] 的 maxAttempts=3 使用（仅前两项生效）。
     */
    private val QQ_RETRY_BACKOFF_MS = longArrayOf(600L, 1500L)

    /**
     * QQ 接口专用 GET：带指数退避重试。
     * 腾讯对「无 cookie」的请求偶发瞬时风控（短时间重复请求被限流），首请求被限后短暂退避再试通常能成功；
     * 仅对「HTTP 响应非成功(!isSuccess)」重试，成功立即返回。最多 [maxAttempts] 次，总退避≈2.1s，
     * 远小于 VM 层 15s 超时，不会与之撞车。用于歌单列表/歌单歌曲/歌单详情等易被限频的 QQ 接口。
     */
    private suspend fun qqGetWithRetry(url: String, headers: Map<String, String>, maxAttempts: Int = 3): HttpResponse {
        var resp = httpClient.get(url, headers = headers)
        var attempt = 1
        while (!resp.isSuccess && attempt < maxAttempts) {
            delay(QQ_RETRY_BACKOFF_MS[attempt - 1])
            resp = httpClient.get(url, headers = headers)
            attempt++
        }
        return resp
    }

    private suspend fun fetchQqPlaylists(offset: Int = 0): List<BrowseItem> {
        // 歌单列表（参考 lxserver musicSdk/tx/songList.js get_playlist_by_tag，返回列表与 lxserver 一致）
        val page = (offset / 36) + 1
        val body = JSONObject().apply {
            put("comm", JSONObject().apply { put("cv", 1602); put("ct", 20) })
            put("playlist", JSONObject().apply {
                put("method", "get_playlist_by_tag")
                put("param", JSONObject().apply {
                    put("id", 10000000)
                    put("sin", 36 * (page - 1))
                    put("size", 36)
                    put("order", 5)
                    put("cur_page", page)
                })
                put("module", "playlist.PlayListPlazaServer")
            })
        }
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=" +
                URLEncoder.encode(body.toString(), "UTF-8")
        // 腾讯对无 cookie 的 musicu.fcg 偶发瞬时风控（首次放行、短时间重复请求被限），失败自动退避重试
        val response = qqGetWithRetry(url, QQ_HEADERS)
        // 排查日志
        Log.d(TAG, "QQ歌单列表响应(${response.code})前200: ${response.body.take(200).replace("\n", " ")}")
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val playlist = json.optJSONObject("playlist") ?: return emptyList()
        if (playlist.optInt("code", -1) != 0) return emptyList()
        val data = playlist.optJSONObject("data") ?: return emptyList()
        val vPlaylist = data.optJSONArray("v_playlist") ?: return emptyList()
        return buildList {
            for (i in 0 until vPlaylist.length()) {
                val item = vPlaylist.getJSONObject(i)
                add(
                    BrowseItem(
                        id = item.optString("tid", ""),
                        name = item.optString("title", ""),
                        coverUrl = item.optString("cover_url_medium", "").ifEmpty { null },
                        songCount = item.optJSONArray("song_ids")?.length() ?: 0
                    )
                )
            }
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    /**
     * QQ 歌单歌曲（参考 lxserver musicSdk/tx/songList.js getListDetail）
     * fcg_ucc_getcdinfo_byids_cp + new_format=1 + Origin/Referer 头（用户网络下免登录可用）
     */
    private suspend fun fetchQqPlaylistSongs(disstid: String): List<Song> {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                "?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$disstid&loginUin=0&hostUin=0" +
                "&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        // 实测必须用极简 UA（QQ_HEADERS）：08-06 版本极简 UA 100% 成功约 50ms；
        // 08-07 误改完整 Chrome UA 后本接口失败率高 + 挂起卡住，08-08 对照实验确认极简 UA 才是正解。
        val response = qqGetWithRetry(url, QQ_HEADERS + mapOf(
            "Referer" to "https://y.qq.com/n/yqq/playsquare/$disstid.html"
        ))
        Log.d(TAG, "QQ歌单详情响应(${response.code})前200: ${response.body.take(200).replace("\n", " ")}")
        if (!response.isSuccess) return emptyList()
        // 宽松解析（lenientJson）：规避 Android 13+ R8 下系统 org.json 严格模式遇重复 key 崩溃
        val json = parseToObj(response.body)
        val cdlist = json.optJSONArray("cdlist") ?: return emptyList()
        if (cdlist.length() == 0) return emptyList()
        val cd = cdlist.getJSONObject(0)
        val songlist = cd.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (i in 0 until songlist.length()) {
                val item = songlist.getJSONObject(i)
                val songMid = item.optString("mid", "")
                if (songMid.isEmpty()) continue
                val name = item.optString("title", "")
                val singers = item.optJSONArray("singer") ?: JsonArray(emptyList())
                val singer = if (singers.length() > 0) singers.getJSONObject(0).optString("name", "") else ""
                val album = item.optJSONObject("album") ?: JsonObject(emptyMap())
                val albumMid = album.optString("mid", "")
                val albumName = album.optString("name", "").ifBlank { null }
                val file = item.optJSONObject("file") ?: JsonObject(emptyMap())
                val mediaMid = file.optString("media_mid", "")
                val picUrl = if (albumMid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T002R800x800M000${albumMid}.jpg" else null
                val id = if (mediaMid.isNotEmpty()) "${songMid}_$mediaMid" else songMid
                add(
                    Song(
                        id = id,
                        name = name,
                        singer = singer,
                        albumName = albumName,
                        albumId = albumMid,
                        picUrl = picUrl,
                        duration = item.optLong("interval", 0) * 1000,
                        platform = MusicPlatform.TX
                    )
                )
            }
        }
    }

    private suspend fun fetchQqTopSongs(topId: String): List<Song> {
        // 榜单歌曲（参考 lxserver musicSdk/tx/leaderboard.js：musicu.fcg POST GetDetail）
        val body = JSONObject().apply {
            put("toplist", JSONObject().apply {
                put("module", "musicToplist.ToplistInfoServer")
                put("method", "GetDetail")
                put("param", JSONObject().apply {
                    put("topid", topId.toIntOrNull() ?: 4)
                    put("num", 100)
                    put("period", "")
                })
            })
            put("comm", JSONObject().apply {
                put("uin", 0); put("format", "json"); put("ct", 20); put("cv", 1859)
            })
        }
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?data=${URLEncoder.encode(body.toString(), "UTF-8")}"
        val response = httpClient.get(url, headers = QQ_HEADERS)
        if (response.isSuccess) {
            val json = try { JSONObject(response.body) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { null }
            val tl = json?.optJSONObject("toplist")
            val data = tl?.optJSONObject("data")
            val song = data?.optJSONObject("song")
            val list = song?.optJSONArray("list")
            if (list != null && list.length() > 0) {
                return parseQqSongList(list)
            }
        }
        // 兜底：老接口 fcg_v8_toplist_cp
        val fallbackUrl = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$topId&format=json&inCharset=utf8&outCharset=utf-8"
        val fallback = httpClient.get(fallbackUrl, headers = QQ_HEADERS)
        if (!fallback.isSuccess) return emptyList()
        val json = JSONObject(fallback.body)
        val songlist = json.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (i in 0 until songlist.length()) {
                val item = songlist.getJSONObject(i)
                val data = item.optJSONObject("data") ?: continue
                val songMid = data.optString("songmid", "")
                if (songMid.isEmpty()) continue
                val name = data.optString("songname", "")
                val singers = data.optJSONArray("singer") ?: JSONArray()
                val singer = if (singers.length() > 0) singers.getJSONObject(0).optString("name", "") else ""
                val albumMid = data.optString("albummid", "")
                val album = data.optString("albumname", "").ifBlank { null }
                val mediaMid = data.optString("media_mid", "")
                    .ifEmpty { data.optString("strMediaMid", "") }
                val picUrl = if (albumMid.isNotEmpty()) "https://y.qq.com/music/photo_new/T002R800x800M000${albumMid}.jpg" else null
                val id = if (mediaMid.isNotEmpty()) "${songMid}_$mediaMid" else songMid
                add(
                    Song(
                        id = id,
                        name = name,
                        singer = singer,
                        albumName = album,
                        albumId = albumMid,
                        picUrl = picUrl,
                        duration = data.optLong("interval", 0) * 1000,
                        platform = MusicPlatform.TX
                    )
                )
            }
        }
    }

    /**
     * QQ 榜单列表（参考 lxserver musicSdk/tx/leaderboard.js：fcg_myqq_toplist.fcg）
     * 实测该接口返回约 25 个官方榜单（巅峰榜·流行指数/热歌/内地/欧美/香港/韩国/日本 + 飙升/新歌/说唱/电音/
     * 抖音热歌/DJ舞曲/国风/游戏/动漫/有声榜等）。
     * ⚠️ 榜单条目的 id 字段名是「id」（不是 topId，后者会全读成 0 被过滤，导致只显示硬编码兜底的 6 个）。
     * 失败时兜底硬编码热门榜单
     */
    private suspend fun fetchQqRankingList(): List<BrowseItem> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?g_tk=1928093487&inCharset=utf-8&outCharset=utf-8&notice=0&format=json&uin=0&needNewCode=1&platform=h5"
        val response = httpClient.get(url, headers = QQ_HEADERS)
        Log.d(TAG, "[QQ榜单] 接口 code=${response.code} isSuccess=${response.isSuccess}")
        if (response.isSuccess) {
            try {
                val json = JSONObject(response.body)
                // 实测 fcg_myqq_toplist.fcg 返回结构为 {"code":0,"data":{"topList":[...]}}，
                // topList 在 data 对象内（此前在根级查找恒为空 → 落兜底 6 个）
                val topList = json.optJSONObject("data")?.optJSONArray("topList") ?: JSONArray()
                Log.d(TAG, "[QQ榜单] topList 数组长度=${topList.length()}")
                if (topList.length() > 0) {
                    val items = buildList {
                        for (i in 0 until topList.length()) {
                            val item = topList.getJSONObject(i)
                            // 实测字段名是 id；兼容老接口的 topId
                            var topId = item.optInt("id", 0)
                            if (topId == 0) topId = item.optInt("topId", 0)
                            if (topId == 0) continue
                            add(
                                BrowseItem(
                                    id = topId.toString(),
                                    name = item.optString("topTitle", ""),
                                    coverUrl = item.optString("picUrl", "").ifEmpty { null },
                                    songCount = item.optInt("totalNum", 0)
                                )
                            )
                        }
                    }
                    if (items.isNotEmpty()) {
                        Log.d(TAG, "[QQ榜单] 解析出 ${items.size} 个榜单")
                        return items
                    }
                    Log.e(TAG, "[QQ榜单] 解析后为空（字段名/过滤问题？）")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e(TAG, "解析QQ榜单列表失败: ${e.message}")
            }
        }
        Log.e(TAG, "[QQ榜单] 接口失败/无数据，走硬编码兜底 ${QQ_TOP_IDS.size} 个")
        // 兜底：硬编码热门榜单
        return QQ_TOP_IDS.map { (id, name, _) ->
            BrowseItem(id = id, name = name, coverUrl = null)
        }
    }

    /**
     * 解析 musicu.fcg GetDetail 返回的歌曲列表（songmid/media_mid 格式）
     */
    private fun parseQqSongList(list: JSONArray): List<Song> {
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val songMid = item.optString("mid", "")
                if (songMid.isEmpty()) continue
                val name = item.optString("title", "")
                val singers = item.optJSONArray("singer") ?: JSONArray()
                val singer = if (singers.length() > 0) singers.getJSONObject(0).optString("name", "") else ""
                val album = item.optJSONObject("album") ?: JSONObject()
                val albumMid = album.optString("mid", "")
                val albumName = album.optString("name", "").ifBlank { null }
                val file = item.optJSONObject("file") ?: JSONObject()
                val mediaMid = file.optString("media_mid", "")
                val picUrl = if (albumMid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T002R800x800M000${albumMid}.jpg" else null
                val id = if (mediaMid.isNotEmpty()) "${songMid}_$mediaMid" else songMid
                add(
                    Song(
                        id = id,
                        name = name,
                        singer = singer,
                        albumName = albumName,
                        albumId = albumMid,
                        picUrl = picUrl,
                        duration = item.optLong("interval", 0) * 1000,
                        platform = MusicPlatform.TX
                    )
                )
            }
        }
    }

    // ========== 酷狗 ==========
    // KG 平台数据请求超时（连接+读取各 5s）：酷狗部分榜单/歌单接口响应慢、易长时间挂起，
    // 单独收紧到 5s 快速失败；其余平台仍走 HttpClient 默认 15s/30s，不受影响。
    /**
     * 酷狗排行榜列表（m.kugou.com/rank/list）
     */
    private suspend fun fetchKgRankings(): List<BrowseItem> {
        val url = "https://m.kugou.com/rank/list&json=true"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/"),
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val rank = json.optJSONObject("rank") ?: return emptyList()
        val list = rank.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val rankId = item.optLong("rankid", 0).toString()
                if (rankId == "0") continue
                val name = item.optString("rankname", "")
                // 封面模板 {size} 替换为 300
                val cover = item.optString("imgurl", "")
                    .ifEmpty { item.optString("img_cover", "") }
                    .replace("{size}", "1000")
                add(
                    BrowseItem(
                        id = rankId,
                        name = name,
                        coverUrl = cover.ifEmpty { null },
                        description = item.optString("intro", "").ifBlank { null }
                    )
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 酷狗榜单歌曲（mobilecdn.kugou.com/api/v3/rank/song，字段完整含 hash/album_id）
     * 支持分页：page/pagesize；兜底：m.kugou.com/rank/info/{rankid}
     */
    private suspend fun fetchKgRankSongs(rankId: String, page: Int = 1, pageSize: Int = 30): List<Song> {
        // 主接口：mobilecdn API（实测可用，字段与 parseKgSongs 匹配），按 page/pagesize 翻页
        val url = "https://mobilecdn.kugou.com/api/v3/rank/song?rankid=$rankId&page=$page&pagesize=$pageSize&format=json"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/"),
        )
        // message 含失败异常详情（SSL/DNS/超时等），用于排查 mobilecdn 在部分设备不可达的问题
        Log.d(TAG, "[KG榜单歌曲] page=$page 主接口 code=${response.code} isSuccess=${response.isSuccess} msg=${response.message}")
        if (response.isSuccess) {
            val json = try { JSONObject(response.body) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { null }
            val list = json?.optJSONObject("data")?.optJSONArray("info")
            if (list != null && list.length() > 0) {
                val songs = parseKgSongs(list)
                Log.d(TAG, "[KG榜单歌曲] page=$page 主接口解析 ${songs.size} 首")
                return songs
            }
            Log.e(TAG, "[KG榜单歌曲] page=$page 主接口无 data.info，尝试兜底")
        }
        // 兜底：m.kugou.com/rank/info/{rankid}?page={page}&json=true
        // 注意参数必须用 ? 分隔（曾误用 & 混进路径导致 page 失效，永远返回第一页）
        val fallbackUrl = "https://m.kugou.com/rank/info/$rankId?page=$page&json=true"
        val fallback = httpClient.get(
            fallbackUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/"),
        )
        Log.d(TAG, "[KG榜单歌曲] page=$page 兜底 code=${fallback.code} isSuccess=${fallback.isSuccess} msg=${fallback.message}")
        if (!fallback.isSuccess) return emptyList()
        val json = JSONObject(fallback.body)
        val songsObj = json.optJSONObject("songs") ?: return emptyList()
        val list = songsObj.optJSONArray("list") ?: return emptyList()
        return parseKgSongs(list)
    }

    /**
     * 酷狗歌单列表（m.kugou.com/plist/index，page 分页）
     */
    private suspend fun fetchKgPlaylists(offset: Int = 0): List<BrowseItem> {
        val page = (offset / 30) + 1
        val url = "https://m.kugou.com/plist/index&page=$page&json=true"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/"),
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val plist = json.optJSONObject("plist")?.optJSONObject("list") ?: return emptyList()
        val list = plist.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val specialId = item.optLong("specialid", 0).toString()
                if (specialId == "0") continue
                val name = item.optString("specialname", "")
                val cover = item.optString("imgurl", "").replace("{size}", "1000")
                add(
                    BrowseItem(
                        id = specialId,
                        name = name,
                        coverUrl = cover.ifEmpty { null },
                        songCount = item.optInt("songcount", 0)
                    )
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 酷狗歌单歌曲（分页）。
     * 主接口：pubsongscdn.kugou.com/v2/get_other_list_file（洛雪 PC 端同款，按 specialid+page+pagesize 翻页），
     * 需 KG H5 签名（web 密钥 + 参数按 key 排序拼接 + web 密钥，MD5）。
     * 返回 data.info 歌曲数组（扁平字段含 hash/name/singerinfo/album_id/timelen/cover）、data.count=总数。
     * 兜底：原 www.kugou.com/yy/special/single/{id}.html 内嵌 var data=[...]（仅首屏 ~30 首、无分页）。
     */
    private suspend fun fetchKgPlaylistSongs(specialId: String, page: Int = 1, pageSize: Int = 30): List<Song> {
        val params = mapOf(
            "srcappid" to "2919",
            "clientver" to "20000",
            "appid" to "1058",
            "type" to "0",
            "module" to "playlist",
            "page" to page.toString(),
            "pagesize" to pageSize.toString(),
            "specialid" to specialId,
        )
        val signature = kgSign(params)
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" } + "&signature=$signature"
        val url = "https://pubsongscdn.kugou.com/v2/get_other_list_file?$query"
        val response = httpClient.get(
            url,
            headers = mapOf(
                "Referer" to "https://m3ws.kugou.com/share/index.php",
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1",
                "dfid" to "-",
            ),
        )
        Log.d(TAG, "[KG歌单歌曲] page=$page 主接口 code=${response.code} isSuccess=${response.isSuccess} msg=${response.message}")
        if (response.isSuccess) {
            val json = try { JSONObject(response.body) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { null }
            val list = json?.optJSONObject("data")?.optJSONArray("info")
            if (list != null && list.length() > 0) {
                val songs = parseKgPlaylistSongs(list)
                Log.d(TAG, "[KG歌单歌曲] page=$page 主接口解析 ${songs.size} 首")
                return songs
            }
            Log.e(TAG, "[KG歌单歌曲] page=$page 主接口无 data.info，尝试 HTML 兜底")
        }
        return fetchKgPlaylistSongsHtml(specialId)
    }

    /**
     * 酷狗歌单歌曲兜底（www.kugou.com/yy/special/single/{specialid}.html 页面内嵌 var data=[...] JSON）。
     * 仅首屏内嵌约 30 首，后续靠页面 JS 异步加载，无法翻页（主接口才是分页方案）。
     */
    private suspend fun fetchKgPlaylistSongsHtml(specialId: String): List<Song> {
        val url = "https://www.kugou.com/yy/special/single/$specialId.html"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/"),
        )
        if (!response.isSuccess) return emptyList()
        return parseKgPlistHtmlSongs(response.body)
    }

    /**
     * 酷狗 H5 签名（infSign useH5+isCDN 方案）：web 密钥包裹「参数按 key 排序后 key=value 拼接（无分隔符）」再 MD5。
     * isCDN=true 时默认参数仅保留 srcappid/clientver（clienttime/mid/uuid/dfid 被剔除），与业务参数合并后签名。
     */
    private fun kgSign(params: Map<String, String>): String {
        val key = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        val concat = params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest((key + concat + key).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解析酷狗歌单歌曲（get_other_list_file 扁平结构：name/singerinfo/album_id/timelen/cover）
     */
    private fun parseKgPlaylistSongs(list: JSONArray): List<Song> {
        val songs = mutableListOf<Song>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val hash = item.optString("hash", "").ifEmpty { continue }
            val rawName = item.optString("name", "")
            val singerinfo = item.optJSONArray("singerinfo")
            val singer = if (singerinfo != null && singerinfo.length() > 0)
                singerinfo.getJSONObject(0).optString("name", "") else ""
            // KG 歌单 name 为「歌手 - 歌名」格式，去掉歌手前缀避免与 singer 重复显示
            val name = if (singer.isNotBlank() && rawName.startsWith("$singer - "))
                rawName.substring(singer.length + 3) else rawName
            val albumId = item.optString("album_id", "")
            val timelen = item.optLong("timelen", 0)
            // timelen 单位为毫秒（与 Song.duration 一致）；个别项缺失时回退 duration(秒)*1000
            val duration = if (timelen > 0) timelen else item.optLong("duration", 0) * 1000
            val cover = item.optString("cover", "").replace("{size}", "1000")
            val id = if (albumId.isNotBlank()) "${hash}_$albumId" else hash
            songs.add(
                Song(
                    id = id,
                    name = name,
                    singer = singer,
                    albumName = null,
                    albumId = albumId,
                    picUrl = cover.ifEmpty { null },
                    duration = duration,
                    platform = MusicPlatform.KG
                )
            )
        }
        return songs
    }

    /**
     * 从酷狗歌单页 HTML 提取 var data=[...] 歌曲数组
     */
    private fun parseKgPlistHtmlSongs(html: String): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            // 定位歌曲数据所在的 <script> 块（含 songname 的 script）
            val songIdx = html.indexOf("songname")
            if (songIdx < 0) return songs
            val scriptStart = html.lastIndexOf("<script", songIdx)
            val scriptEnd = html.indexOf("</script>", songIdx)
            if (scriptStart < 0 || scriptEnd < 0) return songs
            val seg = html.substring(scriptStart, scriptEnd)
            val dataStart = seg.indexOf("var data=")
            if (dataStart < 0) return songs
            // 括号深度匹配提取数组内容
            val contentStart = dataStart + "var data=".length
            var depth = 0
            var inString = false
            var escaped = false
            var contentEnd = -1
            for (j in contentStart until seg.length) {
                val c = seg[j]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                contentEnd = j
                                break
                            }
                        }
                    }
                }
            }
            if (contentEnd < 0) return songs
            val arrayJson = seg.substring(contentStart, contentEnd + 1)
            val arr = JSONArray(arrayJson)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val hash = item.optString("hash", "")
                    .ifEmpty { item.optString("HASH", "") }
                if (hash.isEmpty()) continue
                val name = item.optString("songname", "")
                val singer = item.optString("singername", "")
                val albumId = item.optString("album_id", "")
                val duration = item.optLong("duration", 0)
                    .takeIf { it > 0 } ?: item.optLong("timelength", 0)
                // 封面：trans_param.union_cover 模板（{size}→300）
                val transParam = item.optJSONObject("trans_param") ?: JSONObject()
                val picUrl = transParam.optString("union_cover", "")
                    .replace("{size}", "1000")
                val id = if (albumId.isNotBlank()) "${hash}_$albumId" else hash
                songs.add(
                    Song(
                        id = id,
                        name = name,
                        singer = singer,
                        albumName = null,
                        albumId = albumId,
                        picUrl = picUrl.ifEmpty { null },
                        duration = duration,
                        platform = MusicPlatform.KG
                    )
                )
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "解析酷狗歌单HTML歌曲失败: ${e.message}", e)
        }
        return songs
    }

    /**
     * 解析酷狗歌曲列表（hash/songname/singername 或 filename/filename）
     */
    private fun parseKgSongs(list: JSONArray): List<Song> {
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val hash = item.optString("hash", "")
                if (hash.isEmpty()) continue
                val name = item.optString("songname", "")
                val singer = item.optString("singername", "")
                val albumId = item.optString("album_id", "")
                val duration = item.optLong("duration", 0) * 1000
                // 歌手兜底：从 filename（"歌手 - 歌名"）解析
                val fallbackSinger = if (singer.isBlank()) {
                    item.optString("filename", "").substringBefore(" - ").trim()
                } else singer
                val fallbackName = if (name.isBlank()) {
                    val fn = item.optString("filename", "")
                    fn.substringAfter(" - ", fn).trim()
                } else name
                // 封面：trans_param.union_cover（{size}→300）
                val transParam = item.optJSONObject("trans_param") ?: JSONObject()
                val picUrl = transParam.optString("union_cover", "")
                    .replace("{size}", "1000")
                    .ifEmpty {
                        // 酷狗歌单 HTML 的 trans_param 里也有 union_cover
                        transParam.optString("unionCover", "").replace("{size}", "1000")
                    }
                // id 拼接 albumId（播放需要，格式: hash_albumId）
                val id = if (albumId.isNotBlank()) "${hash}_$albumId" else hash
                add(
                    Song(
                        id = id,
                        name = fallbackName,
                        singer = fallbackSinger,
                        albumName = null,
                        albumId = albumId,
                        picUrl = picUrl.ifEmpty { null },
                        duration = duration,
                        platform = MusicPlatform.KG
                    )
                )
            }
        }
    }

    // ========== 酷我 ==========
    // 参考 lxserver musicSdk/kw（wapi.kuwo.cn / nplserver.kuwo.cn / wbd.kuwo.cn，均免登录）

    /**
     * 酷我歌单列表（wapi.kuwo.cn getRcmPlayList，免 csrf）
     */
    private suspend fun fetchKwPlaylists(offset: Int = 0): List<BrowseItem> {
        val page = (offset / 36) + 1
        val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList" +
                "?loginUid=0&loginSid=0&appUid=76039576&pn=$page&rn=36&order=hot"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "http://www.kuwo.cn/")
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 200) return emptyList()
        val list = json.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val id = item.optString("id", "")
                if (id.isEmpty()) continue
                // id 带 digest 前缀（歌单详情需要）：digest-{digest}__{id}
                val digest = item.optString("digest", "8")
                val cover = item.optString("img", "").ifEmpty { null }
                add(
                    BrowseItem(
                        id = "digest-${digest}__${id}",
                        name = item.optString("name", ""),
                        coverUrl = cover,
                        songCount = item.optInt("total", 0),
                        description = item.optString("desc", "").ifBlank { null }
                    )
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 酷我歌单歌曲（nplserver.kuwo.cn pl.svc，免 csrf）
     * id 格式：digest-{digest}__{pid}
     */
    private suspend fun fetchKwPlaylistSongs(id: String): List<Song> {
        // 解析 digest 和 pid
        var pid = id
        var digest = "8"
        if (id.startsWith("digest-")) {
            val parts = id.removePrefix("digest-").split("__", limit = 2)
            if (parts.size == 2) {
                digest = parts[0]
                pid = parts[1]
            }
        }
        // digest 8 用 nplserver；其他（百度系）也用 nplserver 尝试
        val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$pid&pn=0&rn=1000" +
                "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "http://www.kuwo.cn/")
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optString("result", "") != "ok") return emptyList()
        val musiclist = json.optJSONArray("musiclist") ?: return emptyList()
        return buildList {
            for (i in 0 until musiclist.length()) {
                val item = musiclist.getJSONObject(i)
                val mid = item.optString("id", "")
                if (mid.isEmpty()) continue
                // 封面：pic/albumpic/web_albumpic_short（相对路径拼域名，参考 lxserver formatPic）
                val pic = item.optString("pic", "")
                    .ifEmpty { item.optString("albumpic", "") }
                    .ifEmpty { item.optString("prob_albumpic", "") }
                    .ifEmpty {
                        val short = item.optString("web_albumpic_short", "")
                        if (short.isNotEmpty()) "https://img4.kuwo.cn/star/albumcover/1000$short" else ""
                    }
                add(
                    Song(
                        id = mid,
                        name = item.optString("FSONGNAME", "").ifEmpty { item.optString("name", "") },
                        singer = item.optString("FARTIST", "").ifEmpty { item.optString("artist", "") },
                        albumName = item.optString("FALBUM", "").ifBlank { null },
                        albumId = null,
                        picUrl = pic.ifEmpty { null },
                        duration = item.optLong("duration", 0) * 1000,
                        platform = MusicPlatform.KW
                    )
                )
            }
        }
    }

    /**
     * 酷我榜单列表（硬编码，参考 lxserver musicSdk/kw/leaderboard.js）
     */
    private suspend fun fetchKwRankings(): List<BrowseItem> {
        return KW_TOP_IDS.map { (id, name) ->
            BrowseItem(id = id, name = name, coverUrl = null)
        }
    }

    /**
     * 酷我榜单歌曲（wbd.kuwo.cn bang_info，AES-128-ECB + MD5 签名）
     */
    private suspend fun fetchKwRankSongs(bangId: String): List<Song> {
        val paramJson = JSONObject().apply {
            put("uid", "")
            put("devId", "")
            put("sFrom", "kuwo_sdk")
            put("user_type", "AP")
            put("carSource", "kwplayercar_ar_6.0.1.0_apk_keluze.apk")
            put("id", bangId.toIntOrNull() ?: 16)
            put("pn", 0)
            put("rn", 100)
        }
        val url = "https://wbd.kuwo.cn/api/bd/bang/bang_info?${KwCrypto.buildParam(paramJson.toString())}"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        )
        if (!response.isSuccess) return emptyList()
        val raw = try {
            KwCrypto.decodeData(response.body)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "酷我榜单解密失败: ${e.message}")
            return emptyList()
        }
        val json = JSONObject(raw)
        if (json.optInt("code", -1) != 200) return emptyList()
        val musiclist = json.optJSONObject("data")?.optJSONArray("musiclist") ?: return emptyList()
        return buildList {
            for (i in 0 until musiclist.length()) {
                val item = musiclist.getJSONObject(i)
                val mid = item.optLong("id", 0).toString()
                if (mid == "0") continue
                // 封面：pic 字段（wbd 返回完整 URL）
                val pic = item.optString("pic", "")
                add(
                    Song(
                        id = mid,
                        name = item.optString("name", ""),
                        singer = item.optString("artist", ""),
                        albumName = item.optString("album", "").ifBlank { null },
                        albumId = item.optLong("albumId", 0).toString().takeIf { it != "0" },
                        picUrl = pic.ifEmpty { null },
                        duration = item.optLong("duration", 0) * 1000,
                        platform = MusicPlatform.KW
                    )
                )
            }
        }
    }

    // ========== 咪咕 ==========
    // 参考 lxserver musicSdk/mg（app.c.nf.migu.cn / c.musicapp.migu.cn，均免登录）

    private val MG_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36",
        "Referer" to "https://app.c.nf.migu.cn/",
        "channel" to "0146921"
    )

    /**
     * 咪咕歌单列表（playlist-square-recommend，递归提取 resType==2021 歌单项）
     */
    private suspend fun fetchMgPlaylists(offset: Int = 0): List<BrowseItem> {
        val page = (offset / 30) + 1
        val url = "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=$page"
        val response = httpClient.get(url, headers = MG_HEADERS)
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optString("code", "") != "000000") return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val items = mutableListOf<BrowseItem>()
        collectMgPlaylistItems(data, items)
        return items
    }

    /**
     * 递归提取咪咕歌单列表项（resType == 2021）
     */
    private fun collectMgPlaylistItems(obj: Any?, out: MutableList<BrowseItem>) {
        when (obj) {
            is JSONObject -> {
                if (obj.optString("resType", "") == "2021") {
                    val id = obj.optString("resId", "")
                    if (id.isNotEmpty()) {
                        out.add(
                            BrowseItem(
                                id = id,
                                name = obj.optString("txt", ""),
                                coverUrl = obj.optString("img", "").ifEmpty { null },
                                songCount = 0,
                                description = obj.optString("txt2", "").ifBlank { null }
                            )
                        )
                    }
                } else {
                    val it = obj.keys()
                    while (it.hasNext()) {
                        collectMgPlaylistItems(obj.opt(it.next()), out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    collectMgPlaylistItems(obj.opt(i), out)
                }
            }
        }
    }

    /**
     * 咪咕歌单歌曲（MIGUM2.0 queryMusicListSongs.do，list[] 字段完整）
     */
    private suspend fun fetchMgPlaylistSongs(playlistId: String): List<Song> {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/user/queryMusicListSongs.do" +
                "?musicListId=$playlistId&pageNo=1&pageSize=100"
        val response = httpClient.get(url, headers = MG_HEADERS)
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optString("code", "") != "000000") return emptyList()
        val list = json.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val songId = item.optString("songId", "")
                if (songId.isEmpty()) continue
                // 封面：albumImgs[0].img 或 img1（相对路径拼域名）
                val imgs = item.optJSONArray("albumImgs")
                val pic = if (imgs != null && imgs.length() > 0) {
                    imgs.getJSONObject(0).optString("img", "")
                } else {
                    val img1 = item.optString("img1", "")
                    if (img1.isNotEmpty()) "https://d.musicapp.migu.cn$img1" else ""
                }
                add(
                    Song(
                        id = songId,
                        name = item.optString("songName", ""),
                        singer = item.optString("singer", ""),
                        albumName = item.optString("album", "").ifBlank { null },
                        albumId = item.optString("albumId", ""),
                        picUrl = pic.ifEmpty { null },
                        duration = item.optLong("duration", 0) * 1000,
                        platform = MusicPlatform.MG
                    )
                )
            }
        }
    }

    /**
     * 咪咕榜单列表（硬编码，参考 lxserver musicSdk/mg/leaderboard.js boardList）
     */
    private suspend fun fetchMgRankings(): List<BrowseItem> {
        return MG_TOP_IDS.map { (id, name) ->
            BrowseItem(id = id, name = name, coverUrl = null)
        }
    }

    /**
     * 咪咕榜单歌曲（querycontentbyId.do，columnInfo.contents[].objectInfo）
     */
    private suspend fun fetchMgRankSongs(bangId: String): List<Song> {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do?columnId=$bangId&needAll=0"
        val response = httpClient.get(url, headers = MG_HEADERS)
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optString("code", "") != "000000") return emptyList()
        val contents = json.optJSONObject("columnInfo")?.optJSONArray("contents") ?: return emptyList()
        return buildList {
            for (i in 0 until contents.length()) {
                val obj = contents.getJSONObject(i).optJSONObject("objectInfo") ?: continue
                val songId = obj.optString("songId", "")
                if (songId.isEmpty()) continue
                // 歌手：artists[].name 拼 "、"
                val artists = obj.optJSONArray("artists") ?: JSONArray()
                val singer = buildString {
                    for (a in 0 until artists.length()) {
                        if (a > 0) append("、")
                        append(artists.getJSONObject(a).optString("name", ""))
                    }
                }
                val imgs = obj.optJSONArray("albumImgs")
                val pic = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("img", "") else ""
                add(
                    Song(
                        id = songId,
                        name = obj.optString("songName", ""),
                        singer = singer,
                        albumName = obj.optString("album", "").ifBlank { null },
                        albumId = obj.optString("albumId", ""),
                        picUrl = pic.ifEmpty { null },
                        duration = obj.optLong("duration", 0) * 1000,
                        platform = MusicPlatform.MG
                    )
                )
            }
        }
    }
}
