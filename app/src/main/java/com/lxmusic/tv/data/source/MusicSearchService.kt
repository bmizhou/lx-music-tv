package com.lxmusic.tv.data.source

import android.util.Log
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.model.*
import com.lxmusic.tv.network.HttpClient
import com.lxmusic.tv.network.KuwoApi
import com.lxmusic.tv.network.KugouApi
import com.lxmusic.tv.network.QQMusicApi
import com.lxmusic.tv.network.NeteaseApi
import com.lxmusic.tv.script.SourceExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 音乐搜索服务
 * 优先使用内置API（酷我搜索），同时支持自定义JS源搜索
 *
 * 架构参考洛雪音乐：
 * - 搜索：内置SDK直接HTTP调用（酷我搜索API）
 * - 播放URL：通过自定义源脚本的 request/musicUrl 事件获取
 * - 歌词/封面：通过自定义源或内置API获取
 */
class MusicSearchService(
    private val sourceManager: SourceManagerImpl
) {
    companion object {
        private const val TAG = "MusicSearchService"
    }

    // 内置各平台搜索API
    private val httpClient = HttpClient()
    private val kuwoApi = KuwoApi(HttpClient())
    private val kugouApi = KugouApi(HttpClient())
    private val qqMusicApi = QQMusicApi(HttpClient())
    private val neteaseApi = NeteaseApi(HttpClient())

    /**
     * 搜索音乐
     * 根据选择的平台使用对应的内置API，如果失败则尝试通过JS源搜索
     */
    suspend fun search(params: SearchParams, platform: MusicPlatform = MusicPlatform.KW): ApiResponse<SearchResult> = withContext(Dispatchers.IO) {
        try {
            // 根据平台选择使用不同的内置API
            Log.d(TAG, "使用内置${platform.displayName}API搜索: ${params.keyword}")
            
            val searchResult = when (platform) {
                MusicPlatform.KW -> {
                    val result = kuwoApi.search(
                        keyword = params.keyword,
                        page = params.page,
                        limit = params.pageSize
                    )
                    if (result.list.isNotEmpty()) {
                        result.list.map { kuwoApi.toSong(it) }
                    } else {
                        emptyList()
                    }
                }
                MusicPlatform.KG -> {
                    val result = kugouApi.search(
                        keyword = params.keyword,
                        page = params.page,
                        limit = params.pageSize
                    )
                    if (result.list.isNotEmpty()) {
                        result.list.map { kugouApi.toSong(it) }
                    } else {
                        emptyList()
                    }
                }
                MusicPlatform.TX -> {
                    val result = qqMusicApi.search(
                        keyword = params.keyword,
                        page = params.page,
                        limit = params.pageSize
                    )
                    if (result.list.isNotEmpty()) {
                        result.list.map { qqMusicApi.toSong(it) }
                    } else {
                        emptyList()
                    }
                }
                MusicPlatform.WY -> {
                    val result = neteaseApi.search(
                        keyword = params.keyword,
                        page = params.page,
                        limit = params.pageSize
                    )
                    if (result.list.isNotEmpty()) {
                        result.list.map { neteaseApi.toSong(it) }
                    } else {
                        emptyList()
                    }
                }
                MusicPlatform.MG -> {
                    // 咪咕音乐暂未实现，返回空列表
                    emptyList()
                }
                MusicPlatform.LOCAL -> {
                    // 本地音乐不支持搜索，返回空列表
                    emptyList()
                }
            }

            if (searchResult.isNotEmpty()) {
                Log.d(TAG, "内置API搜索成功: ${searchResult.size} 首歌曲")
                return@withContext ApiResponse.success(
                    SearchResult(
                        songs = searchResult,
                        total = searchResult.size,
                        page = params.page,
                        pageSize = params.pageSize
                    )
                )
            }

            // 方案2：内置API无结果，尝试通过JS源搜索
            Log.d(TAG, "内置API无结果，尝试JS源搜索")
            val enabledSource = sourceManager.getEnabledSource()
            if (enabledSource != null) {
                val result = sourceManager.searchMusicWithSource(
                    keyword = params.keyword,
                    page = params.page,
                    limit = params.pageSize
                )

                when (result) {
                    is SourceExecutionResult.SearchSuccess -> {
                        val songs = result.musicList.map { musicItem ->
                            musicItem.toSong()
                        }
                        return@withContext ApiResponse.success(
                            SearchResult(
                                songs = songs,
                                total = songs.size,
                                page = params.page,
                                pageSize = params.pageSize
                            )
                        )
                    }
                    is SourceExecutionResult.Error -> {
                        Log.w(TAG, "JS源搜索失败: ${result.message}")
                    }
                    else -> {}
                }
            }

            // 两种方式都无结果
            ApiResponse.success(
                SearchResult(
                    songs = emptyList(),
                    total = 0,
                    page = params.page,
                    pageSize = params.pageSize
                )
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "搜索异常", e)
            ApiResponse.error("搜索失败: ${e.message}")
        }
    }

    /**
     * 搜索歌单（按平台调用内置歌单搜索接口）
     *
     * 实测可用：
     * - 网易云：/api/search/get type=1000（result.playlists[]）
     * - 酷狗：mobilecdn /api/v3/search/special（data.info[]）
     * - QQ：老接口 client_music_search_songlist（lxserver tx/songList.js search，实测可用；
     *   musicu.fcg SearchPlayList 已失效 500003 风控）
     * - 酷我/咪咕：暂无可用接口
     */
    suspend fun searchPlaylist(
        params: SearchParams,
        platform: MusicPlatform = MusicPlatform.KW
    ): ApiResponse<List<Playlist>> = withContext(Dispatchers.IO) {
        try {
            val playlists = when (platform) {
                MusicPlatform.WY -> fetchWyPlaylistSearch(params.keyword, params.page, params.pageSize)
                MusicPlatform.KG -> fetchKgPlaylistSearch(params.keyword, params.page, params.pageSize)
                MusicPlatform.TX -> fetchQqPlaylistSearch(params.keyword, params.page, params.pageSize)
                else -> null
            }
            when {
                playlists == null -> ApiResponse.error("该平台暂不支持歌单搜索")
                playlists.isEmpty() -> ApiResponse.success(emptyList())
                else -> ApiResponse.success(playlists)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "歌单搜索异常", e)
            ApiResponse.error("歌单搜索失败: ${e.message}")
        }
    }

    /**
     * 网易云歌单搜索（/api/search/get?type=1000，实测可用）
     */
    private suspend fun fetchWyPlaylistSearch(keyword: String, page: Int, pageSize: Int): List<Playlist> {
        val offset = (page - 1) * pageSize
        val url = "https://music.163.com/api/search/get?s=${httpClient.encodeUrl(keyword)}" +
                "&type=1000&limit=$pageSize&offset=$offset"
        val response = httpClient.get(
            url,
            headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to "Mozilla/5.0")
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val playlists = json.optJSONObject("result")?.optJSONArray("playlists") ?: return emptyList()
        return buildList {
            for (i in 0 until playlists.length()) {
                val item = playlists.getJSONObject(i)
                val id = item.optLong("id", 0).toString()
                if (id == "0") continue
                val creator = item.optJSONObject("creator")
                add(
                    Playlist(
                        id = id,
                        name = item.optString("name", ""),
                        description = item.optString("description", "").ifBlank { null },
                        coverUrl = item.optString("coverImgUrl", "").ifEmpty { null },
                        songCount = item.optInt("trackCount", 0),
                        platform = MusicPlatform.WY,
                        creator = creator?.optString("nickname", null)
                    )
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 酷狗歌单搜索（mobilecdn /api/v3/search/special，实测可用）
     */
    private suspend fun fetchKgPlaylistSearch(keyword: String, page: Int, pageSize: Int): List<Playlist> {
        val url = "http://mobilecdn.kugou.com/api/v3/search/special?keyword=${httpClient.encodeUrl(keyword)}" +
                "&page=$page&pagesize=$pageSize&format=json"
        val response = httpClient.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.kugou.com/")
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        val info = json.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until info.length()) {
                val item = info.getJSONObject(i)
                val id = item.optLong("specialid", 0).toString()
                if (id == "0") continue
                val cover = item.optString("imgurl", "").replace("{size}", "1000")
                add(
                    Playlist(
                        id = id,
                        name = item.optString("specialname", ""),
                        description = item.optString("intro", "").ifBlank { null },
                        coverUrl = cover.ifEmpty { null },
                        songCount = item.optInt("songcount", 0),
                        platform = MusicPlatform.KG,
                        creator = item.optString("nickname", "").ifBlank { null }
                    )
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * QQ 歌单搜索（c.y.qq.com/soso/fcgi-bin/client_music_search_songlist，实测可用）
     * 参考 lxserver musicSdk/tx/songList.js search：
     * - 参数：page_no（0 基）/num_per_page/query/remoteplace=txt.yqq.playlist
     * - 头：IE9 UA + Referer: http://y.qq.com/portal/search.html
     * - 返回：body.code==0，data.list[] 字段 dissid/dissname/creator.name/imgurl/song_count/introduction
     * - dissname 含 HTML 实体（如 &#32;&#58;），需解码
     */
    private suspend fun fetchQqPlaylistSearch(keyword: String, page: Int, pageSize: Int): List<Playlist> {
        val url = "http://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist" +
                "?page_no=${page - 1}&num_per_page=$pageSize&format=json" +
                "&query=${httpClient.encodeUrl(keyword)}&remoteplace=txt.yqq.playlist" +
                "&inCharset=utf8&outCharset=utf-8"
        val response = httpClient.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)",
                "Referer" to "http://y.qq.com/portal/search.html"
            )
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 0) return emptyList()
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val id = item.optLong("dissid", 0).toString()
                if (id == "0") continue
                val cover = item.optString("imgurl", "")
                    .replace("http://", "https://")
                val creator = item.optJSONObject("creator")
                add(
                    Playlist(
                        id = id,
                        name = decodeHtmlEntities(item.optString("dissname", "")),
                        description = decodeHtmlEntities(item.optString("introduction", "")).ifBlank { null },
                        coverUrl = cover.ifEmpty { null },
                        songCount = item.optInt("song_count", 0),
                        platform = MusicPlatform.TX,
                        creator = creator?.optString("name", "")?.let { decodeHtmlEntities(it) }?.ifBlank { null }
                    )
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 解码 HTML 实体（lxserver decodeName 简化版）
     * 先解码数字实体（&#DDD; / &#xHH;），再解码命名实体，最后 &amp;
     * 循环解码 3 轮，处理嵌套实体（如 &#38;&#35;160&#59; → &amp;#160; → 空格）
     */
    private fun decodeHtmlEntities(text: String): String {
        var result = text
        repeat(3) {
            val decoded = result
                .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                    m.groupValues[1].toIntOrNull(16)?.let { it.toChar().toString() } ?: m.value
                }
                .replace(Regex("&#(\\d+);")) { m ->
                    m.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: m.value
                }
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
            if (decoded == result) return result
            result = decoded
        }
        return result
    }

    /**
     * 获取音乐播放URL（2.8 音质交由源自行处理，不做应用层降级重试）
     * 仅按请求音质单次尝试（按优先级遍历 JS 源），
     * 避免逐级降级重试把网络超时放大 N 倍（如 8s × 4 级 = 32s）。
     * 是否降级/实际音质由 JS 源脚本内部决定（洛雪协议行为），应用记录请求成功的音质。
     * @param excludeSourceIds 本轮播放尝试中已失败的源 id 集合：这些源返回的 URL 已被实测无法播放（403 等），
     * 本轮按顺序继续尝试下一个源时跳过它们（下次播放重新按顺序从头尝试）
     * @param onSourceLoadFailed 某个源解析播放 URL 失败时回调：(失败源名称, 下一个要尝试的源名称或 null)
     * @param onSourceTrying 2.8 开始尝试某个源时回调（源名称），用于 UI 展示「正在尝试源 X」进度反馈
     */
    suspend fun getMusicUrl(
        song: Song,
        quality: AudioQuality = AudioQuality.QUALITY_320K,
        excludeSourceIds: Set<String> = emptySet(),
        onSourceLoadFailed: (failedSourceName: String, nextSourceName: String?) -> Unit = { _, _ -> },
        onSourceTrying: ((sourceName: String) -> Unit)? = null
    ): ApiResponse<MusicPlayResult> = withContext(Dispatchers.IO) {
        try {
            val result = tryFetchUrlOnce(song, quality, excludeSourceIds, onSourceLoadFailed, onSourceTrying)
            if (result != null) {
                ApiResponse.success(result)
            } else {
                ApiResponse.error("获取播放URL失败: 没有可用的播放源")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "获取播放URL异常", e)
            ApiResponse.error("获取播放URL失败: ${e.message}")
        }
    }

    /**
     * 单次音质尝试：按优先级遍历 JS 源（2.8 取消内置 API 播放兜底）
     * @return 播放结果（URL + 来源源 id）；JS 源全部失败返回 null
     */
    private suspend fun tryFetchUrlOnce(
        song: Song,
        quality: AudioQuality,
        excludeSourceIds: Set<String>,
        onSourceLoadFailed: (String, String?) -> Unit,
        onSourceTrying: ((String) -> Unit)? = null
    ): MusicPlayResult? {
        // 2.8 恢复播放 URL 短期缓存（歌曲维度 key，URL 与歌曲绑定）：命中直接返回。
        // 意义：① 缓解解析接口按频率风控；② 断网时命中缓存 URL → play 走 CacheDataSource
        // 读 SimpleCache 音频缓存，实现真离线播放。URL 失效（过期/坏链）由 onPlaybackError
        // 移除缓存并重新解析（见 MainViewModel），不会死循环。
        val urlCacheKey = CacheManager.songCacheKey(song, quality)
        val cachedUrl = try {
            CacheManager.getUrl(urlCacheKey)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            null
        }
        if (!cachedUrl.isNullOrEmpty()) {
            Log.i(TAG, "命中播放 URL 缓存: ${song.name} key=$urlCacheKey")
            // sourceId=null：缓存未记录来源源；播放失败时走 onPlaybackError 的
            // badSourceId==null 分支（移除缓存 + 重新解析换源）
            return MusicPlayResult(cachedUrl, quality, null)
        }

        // 按优先级遍历启用的 JS 源（先启用/先加载的优先级最高），
        // 当前源获取失败时自动切换到下一个源尝试（传入 source.id，逐个真正尝试）
        val sourceCode = song.platform.key
        // 先过滤本轮已失败源，得到实际要尝试的候选列表（保持导入顺序），便于取「下一个源」名称。
        // 2.8 双保险：getSourcesForPlatform 可能因源 id 变化/平台配置缺失把不支持平台的源误判为全平台，
        // 遍历时再显式校验一次「空配置=全平台 或 含当前平台」，避免未勾选平台的源被尝试
        val candidates = sourceManager.getSourcesForPlatform(sourceCode)
            .filter { it.id !in excludeSourceIds }
            .filter { source ->
                val platforms = try {
                    sourceManager.getSourcePlatforms(source.id)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                    emptySet()
                }
                platforms.isEmpty() || platforms.contains(sourceCode)
            }
        candidates.forEachIndexed { index, source ->
            // 2.8 开始尝试前回调（UI 展示「正在尝试源 X」，避免聚合源长等待无反馈）
            onSourceTrying?.invoke(source.name)
            val result = sourceManager.getMusicUrlWithSource(
                musicId = song.id,
                quality = quality,
                source = sourceCode,
                sourceId = source.id,
                // 2.8 传歌名/歌手：JS 源按歌名搜索需要（缺失报「没歌名搜不了」）
                songName = song.name,
                singer = song.singer
            )
            when (result) {
                is SourceExecutionResult.UrlSuccess -> {
                    if (result.url.isNotEmpty()) {
                        // 2.8 解析成功写入 URL 缓存（下次直接命中，断网可离线播放）
                        try {
                            CacheManager.putUrl(urlCacheKey, result.url)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                            Log.w(TAG, "写入 URL 缓存失败: ${e.message}")
                        }
                        return MusicPlayResult(result.url, quality, source.id)
                    }
                    // 2.8 空 URL 视为失败：回调失败信息并切下一个源（原来静默跳过，无提示且少试一个源）
                    Log.w(TAG, "JS源[${source.name}]返回空URL，尝试下一个源")
                    onSourceLoadFailed(source.name, candidates.getOrNull(index + 1)?.name)
                }
                is SourceExecutionResult.Error -> {
                    Log.w(TAG, "JS源[${source.name}]获取URL失败，尝试下一个源: ${result.message}")
                    // 回调失败信息（失败源名 + 下一个要尝试的源名，没有则 null）
                    onSourceLoadFailed(source.name, candidates.getOrNull(index + 1)?.name)
                }
                else -> {}
            }
        }
        return null
    }

    /**
     * 获取歌词
     */
    suspend fun getLyrics(song: Song): ApiResponse<LyricsInfo> = withContext(Dispatchers.IO) {
        try {
            // 先尝试通过自定义源获取（按优先级遍历，失败自动切换下一个源）
            for (source in sourceManager.getSourcesForPlatform(song.platform.key)) {
                // 2.8 传平台/歌名/歌手：JS 源歌词此前缺 name 且 source 硬编码，全部失败
                val result = sourceManager.getLyricWithSource(
                    song.id, song.platform.key, song.name, song.singer
                )
                when (result) {
                    is SourceExecutionResult.LyricSuccess -> {
                        val lyricsInfo = parseLyricsResponse(result.lyric)
                        return@withContext ApiResponse.success(lyricsInfo)
                    }
                    else -> {}
                }
            }

            // 使用内置API获取歌词
            Log.d(TAG, "使用内置${song.platform.displayName}API获取歌词: ${song.id}")
            
            val lyricText = when (song.platform) {
                MusicPlatform.KW -> {
                    kuwoApi.getLyric(song.id, song.name, song.singer)
                }
                MusicPlatform.KG -> {
                    // 酷狗需要hash（id 格式为 hash_albumId）
                    val parts = song.id.split("_")
                    if (parts.isNotEmpty()) {
                        kugouApi.getLyric(hash = parts[0], albumId = parts.getOrNull(1), keyword = song.name)
                    } else {
                        null
                    }
                }
                MusicPlatform.TX -> {
                    // QQ音乐需要songMid（id 格式为 songMid_mediaMid）
                    val songMid = song.id.split("_")[0]
                    qqMusicApi.getLyric(songMid)
                }
                MusicPlatform.WY -> {
                    neteaseApi.getLyric(song.id)
                }
                MusicPlatform.MG -> {
                    // 咪咕音乐暂未实现
                    null
                }
                MusicPlatform.LOCAL -> {
                    null
                }
            }

            if (!lyricText.isNullOrEmpty()) {
                return@withContext ApiResponse.success(parseLyricsResponse(lyricText))
            }

            // 跨平台兜底：KG/KW 等内置歌词失败时，用歌名+歌手去 QQ 搜索拿歌词（容错提升覆盖率）
            if (!song.name.isNullOrBlank()) {
                val cross = tryCrossPlatformLyric(song.name, song.singer)
                if (!cross.isNullOrEmpty()) {
                    return@withContext ApiResponse.success(parseLyricsResponse(cross))
                }
            }

            ApiResponse.error("获取歌词失败")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ApiResponse.error("获取歌词失败: ${e.message}")
        }
    }

    /**
     * 跨平台歌词兜底：内置主流平台（QQ）按歌名+歌手搜索取 songMid，再拿歌词。
     * 用于内置 KG/KW 等歌词失败时容错，提升覆盖率。
     */
    private suspend fun tryCrossPlatformLyric(name: String, singer: String?): String? = withContext(Dispatchers.IO) {
        try {
            val keyword = if (!singer.isNullOrBlank()) "$singer $name" else name
            val result = qqMusicApi.search(keyword, limit = 5)
            val first = result.list.firstOrNull() ?: return@withContext null
            qqMusicApi.getLyric(first.songMid)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "跨平台歌词兜底失败: ${e.message}")
            null
        }
    }

    /**
     * 获取歌曲封面
     */
    suspend fun getSongPic(song: Song): ApiResponse<String> = withContext(Dispatchers.IO) {
        try {
            // 先尝试通过自定义源获取（按优先级遍历，失败自动切换下一个源）
            for (source in sourceManager.getSourcesForPlatform(song.platform.key)) {
                val result = sourceManager.getPicUrlWithSource(song.id)
                when (result) {
                    is SourceExecutionResult.PicSuccess -> {
                        if (result.picUrl.isNotEmpty()) {
                            return@withContext ApiResponse.success(result.picUrl)
                        }
                    }
                    else -> {}
                }
            }

            // 使用内置API获取封面
            Log.d(TAG, "使用内置${song.platform.displayName}API获取封面: ${song.id}")
            
            val picUrl = when (song.platform) {
                MusicPlatform.KW -> {
                    kuwoApi.getPicUrl(song.id)
                }
                MusicPlatform.KG -> {
                    // 酷狗需要hash
                    val parts = song.id.split("_")
                    if (parts.isNotEmpty()) {
                        kugouApi.getPicUrl(parts[0])
                    } else {
                        null
                    }
                }
                MusicPlatform.TX -> {
                    // QQ音乐需要albumMid
                    val parts = song.id.split("_")
                    if (parts.size >= 2) {
                        qqMusicApi.getPicUrl(parts[1])
                    } else {
                        null
                    }
                }
                MusicPlatform.WY -> {
                    // 网易云音乐，封面URL在song中
                    neteaseApi.getPicUrl(song.picUrl ?: "")
                }
                MusicPlatform.MG -> {
                    // 咪咕音乐暂未实现
                    null
                }
                MusicPlatform.LOCAL -> {
                    null
                }
            }

            if (!picUrl.isNullOrEmpty()) {
                return@withContext ApiResponse.success(picUrl)
            }

            ApiResponse.error("获取封面失败")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ApiResponse.error("获取封面失败: ${e.message}")
        }
    }

    /**
     * 获取推荐歌单（暂未实现）
     */
    suspend fun getRecommendPlaylists(
        page: Int = 1,
        pageSize: Int = 20
    ): ApiResponse<List<Playlist>> = withContext(Dispatchers.IO) {
        ApiResponse.success(emptyList())
    }

    /**
     * 获取排行榜（暂未实现）
     */
    suspend fun getRankings(): ApiResponse<List<Ranking>> = withContext(Dispatchers.IO) {
        ApiResponse.success(emptyList())
    }

    /**
     * 获取搜索建议（暂未实现）
     */
    suspend fun getSearchSuggestions(keyword: String): ApiResponse<List<SearchSuggestion>> = withContext(Dispatchers.IO) {
        ApiResponse.success(emptyList())
    }

    /**
     * 解析歌词响应
     */
    private fun parseLyricsResponse(lyricText: String): LyricsInfo {
        return try {
            if (lyricText.trimStart().startsWith("{")) {
                val json = JSONObject(lyricText)
                LyricsInfo(
                    lyric = json.optString("lyric", null),
                    tlyric = json.optString("tlyric", null),
                    rlyric = json.optString("rlyric", null),
                    lxlyric = json.optString("lxlyric", null)
                )
            } else {
                LyricsInfo(
                    lyric = lyricText,
                    tlyric = null,
                    rlyric = null,
                    lxlyric = null
                )
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            LyricsInfo(
                lyric = lyricText,
                tlyric = null,
                rlyric = null,
                lxlyric = null
            )
        }
    }
}

/**
 * MusicItem 转 Song 扩展函数
 */
private fun MusicItem.toSong(): Song {
    return Song(
        id = id,
        name = name,
        singer = artist,
        albumName = album,
        albumId = null,
        picUrl = picUrl,
        duration = duration,
        platform = try {
            MusicPlatform.valueOf(platform.uppercase())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            MusicPlatform.LOCAL
        },
        quality = listOf(AudioQuality.QUALITY_128K, AudioQuality.QUALITY_320K, AudioQuality.FLAC)
    )
}

/**
 * 2.8 播放结果：URL + 实际播放音质 + 来源播放源 id。
 * sourceId 用于播放失败后把该源加入黑名单，重试时跳过它尝试下一个源
 */
data class MusicPlayResult(
    val url: String,
    val quality: AudioQuality,
    val sourceId: String? = null
)
