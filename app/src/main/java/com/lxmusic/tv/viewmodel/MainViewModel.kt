package com.lxmusic.tv.viewmodel

import android.app.Application
import android.content.ComponentName
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lxmusic.tv.LXMusicApplication
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.model.*
import com.lxmusic.tv.presentation.theme.LX_THEME_PREFS
import com.lxmusic.tv.data.source.BrowseDataService
import com.lxmusic.tv.data.source.MusicSearchService
import com.lxmusic.tv.data.source.SearchSuggestEngine
import com.lxmusic.tv.data.source.SourceManagerImpl
import com.lxmusic.tv.script.SourceExecutionResult
import com.lxmusic.tv.service.http.HttpServer
import com.lxmusic.tv.service.http.PlaylistAddResult
import com.lxmusic.tv.service.http.PlaylistManager
import com.lxmusic.tv.service.player.PlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 主ViewModel
 * 连接UI和数据层，管理所有应用状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // 持久化文件名与 Theme.kt 的主题状态共用（LX_THEME_PREFS）
        private const val PREFS_NAME = LX_THEME_PREFS
        // 搜索历史存储 key / 分隔符 / 上限
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val HISTORY_SEPARATOR = "\u0001"
        private const val SEARCH_HISTORY_MAX = 10
        // 2.9 播放失败尝试切换平台开关（lx_settings；设置-播放源管理页切换，MainActivity/本类共用）
        const val KEY_PLATFORM_SWITCH = "play_platform_switch_enabled"
    }

    private val app = application as LXMusicApplication
    private val sourceManager = app.sourceManager
    private val dataStoreManager = app.dataStoreManager
    private val searchService = MusicSearchService(sourceManager)
    private val browseDataService = BrowseDataService()
    private val suggestEngine = SearchSuggestEngine()

    // 搜索联想防抖任务（参考 blbl：输入变化 200ms 防抖 + 取消竞态）
    private var suggestJob: kotlinx.coroutines.Job? = null

    // ========== 浏览数据状态（发现/歌单/排行） ==========
    private val _browseItems = MutableStateFlow<List<BrowseItem>>(emptyList())
    val browseItems: StateFlow<List<BrowseItem>> = _browseItems.asStateFlow()
    private val _browseSongs = MutableStateFlow<List<Song>>(emptyList())
    val browseSongs: StateFlow<List<Song>> = _browseSongs.asStateFlow()
    private val _browseLoading = MutableStateFlow(false)
    val browseLoading: StateFlow<Boolean> = _browseLoading.asStateFlow()
    /** 榜单/歌单歌曲加载失败提示（超时或异常）；正常空歌单不置此值，UI 显示空态文案 */
    private val _browseSongsError = MutableStateFlow<String?>(null)
    val browseSongsError: StateFlow<String?> = _browseSongsError.asStateFlow()
    /** 当前正在进行的「歌曲列表」加载任务，新一次加载会先取消上一次，避免旧协程覆盖新结果 */
    private var browseSongsJob: Job? = null
    /** 自增请求令牌：只采纳最新一次请求的结果，陈旧（如用户已返回并点了别的榜单）的请求结果直接丢弃 */
    private var browseSongsRequestId = 0
    /** 歌曲列表分页状态（与卡片网格的 browsePage/browseHasMore 平行）：
     *  - browseSongsId / browseSongsIsRanking 记录当前正在查看的歌单/榜单，供「加载更多」续拉下一页
     *  - browseSongsPage 当前页；browseSongsHasMore 是否还有下一页（仅酷狗榜单/歌单走服务端翻页）
     *  - browseSongsPageSize 每页歌曲数（对齐能正常加载的体积，避免一次拉过多变慢/卡） */
    private var browseSongsPage = 1
    private var browseSongsHasMore = false
    private var browseSongsId = ""
    private var browseSongsIsRanking = false
    private val browseSongsPageSize = 30

    // ========== 播放源状态 ==========
    private val _sources = MutableStateFlow<List<MusicSource>>(emptyList())
    val sources: StateFlow<List<MusicSource>> = _sources.asStateFlow()

    // ========== HTTP服务器状态 ==========
    private var httpServer: HttpServer? = null
    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()
    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    // Web 端添加收藏歌单（PlaylistManager 实现）
    private val playlistManager = object : PlaylistManager {
        override fun addPlaylistByUrl(url: String): PlaylistAddResult {
            return addPlaylistFromUrl(url)
        }
    }

    // ========== 搜索状态 ==========
    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // 搜索页状态提升到 ViewModel，保证从播放页返回后搜索词/平台/触发状态不丢失
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    // 2.8 当前主页 tab（侧栏索引；搜索页 = 3）。Web 端搜索推送/清空仅在搜索页生效
    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()
    // 2.8 当前导航路由（MainActivity 同步）：搜索结果页等独立路由时 Web 推送不生效
    private val _currentRoute = MutableStateFlow("main")
    val currentRoute: StateFlow<String> = _currentRoute.asStateFlow()
    private val _searchPlatform = MutableStateFlow(MusicPlatform.TX)
    val searchPlatform: StateFlow<MusicPlatform> = _searchPlatform.asStateFlow()
    private val _searchTriggered = MutableStateFlow(false)
    val searchTriggered: StateFlow<Boolean> = _searchTriggered.asStateFlow()

    // 搜索类型（歌曲/歌单），搜索页可切换
    private val _searchType = MutableStateFlow(SearchType.SONG)
    val searchType: StateFlow<SearchType> = _searchType.asStateFlow()

    // 搜索历史（本地持久化，最多 10 条，最近在前）；搜索页输入为空时展示
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    // 歌单搜索结果 + 歌单详情歌曲（歌单搜索流程独立于歌曲搜索）
    private val _playlistResults = MutableStateFlow<List<Playlist>>(emptyList())
    val playlistResults: StateFlow<List<Playlist>> = _playlistResults.asStateFlow()
    private val _playlistSongs = MutableStateFlow<List<Song>?>(null)
    val playlistSongs: StateFlow<List<Song>?> = _playlistSongs.asStateFlow()

    // 歌单歌曲加载状态：加载中转圈，加载完成（含空/失败）才落定，避免一上来闪现"暂无歌曲/加载失败"
    private val _isPlaylistSongsLoading = MutableStateFlow(false)
    val isPlaylistSongsLoading: StateFlow<Boolean> = _isPlaylistSongsLoading.asStateFlow()
    // 歌单歌曲加载失败提示（超时或异常）；正常空歌单不置此值，仅提示"暂无歌曲"
    private val _playlistSongsError = MutableStateFlow<String?>(null)
    val playlistSongsError: StateFlow<String?> = _playlistSongsError.asStateFlow()

    // 搜索歌单详情分页状态（仅酷狗签名接口按页返回；其余平台一次全量，无续拉）
    private var playlistSongsPlatform = MusicPlatform.KW
    private var playlistSongsId = ""
    private var playlistSongsPage = 1
    private val _playlistSongsHasMore = MutableStateFlow(false)
    val playlistSongsHasMore: StateFlow<Boolean> = _playlistSongsHasMore.asStateFlow()
    private val _playlistSongsLoadingMore = MutableStateFlow(false)
    val playlistSongsLoadingMore: StateFlow<Boolean> = _playlistSongsLoadingMore.asStateFlow()

    // 歌单歌曲加载超时（毫秒）：浏览数据链路（BrowseDataService）网络层 15s 超时，
    // 这里对齐 15s 做 VM 层兜底（防网络层极端挂死；若比网络层更紧会误伤 QQ 榜单等大响应接口）
    private val PLAYLIST_SONGS_TIMEOUT_MS = 15_000L
    // 浏览数据（榜单/歌单卡片与歌曲列表）加载超时（毫秒）：与 BrowseDataService 的 15s 网络超时对齐。
    // 说明：全局 HttpClient 默认 5s 只用于搜索/联想等轻接口；榜单/歌单响应大（QQ 榜单一次 25 榜），
    // 在 TV 网络下需要 15s 才稳。此兜底保证即使网络层极端挂死，UI 也绝不超过 15s 转圈。
    private val BROWSE_TIMEOUT_MS = 15_000L

    // 搜索页右侧热门搜索关键词（按平台从接口获取，失败为空）
    private val _hotKeywords = MutableStateFlow<List<String>>(emptyList())
    val hotKeywords: StateFlow<List<String>> = _hotKeywords.asStateFlow()

    // 搜索页右侧酷狗热门歌曲（取 TOP500 前 10 首，点击直接播放；仅酷狗有数据，其余平台为空）
    private val _hotSongs = MutableStateFlow<List<Song>>(emptyList())
    val hotSongs: StateFlow<List<Song>> = _hotSongs.asStateFlow()

    // 搜索联想关键词（根据输入实时过滤）
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    // 默认音乐平台（设置页配置，搜索/歌单/排行/发现共用）
    private val _defaultPlatform = MutableStateFlow(MusicPlatform.TX)
    val defaultPlatform: StateFlow<MusicPlatform> = _defaultPlatform.asStateFlow()

    // 播放模式（顺序/随机/单曲循环）
    private val _playMode = MutableStateFlow(PlayMode.SEQUENCE)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    // 随机播放洗牌队列：随机=打乱顺序后每首播一次，一轮播完重新洗牌（不是有放回纯随机——
    // 否则同一首会反复被选中、部分歌曲永远轮不到）。列表变化时在 playSong 里重置。
    private var randomPlaylist: List<Song> = emptyList()
    private var randomPlaylistIndex = -1

    // ========== 播放状态 ==========
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // 偏好音质（设置页播放设置，SharedPreferences 持久化，默认 320k）
    private val _preferredQuality = MutableStateFlow(AudioQuality.QUALITY_320K)
    val preferredQuality: StateFlow<AudioQuality> = _preferredQuality.asStateFlow()

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()
    private val _currentLyric = MutableStateFlow<String?>(null)
    val currentLyric: StateFlow<String?> = _currentLyric.asStateFlow()
    // 2.8 翻译歌词（tlyric，JS 源返回 JSON 歌词时才有；设置页可开关显示）
    private val _currentLyricTranslation = MutableStateFlow<String?>(null)
    val currentLyricTranslation: StateFlow<String?> = _currentLyricTranslation.asStateFlow()
    // 2.8 是否显示翻译歌词（歌词设置，SharedPreferences 持久化，默认开启）
    private val _lyricTranslationEnabled = MutableStateFlow(true)
    val lyricTranslationEnabled: StateFlow<Boolean> = _lyricTranslationEnabled.asStateFlow()
    // 2.8 当前实际播放音质（getMusicUrl 降级重试成功后记录的真实音质，非设置里的偏好音质）
    private val _currentPlayQuality = MutableStateFlow<AudioQuality?>(null)
    val currentPlayQuality: StateFlow<AudioQuality?> = _currentPlayQuality.asStateFlow()
    // 2.8 本轮播放尝试中已失败的源 id 集合：某源返回的 URL 实测无法播放（403/404 等）后加入，
    // 同一轮自动失败重试时跳过这些源、按设置顺序继续尝试下一个源；
    // **仅本轮有效**——用户每次主动播放/点播放都会清空，重新按设置顺序从头尝试（源可能只是暂时不可用）
    private val tryFailedSourceIds = mutableSetOf<String>()
    // 当前播放 URL 来自哪个源（播放失败时把该源加入失败集合，重试跳过它）
    private var currentPlaySourceId: String? = null
    // 2.8 播放失败标记：失败后「播放/暂停」恢复播放时需重新解析 URL（坏 URL/换源场景），
    // 否则 resume 旧 MediaItem 仍会失败；playSong 成功启动时重置
    private var playNeedsReFetch = false

    // ========== 播放历史和收藏 ==========
    private val _playHistory = MutableStateFlow<List<PlayHistory>>(emptyList())
    val playHistory: StateFlow<List<PlayHistory>> = _playHistory.asStateFlow()
    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    // 已收藏歌曲 id 集合（UI 判断收藏状态用）
    private val _favoriteSongIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongIds: StateFlow<Set<String>> = _favoriteSongIds.asStateFlow()

    // 收藏歌单 + 已收藏歌单 id 集合
    private val _favoritePlaylists = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
    val favoritePlaylists: StateFlow<List<FavoritePlaylist>> = _favoritePlaylists.asStateFlow()
    private val _favoritePlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val favoritePlaylistIds: StateFlow<Set<String>> = _favoritePlaylistIds.asStateFlow()

    // 收藏页打开收藏歌单的歌曲列表（null = 未进入歌单详情）
    private val _favoritePlaylistSongs = MutableStateFlow<List<Song>?>(null)
    val favoritePlaylistSongs: StateFlow<List<Song>?> = _favoritePlaylistSongs.asStateFlow()

    // 收藏歌单歌曲是否正在加载（网易云等平台接口较慢，点击后立即显示转圈提示）
    private val _favoritePlaylistLoading = MutableStateFlow(false)
    val favoritePlaylistLoading: StateFlow<Boolean> = _favoritePlaylistLoading.asStateFlow()

    // 收藏歌单加载令牌：防止快速切换歌单时旧结果覆盖新结果
    private var favoritePlaylistLoadToken = 0L

    // 收藏歌单详情分页状态（仅酷狗签名接口按页返回；其余平台一次全量，无续拉）
    private var favoritePlaylistSongsPlatform = MusicPlatform.KW
    private var favoritePlaylistSongsId = ""
    private var favoritePlaylistSongsPage = 1
    private val _favoritePlaylistSongsHasMore = MutableStateFlow(false)
    val favoritePlaylistSongsHasMore: StateFlow<Boolean> = _favoritePlaylistSongsHasMore.asStateFlow()
    private val _favoritePlaylistSongsLoadingMore = MutableStateFlow(false)
    val favoritePlaylistSongsLoadingMore: StateFlow<Boolean> = _favoritePlaylistSongsLoadingMore.asStateFlow()

    // ========== 消息提示 ==========
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // ========== PlayerService绑定 ==========
    private var playerService: PlayerService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("LX-MainViewModel", "[service] onServiceConnected")
            val binder = service as PlayerService.LocalBinder
            playerService = binder.getService()
            isServiceBound = true
            setupPlayerCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("LX-MainViewModel", "[service] onServiceDisconnected")
            playerService = null
            isServiceBound = false
        }
    }

    init {
        // 读取默认音乐平台设置（SharedPreferences）
        _defaultPlatform.value = loadDefaultPlatform()
        _searchPlatform.value = _defaultPlatform.value

        // 读取偏好音质（设置页播放设置，默认 320k）
        _preferredQuality.value = loadPreferredQuality()

        // 2.8 读取「显示翻译歌词」开关（歌词设置，默认开启）
        _lyricTranslationEnabled.value = loadLyricTranslationEnabled()

        // 加载搜索页热门搜索关键词
        loadHotKeywords(_defaultPlatform.value)
        // 加载搜索页右侧酷狗热门歌曲（其余平台为空）
        loadHotSongs(_defaultPlatform.value)

        // 加载搜索历史（SharedPreferences，最多 10 条）
        _searchHistory.value = loadSearchHistory()

        // 监听播放源列表变化
        viewModelScope.launch {
            sourceManager.sourcesFlow.collect { sourceList ->
                _sources.value = sourceList
            }
        }

        // 加载播放历史
        viewModelScope.launch {
            dataStoreManager.getPlayHistory().collect { history ->
                _playHistory.value = history
            }
        }

        // 加载收藏
        viewModelScope.launch {
            dataStoreManager.getAllFavorites().collect { favs ->
                _favorites.value = favs
                _favoriteSongIds.value = favs.map { it.musicId }.toSet()
            }
        }

        // 加载收藏歌单
        viewModelScope.launch {
            dataStoreManager.getAllFavoritePlaylists().collect { favs ->
                _favoritePlaylists.value = favs
                _favoritePlaylistIds.value = favs.map { it.playlistId }.toSet()
            }
        }

        // 绑定播放器服务
        bindPlayerService()

        // 注意：init 不直接从 SharedPreferences 恢复上次播放歌曲——
        // 冷启动（退出后重新打开）时服务未在播放，恢复会显示无法播放的假卡片；
        // 恢复动作放在服务绑定回调（setupPlayerCallbacks）中，且仅在服务确认在播放时执行，
        // 正好覆盖「后台播放 → 进程重建 → 重新进入」场景（前台服务常驻、仍在播）。
        // 「后台播放」返回场景进程未被销毁，ViewModel 的 currentSong 状态自然保留，卡片仍在。
        // 同步真实播放状态由 bindPlayerService 绑定后的回调处理。

        // 启动进度更新协程
        startProgressUpdater()

        // 2.8 恢复 HTTP 服务器上次状态：开启过则应用启动时自动开启（记住用户选择）
        if (loadHttpServerEnabled()) {
            startServer()
        }
    }

    // ========== 播放源管理 ==========

    /**
     * 导入播放源
     */
    fun importSource(fileContent: String, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sourceManager.importSourceFile(fileContent, fileName)
            withContext(Dispatchers.Main) {
                when (result) {
                    is SourceManagerImpl.ImportResult.Success -> {
                        _toastMessage.value = result.message
                        // 自动启用第一个源
                        val sources = sourceManager.getAllSources()
                        if (sources.size == 1) {
                            sourceManager.setSourceEnabled(sources[0].id, true)
                            loadAndExecuteSource(sources[0].id)
                        }
                    }
                    is SourceManagerImpl.ImportResult.Error -> {
                        _toastMessage.value = result.message
                    }
                }
            }
        }
    }

    /**
     * 切换播放源启用状态
     * 启用时加载脚本，失败则自动恢复关闭状态
     */
    fun toggleSource(sourceId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                // 先设置启用状态，再尝试加载
                sourceManager.setSourceEnabled(sourceId, enabled)
                val result = sourceManager.loadAndExecuteSource(sourceId)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is SourceExecutionResult.Success -> {
                            _toastMessage.value = "播放源加载成功"
                        }
                        is SourceExecutionResult.Error -> {
                            // 加载失败，恢复关闭状态
                            sourceManager.setSourceEnabled(sourceId, false)
                            _toastMessage.value = "播放源加载失败: ${result.message}"
                        }
                        else -> {}
                    }
                }
            } else {
                sourceManager.setSourceEnabled(sourceId, false)
            }
        }
    }

    /**
     * 删除播放源
     */
    fun deleteSource(sourceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceManager.deleteSource(sourceId)
            withContext(Dispatchers.Main) {
                _toastMessage.value = "播放源已删除"
            }
        }
    }

    /**
     * 加载并执行播放源脚本
     */
    private suspend fun loadAndExecuteSource(sourceId: String) {
        val result = sourceManager.loadAndExecuteSource(sourceId)
        withContext(Dispatchers.Main) {
            when (result) {
                is SourceExecutionResult.Success -> {
                    _toastMessage.value = "播放源加载成功"
                }
                is SourceExecutionResult.Error -> {
                    _toastMessage.value = "播放源加载失败: ${result.message}"
                }
                else -> {}
            }
        }
    }

    // ========== HTTP服务器 ==========

    /** 2.8 搜索页在侧栏的 tab 索引（0歌单/1排行/2收藏/3搜索/4设置），Web 推送/清空仅搜索页生效 */
    private val SEARCH_TAB_INDEX = 3

    /**
     * 2.8 主页 tab 切换同步（MainActivity onTabSelected 调用）：Web 端推送只在搜索页生效
     */
    fun setCurrentTab(tab: Int) {
        _currentTab.value = tab
    }

    /**
     * 2.8 当前导航路由同步（MainActivity NavHost 监听）：搜索结果页等独立路由时推送不生效
     */
    fun setCurrentRoute(route: String) {
        _currentRoute.value = route
    }

    /**
     * 2.8 Web 推送/清空是否生效：必须处于「主页（main）的搜索 tab」——
     * 搜索结果页（search_result）、设置页等独立路由或非搜索 tab 均不生效
     */
    private fun webActionActive(): Boolean =
        _currentRoute.value == "main" && _currentTab.value == SEARCH_TAB_INDEX

    /**
     * 2.8 HTTP 服务器开启状态持久化（记住用户选择，下次启动自动恢复）
     */
    private fun saveHttpServerEnabled(enabled: Boolean) {
        try {
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("http_server_enabled", enabled)
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
        }
    }

    private fun loadHttpServerEnabled(): Boolean {
        return try {
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean("http_server_enabled", false)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            false
        }
    }

    /**
     * 2.8 Web 端（/search 页）推送的搜索文字：更新输入框 + 按当前搜索类型触发搜索，
     * 等价于用户在小键盘输入后按「搜索」键（HttpServer 回调线程 → 主线程执行）。
     * 仅在应用处于搜索页时生效（_currentTab == 3）；且不写入搜索历史
     * （历史需用户用遥控器点「搜索」键才记录，见 search/searchPlaylist 的 recordHistory）。
     */
    private fun onWebSearchText(text: String) {
        val keyword = text.trim()
        if (keyword.isEmpty()) return
        if (!webActionActive()) {
            Log.w("LX-MainViewModel", "Web推送忽略：不在主页搜索页（route=${_currentRoute.value}, tab=${_currentTab.value}）")
            return
        }
        viewModelScope.launch {
            updateSearchQuery(keyword)
            when (_searchType.value) {
                SearchType.SONG -> search(keyword, _searchPlatform.value, recordHistory = false)
                SearchType.PLAYLIST -> searchPlaylist(keyword, _searchPlatform.value, recordHistory = false)
            }
        }
    }

    /**
     * 2.8 Web 端清空搜索框：仅在应用处于主页搜索页时生效
     */
    private fun onWebClearSearch() {
        if (!webActionActive()) {
            Log.w("LX-MainViewModel", "Web清空忽略：不在主页搜索页（route=${_currentRoute.value}, tab=${_currentTab.value}）")
            return
        }
        viewModelScope.launch {
            updateSearchQuery("")
        }
    }

    /**
     * 启动HTTP服务器
     */
    fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 先清理旧实例
                try { httpServer?.stop() } catch (_: Exception) {}
                httpServer = null
                _serverRunning.value = false

                httpServer = HttpServer(app)
                httpServer?.setSourceManager(sourceManager)
                httpServer?.setPlaylistManager(playlistManager)
                // 2.8 Web 端搜索推送：/search 页提交 → 更新 TV 搜索输入框并触发搜索
                httpServer?.onSearchText = { text -> onWebSearchText(text) }
                // 2.8 Web 端清空搜索框
                httpServer?.onClearSearch = { onWebClearSearch() }
                httpServer?.start()

                // 等待一小段时间确认服务器真正启动
                delay(200)
                if (httpServer?.isAlive == true) {
                    withContext(Dispatchers.Main) {
                        _serverRunning.value = true
                        _serverUrl.value = httpServer?.getAccessUrl()
                        _toastMessage.value = "服务器已启动"
                    }
                    // 2.8 记住开启状态：下次启动自动开启
                    saveHttpServerEnabled(true)
                } else {
                    withContext(Dispatchers.Main) {
                        _serverRunning.value = false
                        httpServer = null
                        _toastMessage.value = "服务器启动失败: 端口可能被占用"
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _serverRunning.value = false
                    try { httpServer?.stop() } catch (_: Exception) {}
                    httpServer = null
                    _toastMessage.value = "服务器启动失败: ${e.message}"
                }
            }
        }
    }

    /**
     * 停止HTTP服务器
     */
    fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                httpServer?.stop()
                withContext(Dispatchers.Main) {
                    _serverRunning.value = false
                    _serverUrl.value = null
                    _toastMessage.value = "服务器已停止"
                }
                // 2.8 记住关闭状态：下次启动不自动开启
                saveHttpServerEnabled(false)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _toastMessage.value = "服务器停止失败: ${e.message}"
                }
            }
        }
    }

    /**
     * 切换服务器状态
     */
    fun toggleServer() {
        if (_serverRunning.value) {
            stopServer()
        } else {
            startServer()
        }
    }

    // ========== 搜索 ==========

    /**
     * 从 SharedPreferences 读取搜索历史（最多 10 条，最近在前）
     */
    private fun loadSearchHistory(): List<String> {
        return try {
            val app = getApplication<Application>()
            val raw = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SEARCH_HISTORY, "") ?: ""
            if (raw.isEmpty()) emptyList() else raw.split(HISTORY_SEPARATOR).filter { it.isNotBlank() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 记录搜索历史：去重置顶、最多保留 10 条，并持久化
     */
    private fun addSearchHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val updated = (listOf(kw) + _searchHistory.value.filterNot { it == kw }).take(SEARCH_HISTORY_MAX)
        _searchHistory.value = updated
        try {
            val app = getApplication<Application>()
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SEARCH_HISTORY, updated.joinToString(HISTORY_SEPARATOR))
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            // 持久化失败不影响本次会话内使用
        }
    }

    /**
     * 清空搜索历史（内存 + 持久化）
     */
    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        try {
            val app = getApplication<Application>()
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SEARCH_HISTORY)
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 更新搜索关键词
     * 仅更新输入框内容，不触发任何搜索请求；只有用户点击小键盘「搜索」键才发起搜索
     * 输入变化时 200ms 防抖调用联想引擎（平台接口支持拼音，失败回退本地词库）
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _searchTriggered.value = false
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _playlistResults.value = emptyList()
            _playlistSongs.value = null
            _isPlaylistSongsLoading.value = false
            _playlistSongsError.value = null
            _searchError.value = null
            _suggestions.value = emptyList()
            suggestJob?.cancel()
            suggestJob = null
        } else {
            // 参考 blbl：取消上一个联想任务，防抖 200ms 后请求联想接口
            suggestJob?.cancel()
            suggestJob = viewModelScope.launch {
                delay(200)
                val result = try {
                    suggestEngine.suggest(query, _searchPlatform.value)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                    Log.e("LX-MainViewModel", "搜索联想失败: ${e.message}")
                    emptyList()
                }
                // 只展示当前输入对应的联想（防止竞态覆盖）
                if (_searchQuery.value == query) {
                    _suggestions.value = result
                }
            }
        }
    }

    /**
     * 切换搜索平台；如果已有搜索词且已触发过搜索，自动用新平台重新搜索
     */
    fun selectSearchPlatform(platform: MusicPlatform) {
        if (_searchPlatform.value == platform) return
        _searchPlatform.value = platform
        // 先清空旧平台的热门数据，避免切换瞬间右侧仍残留上一个平台的榜单/热词
        _hotKeywords.value = emptyList()
        _hotSongs.value = emptyList()
        // 切换平台后刷新右侧热门搜索 / 酷狗热门歌曲
        loadHotKeywords(platform)
        loadHotSongs(platform)
        if (_searchQuery.value.isNotBlank() && _searchTriggered.value) {
            when (_searchType.value) {
                SearchType.SONG -> search(_searchQuery.value, platform)
                SearchType.PLAYLIST -> searchPlaylist(_searchQuery.value, platform)
            }
        }
    }

    /**
     * 切换搜索类型（歌曲/歌单）；已触发过搜索时按新类型重新搜索
     */
    fun setSearchType(type: SearchType) {
        if (_searchType.value == type) return
        _searchType.value = type
        _playlistSongs.value = null
        _isPlaylistSongsLoading.value = false
        _playlistSongsError.value = null
        if (_searchQuery.value.isNotBlank() && _searchTriggered.value) {
            when (type) {
                SearchType.SONG -> search(_searchQuery.value, _searchPlatform.value)
                SearchType.PLAYLIST -> searchPlaylist(_searchQuery.value, _searchPlatform.value)
            }
        }
    }

    /**
     * 加载搜索页右侧热门搜索关键词（按平台从接口获取，失败为空列表由 UI 兜底）
     */
    fun loadHotKeywords(platform: MusicPlatform) {
        viewModelScope.launch {
            _hotKeywords.value = try {
                browseDataService.getHotSearch(platform)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载热门搜索失败: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * 加载搜索页右侧酷狗热门歌曲（取 TOP500 榜单前 10 首，点击直接播放）。
     * 仅酷狗平台有数据；其余平台该面板仍展示「热门搜索」关键词列表。
     */
    fun loadHotSongs(platform: MusicPlatform) {
        viewModelScope.launch {
            _hotSongs.value = try {
                if (platform == MusicPlatform.KG) {
                    browseDataService.getHotSongs(platform, 10)
                } else {
                    emptyList()
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载酷狗热门歌曲失败: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * 设置偏好音质（设置页播放设置），持久化到 SharedPreferences
     */
    fun setPreferredQuality(quality: AudioQuality) {
        _preferredQuality.value = quality
        try {
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("preferred_quality", quality.name)
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "保存偏好音质失败: ${e.message}")
        }
    }

    private fun loadPreferredQuality(): AudioQuality {
        return try {
            val name = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString("preferred_quality", AudioQuality.QUALITY_320K.name) ?: AudioQuality.QUALITY_320K.name
            AudioQuality.valueOf(name)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            AudioQuality.QUALITY_320K
        }
    }

    /**
     * 2.8 设置「显示翻译歌词」开关（设置页歌词设置），持久化到 SharedPreferences
     */
    fun setLyricTranslationEnabled(enabled: Boolean) {
        _lyricTranslationEnabled.value = enabled
        try {
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("lyric_translation_enabled", enabled)
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "保存歌词翻译开关失败: ${e.message}")
        }
    }

    private fun loadLyricTranslationEnabled(): Boolean {
        return try {
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean("lyric_translation_enabled", true)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            true
        }
    }

    /**
     * 设置默认音乐平台（设置页）
     * 同步更新搜索平台，并持久化到 SharedPreferences
     */
    fun setDefaultPlatform(platform: MusicPlatform) {
        _defaultPlatform.value = platform
        _searchPlatform.value = platform
        // 先清空旧平台的热门数据，避免切换瞬间右侧仍残留上一个平台的榜单/热词
        _hotKeywords.value = emptyList()
        _hotSongs.value = emptyList()
        // 同步刷新搜索页热门搜索关键词 / 酷狗热门歌曲
        loadHotKeywords(platform)
        loadHotSongs(platform)
        try {
            app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("default_platform", platform.name)
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "保存默认音乐平台失败: ${e.message}")
        }
    }

    /**
     * 切换播放模式（播放页改为弹窗选择，不再用 Toast 提示）
     */
    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
    }

    // ========== 播放源平台配置 ==========

    /**
     * 获取播放源启用的平台 key 集合（空 = 全部平台）
     */
    fun getSourcePlatforms(sourceId: String): Set<String> {
        return sourceManager.getSourcePlatforms(sourceId)
    }

    /**
     * 设置播放源启用的平台 key 集合（空 = 全部平台）
     */
    fun setSourcePlatforms(sourceId: String, platforms: Set<String>) {
        sourceManager.setSourcePlatforms(sourceId, platforms)
    }

    /**
     * 读取持久化的默认音乐平台
     */
    private fun loadDefaultPlatform(): MusicPlatform {
        return try {
            val name = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString("default_platform", MusicPlatform.TX.name) ?: MusicPlatform.TX.name
            MusicPlatform.valueOf(name)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            MusicPlatform.TX
        }
    }

    /**
     * 搜索音乐
     */
    fun search(keyword: String, platform: MusicPlatform = _defaultPlatform.value, page: Int = 1, pageSize: Int = 30, recordHistory: Boolean = true) {
        if (keyword.isBlank()) return

        _searchQuery.value = keyword
        _searchPlatform.value = platform
        _searchTriggered.value = true
        _playlistResults.value = emptyList()
        // 仅新搜索（第一页）记录历史；分页续拉不重复记录。
        // 2.8 recordHistory=false：Web 端推送的搜索不记历史（需用户遥控器点「搜索」键才记）
        if (page == 1 && recordHistory) addSearchHistory(keyword)

        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null

            try {
                // 使用内置API搜索，根据选择的平台使用对应的API
                // MusicSearchService 会先尝试内置API，失败后再尝试JS源
                val params = SearchParams(keyword = keyword, page = page, pageSize = pageSize)
                val result = searchService.search(params, platform)

                withContext(Dispatchers.Main) {
                    if (result.success && result.data != null) {
                        _searchResults.value = result.data.songs
                    } else {
                        _searchError.value = result.message ?: "搜索失败"
                    }
                    _isSearching.value = false
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _searchError.value = "搜索出错: ${e.message}"
                    _isSearching.value = false
                }
            }
        }
    }

    /**
     * 搜索歌单（按平台调用内置歌单搜索接口）
     */
    fun searchPlaylist(keyword: String, platform: MusicPlatform = _defaultPlatform.value, page: Int = 1, pageSize: Int = 30, recordHistory: Boolean = true) {
        if (keyword.isBlank()) return

        _searchQuery.value = keyword
        _searchPlatform.value = platform
        _searchTriggered.value = true
        _searchResults.value = emptyList()
        _playlistSongs.value = null
        _isPlaylistSongsLoading.value = false
        _playlistSongsError.value = null
        // 仅新搜索（第一页）记录历史；分页续拉不重复记录。
        // 2.8 recordHistory=false：Web 端推送的搜索不记历史（需用户遥控器点「搜索」键才记）
        if (page == 1 && recordHistory) addSearchHistory(keyword)

        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null

            try {
                val params = SearchParams(keyword = keyword, page = page, pageSize = pageSize)
                val result = searchService.searchPlaylist(params, platform)

                withContext(Dispatchers.Main) {
                    if (result.success && result.data != null) {
                        _playlistResults.value = result.data
                        if (result.data.isEmpty()) {
                            _searchError.value = "未找到相关歌单"
                        }
                    } else {
                        _searchError.value = result.message ?: "歌单搜索失败"
                    }
                    _isSearching.value = false
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _searchError.value = "歌单搜索出错: ${e.message}"
                    _isSearching.value = false
                }
            }
        }
    }

    /**
     * 打开歌单，加载歌单内歌曲列表（搜索页内展示，可返回继续浏览歌单）
     * 加载中保持 _playlistSongs=null 且 isPlaylistSongsLoading=true，UI 显示转圈；
     * 正常 0.5~1s 返回不会触发失败提示，仅超时（PLAYLIST_SONGS_TIMEOUT_MS）才提示加载失败。
     */
    fun openPlaylist(playlist: Playlist) {
        // 记录当前歌单与平台，供「加载更多」续拉（仅酷狗签名接口按页返回；其余平台一次全量）
        playlistSongsPlatform = playlist.platform
        playlistSongsId = playlist.id
        playlistSongsPage = 1
        _playlistSongsHasMore.value = false
        _playlistSongsLoadingMore.value = false
        viewModelScope.launch {
            _isPlaylistSongsLoading.value = true
            _playlistSongs.value = null          // 加载中保持 null，避免闪现"暂无歌曲/加载失败"
            _playlistSongsError.value = null
            _searchError.value = null
            val songs = try {
                // 超时保护：超时才判定加载失败；正常返回（含空列表）均走成功分支
                withTimeoutOrNull(PLAYLIST_SONGS_TIMEOUT_MS) {
                    browseDataService.getPlaylistSongs(playlist.platform, playlist.id, 1, 30)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载歌单歌曲失败: ${e.message}")
                null
            }
            _isPlaylistSongsLoading.value = false
            if (songs == null) {
                // 超时或异常：提示加载失败（与"空歌单"区分）
                _playlistSongsError.value = "歌单加载失败，请重试"
                _playlistSongs.value = emptyList()
            } else {
                _playlistSongs.value = songs
                // 真正空歌单：不置错误，UI 提示"暂无歌曲"
                // 仅酷狗支持按页续拉：本页返回数>=页大小则还有下一页
                _playlistSongsHasMore.value = playlist.platform == MusicPlatform.KG && songs.size >= 30
            }
        }
    }

    /**
     * 搜索歌单详情滚动到底部时调用：加载下一页并追加（仅酷狗按页返回；其余平台无续拉）
     */
    fun loadMorePlaylistSongs() {
        if (!_playlistSongsHasMore.value || _playlistSongsLoadingMore.value || playlistSongsId.isEmpty()) return
        _playlistSongsLoadingMore.value = true
        val next = playlistSongsPage + 1
        val platform = playlistSongsPlatform
        viewModelScope.launch {
            val batch = try {
                withTimeoutOrNull(PLAYLIST_SONGS_TIMEOUT_MS) {
                    browseDataService.getPlaylistSongs(platform, playlistSongsId, next, 30)
                }
            } catch (e: CancellationException) {
                throw e // 协程被取消（新歌单接管）：不吞取消
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载更多歌单歌曲失败: ${e.message}")
                null
            }
            _playlistSongsLoadingMore.value = false
            if (batch != null) {
                playlistSongsPage = next
                _playlistSongs.value = (_playlistSongs.value ?: emptyList()) + batch
                _playlistSongsHasMore.value = platform == MusicPlatform.KG && batch.size >= 30
            }
        }
    }

    /**
     * 从歌单详情返回歌单搜索结果列表
     */
    fun backFromPlaylistSongs() {
        _playlistSongs.value = null
        _isPlaylistSongsLoading.value = false
        _playlistSongsError.value = null
    }

    /**
     * 清除搜索结果
     */
    fun clearSearchResults() {
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    // ========== 浏览数据（发现/歌单/排行） ==========

    // 浏览列表分页状态
    private var browsePage = 0
    private var browseHasMore = true
    /** 浏览列表加载任务：与 browseSongsJob 平行的「最新请求获胜」守卫，防陈旧协程覆盖新结果
     *（如歌单广场首次成功、切页后再次加载失败却把成功列表覆盖成空） */
    private var browseItemsJob: Job? = null
    private var browseItemsRequestId = 0

    /**
     * 加载浏览列表（推荐歌单/歌单广场/排行榜），使用默认音乐平台
     * @param loadMore true 表示滚动到底部分页追加加载
     */
    fun loadBrowseItems(type: BrowseType, loadMore: Boolean = false) {
        val platform = _defaultPlatform.value
        browseItemsJob?.cancel()
        val myId = ++browseItemsRequestId
        browseItemsJob = viewModelScope.launch {
            if (!loadMore) {
                browsePage = 0
                browseHasMore = true
                _browseItems.value = emptyList()
            }
            _browseLoading.value = true
            val offset = browsePage * 30
            val items = try {
                // 超时兜底：15s 内未返回按失败处理并结束转圈，绝不无限加载
                withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    when (type) {
                        BrowseType.RECOMMEND -> browseDataService.getRecommendList(platform, offset)
                        BrowseType.PLAYLIST -> browseDataService.getPlaylistList(platform, offset)
                        BrowseType.RANKING -> browseDataService.getRankingList(platform, offset)
                    }
                } ?: emptyList()
            } catch (e: CancellationException) {
                throw e // 协程被取消（新任务接管）：不吞取消，交由新任务控制 loading 状态
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载浏览数据失败: ${e.message}")
                emptyList()
            }
            // 只采纳最新一次请求：期间又发起了新请求（myId 已变化）则丢弃本次结果
            if (myId == browseItemsRequestId) {
                if (loadMore) {
                    _browseItems.value = _browseItems.value + items
                } else {
                    _browseItems.value = items
                }
                browseHasMore = items.size >= 30
                browsePage++
                _browseLoading.value = false
            }
        }
    }

    /**
     * 无限加载：列表滚动到底部时调用，自动加载下一页
     */
    fun loadMoreBrowseItems(type: BrowseType) {
        if (browseHasMore && !_browseLoading.value) {
            loadBrowseItems(type, loadMore = true)
        }
    }

    /**
     * 加载榜单/歌单的歌曲列表
     */
    fun loadBrowseSongs(itemId: String, isRanking: Boolean) {
        val platform = _defaultPlatform.value
        // 取消上一次未完成的加载（如 Top500 等大榜单请求较慢时，用户已返回并点了别的榜单），
        // 避免旧协程最终完成时把新榜单的结果覆盖成空/脏数据
        browseSongsJob?.cancel()
        val myId = ++browseSongsRequestId
        // 进入新的歌单/榜单：重置分页状态，从第 1 页重新加载
        browseSongsId = itemId
        browseSongsIsRanking = isRanking
        browseSongsPage = 1
        browseSongsHasMore = true
        browseSongsJob = viewModelScope.launch {
            _browseLoading.value = true
            _browseSongsError.value = null
            // 先清空旧歌单歌曲，使详情页立即显示转圈（与搜索歌单详情一致），避免闪现上一歌单数据
            _browseSongs.value = emptyList()
            val result = try {
                // 超时兜底：酷狗等平台偶发连接挂死（复用连接失效），5s 未返回即结束转圈，避免无限等待。
                // 返回值语义：null=超时/异常（失败）；空列表=平台真实无数据（如 MG 无排行榜）
                withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    if (isRanking) browseDataService.getRankingSongs(platform, itemId, 1, browseSongsPageSize)
                    else browseDataService.getPlaylistSongs(platform, itemId, 1, browseSongsPageSize)
                }
            } catch (e: CancellationException) {
                throw e // 协程被取消（新请求接管）：不吞取消，交由新任务控制 loading 状态
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载歌曲列表失败: ${e.message}")
                null
            }
            // 只采纳最新一次请求的结果：若期间又发起了新请求（myId 已变化），本次结果直接丢弃
            if (myId == browseSongsRequestId) {
                if (result == null) {
                    // 超时/异常：提示失败（区别于真正的空歌单），UI 显示错误文案而非「暂不支持」
                    _browseSongsError.value = "加载超时或失败，请返回重试"
                    _browseSongs.value = emptyList()
                } else {
                    _browseSongs.value = result
                    // 仅酷狗【榜单】走服务端翻页（mobilecdn rank/song 支持 page 参数）；
                    // 酷狗歌单走 HTML 抓取，源站仅内嵌约 30 首（其余靠页面 JS 异步加载，无法翻页），故歌单不续拉；
                    // 其余平台一次性返回全部，无需继续加载
                    // 酷狗【榜单】与【歌单】均走服务端翻页（榜单=mobilecdn rank/song；歌单=pubsongscdn get_other_list_file 带签名），
                    // 其余平台一次性返回全部，无需继续加载。hasMore 以「本页返回数>=页大小」为续拉判据。
                    browseSongsHasMore = platform == MusicPlatform.KG && result.size >= browseSongsPageSize
                }
                _browseLoading.value = false
            }
        }
    }

    /**
     * 拉取指定页的歌曲（仅酷狗榜单/歌单支持翻页；其余平台一次性返回全部，page 参数无效）。
     * 返回 null 表示超时/异常（失败）；返回列表（可为空但非 null）表示成功。
     * 供 UI 滚动续拉（loadMoreBrowseSongs）与「整列表播放」补拉（playBrowseAll）共用，避免重复网络代码。
     */
    private suspend fun fetchBrowsePage(page: Int): List<Song>? {
        val platform = _defaultPlatform.value
        return try {
            // 超时兜底：与首屏一致，超时/失败返回 null（不吞成空列表，以便区分「失败」与「真实无更多」）
            withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                if (browseSongsIsRanking) browseDataService.getRankingSongs(platform, browseSongsId, page, browseSongsPageSize)
                else browseDataService.getPlaylistSongs(platform, browseSongsId, page, browseSongsPageSize)
            }
        } catch (e: CancellationException) {
            throw e // 协程被取消（新请求接管）：不吞取消，交由新任务控制 loading 状态
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "加载歌曲分页失败: ${e.message}")
            null
        }
    }

    /**
     * 歌曲列表滚动到底部时调用：加载下一页并追加（仅酷狗榜单/歌单支持服务端翻页）
     */
    fun loadMoreBrowseSongs() {
        // 入口日志：用于确认 UI 是否真的触发了「加载更多」（若列表滚到底但此日志不出现 → 触发层问题）
        Log.d("LX-MainViewModel", "loadMoreBrowseSongs 被调用 hasMore=$browseSongsHasMore loading=${_browseLoading.value} id=${browseSongsId}")
        if (!browseSongsHasMore || _browseLoading.value || browseSongsId.isEmpty()) return
        val platform = _defaultPlatform.value
        browseSongsJob?.cancel()
        val myId = ++browseSongsRequestId
        val nextPage = browseSongsPage + 1
        // ⚠️ 同步置位 loading=true：必须在 launch 之外立即设置，否则 onLoadMoreSongs() 返回后、
        // 协程尚未跑起来前的一帧内 loading 仍为 false，UI 的 derivedStateOf 若再读到 true 会重入重复请求。
        _browseLoading.value = true
        browseSongsJob = viewModelScope.launch {
            val result = fetchBrowsePage(nextPage)
            if (myId == browseSongsRequestId) {
                if (result == null) {
                    // 超时/异常失败：不静默停拉——保持 hasMore=true，用户再次滚动可自动重试
                    Log.e("LX-MainViewModel", "加载更多失败（超时/异常），保留重试机会 page=$nextPage")
                    browseSongsHasMore = true
                } else {
                    browseSongsPage = nextPage
                    _browseSongs.value = _browseSongs.value + result
                    // 酷狗【榜单】与【歌单】均走服务端翻页（榜单=mobilecdn rank/song；歌单=pubsongscdn get_other_list_file 带签名），
                    // 其余平台一次性返回全部，无需继续加载。hasMore 以「本页返回数>=页大小」为续拉判据。
                    browseSongsHasMore = platform == MusicPlatform.KG && result.size >= browseSongsPageSize
                    Log.d("LX-MainViewModel", "加载更多成功 page=$nextPage 追加${result.size}首 hasMore=$browseSongsHasMore")
                }
                _browseLoading.value = false
            }
        }
    }

    /**
     * 「整列表播放」（歌曲行②：播放本首并进入播放页）。
     * 若歌曲列表尚未全部加载（酷狗榜单/歌单动辄数百首，走服务端分页、可能只拉了第一页 30 首），
     * 先**同步等待**把剩余所有页拉完，再以完整列表建立播放队列、播放点击的本首，
     * 避免只把已加载的第一页当作整列表队列而漏掉后续歌曲（方案A）。
     */
    fun playBrowseAll(song: Song) {
        // 无当前浏览歌单，或已加载完整列表（非分页平台一次性返回 / 分页已拉完）：直接用当前列表播放
        if (browseSongsId.isEmpty() || !browseSongsHasMore) {
            Log.d("LX-MainViewModel", "playBrowseAll 直接用当前列表 ${_browseSongs.value.size} 首")
            playSong(song, _browseSongs.value)
            return
        }
        Log.d("LX-MainViewModel", "playBrowseAll 补拉开始 id=$browseSongsId 当前${_browseSongs.value.size}首 hasMore=$browseSongsHasMore")
        // 取消可能正在进行的 UI 续拉任务，由本方法独占加载，避免陈旧协程竞争覆盖结果
        browseSongsJob?.cancel()
        val myId = ++browseSongsRequestId
        _browseLoading.value = true
        browseSongsJob = viewModelScope.launch {
            try {
                var guard = 0
                // 最多再拉 300 页（远超任何歌单/榜单规模），附带失败/超时保护，避免死循环
                while (browseSongsHasMore && guard < 300) {
                    guard++
                    val nextPage = browseSongsPage + 1
                    val result = fetchBrowsePage(nextPage)
                    if (result == null) {
                        // 本页超时/失败：保留重试机会，但退出循环（不再死拉），用已加载部分播放
                        browseSongsHasMore = true
                        break
                    }
                    browseSongsPage = nextPage
                    _browseSongs.value = _browseSongs.value + result
                    browseSongsHasMore = _defaultPlatform.value == MusicPlatform.KG && result.size >= browseSongsPageSize
                    Log.d("LX-MainViewModel", "playBrowseAll page=$nextPage 追加${result.size}首 累计${_browseSongs.value.size}首")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "整列表播放补拉失败: ${e.message}")
            }
            if (myId == browseSongsRequestId) {
                _browseLoading.value = false
                // 全部加载完成（或达上限/失败退出）：用完整列表建立播放队列，播放点击的本首
                Log.d("LX-MainViewModel", "playBrowseAll 完成 共${_browseSongs.value.size}首，开始播放")
                playSong(song, _browseSongs.value)
            }
        }
    }

    /**
     * 「整列表播放」搜索歌单详情（歌曲行②：播放本首并进入播放页）。
     * 酷狗歌单分页加载中可能只加载了第一页 30 首：先补拉完剩余页，再以完整列表建立播放队列播放本首
     * （与 playBrowseAll 同理；仅酷狗需要补拉，其余平台一次性全量返回）。
     */
    fun playPlaylistSongsAll(song: Song) {
        val platform = playlistSongsPlatform
        val id = playlistSongsId
        if (id.isEmpty() || platform != MusicPlatform.KG) {
            Log.d("LX-MainViewModel", "playPlaylistSongsAll 直接用当前列表 ${_playlistSongs.value?.size ?: 0} 首")
            playSong(song, _playlistSongs.value ?: emptyList())
            return
        }
        Log.d("LX-MainViewModel", "playPlaylistSongsAll 补拉开始 id=$id 当前${_playlistSongs.value?.size ?: 0}首")
        viewModelScope.launch {
            val all = (_playlistSongs.value ?: emptyList()).toMutableList()
            var page = playlistSongsPage + 1
            var guard = 0
            while (guard < 300) {
                guard++
                val batch = try {
                    withTimeoutOrNull(PLAYLIST_SONGS_TIMEOUT_MS) {
                        browseDataService.getPlaylistSongs(platform, id, page, 30)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                    Log.e("LX-MainViewModel", "搜索歌单补拉失败 page=$page: ${e.message}")
                    null
                }
                if (batch == null || batch.isEmpty()) break
                all += batch
                Log.d("LX-MainViewModel", "playPlaylistSongsAll page=$page 追加${batch.size}首 累计${all.size}首")
                if (batch.size < 30) break
                page++
            }
            Log.d("LX-MainViewModel", "playPlaylistSongsAll 完成 共${all.size}首，开始播放")
            playSong(song, all)
        }
    }

    /**
     * 「整列表播放」收藏歌单详情（歌曲行②）：酷狗先补拉剩余页再播（同 playPlaylistSongsAll）。
     */
    fun playFavoritePlaylistSongsAll(song: Song) {
        val platform = favoritePlaylistSongsPlatform
        val id = favoritePlaylistSongsId
        if (id.isEmpty() || platform != MusicPlatform.KG) {
            Log.d("LX-MainViewModel", "playFavoritePlaylistSongsAll 直接用当前列表 ${_favoritePlaylistSongs.value?.size ?: 0} 首")
            playSong(song, _favoritePlaylistSongs.value ?: emptyList())
            return
        }
        Log.d("LX-MainViewModel", "playFavoritePlaylistSongsAll 补拉开始 id=$id 当前${_favoritePlaylistSongs.value?.size ?: 0}首")
        viewModelScope.launch {
            val all = (_favoritePlaylistSongs.value ?: emptyList()).toMutableList()
            var page = favoritePlaylistSongsPage + 1
            var guard = 0
            while (guard < 300) {
                guard++
                val batch = try {
                    browseDataService.getPlaylistSongs(platform, id, page, 30)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                    Log.e("LX-MainViewModel", "收藏歌单补拉失败 page=$page: ${e.message}")
                    emptyList()
                }
                if (batch.isEmpty()) break
                all += batch
                Log.d("LX-MainViewModel", "playFavoritePlaylistSongsAll page=$page 追加${batch.size}首 累计${all.size}首")
                if (batch.size < 30) break
                page++
            }
            Log.d("LX-MainViewModel", "playFavoritePlaylistSongsAll 完成 共${all.size}首，开始播放")
            playSong(song, all)
        }
    }

    /**
     * 加载平台热门歌曲（直接展示歌曲，不走「榜单名列表 → 点开看歌曲」两级）
     * 目前仅酷狗实现：取 Top500 榜单前 10 首；其余平台返回空（调用方按原排行榜逻辑展示）
     */
    fun loadHotSongs() {
        val platform = _defaultPlatform.value
        // 同上：避免旧的大榜单请求覆盖新结果（热门歌曲取的是酷狗 TOP500，量大时请求较慢）
        browseSongsJob?.cancel()
        val myId = ++browseSongsRequestId
        browseSongsJob = viewModelScope.launch {
            _browseLoading.value = true
            // 先清空旧歌曲，使详情页立即显示转圈
            _browseSongs.value = emptyList()
            val result = try {
                // 超时兜底：与榜单加载一致，5s 未返回按失败处理
                withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    browseDataService.getHotSongs(platform, 10)
                } ?: emptyList()
            } catch (e: CancellationException) {
                throw e // 协程被取消（新请求接管）：不吞取消，交由新任务控制 loading 状态
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载热门歌曲失败: ${e.message}")
                emptyList()
            }
            if (myId == browseSongsRequestId) {
                _browseSongs.value = result
                _browseLoading.value = false
            }
        }
    }

    // ========== 播放控制 ==========

    /**
     * 播放歌曲
     */
    fun playSong(song: Song, playlist: List<Song> = emptyList()) {
        viewModelScope.launch {
            // 2.8 用户主动选歌：清空本轮失败源（新一次播放按设置顺序从最高优先级源重新尝试，
            // 上次失败的源可能已恢复）
            tryFailedSourceIds.clear()
            currentPlaySourceId = null
            playNeedsReFetch = false
            _currentSong.value = song
            _isPlaying.value = true
            // 立即停止旧播放并清空进度/时长：JS 源获取 URL 可能耗时数秒，
            // 若不先停掉旧歌，加载期间 onPositionChanged 会持续把旧歌进度写回 UI
            playerService?.stop()
            _progress.value = 0f
            _duration.value = 0L

            if (playlist.isNotEmpty()) {
                _playlist.value = playlist
                // 新队列：重置随机洗牌队列（打乱顺序、游标定位到本次播放的歌曲），
                // 保证随机模式下每首歌都播一次、一轮播完重新洗牌
                randomPlaylist = playlist.shuffled()
                randomPlaylistIndex = randomPlaylist.indexOfFirst { it.id == song.id }.let { if (it < 0) 0 else it }
            } else if (randomPlaylist.isNotEmpty()) {
                // 列表不变的单曲切换（如随机模式下手动点列表某首）：同步洗牌游标到当前歌，避免与随机队列脱节
                randomPlaylist.indexOfFirst { it.id == song.id }.let { if (it >= 0) randomPlaylistIndex = it }
            }

            // 自动加载歌词（播放页右侧显示）
            _currentLyric.value = null
            _currentLyricTranslation.value = null
            // 2.8 清空音质：新歌解析前显示「未知」，避免残留上一首歌的音质
            _currentPlayQuality.value = null
            loadLyrics(song)

            // 记录播放历史
            try {
                dataStoreManager.addPlayHistory(
                    PlayHistory(
                        musicId = song.id,
                        musicName = song.name,
                        artist = song.singer,
                        platform = song.platform,
                        duration = song.duration ?: 0L,
                        sourceId = sourceManager.getEnabledSource()?.id ?: ""
                    )
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "记录播放历史失败: ${e.message}", e)
            }

            // 获取播放URL并播放（失败提示由 doResolveAndPlay 处理）
            try {
                Log.d("LX-MainViewModel", "[playSong] 获取URL: ${song.name}, playerService=${if (playerService != null) "非空" else "null"}, isServiceBound=$isServiceBound")
                // 2.8 播放 URL 只走 JS 源；返回的 sourceId 记录当前 URL 来源，
                // 播放失败时把该源加入黑名单，重试跳过它真正尝试下一个源
                doResolveAndPlay(song, playlist, _preferredQuality.value)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                _toastMessage.value = "播放失败: ${e.message}"
                _isPlaying.value = false
            }
        }
    }

    /**
     * 2.8 解析播放 URL 并播放（跳过 tryFailedSourceIds 本轮已失败源）
     * @return 是否成功开始播放
     */
    private suspend fun doResolveAndPlay(song: Song, playlist: List<Song>, quality: AudioQuality): Boolean {
        // 2.8 播放 URL 短期缓存 + SimpleCache 实现真离线：getMusicUrl 先查 URL 缓存（命中直接返回），
        // 断网时命中缓存 URL → play(真实URL, 歌曲维度cacheKey) → CacheDataSource 读 SimpleCache 不触网。
        // URL 失效由 onPlaybackError 移除缓存并重试（见 onPlaybackError 的 badSourceId==null 分支）。
        // 总超时兜底（45s）：每源 15s × 最多 3 源 + 余量，保证「5秒后下一曲」提示必然出现；
        // 又不至于像 120s 那样让用户等太久。逐源失败提示不受影响。
        // 2.8 仅失败时提示（不提示「正在尝试」）；最后失败源合并进最终提示（「D」播放失败，5秒后播放下一曲）
        var lastFailedSource: String? = null
        // 2.9 播放失败尝试切换平台开关（设置-播放源管理，默认启用）：开启后 JS 源解析失败时用同一源尝试其他平台版本
        val platformSwitchEnabled = try {
            getApplication<Application>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLATFORM_SWITCH, true)
        } catch (e: Exception) {
            true
        }
        val urlResult = try {
            withTimeoutOrNull(30_000) {
                searchService.getMusicUrl(
                    song, quality, tryFailedSourceIds.toSet(),
                    onSourceLoadFailed = { failedName, nextName ->
                        lastFailedSource = failedName
                        // 2.8 失败提示：有下一个源 → 「「A」失败，尝试下一个源「B」」；
                        // 无下一个源（最后一个源也失败）→ 不单独提示，信息合并到失败分支的最终提示
                        if (nextName != null) {
                            _toastMessage.value = "「$failedName」获取${song.platform.displayName}《${song.name}》播放地址失败，尝试下一个源「$nextName」"
                        }
                    },
                    enablePlatformSwitch = platformSwitchEnabled
                )
            } ?: ApiResponse.error("获取播放URL超时（30s）")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ApiResponse.error("获取播放URL异常: ${e.message}")
        }
        if (urlResult.success && urlResult.data != null) {
            // 记录 URL 来源源 id：播放失败时加入黑名单，重试跳过该源
            currentPlaySourceId = urlResult.data.sourceId
            playNeedsReFetch = false
            // 2.8 播放来源提示：toast 显示最终成功的源名（用户可确认用了哪个源；URL 缓存命中无 sourceId 不提示）
            urlResult.data.sourceId?.let { sid ->
                val srcName = runCatching { sourceManager.getSourceById(sid)?.name }.getOrNull()
                if (srcName != null) {
                    // 2.9 平台切换成功：toast 附注实际播放平台版本（原歌曲平台显示不变，仅播放版本切换）
                    val actualName = urlResult.data.actualPlatformKey
                        ?.let { pk -> MusicPlatform.entries.firstOrNull { it.key == pk }?.displayName }
                    _toastMessage.value = if (actualName != null) {
                        "正在播放《${song.name}》（源：$srcName · ${actualName}版本）"
                    } else {
                        "正在播放《${song.name}》（源：$srcName）"
                    }
                }
            }
            // 通过PlayerService播放（cacheKey=歌曲维度：JS 源 URL 变化仍命中本地音频缓存，不重新下载）
            playerService?.play(
                PlayerService.MusicInfo(
                    id = song.id,
                    title = song.name,
                    artist = song.singer,
                    url = urlResult.data.url,
                    picUrl = song.picUrl,
                    cacheKey = CacheManager.songCacheKey(song, quality)
                )
            )
            // 持久化当前歌曲：后台播放后进程重建可恢复左下角播放卡片
            saveCurrentSongPrefs(song)
            return true
        }
        // 2.8 所有播放源均失败：Toast 提示 5 秒后自动播放下一曲，不卡在失败歌曲上。
        // 最终提示带最后失败源名（「D」播放失败，5秒后播放下一曲），无失败源则用通用文案
        val nextSong = if (playlist.size > 1) {
            val idx = playlist.indexOfFirst { it.id == song.id }
            when {
                idx >= 0 && idx < playlist.size - 1 -> playlist[idx + 1]
                idx >= 0 -> playlist[0]            // 最后一首失败 → 回到第一首
                else -> null
            }
        } else null
        val failTail = lastFailedSource?.let { "「$it」播放失败" }
            ?: "播放失败：所有播放源均无法获取有效播放地址"
        _isPlaying.value = false
        playNeedsReFetch = false
        if (nextSong != null) {
            _toastMessage.value = "$failTail，5秒后播放下一曲"
            // 5 秒后自动跳到下一曲；期间用户手动切歌/停止则取消自动跳曲（以用户操作为准）
            delay(5000)
            if (_currentSong.value?.id == song.id) {
                playSong(nextSong, playlist)
            } else {
                Log.d("LX-MainViewModel", "5秒内用户已切换歌曲，取消自动跳曲")
            }
        } else {
            _toastMessage.value = failTail
        }
        return false
    }

    /**
     * 一键播放整个列表（从第一首开始，建立播放队列）
     */
    fun playPlaylist(songs: List<Song>) {
        if (songs.isEmpty()) return
        playSong(songs.first(), songs)
    }

    /**
     * 2.8 音频缓存被清除后调用：重建播放器。
     * 旧 ExoPlayer 持有已 release、文件已删除的 SimpleCache，不重建会导致后续播放全部失败。
     */
    fun notifyAudioCacheCleared() {
        playerService?.rebuildForCacheCleared()
    }

    /**
     * 播放/暂停切换
     */
    fun togglePlayPause() {
        if (_isPlaying.value) {
            playerService?.pause()
            _isPlaying.value = false
        } else {
            val song = _currentSong.value
            if (song != null && playNeedsReFetch) {
                // 2.8 上次播放失败（坏 URL/换源后未重新解析）：点播放 = 用户主动发起新一轮播放，
                // 清空本轮失败源，重新按设置顺序从头尝试（上次失败的源可能已恢复）
                Log.w("LX-MainViewModel", "播放失败过，点击播放 → 重新按顺序解析 URL: ${song.name}")
                playNeedsReFetch = false
                tryFailedSourceIds.clear()
                currentPlaySourceId = null
                viewModelScope.launch {
                    playerService?.stop()
                    _progress.value = 0f
                    _duration.value = 0L
                    doResolveAndPlay(song, _playlist.value, _preferredQuality.value)
                }
            } else {
                playerService?.resume()
                _isPlaying.value = true
            }
        }
    }

    /** 确保随机洗牌队列就绪：首次进入随机/队列为空时用当前列表洗牌初始化，游标定位到当前歌曲 */
    private fun ensureRandomPlaylist(currentList: List<Song>, current: Song) {
        if (randomPlaylist.isEmpty()) {
            randomPlaylist = currentList.shuffled()
            randomPlaylistIndex = randomPlaylist.indexOfFirst { it.id == current.id }.let { if (it < 0) 0 else it }
        }
    }

    /**
     * 下一曲（按播放模式处理：顺序/随机/单曲循环）
     */
    fun playNext() {
        viewModelScope.launch {
            val currentList = _playlist.value
            val current = _currentSong.value
            if (currentList.isEmpty() || current == null) return@launch

            when (_playMode.value) {
                PlayMode.RANDOM -> {
                    // 随机播放（洗牌队列）：打乱顺序后每首播一次，一轮播完重新洗牌
                    ensureRandomPlaylist(currentList, current)
                    randomPlaylistIndex++
                    if (randomPlaylistIndex >= randomPlaylist.size) {
                        // 一轮播完：重新洗牌开始新一轮
                        randomPlaylist = currentList.shuffled()
                        randomPlaylistIndex = 0
                    }
                    playSong(randomPlaylist[randomPlaylistIndex])
                }
                PlayMode.LOOP_SINGLE -> {
                    // 单曲循环：重播当前歌曲
                    playSong(current)
                }
                else -> {
                    // 顺序播放
                    val currentIndex = currentList.indexOfFirst { it.id == current.id }
                    val nextIndex = (currentIndex + 1) % currentList.size
                    playSong(currentList[nextIndex])
                }
            }
        }
    }

    /**
     * 上一曲（按播放模式处理）
     */
    fun playPrevious() {
        viewModelScope.launch {
            val currentList = _playlist.value
            val current = _currentSong.value
            if (currentList.isEmpty() || current == null) return@launch

            when (_playMode.value) {
                PlayMode.RANDOM -> {
                    // 随机播放（洗牌队列）：沿洗牌顺序回退，到头则回尾部
                    ensureRandomPlaylist(currentList, current)
                    randomPlaylistIndex--
                    if (randomPlaylistIndex < 0) randomPlaylistIndex = randomPlaylist.size - 1
                    playSong(randomPlaylist[randomPlaylistIndex])
                }
                PlayMode.LOOP_SINGLE -> {
                    playSong(current)
                }
                else -> {
                    val currentIndex = currentList.indexOfFirst { it.id == current.id }
                    val prevIndex = if (currentIndex <= 0) currentList.size - 1 else currentIndex - 1
                    playSong(currentList[prevIndex])
                }
            }
        }
    }

    /**
     * 跳转到指定设置
     */
    fun seekTo(position: Float) {
        _progress.value = position
        val totalMs = _duration.value
        if (totalMs > 0) {
            playerService?.seekTo((position * totalMs).toLong())
        }
    }

    /**
     * 获取歌词（2.7 加磁盘缓存：命中直接返回，未命中获取成功后写入）
     * 2.8 增加翻译歌词（tlyric）链路：单独缓存 + 状态下发（设置页开关控制是否显示）
     */
    fun loadLyrics(song: Song) {
        viewModelScope.launch {
            try {
                // 先查歌词缓存（平台+歌曲id）
                val app = getApplication<Application>()
                CacheManager.getLyric(app, song)?.let { cached ->
                    _currentLyric.value = cached
                    // 翻译歌词缓存（可能为空：无翻译的歌曲）
                    _currentLyricTranslation.value = CacheManager.getLyricTranslation(app, song)
                    return@launch
                }
                val result = searchService.getLyrics(song)
                if (result.success && result.data != null) {
                    _currentLyric.value = result.data.lyric
                    // 2.8 翻译歌词
                    _currentLyricTranslation.value = result.data.tlyric
                    // 写歌词缓存（仅当有内容时）
                    if (!result.data.lyric.isNullOrBlank()) {
                        CacheManager.putLyric(app, song, result.data.lyric)
                    }
                    // 写翻译歌词缓存（有内容才写）
                    if (!result.data.tlyric.isNullOrBlank()) {
                        CacheManager.putLyricTranslation(app, song, result.data.tlyric)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "获取歌词失败: ${e.message}", e)
            }
        }
    }

    // ========== 收藏 ==========

    /**
     * 切换收藏状态
     */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            try {
                val isFav = dataStoreManager.isFavorite(song.id)
                if (isFav) {
                    dataStoreManager.removeFavorite(song.id)
                    _toastMessage.value = "已取消收藏"
                } else {
                    dataStoreManager.addFavorite(
                        Favorite(
                            musicId = song.id,
                            musicName = song.name,
                            artist = song.singer,
                            platform = song.platform,
                            sourceId = sourceManager.getEnabledSource()?.id ?: "",
                            // 保存封面/专辑/时长，收藏页歌曲列表才能显示封面
                            picUrl = song.picUrl,
                            albumName = song.albumName,
                            duration = song.duration
                        )
                    )
                    _toastMessage.value = "已添加到收藏"
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                _toastMessage.value = "操作失败: ${e.message}"
            }
        }
    }

    /**
     * 检查是否已收藏
     */
    suspend fun isFavorite(songId: String): Boolean {
        return try {
            dataStoreManager.isFavorite(songId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            false
        }
    }

    /**
     * 从歌单链接添加收藏歌单（Web 端 PlaylistManager 实现）
     * 解析链接识别平台 + 歌单ID → 拉取歌单详情 → 存入收藏
     * 支持平台：QQ音乐 / 网易云 / 酷狗 / 酷我 / 咪咕
     */
    private fun addPlaylistFromUrl(url: String): PlaylistAddResult {
        // 解析链接 → 平台 + 歌单ID
        val parsed = parsePlaylistUrl(url) ?: return PlaylistAddResult(false, "无法识别的歌单链接，请检查链接格式")
        val (platform, playlistId) = parsed

        return try {
            // 同步执行：拉取歌单详情（挂起函数用 runBlocking 包装）
            val playlist = runBlocking { browseDataService.getPlaylistDetail(platform, playlistId) }
                ?: return PlaylistAddResult(false, "歌单不存在或无法访问（${platform.displayName}）")

            // 检查是否已收藏
            val already = runBlocking { dataStoreManager.isFavoritePlaylist(playlist.id) }
            if (already) {
                return PlaylistAddResult(false, "该歌单已在收藏中")
            }

            // 存入收藏
            runBlocking {
                dataStoreManager.addFavoritePlaylist(
                    FavoritePlaylist(
                        playlistId = playlist.id,
                        name = playlist.name,
                        platform = playlist.platform,
                        coverUrl = playlist.coverUrl,
                        songCount = playlist.songCount,
                        creator = playlist.creator
                    )
                )
            }
            Log.d("LX-MainViewModel", "Web添加歌单成功: ${playlist.name} (${playlist.platform.name})")
            PlaylistAddResult(true, "已收藏歌单「${playlist.name}」")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "Web添加歌单失败: ${e.message}")
            PlaylistAddResult(false, "添加失败: ${e.message}")
        }
    }

    /**
     * 解析歌单链接，返回 (平台, 歌单ID)
     * 支持各平台常见歌单分享链接格式
     */
    private fun parsePlaylistUrl(url: String): Pair<MusicPlatform, String>? {
        val trimmed = url.trim()
        // 去掉查询参数中的井号锚点（网易云 #/playlist?id=xxx）
        val normalized = trimmed.replace("#/", "/")
        return try {
            val uri = java.net.URI(normalized)
            val host = uri.host?.lowercase() ?: return null
            val path = uri.path ?: ""
            val query = uri.query ?: ""

            // QQ音乐：y.qq.com/n/ryqq/playlist/{id} 或 i.y.qq.com/n2/m/share/details/taoge.html?id={id}
            if (host.contains("y.qq.com") || host.contains("qq.com")) {
                // /n/ryqq/playlist/{id} 路径格式
                val pathId = Regex("""playlist/(\d+)""").find(path)?.groupValues?.get(1)
                if (pathId != null) return MusicPlatform.TX to pathId
                // ?id={id} 查询参数格式（taoge.html / qzone 等）
                val qId = Regex("""(?:^|&)id=(\d+)""").find(query)?.groupValues?.get(1)
                if (qId != null) return MusicPlatform.TX to qId
                // disstid 参数
                val disstid = Regex("""(?:^|&)disstid=(\d+)""").find(query)?.groupValues?.get(1)
                if (disstid != null) return MusicPlatform.TX to disstid
                return null
            }

            // 网易云：music.163.com/#/playlist?id={id} 或 y.music.163.com/m/playlist?id={id}
            if (host.contains("music.163.com") || host.contains("163.com")) {
                val qId = Regex("""(?:^|&)id=(\d+)""").find(query)?.groupValues?.get(1)
                if (qId != null) return MusicPlatform.WY to qId
                val pathId = Regex("""playlist/(\d+)""").find(path)?.groupValues?.get(1)
                if (pathId != null) return MusicPlatform.WY to pathId
                return null
            }

            // 酷狗：kugou.com/yy/special/single/{id}.html
            if (host.contains("kugou.com")) {
                val pathId = Regex("""special/single/(\d+)""").find(path)?.groupValues?.get(1)
                if (pathId != null) return MusicPlatform.KG to pathId
                // t3.kugou.com 短链需跳转，直接返回提示不支持
                return null
            }

            // 酷我：kuwo.cn/playlist_detail/{id}
            if (host.contains("kuwo.cn")) {
                val pathId = Regex("""playlist_detail/(\d+)""").find(path)?.groupValues?.get(1)
                    ?: Regex("""playlist/(\d+)""").find(path)?.groupValues?.get(1)
                if (pathId != null) return MusicPlatform.KW to pathId
                // ?pid={id} 查询参数
                val qId = Regex("""(?:^|&)pid=(\d+)""").find(query)?.groupValues?.get(1)
                if (qId != null) return MusicPlatform.KW to qId
                return null
            }

            // 咪咕：music.migu.cn/v3/music/playlist/{id}
            if (host.contains("migu.cn")) {
                val pathId = Regex("""playlist/(\d+)""").find(path)?.groupValues?.get(1)
                if (pathId != null) return MusicPlatform.MG to pathId
                return null
            }

            null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "解析歌单链接失败: ${e.message}")
            null
        }
    }

    /**
     * 切换歌单收藏状态
     */
    fun toggleFavoritePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                val isFav = dataStoreManager.isFavoritePlaylist(playlist.id)
                if (isFav) {
                    dataStoreManager.removeFavoritePlaylist(playlist.id)
                    // 2.8 取消收藏歌单：移除该歌单的歌曲缓存保护 key（缓存管理页可清理其缓存）
                    removeFavoritePlaylistSongKeys(playlist.id)
                    _toastMessage.value = "已取消收藏歌单"
                } else {
                    dataStoreManager.addFavoritePlaylist(
                        FavoritePlaylist(
                            playlistId = playlist.id,
                            name = playlist.name,
                            platform = playlist.platform,
                            coverUrl = playlist.coverUrl,
                            songCount = playlist.songCount,
                            creator = playlist.creator
                        )
                    )
                    _toastMessage.value = "已收藏歌单"
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                _toastMessage.value = "操作失败: ${e.message}"
            }
        }
    }

    /**
     * 检查歌单是否已收藏
     */
    suspend fun isFavoritePlaylist(playlistId: String): Boolean {
        return try {
            dataStoreManager.isFavoritePlaylist(playlistId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            false
        }
    }

    /**
     * 打开收藏的歌单，加载歌单内歌曲列表（收藏页内展示）
     */
    fun openFavoritePlaylist(playlist: FavoritePlaylist) {
        // 递增令牌：快速切换/返回时，丢弃旧歌单的迟到结果，避免覆盖新歌单
        val token = ++favoritePlaylistLoadToken
        // 记录当前收藏歌单与平台，供「加载更多」续拉（仅酷狗签名接口按页返回）
        favoritePlaylistSongsPlatform = playlist.platform
        favoritePlaylistSongsId = playlist.playlistId
        favoritePlaylistSongsPage = 1
        _favoritePlaylistSongsHasMore.value = false
        _favoritePlaylistSongsLoadingMore.value = false
        viewModelScope.launch {
            // 加载前保持 null（界面停留在歌单列表），并置加载中状态，让界面立即显示转圈动画
            _favoritePlaylistSongs.value = null
            _favoritePlaylistLoading.value = true
            val songs = try {
                browseDataService.getPlaylistSongs(playlist.platform, playlist.playlistId, 1, 30)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载收藏歌单歌曲失败: ${e.message}")
                emptyList()
            }
            if (token != favoritePlaylistLoadToken) return@launch
            _favoritePlaylistSongs.value = songs
            _favoritePlaylistLoading.value = false
            // 2.8 收藏歌单歌曲按歌单 id 累积记入持久化集合：缓存管理页「清除未收藏缓存」不清除歌单歌曲缓存；
            // 取消收藏歌单时按 id 移除对应歌曲 key（见 toggleFavoritePlaylist）
            recordFavoritePlaylistSongKeys(playlist.playlistId, songs)
            // 仅酷狗支持按页续拉：本页返回数>=页大小则还有下一页
            _favoritePlaylistSongsHasMore.value = playlist.platform == MusicPlatform.KG && songs.size >= 30
            if (songs.isEmpty()) {
                _toastMessage.value = "该歌单暂无法加载歌曲"
            }
        }
    }

    /**
     * 收藏歌单详情滚动到底部时调用：加载下一页并追加（仅酷狗按页返回；其余平台无续拉）
     */
    fun loadMoreFavoritePlaylistSongs() {
        if (!_favoritePlaylistSongsHasMore.value || _favoritePlaylistSongsLoadingMore.value || favoritePlaylistSongsId.isEmpty()) return
        _favoritePlaylistSongsLoadingMore.value = true
        val next = favoritePlaylistSongsPage + 1
        val platform = favoritePlaylistSongsPlatform
        viewModelScope.launch {
            val batch = try {
                browseDataService.getPlaylistSongs(platform, favoritePlaylistSongsId, next, 30)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e("LX-MainViewModel", "加载更多收藏歌单歌曲失败: ${e.message}")
                emptyList()
            }
            _favoritePlaylistSongsLoadingMore.value = false
            if (batch.isNotEmpty()) {
                favoritePlaylistSongsPage = next
                _favoritePlaylistSongs.value = (_favoritePlaylistSongs.value ?: emptyList()) + batch
                _favoritePlaylistSongsHasMore.value = platform == MusicPlatform.KG && batch.size >= 30
            }
        }
    }

    /**
     * 2.8 收藏歌单的歌曲 key 按歌单 id 分组持久化（格式 "平台key|歌曲id"，与音频缓存 key 前缀一致）。
     * 缓存管理页「清除未收藏缓存」用此集合排除歌单歌曲，避免误删歌单歌曲缓存。
     * 存储格式（SharedPreferences `favorite_playlist_song_map`，无 JSON 依赖）：
     *   歌单id1|key1,key2;歌单id2|key3,key4
     * 取消收藏歌单时按 id 移除对应分组（见 removeFavoritePlaylistSongKeys），不会残留。
     * 上限：歌单 200 个，超出清空重建（下次打开歌单重新累积）。
     */
    private fun recordFavoritePlaylistSongKeys(playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        try {
            val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val map = decodeFavoritePlaylistSongMap(prefs.getString("favorite_playlist_song_map", "") ?: "")
            map[playlistId] = songs.map { "${it.platform.key}|${it.id}" }.toMutableSet()
            if (map.size > 200) map.clear()
            prefs.edit().putString("favorite_playlist_song_map", encodeFavoritePlaylistSongMap(map)).apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "记录收藏歌单歌曲失败: ${e.message}")
        }
    }

    /**
     * 2.8 取消收藏歌单时：移除该歌单的歌曲 key 分组（缓存保护随之失效，清除未收藏缓存可清理）
     */
    private fun removeFavoritePlaylistSongKeys(playlistId: String) {
        try {
            val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val map = decodeFavoritePlaylistSongMap(prefs.getString("favorite_playlist_song_map", "") ?: "")
            if (map.remove(playlistId) != null) {
                if (map.isEmpty()) {
                    prefs.edit().remove("favorite_playlist_song_map").apply()
                } else {
                    prefs.edit().putString("favorite_playlist_song_map", encodeFavoritePlaylistSongMap(map)).apply()
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
        }
    }

    /** 编码：Map<歌单id, 歌曲key集合> → "id|k1,k2;id|k3" */
    private fun encodeFavoritePlaylistSongMap(map: Map<String, Set<String>>): String =
        map.entries.joinToString(";") { (k, v) -> "$k|${v.joinToString(",")}" }

    /** 解码：还原为 MutableMap<歌单id, MutableSet<歌曲key>> */
    private fun decodeFavoritePlaylistSongMap(raw: String): MutableMap<String, MutableSet<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        if (raw.isBlank()) return map
        raw.split(";").forEach { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
                map[parts[0]] = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
            }
        }
        return map
    }

    /**
     * 从收藏歌单详情返回收藏列表
     */
    fun backFromFavoritePlaylistSongs() {
        _favoritePlaylistSongs.value = null
        _favoritePlaylistLoading.value = false
    }

    // ========== 工具方法 ==========

    /**
     * 清除提示消息
     */
    fun clearToastMessage() {
        _toastMessage.value = null
    }

    /**
     * 退出应用：停止播放并释放资源（首页退出确认对话框「退出」）
     *
     * 注意：必须同步执行（不放入 viewModelScope 协程）——因为调用方紧接着会
     * finishAffinity() 销毁 Activity，viewModelScope 会被取消，异步清理
     * 来不及执行，导致服务常驻、重进后播放异常。
     *
     * 服务的停止用显式 stopService（而非仅 stopSelf）：绑定中的服务调用
     * stopSelf 要等所有连接断开才 onDestroy，直接 stopService 可立即触发
     * 销毁流程，确保退出后服务彻底结束、重进应用可正常播放。
     */
    fun exitApplication() {
        Log.d("LX-MainViewModel", "[exit] 开始退出, isServiceBound=$isServiceBound, playerService=${if (playerService != null) "非空" else "null"}")
        // 1. 释放播放器 + 停前台通知
        try { playerService?.stopAndShutdown() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { Log.e("LX-MainViewModel", "[exit] stopAndShutdown异常: ${e.message}") }
        // 2. 解绑服务连接（必须先解绑再 stopService：绑定中服务要等绑定断开才销毁）
        if (isServiceBound) {
            try {
                app.unbindService(serviceConnection)
                Log.d("LX-MainViewModel", "[exit] unbindService 成功")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { Log.e("LX-MainViewModel", "[exit] unbindService异常: ${e.message}") }
            isServiceBound = false
            playerService = null
        }
        // 3. 显式停止服务
        try {
            app.stopService(Intent(app, PlayerService::class.java))
            Log.d("LX-MainViewModel", "[exit] stopService 调用完成")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { Log.e("LX-MainViewModel", "[exit] stopService异常: ${e.message}") }
        // 4. 停止 HTTP 服务器、清空状态与持久化
        try { httpServer?.stop() } catch (_: Exception) {}
        _currentSong.value = null
        _isPlaying.value = false
        clearCurrentSongPrefs()
        Log.d("LX-MainViewModel", "[exit] 退出清理完成")
        // 5. 杀掉进程：等同用户清除后台卡片。进程存活时 Application 单例复用、
        //    JS 引擎/数据库连接等不会重新初始化，会导致重进后播放异常。
        //    杀进程后重进必然是全新 Application，一切重新初始化。
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ========== 当前歌曲持久化（后台播放后进程重建恢复用） ==========

    /**
     * 把当前播放歌曲保存到 SharedPreferences，供进程被杀重建后恢复左下角播放卡片。
     * 后台播放时 PlayerService 是前台服务（START_STICKY）常驻；但 Activity/ViewModel
     * 可能被系统回收重建，此时需从本地恢复 currentSong 才能重新显示播放卡片。
     */
    private fun saveCurrentSongPrefs(song: Song) {
        try {
            val json = org.json.JSONObject().apply {
                put("id", song.id)
                put("name", song.name)
                put("singer", song.singer)
                put("albumName", song.albumName ?: "")
                put("albumId", song.albumId ?: "")
                put("picUrl", song.picUrl ?: "")
                put("duration", song.duration ?: 0L)
                put("platform", song.platform.name)
            }.toString()
            // 同时保存当前播放队列：后台播放进程重建后恢复播放列表（下一曲/上一曲可用）
            val listJson = org.json.JSONArray().apply {
                _playlist.value.forEach { s ->
                    put(org.json.JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("singer", s.singer)
                        put("albumName", s.albumName ?: "")
                        put("albumId", s.albumId ?: "")
                        put("picUrl", s.picUrl ?: "")
                        put("duration", s.duration ?: 0L)
                        put("platform", s.platform.name)
                    })
                }
            }.toString()
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("current_song_json", json)
                .putString("current_playlist_json", listJson)
                .apply()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "保存当前歌曲失败: ${e.message}", e)
        }
    }

    /**
     * 从 SharedPreferences 恢复当前歌曲（进程重建后调用）
     */
    private fun loadCurrentSongPrefs(): Song? {
        return try {
            val jsonStr = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("current_song_json", null) ?: return null
            val json = org.json.JSONObject(jsonStr)
            Song(
                id = json.getString("id"),
                name = json.getString("name"),
                singer = json.getString("singer"),
                albumName = json.optString("albumName").ifBlank { null },
                albumId = json.optString("albumId").ifBlank { null },
                picUrl = json.optString("picUrl").ifBlank { null },
                duration = json.optLong("duration", 0L).takeIf { it > 0 },
                platform = runCatching { MusicPlatform.valueOf(json.getString("platform")) }
                    .getOrDefault(MusicPlatform.KW)
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "恢复当前歌曲失败: ${e.message}", e)
            null
        }
    }

    /**
     * 从 SharedPreferences 恢复当前播放队列（后台播放进程重建后恢复播放列表）
     */
    private fun loadPlaylistPrefs(): List<Song> {
        return try {
            val listStr = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("current_playlist_json", null) ?: return emptyList()
            val arr = org.json.JSONArray(listStr)
            (0 until arr.length()).mapNotNull { i ->
                val j = arr.getJSONObject(i)
                runCatching {
                    Song(
                        id = j.getString("id"),
                        name = j.getString("name"),
                        singer = j.getString("singer"),
                        albumName = j.optString("albumName").ifBlank { null },
                        albumId = j.optString("albumId").ifBlank { null },
                        picUrl = j.optString("picUrl").ifBlank { null },
                        duration = j.optLong("duration", 0L).takeIf { it > 0 },
                        platform = runCatching { MusicPlatform.valueOf(j.getString("platform")) }
                            .getOrDefault(MusicPlatform.KW)
                    )
                }.getOrNull()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e("LX-MainViewModel", "恢复播放队列失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun clearCurrentSongPrefs() {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("current_song_json")
            .remove("current_playlist_json")
            .apply()
    }

    /**
     * 后台播放：返回桌面时保持音乐继续播放（首页退出确认对话框「后台播放」）
     * PlayerService 为前台服务（START_STICKY + 通知），应用退到后台不会停止
     */
    fun keepPlayingInBackground() {
        // 确保播放器在前台服务模式下运行（若正在播放则保持状态）
        if (_currentSong.value == null) {
            _toastMessage.value = "当前没有播放中的音乐"
        } else {
            _toastMessage.value = "已转入后台播放"
        }
    }

    /**
     * 设置播放器回调
     */
    private fun setupPlayerCallbacks() {
        playerService?.setOnPlayerStateListener(object : PlayerService.OnPlayerStateListener {
            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPositionChanged(position: Long, duration: Long) {
                if (duration > 0) {
                    _progress.value = position.toFloat() / duration.toFloat()
                    _duration.value = duration
                }
            }

            override fun onPlaybackCompleted() {
                _isPlaying.value = false
                _progress.value = 0f
                // 自动播放下一曲（2.7 音频缓存由 CacheDataSource 播放时自动写入，无需在此缓存）
                playNext()
            }

            // 2.8 实际播放音质：ExoPlayer 解析出的真实格式（覆盖 playSong 记录的请求音质）
            override fun onAudioFormatChanged(quality: AudioQuality) {
                Log.d("LX-MainViewModel", "onAudioFormatChanged: ${quality.displayName} (${quality.key})")
                _currentPlayQuality.value = quality
            }

            // 2.8 播放出错（坏 URL 等）：把当前 URL 来源的源记入本轮失败集合，
            // 按设置顺序自动切换到下一个源重新解析播放——不卡在同一个失败源上无限重试。
            //（本轮失败集合只影响本轮：用户下次主动播放/点播放会清空，源恢复后重新按顺序尝试。）
            // 所有源都失败后 doResolveAndPlay 返回失败 → 提示并停止，不再无限重试。
            override fun onPlaybackError() {
                // 播放失败标记：后续「播放/暂停」恢复时重新解析 URL（不再 resume 坏 MediaItem）
                playNeedsReFetch = true
                val song = _currentSong.value
                if (song == null) {
                    _isPlaying.value = false
                    return
                }
                // 当前 URL 来源的源加入本轮失败集合（URL 由 JS 源解析返回，必有来源；防御空值则不再重试）
                val badSourceId = currentPlaySourceId
                if (badSourceId == null) {
                    // 2.8 URL 来自缓存（URL 缓存命中未记录源）：移除失效的 URL 缓存 + 坏音频缓存，
                    // 重建播放器重新解析——重新解析会写新 URL 缓存；若新 URL 仍失败则有来源源 → 换源，
                    // 均有终点，不会死循环
                    Log.w("LX-MainViewModel", "播放出错且无来源源（缓存 URL），移除失效缓存并重新解析: ${song.name}")
                    val badKey = CacheManager.songCacheKey(song, _preferredQuality.value)
                    try {
                        CacheManager.removeUrl(badKey)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                        Log.w("LX-MainViewModel", "移除失效 URL 缓存失败: ${e.message}")
                    }
                    try {
                        CacheManager.getAudioCache(app).removeResource(badKey)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                        Log.w("LX-MainViewModel", "移除坏音频缓存失败: ${e.message}")
                    }
                    viewModelScope.launch {
                        playerService?.rebuildPlayer()
                        doResolveAndPlay(song, _playlist.value, _preferredQuality.value)
                    }
                    return
                }
                tryFailedSourceIds.add(badSourceId)
                Log.w("LX-MainViewModel", "播放出错，源[$badSourceId] URL 无效，按顺序切下一个源: ${song.name}（本轮已失败 ${tryFailedSourceIds.size} 个源）")
                viewModelScope.launch {
                    // 2.8 换源重试前重建播放器：ExoPlayer 报错后处于 ERROR 状态，
                    // 不重建直接 setMediaItem/prepare 会立即再次触发错误，导致后续源全部「秒失败」跳过
                    playerService?.rebuildPlayer()
                    _progress.value = 0f
                    _duration.value = 0L
                    doResolveAndPlay(song, _playlist.value, _preferredQuality.value)
                }
            }
        })

        // 服务绑定/重建后：同步真实播放状态（后台播放恢复时服务可能仍在播放）
        runCatching { playerService?.isPlaying() }.getOrNull()?.let { playing ->
            _isPlaying.value = playing
            // Activity/ViewModel 被系统重建、但后台播放服务仍在播（前台服务常驻）时：
            // 从 SharedPreferences 恢复当前歌曲与播放队列，重新显示左下角播放卡片。
            // 仅当服务确实在播放时才恢复——冷启动（服务未播）不恢复，避免出现点击无法播放的假卡片。
            if (playing && _currentSong.value == null) {
                loadCurrentSongPrefs()?.let { song ->
                    _currentSong.value = song
                    val savedList = loadPlaylistPrefs()
                    if (savedList.isNotEmpty()) {
                        _playlist.value = savedList
                    }
                    // 补加载歌词，避免播放页显示"无歌词"
                    _currentLyric.value = null
                    _currentLyricTranslation.value = null
                    loadLyrics(song)
                    // 同步进度/时长
                    val pos = runCatching { playerService?.getCurrentPosition() }.getOrNull() ?: 0L
                    val dur = runCatching { playerService?.getDuration() }.getOrNull() ?: 0L
                    if (dur > 0) {
                        _progress.value = pos.toFloat() / dur.toFloat()
                        _duration.value = dur
                    }
                }
            }
        }

        // 恢复的歌曲若是服务当前播放的（或刚恢复），补加载歌词，避免播放页显示"无歌词"
        val song = _currentSong.value
        if (song != null && _currentLyric.value == null) {
            loadLyrics(song)
        }
    }

    /**
     * 启动进度更新
     */
    private fun startProgressUpdater() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (_isPlaying.value) {
                    playerService?.let { player ->
                        // ExoPlayer 的进度由回调更新，这里不需要手动更新
                    }
                }
            }
        }
    }

    /**
     * 绑定播放器服务
     */
    private fun bindPlayerService() {
        Log.d("LX-MainViewModel", "[service] bindPlayerService 调用, isServiceBound=$isServiceBound")
        val intent = Intent(app, PlayerService::class.java)
        val ok = app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d("LX-MainViewModel", "[service] bindService 返回: $ok")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("LX-MainViewModel", "[service] onCleared, isServiceBound=$isServiceBound")
        // 解绑服务
        if (isServiceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { Log.e("LX-MainViewModel", "[service] onCleared unbind异常: ${e.message}") }
            isServiceBound = false
        }
        // 停止服务器
        httpServer?.stop()
        // 注意：不在此处 sourceManager.release()！
        // sourceManager 是 Application 级单例，生命周期比 ViewModel 长。若 ViewModel 销毁
        // 即释放 JS 引擎，而进程仍存活时 Application 复用、init() 不会重新执行，
        // 会导致重进应用后 JS 源"源执行器未初始化"、无法播放。
        // JS 引擎只随进程销毁（Application onCreate 重新 init）。
    }
}
