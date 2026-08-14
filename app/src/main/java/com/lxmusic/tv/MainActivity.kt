package com.lxmusic.tv

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MusicNote
import androidx.navigation.compose.currentBackStackEntryAsState
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.RemoteImage
import com.lxmusic.tv.presentation.screen.MainScreen
import com.lxmusic.tv.presentation.screen.CacheManageScreen
import com.lxmusic.tv.presentation.screen.PlayerScreen
import com.lxmusic.tv.presentation.screen.SearchResultScreen
import com.lxmusic.tv.presentation.screen.SourceManagementScreen
import com.lxmusic.tv.presentation.screen.InterfaceSettingsScreen
import com.lxmusic.tv.presentation.screen.DiscCoverButton
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXMusicTheme
import com.lxmusic.tv.presentation.theme.LXPrimary
import com.lxmusic.tv.presentation.theme.LXSurfaceDialog
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXThemeMode
import com.lxmusic.tv.presentation.theme.currentThemeColor
import com.lxmusic.tv.presentation.theme.currentThemeMode
import com.lxmusic.tv.presentation.theme.initThemeState
import com.lxmusic.tv.presentation.theme.setThemeColor
import com.lxmusic.tv.presentation.theme.setThemeMode
import com.lxmusic.tv.viewmodel.MainViewModel

/**
 * LX Music TV - 主Activity
 * Android TV 音乐播放器
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 启动时先恢复持久化的主题模式/主题色（模块级单一数据源，见 Theme.kt）
        initThemeState(applicationContext)
        setContent {
            // 主题模式：读取模块级 State（currentThemeMode），与界面设置页写入的是同一份，
            // 规避 NavHost 目的地内 viewModel() 为返回栈条目作用域、主 Activity 收不到改动的坑
            val themeMode = currentThemeMode
            // 主题模式：浅色/深色（界面设置持久化；默认浅色）。切换后整个应用（含 Material 主题与自定义令牌）随之重设。
            LXMusicTheme(darkTheme = themeMode == LXThemeMode.DARK) {
                LXMusicApp()
            }
        }
    }
}

/**
 * 主应用Composable
 */
@Composable
fun LXMusicApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val context = LocalContext.current

    // 首页 tab 状态提升到 LXMusicApp 层级（NavHost 外部），
    // 导航到子页面（source_management / player 等）再返回时保持当前 tab 不重置到歌单首页
    var homeTab by remember { mutableIntStateOf(0) }
    // 重复点击当前 tab 的刷新信号：点击与当前相同的 tab 时 +1，
    // 歌单/排行页监听后重置滚动位置并重新加载（用户要求重复点击生效刷新）
    var tabRefreshTick by remember { mutableIntStateOf(0) }

    // 监听Toast消息
    // 2.8 Toast 队列：依次显示（每条约 2.2s），不再快速替换——
    // 多源切换时「A失败→切B」「正在尝试B」「B失败→切D」…完整提示链用户都能看到。
    //（旧实现是替换式：A 失败 toast 刚设置就被「正在尝试B」覆盖，用户永远看不到失败提示）
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toast = remember { arrayOfNulls<Toast>(1) }
    val toastQueue = remember { mutableStateListOf<String>() }
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            // 队列上限：极端连续提示时丢弃最旧的（保留最新），避免无限堆积
            if (toastQueue.size >= 8) toastQueue.removeAt(0)
            toastQueue.add(it)
            viewModel.clearToastMessage()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (toastQueue.isEmpty()) {
                delay(150)
                continue
            }
            val msg = toastQueue.removeAt(0)
            val t = toast[0] ?: Toast.makeText(context, msg, Toast.LENGTH_SHORT).also { toast[0] = it }
            t.setText(msg)
            t.show()
            // 等当前提示显示完再取下一条（Toast LENGTH_SHORT ≈ 2s）
            delay(2200)
        }
    }

    // 悬浮「正在播放」按钮：除首页(main)与播放页(player)外，有歌曲播放时才显示
    //（2.6 主页已有侧栏底部正在播放入口，右上角悬浮球在主页隐藏，避免冗余）
    val floatingCurrentSong by viewModel.currentSong.collectAsState()
    val floatingIsPlaying by viewModel.isPlaying.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showFloatingBall = floatingCurrentSong != null &&
        currentRoute != null && currentRoute != "player" && currentRoute != "main"

    // 2.8 悬浮球焦点请求器：子页面（播放源管理/缓存管理等）返回按钮按「右键」显式聚焦悬浮球
    //（悬浮层不与内容区做空间焦点竞争，方向键自然导航到不了，需显式桥接）
    val floatingBallRequester = remember { FocusRequester() }

    // 2.8 同步当前导航路由到 VM：Web 端搜索推送/清空仅在「主页（main）的搜索 tab」生效，
    // 搜索结果页（search_result）等独立路由不生效
    LaunchedEffect(currentRoute) {
        viewModel.setCurrentRoute(currentRoute ?: "main")
    }

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("main") {
            val currentSong by viewModel.currentSong.collectAsState()
            val isPlaying by viewModel.isPlaying.collectAsState()
            val defaultPlatform by viewModel.defaultPlatform.collectAsState()
            val preferredQuality by viewModel.preferredQuality.collectAsState()
            // 2.8 歌词设置：是否显示翻译歌词
            val lyricTranslationEnabled by viewModel.lyricTranslationEnabled.collectAsState()
            val browseItems by viewModel.browseItems.collectAsState()
            val browseSongs by viewModel.browseSongs.collectAsState()
            val browseLoading by viewModel.browseLoading.collectAsState()
            val browseSongsError by viewModel.browseSongsError.collectAsState()
            val favorites by viewModel.favorites.collectAsState()
            val favoritePlaylists by viewModel.favoritePlaylists.collectAsState()
            val favoritePlaylistSongs by viewModel.favoritePlaylistSongs.collectAsState()
            val favoritePlaylistLoading by viewModel.favoritePlaylistLoading.collectAsState()
            val favoritePlaylistSongsHasMore by viewModel.favoritePlaylistSongsHasMore.collectAsState()
            val favoritePlaylistSongsLoadingMore by viewModel.favoritePlaylistSongsLoadingMore.collectAsState()
            val favoriteSongIds by viewModel.favoriteSongIds.collectAsState()
            val favoritePlaylistIds by viewModel.favoritePlaylistIds.collectAsState()
            // 搜索页状态（2.6 搜索内嵌为 tab，在 main 路由内 collect 并透传给 MainScreen）
            val searchQuery by viewModel.searchQuery.collectAsState()
            val searchPlatform by viewModel.searchPlatform.collectAsState()
            val searchType by viewModel.searchType.collectAsState()
            val serverUrl by viewModel.serverUrl.collectAsState()
            val hotKeywords by viewModel.hotKeywords.collectAsState()
            val suggestions by viewModel.suggestions.collectAsState()
            val hotSongs by viewModel.hotSongs.collectAsState()
            val searchHistory by viewModel.searchHistory.collectAsState()

            // ===== 首页返回退出确认 =====
            var showExitDialog by remember { mutableStateOf(false) }
            // 退出弹窗默认焦点落在「退出」按钮（而非「后台播放」），符合用户预期
            val exitConfirmFocusRequester = remember { FocusRequester() }
            val activity = context as? android.app.Activity

            // 侧栏（tab 栏）是否持有焦点：用于「返回键先回 tab、再按才弹退出确认」
            var navBarHasFocus by remember { mutableStateOf(true) }
            // 返回键第一次按下（焦点在内容区）时递增，通知 MainScreen 聚焦选中 tab
            var navFocusRequestTick by remember { mutableIntStateOf(0) }

            // 触屏设备（手机/平板）没有遥控器「焦点在侧栏」的语义，navBarHasFocus 不可靠：
            // 直接弹退出确认框；仅 TV（LEANBACK）保留「先回 tab、再按才弹框」的两级逻辑
            val isTvDevice = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

            // 首页返回键：焦点不在侧栏时第一次返回先回 tab（不弹窗），第二次才弹退出确认
            BackHandler {
                if (!isTvDevice || navBarHasFocus) {
                    showExitDialog = true
                } else {
                    navFocusRequestTick++
                }
            }

            if (showExitDialog) {
                // 弹窗出现后将焦点定位到「退出」按钮
                LaunchedEffect(Unit) {
                    exitConfirmFocusRequester.requestFocus()
                }
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    // 弹窗背景：统一为灰白（LXSurfaceDialog #F0F0F2，避免纯白刺眼；与设置页/音源管理弹窗一致）
                    containerColor = LXSurfaceDialog,
                    title = { Text("退出 LX Music TV？", fontSize = 20.sp, color = LXTextPrimary) },
                    text = {
                        Text(
                            text = "选择操作方式：\n\n· 退出：停止播放并关闭应用\n· 后台播放：返回桌面，音乐继续播放",
                            fontSize = 15.sp,
                            color = LXTextPrimary
                        )
                    },
                    confirmButton = {
                        // 退出：停止播放并关闭应用（默认聚焦此按钮）——无背景文本按钮，红字醒目
                        TextButton(
                            modifier = Modifier.focusRequester(exitConfirmFocusRequester),
                            onClick = {
                                showExitDialog = false
                                android.util.Log.d("LX-MainActivity", "[exit] 用户点击退出")
                                // 同步停服务/释放资源（内部已 stopService + 解绑），
                                // 完成后彻底结束 Activity 并移除任务栈（等同清除后台卡片）
                                viewModel.exitApplication()
                                activity?.finishAffinity()
                                activity?.finishAndRemoveTask()
                                android.util.Log.d("LX-MainActivity", "[exit] finishAffinity+finishAndRemoveTask 已调用")
                            }) {
                            Text("退出", color = LXPrimary)
                        }
                    },
                    dismissButton = {
                        // 后台播放：返回桌面，音乐继续（再次按返回键可关闭弹窗）——无背景文本按钮
                        TextButton(onClick = {
                            showExitDialog = false
                            viewModel.keepPlayingInBackground()
                            activity?.moveTaskToBack(true)
                        }) {
                            Text("后台播放", color = LXTextPrimary)
                        }
                    }
                )
            }

            MainScreen(
                onNavigateToPlaylist = { navController.navigate("main") },
                onNavigateToRanking = { navController.navigate("main") },
                onNavigateToSettings = { navController.navigate("main") },
                onNavigateToSourceManagement = { navController.navigate("source_management") },
                onNavigateToInterfaceSettings = { navController.navigate("interface_settings") },
                // 2.8 缓存管理独立子页面
                onNavigateToCacheManage = { navController.navigate("cache_manage") },
                onNavigateToPlayer = {
                    // 有正在播放的歌曲时才允许进入播放页
                    if (viewModel.currentSong.value != null) {
                        navController.navigate("player")
                    }
                },
                currentSong = currentSong,
                isPlaying = isPlaying,
                onPlayPause = { viewModel.togglePlayPause() },
                defaultPlatform = defaultPlatform,
                onDefaultPlatformChange = { viewModel.setDefaultPlatform(it) },
                preferredQuality = preferredQuality,
                onPreferredQualityChange = { viewModel.setPreferredQuality(it) },
                // 2.8 歌词设置：是否显示翻译歌词
                lyricTranslationEnabled = lyricTranslationEnabled,
                onLyricTranslationEnabledChange = { viewModel.setLyricTranslationEnabled(it) },
                browseItems = browseItems,
                browseSongs = browseSongs,
                browseLoading = browseLoading,
                browseSongsError = browseSongsError,
                onLoadBrowse = { viewModel.loadBrowseItems(it) },
                onLoadMoreBrowse = { viewModel.loadMoreBrowseItems(it) },
                onLoadSongs = { id, isRanking -> viewModel.loadBrowseSongs(id, isRanking) },
                onLoadMoreSongs = { viewModel.loadMoreBrowseSongs() },
                onLoadHotSongs = { viewModel.loadHotSongs() },
                onPlaySong = { song, songs ->
                    viewModel.playSong(song, songs)
                    navController.navigate("player")
                },
                onPlaySongStay = { song, _ ->
                    // ① 播放本首但停留在当前页（不进入播放页），仅播放当前歌曲（不入队整列表）
                    viewModel.playSong(song, listOf(song))
                },
                onPlayBrowseList = { song ->
                    // ② 整列表播放并进入播放页：先进入播放页，再由 VM 补拉完整列表后建立队列播放
                    navController.navigate("player")
                    viewModel.playBrowseAll(song)
                },
                onPlayAll = { songs ->
                    viewModel.playPlaylist(songs)
                    navController.navigate("player")
                },
                favorites = favorites,
                favoritePlaylists = favoritePlaylists,
                favoritePlaylistSongs = favoritePlaylistSongs,
                favoritePlaylistLoading = favoritePlaylistLoading,
                favoritePlaylistSongsHasMore = favoritePlaylistSongsHasMore,
                favoritePlaylistSongsLoadingMore = favoritePlaylistSongsLoadingMore,
                onLoadMoreFavoritePlaylistSongs = { viewModel.loadMoreFavoritePlaylistSongs() },
                // 收藏歌单详情②「整列表播放」：先进播放页，由 VM 补拉完整队列再播
                onPlayPlaylistSongsAll = { song ->
                    navController.navigate("player")
                    viewModel.playFavoritePlaylistSongsAll(song)
                },
                favoriteSongIds = favoriteSongIds,
                favoritePlaylistIds = favoritePlaylistIds,
                onToggleSongFavorite = { viewModel.toggleFavorite(it) },
                onTogglePlaylistFavorite = { viewModel.toggleFavoritePlaylist(it) },
                onOpenFavoritePlaylist = { viewModel.openFavoritePlaylist(it) },
                onBackFromFavoritePlaylistSongs = { viewModel.backFromFavoritePlaylistSongs() },
                selectedTab = homeTab,
                // ===== 搜索页内嵌参数（2.6）=====
                searchQuery = searchQuery,
                searchPlatform = searchPlatform,
                searchType = searchType,
                hotKeywords = hotKeywords,
                suggestions = suggestions,
                hotSongs = hotSongs,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onSearch = { keyword, platform ->
                    viewModel.search(keyword, platform)
                    navController.navigate("search_result")
                },
                onSearchPlaylist = { keyword, platform ->
                    viewModel.searchPlaylist(keyword, platform)
                    navController.navigate("search_result")
                },
                onSearchTypeChange = { viewModel.setSearchType(it) },
                // 搜索历史（输入为空时中间列展示；清空按钮）
                searchHistory = searchHistory,
                onClearSearchHistory = { viewModel.clearSearchHistory() },
                // 2.8 搜索页扫码推送弹窗需要服务器地址
                serverUrl = serverUrl,
                // 2.8 二维码按钮：HTTP 未开启时询问是否启用
                onEnableServer = { viewModel.startServer() },
                onTabSelected = { newTab ->
                    // 2.8 同步当前 tab 到 VM：Web 端搜索推送/清空仅在搜索页（3）生效
                    viewModel.setCurrentTab(newTab)
                    // 重复点击当前 tab → 触发刷新（歌单/排行重置滚动并重新加载）
                    if (newTab == homeTab) {
                        tabRefreshTick++
                    }
                    // 切到搜索 tab 时清空上次输入，避免残留关键词（原 navigate("search") 时的行为）
                    if (newTab == 3) {
                        viewModel.updateSearchQuery("")
                    }
                    // 切走收藏页时退出歌单详情（清除歌曲列表状态），
                    // 避免切回收藏页仍停留在歌单歌曲列表、顶部「歌曲/歌单」无法切换
                    if (newTab != 2) {
                        viewModel.backFromFavoritePlaylistSongs()
                    }
                    homeTab = newTab
                },
                // 重复点击 tab 的刷新信号（歌单/排行页监听）
                tabRefreshTick = tabRefreshTick,
                // 侧栏焦点状态上报（返回键先回 tab 逻辑）
                onNavBarFocusChanged = { navBarHasFocus = it },
                // 返回键第一次按下（焦点在内容区）：通知 MainScreen 聚焦选中 tab
                navFocusRequestTick = navFocusRequestTick
            )
        }

        composable("search_result") {
            val searchResults by viewModel.searchResults.collectAsState()
            val isSearching by viewModel.isSearching.collectAsState()
            val searchError by viewModel.searchError.collectAsState()
            val searchQuery by viewModel.searchQuery.collectAsState()
            val searchPlatform by viewModel.searchPlatform.collectAsState()
            val searchType by viewModel.searchType.collectAsState()
            val serverUrl by viewModel.serverUrl.collectAsState()
            val searchTriggered by viewModel.searchTriggered.collectAsState()
            val playlistResults by viewModel.playlistResults.collectAsState()
            val playlistSongs by viewModel.playlistSongs.collectAsState()
            val isPlaylistSongsLoading by viewModel.isPlaylistSongsLoading.collectAsState()
            val playlistSongsError by viewModel.playlistSongsError.collectAsState()
            val playlistSongsHasMore by viewModel.playlistSongsHasMore.collectAsState()
            val playlistSongsLoadingMore by viewModel.playlistSongsLoadingMore.collectAsState()
            val currentSong by viewModel.currentSong.collectAsState()
            val isPlaying by viewModel.isPlaying.collectAsState()
            val favoriteSongIds by viewModel.favoriteSongIds.collectAsState()
            val favoritePlaylistIds by viewModel.favoritePlaylistIds.collectAsState()

            SearchResultScreen(
                searchQuery = searchQuery,
                searchType = searchType,
                searchPlatform = searchPlatform,
                searchResults = searchResults,
                playlistResults = playlistResults,
                playlistSongs = playlistSongs,
                isPlaylistSongsLoading = isPlaylistSongsLoading,
                playlistSongsError = playlistSongsError,
                playlistSongsHasMore = playlistSongsHasMore,
                playlistSongsLoadingMore = playlistSongsLoadingMore,
                onLoadMorePlaylistSongs = { viewModel.loadMorePlaylistSongs() },
                isSearching = isSearching,
                searchError = searchError,
                searchTriggered = searchTriggered,
                currentSongId = currentSong?.id,
                isPlaying = isPlaying,
                onBack = { navController.popBackStack() },
                onSearchTypeChange = { viewModel.setSearchType(it) },
                onPlaylistClick = { viewModel.openPlaylist(it) },
                onBackFromPlaylistSongs = { viewModel.backFromPlaylistSongs() },
                onSongClick = { song ->
                    if (playlistSongs != null) {
                        // 歌单详情（酷狗分页可能只加载了第一页）：先进播放页，由 VM 补拉完整队列再播
                        navController.navigate("player")
                        viewModel.playPlaylistSongsAll(song)
                    } else {
                        // 歌曲搜索结果：用搜索结果作为播放队列
                        viewModel.playSong(song, searchResults)
                        navController.navigate("player")
                    }
                },
                // 三按钮①：仅播放本首、停留在当前页（不进入播放页），不入队整列表
                onPlaySongStay = { song ->
                    viewModel.playSong(song, listOf(song))
                },
                isSongFavorite = { id -> favoriteSongIds.contains(id) },
                onToggleSongFavorite = { viewModel.toggleFavorite(it) },
                isPlaylistFavorite = { id -> favoritePlaylistIds.contains(id) },
                onTogglePlaylistFavorite = { viewModel.toggleFavoritePlaylist(it) }
            )
        }

        composable("source_management") {
            val sources by viewModel.sources.collectAsState()
            val serverRunning by viewModel.serverRunning.collectAsState()
            val serverUrl by viewModel.serverUrl.collectAsState()

            SourceManagementScreen(
                sources = sources,
                serverRunning = serverRunning,
                serverUrl = serverUrl,
                onToggleServer = { viewModel.toggleServer() },
                onToggleSource = { sourceId, enabled -> viewModel.toggleSource(sourceId, enabled) },
                onDeleteSource = { sourceId -> viewModel.deleteSource(sourceId) },
                onBack = { navController.popBackStack() },
                // 2.8 返回按钮按右键 → 聚焦右上角悬浮播放球
                onFocusFloatingBall = { floatingBallRequester.requestFocus() },
                onGetSourcePlatforms = { sourceId -> viewModel.getSourcePlatforms(sourceId) },
                onSetSourcePlatforms = { sourceId, platforms ->
                    viewModel.setSourcePlatforms(sourceId, platforms)
                }
            )
        }

        // 2.8 缓存管理（独立子页面）
        composable("cache_manage") {
            val favorites by viewModel.favorites.collectAsState()
            CacheManageScreen(
                favorites = favorites,
                onBack = { navController.popBackStack() },
                // 2.8 返回按钮按右键 → 聚焦右上角悬浮播放球
                onFocusFloatingBall = { floatingBallRequester.requestFocus() },
                // 2.8 清除音频缓存后重建播放器（避免旧 SimpleCache 失效导致后续播放失败）
                onCacheCleared = { viewModel.notifyAudioCacheCleared() },
                modifier = Modifier.fillMaxSize()
            )
        }

        composable("player") {
            val currentSong by viewModel.currentSong.collectAsState()
            val isPlaying by viewModel.isPlaying.collectAsState()
            val progress by viewModel.progress.collectAsState()
            val duration by viewModel.duration.collectAsState()
            val currentLyric by viewModel.currentLyric.collectAsState()
            // 2.8 翻译歌词 + 显示开关
            val currentLyricTranslation by viewModel.currentLyricTranslation.collectAsState()
            val lyricTranslationEnabled by viewModel.lyricTranslationEnabled.collectAsState()
            // 2.8 实际播放音质
            val currentPlayQuality by viewModel.currentPlayQuality.collectAsState()
            val playMode by viewModel.playMode.collectAsState()
            val playlist by viewModel.playlist.collectAsState()
            val favoriteSongIds by viewModel.favoriteSongIds.collectAsState()

            // 播放页没有歌曲时自动返回；但酷狗「整列表播放」是"先进播放页、后台补拉队列再播放"，
            // 刚进入瞬间 currentSong 仍为 null——若立即返回会把补拉中的播放页弹走（表现为"会播放但不跳转"）。
            // 宽限等待：8s 内 currentSong 被补拉设置（key 变化重启本协程）则正常显示；仍为 null 才自动返回。
            LaunchedEffect(currentSong) {
                if (currentSong == null) {
                    delay(8_000)
                    if (currentSong == null) {
                        navController.popBackStack()
                    }
                }
            }

            PlayerScreen(
                currentSong = currentSong,
                isPlaying = isPlaying,
                progress = progress,
                totalDurationMs = duration,
                currentLyric = currentLyric,
                // 2.8 翻译歌词 + 开关
                currentLyricTranslation = currentLyricTranslation,
                lyricTranslationEnabled = lyricTranslationEnabled,
                // 2.8 实际播放音质
                currentPlayQuality = currentPlayQuality,
                playlist = playlist,
                playMode = playMode,
                isFavorite = currentSong?.let { favoriteSongIds.contains(it.id) } ?: false,
                onToggleFavorite = currentSong?.let { song -> { viewModel.toggleFavorite(song) } },
                onPlayModeChange = { viewModel.setPlayMode(it) },
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.playNext() },
                onPrevious = { viewModel.playPrevious() },
                onSeek = { viewModel.seekTo(it) },
                onBack = { navController.popBackStack() },
                onSongSelect = { song -> viewModel.playSong(song, playlist) }
            )
        }

        composable("interface_settings") {
            // 直接读写模块级主题状态（与主 Activity 同一份），不再经 ViewModel 中转
            val context = LocalContext.current
            InterfaceSettingsScreen(
                themeMode = currentThemeMode,
                themeColor = currentThemeColor,
                onThemeModeChange = { setThemeMode(it, context) },
                onThemeColorChange = { setThemeColor(it, context) },
                onBack = { navController.popBackStack() }
            )
        }
    }

        // 悬浮「正在播放」按钮：非播放页且有歌曲播放时显示（含首页），点击进入播放页。
        // 置于右上角；搜索结果页的「歌曲/歌单」切换按钮已左移为其让位（见 SearchResultScreen）。
        // 2.8 用 if 而非 AnimatedVisibility：动画过渡层曾导致方向键焦点无法切入该悬浮按钮
        if (showFloatingBall) {
            FloatingNowPlayingButton(
                coverUrl = floatingCurrentSong?.picUrl,
                isPlaying = floatingIsPlaying,
                onClick = { navController.navigate("player") },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 20.dp)
                    // 2.8 供子页面「返回按钮按右键」显式聚焦
                    .focusRequester(floatingBallRequester)
            )
        }
    }
}

/**
 * 悬浮「正在播放」按钮（仿菠萝音乐的播放浮球）
 * 仅在有歌曲播放、且不在播放页时出现（首页也会显示）；点击跳转播放页。
 * 圆形浮球显示正在播放歌曲封面，2.8 取消右下角播放/暂停小标，
 * 封面改为随播放状态旋转：播放中匀速旋转（1.2s/圈），暂停停止并归 0。
 */
@Composable
fun FloatingNowPlayingButton(
    coverUrl: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 仿菠萝音乐：圆形浮球显示正在播放歌曲封面，点击进入播放页
    // 2.8 封面旋转动画：播放中缓慢旋转（8s/圈），暂停停止并平滑归 0
    val coverRotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                coverRotation.animateTo(
                    coverRotation.value + 360f,
                    animationSpec = tween(durationMillis = 8000, easing = LinearEasing)
                )
            }
        } else {
            coverRotation.animateTo(0f, animationSpec = tween(durationMillis = 300))
        }
    }

    // 2.8 复用公共黑胶光盘组件 DiscCoverButton（与主页 NowPlayingBar 完全一致：48dp/环6/封面36）。
    // 不用 IconButton：其内部强制 40dp + 自带状态层，会导致封面非标准圆形/多出一圈
    DiscCoverButton(
        onClick = onClick,
        modifier = modifier,
        rotation = coverRotation.value
    ) {
        if (!coverUrl.isNullOrBlank()) {
            RemoteImage(
                url = coverUrl,
                contentDescription = "正在播放，点击进入播放页",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = "正在播放，点击进入播放页",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
