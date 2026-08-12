package com.lxmusic.tv.presentation.screen

import com.lxmusic.tv.R

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import android.os.Build
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.model.AudioQuality
import com.lxmusic.tv.data.model.BrowseItem
import com.lxmusic.tv.data.model.BrowseType
import com.lxmusic.tv.data.model.Favorite
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.data.model.SearchType
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.component.RemoteImage
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.lxFocusBorder
import com.lxmusic.tv.presentation.component.lxSelectorFocus
import com.lxmusic.tv.presentation.component.lxCircleButtonFocus
import com.lxmusic.tv.presentation.component.requestInitialFocus
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXAccentGradientBrush
import com.lxmusic.tv.presentation.theme.LXPrimary
import com.lxmusic.tv.presentation.theme.LXSidebarGradientBrush
import com.lxmusic.tv.presentation.theme.LXCardDark
import com.lxmusic.tv.presentation.theme.LXSurfaceDialog
import com.lxmusic.tv.presentation.theme.LXSurfaceMain
import com.lxmusic.tv.presentation.theme.LXSurfaceSidebar
import com.lxmusic.tv.presentation.theme.LXSurfaceCard
import com.lxmusic.tv.presentation.theme.LXSurfaceVariant
import com.lxmusic.tv.presentation.theme.LXBorder
import com.lxmusic.tv.presentation.theme.LXFocusFill
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXTextSecondary
import com.lxmusic.tv.presentation.theme.LXOnCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDarkSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面
 * TV端音乐播放器主界面，包含导航菜单和主要功能区域
 */
@Composable
fun MainScreen(
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToRanking: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSourceManagement: () -> Unit,
    onNavigateToInterfaceSettings: () -> Unit = {},
    // 2.8 缓存管理独立子页面
    onNavigateToCacheManage: () -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    currentSong: com.lxmusic.tv.data.model.Song? = null,
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit = {},
    defaultPlatform: MusicPlatform = MusicPlatform.KW,
    onDefaultPlatformChange: (MusicPlatform) -> Unit = {},
    preferredQuality: AudioQuality = AudioQuality.QUALITY_320K,
    onPreferredQualityChange: (AudioQuality) -> Unit = {},
    // 2.8 歌词设置：是否显示翻译歌词
    lyricTranslationEnabled: Boolean = true,
    onLyricTranslationEnabledChange: (Boolean) -> Unit = {},
    browseItems: List<BrowseItem> = emptyList(),
    browseSongs: List<Song> = emptyList(),
    browseLoading: Boolean = false,
    browseSongsError: String? = null,
    onLoadBrowse: (BrowseType) -> Unit = {},
    onLoadMoreBrowse: (BrowseType) -> Unit = {},
    onLoadSongs: (String, Boolean) -> Unit = { _, _ -> },
    onLoadMoreSongs: () -> Unit = {},
    onLoadHotSongs: () -> Unit = {},
    onPlaySong: (Song, List<Song>) -> Unit = { _, _ -> },
    onPlaySongStay: (Song, List<Song>) -> Unit = { _, _ -> },
    onPlayAll: (List<Song>) -> Unit = {},
    // 整列表播放（歌单/榜单详情歌曲行②：先补拉完整列表再播，避免只播已加载首页）
    onPlayBrowseList: (Song) -> Unit = {},
    // 收藏相关
    favorites: List<com.lxmusic.tv.data.model.Favorite> = emptyList(),
    favoritePlaylists: List<com.lxmusic.tv.data.model.FavoritePlaylist> = emptyList(),
    favoritePlaylistSongs: List<Song>? = null,
    favoritePlaylistLoading: Boolean = false,
    // 收藏歌单详情分页续拉（酷狗）：焦点触底加载下一页
    favoritePlaylistSongsHasMore: Boolean = false,
    favoritePlaylistSongsLoadingMore: Boolean = false,
    onLoadMoreFavoritePlaylistSongs: () -> Unit = {},
    onPlayPlaylistSongsAll: (Song) -> Unit = {},
    favoriteSongIds: Set<String> = emptySet(),
    favoritePlaylistIds: Set<String> = emptySet(),
    onToggleSongFavorite: (Song) -> Unit = {},
    onTogglePlaylistFavorite: (com.lxmusic.tv.data.model.Playlist) -> Unit = {},
    onOpenFavoritePlaylist: (com.lxmusic.tv.data.model.FavoritePlaylist) -> Unit = {},
    onBackFromFavoritePlaylistSongs: () -> Unit = {},
    // ===== 搜索页（2.6 起内嵌为 tab，保留左侧导航栏，不再跳转独立页面）=====
    searchQuery: String = "",
    searchPlatform: MusicPlatform = MusicPlatform.KW,
    searchType: SearchType = SearchType.SONG,
    hotKeywords: List<String> = emptyList(),
    suggestions: List<String> = emptyList(),
    hotSongs: List<Song> = emptyList(),
    searchHistory: List<String> = emptyList(),
    onSearchQueryChange: (String) -> Unit = {},
    onSearch: (String, MusicPlatform) -> Unit = { _, _ -> },
    onSearchPlaylist: (String, MusicPlatform) -> Unit = { _, _ -> },
    onSearchTypeChange: (SearchType) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    // 当前选中 tab（由外部持有，导航子页面后返回时保持 tab 不重置）
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    // 重复点击当前 tab 的刷新信号（歌单/排行页监听：重置滚动 + 重新加载）
    tabRefreshTick: Int = 0,
    // 侧栏焦点状态上报（MainActivity 用于「返回键先回 tab、再按才弹退出确认」）
    onNavBarFocusChanged: (Boolean) -> Unit = {},
    // 返回键第一次按下（焦点在内容区）的递增信号：变化时聚焦当前选中 tab
    navFocusRequestTick: Int = 0,
    modifier: Modifier = Modifier
) {
    // 导航栏每个 tab 各自持有焦点请求器：内容页按左键返回时精确聚焦到「当前选中 tab」，
    // 避免焦点按空间位置乱跳到其它 tab；内容页首个可聚焦项持有 contentEnterRequester，
    // 从导航栏按右键时精确进入内容第一项（而非空间最近项）。
    val navRequesters = remember { List(5) { FocusRequester() } }
    val contentEnterRequester = remember { FocusRequester() }
    // rememberSaveable：从子路由（播放源管理/界面设置等）返回时 main 重新组合，
    // 若用普通 remember 会重置导致 LaunchedEffect 再次抢焦点到「歌单」tab
    var navInitialFocusRequested by rememberSaveable { mutableStateOf(false) }
    // 从子路由返回的信号：递增后通知当前 tab 页面恢复内容焦点（如设置页回到点击的卡片）
    var contentRestoreTick by remember { mutableIntStateOf(0) }

    // ===== Logo 焦点兜底（2.8 治本方案：区分「系统分配」与「用户按键」）=====
    // Logo 可点击后成为侧栏第一个可聚焦节点；Compose 在「组合重建/视图切换导致焦点清除」时按布局
    // 顺序默认分配焦点 → 落 Logo（进入详情、返回列表、子路由返回等场景）。
    // 区分机制：用户主动聚焦 Logo 一定是「方向键按下」触发的焦点导航（按键先于聚焦）；
    // 系统自动分配则无任何按键操作。故：Logo 获得焦点时，若距上次用户按键 > 300ms，
    // 判定为系统分配 → 立即（同一帧）移回当前选中 tab，用户感知不到闪烁。
    var lastKeyPressTime by remember { mutableLongStateOf(0L) }
    var logoFocusTime by remember { mutableLongStateOf(0L) }
    var logoFocused by remember { mutableStateOf(false) }
    LaunchedEffect(logoFocused) {
        if (logoFocused && logoFocusTime - lastKeyPressTime > 300) {
            navRequesters[selectedTab].requestFocus()
        }
    }

    // 主页返回键第一次按下（焦点在内容区）→ 焦点送回侧栏当前选中 tab（不弹退出确认）
    LaunchedEffect(navFocusRequestTick) {
        if (navFocusRequestTick > 0) {
            navRequesters[selectedTab].requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        if (!navInitialFocusRequested) {
            // 首次进入主页：焦点落在「歌单」tab
            requestInitialFocus(
                focusRequester = navRequesters[0],
                attempted = { navInitialFocusRequested },
                markAttempted = { navInitialFocusRequested = true }
            )
        } else {
            // 从子路由返回（播放页/设置二级页/缓存管理等）：
            // 立即聚焦当前选中 tab，消除「重新组合后焦点空白 → 按键从侧栏 logo 乱跳」的问题；
            // 设置页等有内容恢复逻辑的 tab 会随后经 contentRestoreTick 抢回内容焦点
            contentRestoreTick++
            navRequesters[selectedTab].requestFocus()
        }
    }

    // 全站主题由 MainActivity 根部的 LXMusicTheme 统一提供，此处不再嵌套 LXMusicTheme
    //（嵌套的默认 darkTheme=false 会把 currentLXTheme 强制写回浅色，导致深色模式返回后失效）
    Row(
        modifier = Modifier
            .fillMaxSize()
            // 2.5 浅色主题：主区浅灰白 #F5F5F7（仿 EchoMusic）
            .background(LXSurfaceMain)
            // 2.8 记录用户「上键」时间（区分「用户方向键主动聚焦 logo」与「系统默认分配」）：
            // 聚焦 logo 的唯一方向键路径是「上键」（从选中 tab 按上），进详情/切页用的是右/下/确定键；
            // 故仅上键会触发用户主动聚焦 logo 的判定，系统分配落 logo 时距上次上键必然 > 300ms
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
                    lastKeyPressTime = SystemClock.uptimeMillis()
                }
                false
            }
    ) {
            // 左侧导航栏
            NavigationSidebar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                navRequesters = navRequesters,
                contentEnterRequester = contentEnterRequester,
                // Logo 下方展示当前默认音乐平台
                platform = defaultPlatform,
                // 2.8 Logo 点击 → 下拉菜单选择平台（选中即切换默认音乐平台）
                onPlatformSelect = onDefaultPlatformChange,
                // 2.8 Logo 焦点状态上报：记录聚焦时刻，兜底判断「系统分配 vs 用户按键」
                onLogoFocusChanged = { focused ->
                    logoFocused = focused
                    if (focused) logoFocusTime = SystemClock.uptimeMillis()
                },
                // 侧栏焦点状态上报（返回键逻辑用）
                onNavBarFocusChanged = onNavBarFocusChanged,
                currentSong = currentSong,
                isPlaying = isPlaying,
                onNavigateToPlayer = onNavigateToPlayer,
                onPlayPause = onPlayPause,
                modifier = Modifier
                    // 2.6 窄图标栏：宽度只够放 1 个图标（选中时底部才显示文字），参考 blbl/BBLL
                    .width(72.dp)
                    .fillMaxHeight()
            )

            // 主内容区域
            MainContent(
                selectedTab = selectedTab,
                navRequesters = navRequesters,
                contentEnterRequester = contentEnterRequester,
                // 重复点击 tab 的刷新信号
                tabRefreshTick = tabRefreshTick,
                // 从子路由返回的信号（设置页恢复焦点用）
                restoreTick = contentRestoreTick,
                onNavigateToPlaylist = onNavigateToPlaylist,
                onNavigateToRanking = onNavigateToRanking,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSourceManagement = onNavigateToSourceManagement,
                onNavigateToInterfaceSettings = onNavigateToInterfaceSettings,
                onNavigateToCacheManage = onNavigateToCacheManage,
                // 搜索页内嵌参数（2.6）
                searchQuery = searchQuery,
                searchPlatform = searchPlatform,
                searchType = searchType,
                hotKeywords = hotKeywords,
                suggestions = suggestions,
                hotSongs = hotSongs,
                searchHistory = searchHistory,
                onSearchQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onSearchPlaylist = onSearchPlaylist,
                onSearchTypeChange = onSearchTypeChange,
                onClearSearchHistory = onClearSearchHistory,
                defaultPlatform = defaultPlatform,
                onDefaultPlatformChange = onDefaultPlatformChange,
                preferredQuality = preferredQuality,
                onPreferredQualityChange = onPreferredQualityChange,
                // 2.8 歌词设置
                lyricTranslationEnabled = lyricTranslationEnabled,
                onLyricTranslationEnabledChange = onLyricTranslationEnabledChange,
                browseItems = browseItems,
                browseSongs = browseSongs,
                browseLoading = browseLoading,
                browseSongsError = browseSongsError,
                onLoadBrowse = onLoadBrowse,
                onLoadMoreBrowse = onLoadMoreBrowse,
                onLoadMoreSongs = onLoadMoreSongs,
                onLoadSongs = onLoadSongs,
                onLoadHotSongs = onLoadHotSongs,
                onPlaySong = onPlaySong,
                onPlaySongStay = onPlaySongStay,
                onPlayAll = onPlayAll,
                // ⚠️ 必须透传：此前漏传导致首页歌曲行②「整列表播放」变 no-op（点击无反应）
                onPlayBrowseList = onPlayBrowseList,
                favorites = favorites,
                favoritePlaylists = favoritePlaylists,
                favoritePlaylistSongs = favoritePlaylistSongs,
                favoritePlaylistLoading = favoritePlaylistLoading,
                favoritePlaylistSongsHasMore = favoritePlaylistSongsHasMore,
                favoritePlaylistSongsLoadingMore = favoritePlaylistSongsLoadingMore,
                onLoadMoreFavoritePlaylistSongs = onLoadMoreFavoritePlaylistSongs,
                onPlayPlaylistSongsAll = onPlayPlaylistSongsAll,
                favoriteSongIds = favoriteSongIds,
                favoritePlaylistIds = favoritePlaylistIds,
                onToggleSongFavorite = onToggleSongFavorite,
                onTogglePlaylistFavorite = onTogglePlaylistFavorite,
                onOpenFavoritePlaylist = onOpenFavoritePlaylist,
                onBackFromFavoritePlaylistSongs = onBackFromFavoritePlaylistSongs,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }

/**
 * 左侧导航栏
 */
@Composable
fun NavigationSidebar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    navRequesters: List<FocusRequester>,
    contentEnterRequester: FocusRequester,
    // 当前默认音乐平台（Logo 下方展示）
    platform: MusicPlatform = MusicPlatform.KW,
    // 2.8 Logo 点击 → 下拉菜单选择平台（选中即切换默认音乐平台）
    onPlatformSelect: (MusicPlatform) -> Unit = {},
    // 2.8 Logo 焦点状态上报（MainScreen 用于「系统默认分配落 logo 时移回当前 tab」的兜底）
    onLogoFocusChanged: (Boolean) -> Unit = {},
    // 侧栏是否持有焦点（MainActivity 返回键逻辑用：焦点在内容区时第一次返回先回 tab）
    onNavBarFocusChanged: (Boolean) -> Unit = {},
    currentSong: com.lxmusic.tv.data.model.Song? = null,
    isPlaying: Boolean = false,
    onNavigateToPlayer: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navItems = listOf(
        NavItem("歌单", Icons.Default.LibraryMusic, "歌单广场"),
        NavItem("排行", Icons.Default.Leaderboard, "音乐排行榜"),
        NavItem("收藏", Icons.Default.Favorite, "我的收藏"),
        NavItem("搜索", Icons.Default.Search, "搜索音乐"),
        NavItem("设置", Icons.Default.Settings, "应用设置")
    )

    // 2.5 浅色主题：侧栏纯白基底 + 顶部品牌红氛围渐变（仿 EchoMusic，比灰主区更亮，形成轻微色差）
    Box(
        modifier = modifier
            // 2.6 侧栏焦点组：与内容区 focusGroup 组成「双组边界」，双向禁止方向键跨组移动。
            // 效果（参考 blbl 原生 View 行为）：内容区列表到底按「下」时，焦点空间搜索无法跨入
            // 本组（侧栏）→ 焦点停留原地，不会跳到底部播放/暂停按钮。
            // 跨组导航（左键回 tab / 右键进内容）均为显式 requestFocus，不受 focusGroup 限制。
            .focusGroup()
            // 上报侧栏是否持有焦点（子节点聚焦时祖先 onFocusChanged 也会报告 hasFocus=true）
            .onFocusChanged { focusState -> onNavBarFocusChanged(focusState.hasFocus) }
            .background(LXSurfaceSidebar)
    ) {
        // 侧栏氛围渐变叠加层（与主区 LXAccentGradientBrush 略不同：基底更白、渐变更轻柔）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LXSidebarGradientBrush)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
        // Logo区域（窄栏：小图标 + 底部显示当前音乐平台名；2.8 可点击弹出下拉菜单切换平台）
        // 焦点样式与下方 tab 项（NavigationItem）完全一致：聚焦 LXFocusFill 背景 + 同款边框，
        // modifier 顺序也对齐（lxFocusBorder 在 clickable 之前）
        var logoFocused by remember { mutableStateOf(false) }
        // 2.8 下拉菜单展开状态（点击 logo 弹出，选中/失焦关闭）
        var logoMenuExpanded by remember { mutableStateOf(false) }
        val logoBgColor = if (logoFocused) LXFocusFill else Color.Transparent
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(logoBgColor)
                    .onFocusChanged {
                        logoFocused = it.isFocused
                        onLogoFocusChanged(it.isFocused)
                    }
                    .lxFocusBorder(
                        shape = RoundedCornerShape(10.dp),
                        glow = false,
                        animated = false,
                        unfocusedColor = Color.Transparent,
                        unfocusedWidth = 0.dp
                    )
                    // Logo 可点击：弹出下拉平台菜单（clickable 自带 focusable，遥控器可聚焦）
                    .clickable { logoMenuExpanded = true },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = "LX Music",
                    modifier = Modifier.size(32.dp)
                )
                // 当前默认音乐平台（短名，如 酷狗/QQ）
                Text(
                    text = platformShortName(platform),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = platformBrandColor(platform),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 2.8 下拉平台选择菜单（紧凑：挂在 logo 下方，宽度与 logo 一致（侧栏 72dp - 左右 6dp padding = 60dp））
            // 2.8 背景色与操作大按钮（CacheActionButton）的灰底一致：LXSurfaceVariant
            DropdownMenu(
                expanded = logoMenuExpanded,
                onDismissRequest = { logoMenuExpanded = false },
                modifier = Modifier.width(60.dp),
                containerColor = LXSurfaceVariant
            ) {
                // 可选平台（排除本地音乐），仅显示平台短名（如 酷狗/QQ）
                MusicPlatform.values()
                    .filter { it != MusicPlatform.LOCAL }
                    .forEach { p ->
                        val isSelected = p == platform
                        // 当前平台项的焦点请求器：菜单展开时焦点定位到当前平台
                        val itemRequester = remember(p) { FocusRequester() }
                        // 菜单展开且本项为当前平台 → 焦点定位到它（等一帧确保菜单内容已组合）
                        LaunchedEffect(logoMenuExpanded, p) {
                            if (logoMenuExpanded && isSelected) {
                                withFrameNanos {}
                                itemRequester.requestFocus()
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                // 当前平台用颜色区分（主题色背景），无打勾图标
                                .background(if (isSelected) LXPrimary.copy(alpha = 0.18f) else Color.Transparent)
                                .focusRequester(itemRequester)
                                .lxFocusBorder(
                                    shape = RoundedCornerShape(6.dp),
                                    glow = false,
                                    animated = false,
                                    unfocusedColor = Color.Transparent,
                                    unfocusedWidth = 0.dp
                                )
                                .clickable {
                                    logoMenuExpanded = false
                                    if (!isSelected) onPlatformSelect(p)
                                },
                            // 平台名居中显示
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = platformShortName(p),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) LXPrimary else LXTextPrimary
                            )
                        }
                    }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 导航菜单项
        navItems.forEachIndexed { index, item ->
            NavigationItem(
                item = item,
                isSelected = selectedTab == index,
                onClick = { onTabSelected(index) },
                focusRequester = navRequesters.getOrNull(index),
                // 从导航栏按右键：精确进入右侧内容第一项（而非空间最近项，避免落到第 N 项）
                onEnterContent = { contentEnterRequester.requestFocus() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 正在播放控制条（常驻，窄栏版：封面进播放页 + 播放/暂停）
        if (currentSong != null) {
            NowPlayingBar(
                song = currentSong,
                isPlaying = isPlaying,
                onClick = onNavigateToPlayer,
                onPlayPause = onPlayPause,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
}

/**
 * 正在播放控制条（导航栏底部常驻入口，2.6 窄栏版）
 * 窄栏只放 1 个图标宽：封面（点击进播放页）+ 播放/暂停按钮；
 * 上一曲/下一曲等完整控制在播放页控制栏里操作
 */
@Composable
fun NowPlayingBar(
    song: com.lxmusic.tv.data.model.Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 2.8 封面旋转动画：播放中缓慢匀速旋转（8s/圈，黑胶/光盘感），暂停停止并平滑归 0
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

        // 封面：点击进入播放页
        Box(
            modifier = Modifier
                .size(44.dp)
                // 播放旋转 / 暂停归 0（graphicsLayer 默认以中心为旋转轴）
                .graphicsLayer { rotationZ = coverRotation.value }
                .clip(RoundedCornerShape(10.dp))
                // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
                .lxCircleButtonFocus()
                .clickable { onClick() }
        ) {
            if (!song.picUrl.isNullOrBlank()) {
                RemoteImage(
                    url = song.picUrl,
                    contentDescription = song.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(platformBrandColor(song.platform).copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = platformIcon(song.platform),
                                contentDescription = null,
                                tint = platformBrandColor(song.platform),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(platformBrandColor(song.platform).copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = platformIcon(song.platform),
                        contentDescription = null,
                        tint = platformBrandColor(song.platform),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 播放/暂停按钮（遥控器必须可聚焦操作）
        Box(
            modifier = Modifier
                .requiredSize(40.dp)
                // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
                .lxCircleButtonFocus()
                .clickable { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                // 2.5 浅色主题：白底深色图标
                tint = LXTextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 导航菜单项
 */
@Composable
fun NavigationItem(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    // 从导航栏按右键：精确进入右侧内容第一项（而非空间最近项，避免落到第 N 项）
    onEnterContent: () -> Unit = {}
) {
    // 选中态：品牌红图标+文字（2.6 窄栏：默认只显示图标，选中时图标底部才显示文字）；未选中深色
    val contentColor = if (isSelected) LXPrimary else LXTextPrimary

    // 聚焦态：背景轻微变深（仿 EchoMusic 左侧菜单：焦点选中才加背景色 + 边框色）
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = if (isFocused) LXFocusFill else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            // focusRequester 必须在 focusable(clickable) 之前
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 导航栏右键 → 进入右侧内容第一项（返回 true 拦截默认空间导航，自行聚焦内容首项）
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionRight) {
                    onEnterContent()
                    true
                } else false
            }
            .onFocusChanged { isFocused = it.isFocused }
            // 未选中且无焦点：无边框（纯图标）；仅焦点选中才显示背景+边框
            .lxFocusBorder(
                shape = RoundedCornerShape(10.dp),
                glow = false,
                animated = false,
                unfocusedColor = Color.Transparent,
                unfocusedWidth = 0.dp
            )
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.description,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )

        // 仅选中时在图标底部显示文字，否则只显示图标（参考 blbl/BBLL 窄侧栏）
        if (isSelected) {
            Text(
                text = item.title,
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/**
 * 主内容区域
 */
@Composable
fun MainContent(
    selectedTab: Int,
    navRequesters: List<FocusRequester>,
    contentEnterRequester: FocusRequester,
    // 重复点击当前 tab 的刷新信号（歌单/排行页监听）
    tabRefreshTick: Int = 0,
    // 从子路由返回的信号（设置页恢复焦点用）
    restoreTick: Int = 0,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToRanking: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSourceManagement: () -> Unit,
    onNavigateToInterfaceSettings: () -> Unit = {},
    // 2.8 缓存管理独立子页面
    onNavigateToCacheManage: () -> Unit = {},
    // ===== 搜索页内嵌参数（2.6：tab 内容区直接渲染搜索页，保留左侧导航栏）=====
    searchQuery: String = "",
    searchPlatform: MusicPlatform = MusicPlatform.KW,
    searchType: SearchType = SearchType.SONG,
    hotKeywords: List<String> = emptyList(),
    suggestions: List<String> = emptyList(),
    hotSongs: List<Song> = emptyList(),
    searchHistory: List<String> = emptyList(),
    onSearchQueryChange: (String) -> Unit = {},
    onSearch: (String, MusicPlatform) -> Unit = { _, _ -> },
    onSearchPlaylist: (String, MusicPlatform) -> Unit = { _, _ -> },
    onSearchTypeChange: (SearchType) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    defaultPlatform: MusicPlatform = MusicPlatform.KW,
    onDefaultPlatformChange: (MusicPlatform) -> Unit = {},
    preferredQuality: AudioQuality = AudioQuality.QUALITY_320K,
    onPreferredQualityChange: (AudioQuality) -> Unit = {},
    // 2.8 歌词设置：是否显示翻译歌词
    lyricTranslationEnabled: Boolean = true,
    onLyricTranslationEnabledChange: (Boolean) -> Unit = {},
    browseItems: List<BrowseItem> = emptyList(),
    browseSongs: List<Song> = emptyList(),
    browseLoading: Boolean = false,
    browseSongsError: String? = null,
    onLoadBrowse: (BrowseType) -> Unit = {},
    onLoadMoreBrowse: (BrowseType) -> Unit = {},
    onLoadSongs: (String, Boolean) -> Unit = { _, _ -> },
    onLoadMoreSongs: () -> Unit = {},
    onLoadHotSongs: () -> Unit = {},
    onPlaySong: (Song, List<Song>) -> Unit = { _, _ -> },
    onPlaySongStay: (Song, List<Song>) -> Unit = { _, _ -> },
    onPlayAll: (List<Song>) -> Unit = {},
    // 整列表播放（歌单/榜单详情歌曲行②：先补拉完整列表再播，避免只播已加载首页）
    onPlayBrowseList: (Song) -> Unit = {},
    // 收藏相关
    favorites: List<com.lxmusic.tv.data.model.Favorite> = emptyList(),
    favoritePlaylists: List<com.lxmusic.tv.data.model.FavoritePlaylist> = emptyList(),
    favoritePlaylistSongs: List<Song>? = null,
    favoritePlaylistLoading: Boolean = false,
    // 收藏歌单详情分页续拉（酷狗）：焦点触底加载下一页
    favoritePlaylistSongsHasMore: Boolean = false,
    favoritePlaylistSongsLoadingMore: Boolean = false,
    onLoadMoreFavoritePlaylistSongs: () -> Unit = {},
    onPlayPlaylistSongsAll: (Song) -> Unit = {},
    favoriteSongIds: Set<String> = emptySet(),
    favoritePlaylistIds: Set<String> = emptySet(),
    onToggleSongFavorite: (Song) -> Unit = {},
    onTogglePlaylistFavorite: (com.lxmusic.tv.data.model.Playlist) -> Unit = {},
    onOpenFavoritePlaylist: (com.lxmusic.tv.data.model.FavoritePlaylist) -> Unit = {},
    onBackFromFavoritePlaylistSongs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            // 2.6 内容区焦点组：与侧栏 focusGroup 组成「双组边界」（见 NavigationSidebar 注释），
            // 双向禁止方向键跨组移动 → 列表到底按「下」不会经空间搜索跳到侧栏播放/暂停按钮（用户实测参考 blbl）
            .focusGroup()
    ) {
        // 主区顶部氛围渐变（实色基底由外层 Row 的 #F5F5F7 提供，仿 EchoMusic .layout-accent-gradient）：
        // 右侧主内容区叠加品牌红氛围渐变；侧栏为纯白基底 + 略轻柔的同色渐变（见 NavigationSidebar）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LXAccentGradientBrush)
        )
        when (selectedTab) {
            0 -> PlaylistScreen(
                platform = defaultPlatform,
                items = browseItems,
                songs = browseSongs,
                loading = browseLoading,
                browseSongsError = browseSongsError,
                // 重复点击 tab 刷新信号
                refreshTick = tabRefreshTick,
                onLoadBrowse = onLoadBrowse,
                onLoadSongs = onLoadSongs,
                onPlaySongStay = onPlaySongStay,
                onPlayAll = onPlayAll,
                onPlayBrowseList = onPlayBrowseList,
                onLoadMore = onLoadMoreBrowse,
                onLoadMoreSongs = onLoadMoreSongs,
                favoriteSongIds = favoriteSongIds,
                favoritePlaylistIds = favoritePlaylistIds,
                onToggleSongFavorite = onToggleSongFavorite,
                onTogglePlaylistFavorite = onTogglePlaylistFavorite,
                contentEnterRequester = contentEnterRequester,
                onExitToNav = { navRequesters[selectedTab].requestFocus() },
                modifier = Modifier.fillMaxSize()
            )
            1 -> RankingScreen(
                platform = defaultPlatform,
                items = browseItems,
                songs = browseSongs,
                loading = browseLoading,
                browseSongsError = browseSongsError,
                // 重复点击 tab 刷新信号
                refreshTick = tabRefreshTick,
                onLoadBrowse = onLoadBrowse,
                onLoadSongs = onLoadSongs,
                onLoadHotSongs = onLoadHotSongs,
                onPlaySongStay = onPlaySongStay,
                onPlayAll = onPlayAll,
                onPlayBrowseList = onPlayBrowseList,
                onLoadMore = onLoadMoreBrowse,
                onLoadMoreSongs = onLoadMoreSongs,
                favoriteSongIds = favoriteSongIds,
                onToggleSongFavorite = onToggleSongFavorite,
                contentEnterRequester = contentEnterRequester,
                onExitToNav = { navRequesters[selectedTab].requestFocus() },
                modifier = Modifier.fillMaxSize()
            )
            2 -> FavoritesScreen(
                favorites = favorites,
                favoritePlaylists = favoritePlaylists,
                favoriteSongIds = favoriteSongIds,
                // 重复点击 tab 刷新信号
                refreshTick = tabRefreshTick,
                favoritePlaylistSongs = favoritePlaylistSongs,
                favoritePlaylistLoading = favoritePlaylistLoading,
                favoritePlaylistSongsHasMore = favoritePlaylistSongsHasMore,
                favoritePlaylistSongsLoadingMore = favoritePlaylistSongsLoadingMore,
                onLoadMoreFavoritePlaylistSongs = onLoadMoreFavoritePlaylistSongs,
                onPlayPlaylistSongsAll = onPlayPlaylistSongsAll,
                currentSongId = null,
                isPlaying = false,
                onPlaySong = onPlaySong,
                onPlaySongStay = onPlaySongStay,
                onToggleSongFavorite = onToggleSongFavorite,
                onTogglePlaylistFavorite = onTogglePlaylistFavorite,
                onOpenFavoritePlaylist = onOpenFavoritePlaylist,
                onBackFromFavoritePlaylistSongs = onBackFromFavoritePlaylistSongs,
                onBack = { /* 收藏页为主页 tab，无需返回 */ },
                contentEnterRequester = contentEnterRequester,
                onExitToNav = { navRequesters[selectedTab].requestFocus() },
                modifier = Modifier.fillMaxSize()
            )
            3 -> SearchScreen(
                // 2.6 搜索页内嵌为 tab（保留左侧导航栏），不再跳转独立页面
                searchQuery = searchQuery,
                searchPlatform = searchPlatform,
                searchType = searchType,
                hotKeywords = hotKeywords,
                suggestions = suggestions,
                hotSongs = hotSongs,
                searchHistory = searchHistory,
                onSearchQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onSearchPlaylist = onSearchPlaylist,
                onSearchTypeChange = onSearchTypeChange,
                onClearSearchHistory = onClearSearchHistory,
                // 导航栏右键进入搜索页 → 聚焦类型选择器首项
                contentEnterRequester = contentEnterRequester,
                modifier = Modifier.fillMaxSize()
            )
            4 -> SettingsScreen(
                onNavigateToSourceManagement = onNavigateToSourceManagement,
                onNavigateToInterfaceSettings = onNavigateToInterfaceSettings,
                // 2.8 缓存管理独立子页面
                onNavigateToCacheManage = onNavigateToCacheManage,
                defaultPlatform = defaultPlatform,
                onDefaultPlatformChange = onDefaultPlatformChange,
                preferredQuality = preferredQuality,
                onPreferredQualityChange = onPreferredQualityChange,
                // 2.8 歌词设置
                lyricTranslationEnabled = lyricTranslationEnabled,
                onLyricTranslationEnabledChange = onLyricTranslationEnabledChange,
                contentEnterRequester = contentEnterRequester,
                onExitToNav = { navRequesters[selectedTab].requestFocus() },
                // 重复点击 tab 刷新信号：滚动回顶部
                refreshTick = tabRefreshTick,
                // 从子路由返回：恢复焦点到点击的卡片
                restoreTick = restoreTick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 歌单广场页面
 */
@Composable
fun PlaylistScreen(
    platform: MusicPlatform,
    items: List<BrowseItem>,
    songs: List<Song>,
    loading: Boolean,
    browseSongsError: String? = null,
    onLoadBrowse: (BrowseType) -> Unit,
    onLoadSongs: (String, Boolean) -> Unit,
    onPlayBrowseList: (Song) -> Unit = {},
    onPlaySongStay: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit = {},
    onLoadMore: (BrowseType) -> Unit = {},
    onLoadMoreSongs: () -> Unit = {},
    favoriteSongIds: Set<String> = emptySet(),
    favoritePlaylistIds: Set<String> = emptySet(),
    onToggleSongFavorite: (Song) -> Unit = {},
    onTogglePlaylistFavorite: (com.lxmusic.tv.data.model.Playlist) -> Unit = {},
    contentEnterRequester: FocusRequester,
    onExitToNav: () -> Unit,
    // 重复点击 tab 刷新信号：>0 且变化时回到列表顶部并重新加载
    refreshTick: Int = 0,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<BrowseItem?>(null) }

    LaunchedEffect(platform) {
        selected = null
        onLoadBrowse(BrowseType.PLAYLIST)
    }

    // 重复点击「歌单」tab：重置到列表顶部并重新加载（滚动重置由 BrowsePage 内 refreshTick 处理）。
    // 仅 tick 真正变化时触发：避免组件重建（切 tab 返回）时把上次的 tick 当新刷新重复请求
    var lastRefreshTick by remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && refreshTick != lastRefreshTick) {
            lastRefreshTick = refreshTick
            selected = null
            onLoadBrowse(BrowseType.PLAYLIST)
        }
    }

    BrowsePage(
        platform = platform,
        items = items,
        songs = songs,
        loading = loading,
        browseSongsError = browseSongsError,
        selected = selected,
        // 透传刷新信号给 BrowsePage（滚动位置回顶部）
        refreshTick = refreshTick,
        onItemClick = { item ->
            selected = item
            onLoadSongs(item.id, false)
        },
        onBack = { selected = null },
        onSongClick = { song -> onPlayBrowseList(song) },
        onPlaySongStay = { song -> onPlaySongStay(song, songs) },
        onPlayAll = onPlayAll,
        onLoadMoreBrowse = { onLoadMore(BrowseType.PLAYLIST) },
        onLoadMoreSongs = onLoadMoreSongs,
        isPlaylistMode = true,
        favoriteSongIds = favoriteSongIds,
        favoritePlaylistIds = favoritePlaylistIds,
        onToggleSongFavorite = onToggleSongFavorite,
        onTogglePlaylistFavorite = onTogglePlaylistFavorite,
        contentEnterRequester = contentEnterRequester,
        onExitToNav = onExitToNav,
        modifier = modifier
    )
}

/**
 * 排行榜页面
 */
@Composable
fun RankingScreen(
    platform: MusicPlatform,
    items: List<BrowseItem>,
    songs: List<Song>,
    loading: Boolean,
    browseSongsError: String? = null,
    onLoadBrowse: (BrowseType) -> Unit,
    onLoadSongs: (String, Boolean) -> Unit,
    onLoadHotSongs: () -> Unit = {},
    onPlayBrowseList: (Song) -> Unit = {},
    onPlaySongStay: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit = {},
    onLoadMore: (BrowseType) -> Unit = {},
    onLoadMoreSongs: () -> Unit = {},
    favoriteSongIds: Set<String> = emptySet(),
    onToggleSongFavorite: (Song) -> Unit = {},
    contentEnterRequester: FocusRequester,
    onExitToNav: () -> Unit,
    // 重复点击 tab 刷新信号：>0 且变化时回到列表顶部并重新加载
    refreshTick: Int = 0,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<BrowseItem?>(null) }

    // 所有平台（含酷狗）排行榜统一走「榜单名列表 → 点开看歌曲」两级模式：
    // 先展示 Top500/民谣版/飙升版 等榜单，点击后再加载该榜单的歌曲。
    LaunchedEffect(platform) {
        selected = null
        onLoadBrowse(BrowseType.RANKING)
    }

    // 重复点击「排行」tab：重置到列表顶部并重新加载（滚动重置由 BrowsePage 内 refreshTick 处理）。
    // 仅 tick 真正变化时触发：避免组件重建（切 tab 返回）时把上次的 tick 当新刷新重复请求
    var lastRefreshTick by remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && refreshTick != lastRefreshTick) {
            lastRefreshTick = refreshTick
            selected = null
            onLoadBrowse(BrowseType.RANKING)
        }
    }

    BrowsePage(
        platform = platform,
        items = items,
        songs = songs,
        loading = loading,
        browseSongsError = browseSongsError,
        selected = selected,
        // 透传刷新信号给 BrowsePage（滚动位置回顶部）
        refreshTick = refreshTick,
        onItemClick = { item ->
            selected = item
            onLoadSongs(item.id, true)
        },
        onBack = { selected = null },
        onSongClick = { song -> onPlayBrowseList(song) },
        onPlaySongStay = { song -> onPlaySongStay(song, songs) },
        onPlayAll = onPlayAll,
        onLoadMoreBrowse = { onLoadMore(BrowseType.RANKING) },
        onLoadMoreSongs = onLoadMoreSongs,
        isPlaylistMode = false,
        favoriteSongIds = favoriteSongIds,
        onToggleSongFavorite = onToggleSongFavorite,
        contentEnterRequester = contentEnterRequester,
        onExitToNav = onExitToNav,
        modifier = modifier
    )
}

/**
 * 通用浏览页面：列表（歌单/榜单）↔ 歌曲列表 两级视图
 * 列表支持无限滚动加载（滚动到底部自动加载下一页）
 */
@Composable
fun BrowsePage(
    platform: MusicPlatform,
    items: List<BrowseItem>,
    songs: List<Song>,
    loading: Boolean,
    browseSongsError: String? = null,
    selected: BrowseItem?,
    // 直接展示歌曲（不走「榜单名列表 → 点开看歌曲」两级）：酷狗热门歌曲场景使用
    directSongs: Boolean = false,
    onItemClick: (BrowseItem) -> Unit,
    onBack: () -> Unit,
    onSongClick: (Song) -> Unit,
    onPlaySongStay: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit = {},
    // 加载更多：卡片网格模式追加更多卡片；歌曲列表模式追加更多歌曲（两种模式需不同回调）
    onLoadMoreBrowse: () -> Unit = {},
    onLoadMoreSongs: () -> Unit = {},
    // 收藏相关
    isPlaylistMode: Boolean = true,
    favoriteSongIds: Set<String> = emptySet(),
    favoritePlaylistIds: Set<String> = emptySet(),
    onToggleSongFavorite: (Song) -> Unit = {},
    onTogglePlaylistFavorite: (com.lxmusic.tv.data.model.Playlist) -> Unit = {},
    // 导航↔内容边界焦点控制：contentEnterRequester 供首项承载「右键进入」，
    // onExitToNav 供「左键返回选中 tab」（网格仅首列拦截，保留列内左右导航）
    contentEnterRequester: FocusRequester,
    onExitToNav: () -> Unit,
    // 重复点击 tab 刷新信号：变化时滚动位置回顶部（数据重载由外层 PlaylistScreen/RankingScreen 触发）
    refreshTick: Int = 0,
    modifier: Modifier = Modifier
) {
    // 列表滚动状态（无限加载）
    val gridState = rememberLazyGridState()
    // 歌曲列表滚动状态：LazyColumn 必须绑定自己的 LazyListState，
    // 用于 scrollToItem(0) 重置位置与 firstVisibleSongIndex 焦点挂载；分页改由「焦点行号」驱动（见下方 onFocusChanged），
    // 不再依赖滚动改 layoutInfo 被监听（v107~v109 证明该路径在 TV 焦点停在按钮上时不可靠）。
    val songListState = rememberLazyListState()

    // 重复点击 tab 刷新：网格与歌曲列表滚动位置都回到顶部
    //（PlaylistScreen/RankingScreen 已重置 selected=null 并触发重新加载）
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            gridState.scrollToItem(0)
            songListState.scrollToItem(0)
        }
    }

    // 歌单/排行详情打开时，遥控器返回键优先退出详情回到列表主页
    // （BackHandler 由内向外生效，这里会先于 MainActivity 的退出确认对话框被触发）
    if (selected != null) {
        BackHandler {
            onBack()
        }
    }

    // 「当前可见首项」index：contentEnterRequester 挂在可见首项上（而非固定第 0 项）。
    // 否则滚动到深处（如 index 100）后第 0 项未组合，焦点请求器不存在，
    // 用户「左键回 tab → 右键想回到列表」时 requestFocus 会失效，表现为按右键进不去。
    val firstVisibleCardIndex by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0 }
    }
    val firstVisibleSongIndex by remember {
        derivedStateOf { songListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0 }
    }

    // 进入新的榜单/歌单详情时，把歌曲列表滚动位置重置到顶部：
    // songListState 在 BrowsePage 组合内跨详情持久，不重置会停留在上一个榜单的滚动位置
    //（如 index 100），表现为「退出重进下一个榜单 index 不恢复成 0」。
    // remember(selected?.id) 让标记随详情 id 复位；翻页追加（songs.size 变化）不重复触发。
    var songListResetDone by remember(selected?.id) { mutableStateOf(false) }
    LaunchedEffect(selected?.id, songs.size) {
        if (selected != null && songs.isNotEmpty() && !songListResetDone) {
            songListResetDone = true
            songListState.scrollToItem(0)
        }
    }

    // 分页触发：用【焦点所在行/卡片号】驱动，而非依赖 LazyColumn/Grid 滚动改 layoutInfo 被发现。
    // TV 上用户在歌曲行间用方向键移动焦点（焦点停在每行的三个按钮上），焦点下移并不一定能驱动列表滚动，
    // 导致 layoutInfo 不变、滚动监听（snapshotFlow / derivedStateOf）收不到信号 → 续拉不触发（v107~v109 均栽此）。
    // 改为追踪「当前焦点行号」：焦点接近列表末尾（>= 总数-8）即触发续拉，与用户实际操作一致、可靠。
    // VM 层 loadMoreBrowseSongs / loadMoreBrowse 均有 _browseLoading + hasMore 守卫，重复触发会被拦截。
    var focusedSongIndex by remember { mutableStateOf(0) }
    var focusedCardIndex by remember { mutableStateOf(0) }
    LaunchedEffect(focusedSongIndex) {
        val isSongMode = selected != null || directSongs
        if (isSongMode && songs.isNotEmpty() && focusedSongIndex >= songs.size - 8 && !loading) {
            android.util.Log.d("LX-BrowsePage", "[续拉] 焦点触底 onLoadMoreSongs focused=$focusedSongIndex songs=${songs.size} loading=$loading")
            onLoadMoreSongs()
        }
    }
    LaunchedEffect(focusedCardIndex) {
        if (items.isNotEmpty() && focusedCardIndex >= items.size - 8 && !loading) {
            android.util.Log.d("LX-BrowsePage", "[续拉] 焦点触底 onLoadMoreBrowse focused=$focusedCardIndex items=${items.size} loading=$loading")
            onLoadMoreBrowse()
        }
    }

    // 进入歌曲列表后：焦点应落在第一首歌（或其操作按钮），而非因网格卡片被卸载而逃回左侧 tab 栏。
    // 仅在歌曲数据已加载（列表可见）时请求，避免加载中转圈期间抢焦导致焦点闪烁。
    // contentEnterRequester 挂载在当前可见首行（见 BrowseSongRow firstVisibleSongIndex），requestFocus 即聚焦该行。
    LaunchedEffect(selected != null, directSongs, songs.isNotEmpty()) {
        if ((selected != null || directSongs) && songs.isNotEmpty()) {
            contentEnterRequester.requestFocus()
        }
    }

    // 2.8 从详情返回列表时，焦点回到之前点击的那张卡片（而非丢到列表顶部/侧栏 logo）。
    // 网格 LazyGrid state 跨详情持久、滚动位置保留，返回时视口内卡片同帧已组合：
    // 直接 requestFocus（不等帧/不滚动），杜绝「焦点空白 → 乱跳 → 再定位」的闪烁。
    var hasOpenedDetail by remember { mutableStateOf(false) }
    var lastOpenedCardIndex by remember { mutableStateOf(0) }
    // 每张卡片的 FocusRequester 登记表（按 index）：返回时按 index 精准聚焦
    val cardFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    LaunchedEffect(selected) {
        if (selected == null && hasOpenedDetail) {
            val target = lastOpenedCardIndex
            cardFocusRequesters[target]?.requestFocus()
            // 兜底：目标卡片不在视口（未组合）时滚动过去再聚焦一次
            if (cardFocusRequesters[target] == null) {
                gridState.scrollToItem(target)
                withFrameNanos {}
                withFrameNanos {}
                cardFocusRequesters[target]?.requestFocus()
            }
            hasOpenedDetail = false
        }
    }

    // 2.8 详情顶部返回按钮焦点请求器：进入详情立即聚焦它，消除「歌曲加载中焦点空白 → 按键从侧栏 logo 起跳」的问题
    val detailBackRequester = remember { FocusRequester() }
    LaunchedEffect(selected != null) {
        if (selected != null) {
            detailBackRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .padding(24.dp)
            .fillMaxSize()
    ) {
        // 2.6 去除顶部标题栏：一级页（歌单/榜单列表）无标题，内容直接铺满；
        // 二级页（歌单详情歌曲列表）保留「返回 + 歌单名 + 收藏」，仅左对齐（右上角悬浮球已在主页隐藏，无需预留）
        if (selected != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .focusRequester(detailBackRequester)
                        .lxBackButtonFocus()
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        // 2.5 浅色主题：浅灰主区上的深色返回箭头
                        tint = LXTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 当前歌单名（位置指示，替代原大标题）
                Text(
                    text = selected.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 歌单二级页：收藏按钮（仅歌单支持收藏，排行榜不显示）
                if (isPlaylistMode) {
                    Spacer(modifier = Modifier.width(12.dp))
                    FavoriteButton(
                        isFavorite = favoritePlaylistIds.contains(selected.id),
                        onClick = {
                            onTogglePlaylistFavorite(
                                com.lxmusic.tv.data.model.Playlist(
                                    id = selected.id,
                                    name = selected.name,
                                    description = selected.description,
                                    coverUrl = selected.coverUrl,
                                    songCount = selected.songCount,
                                    platform = platform
                                )
                            )
                        }
                    )
                }
            }
        }

        // 仅【初始加载】（items 尚为空且非直接歌曲模式）显示全屏转圈；
        // 【加载更多】时 items 非空，保留网格并在底部显示 footer（见下方 item），
        // 这样网格不会被卸载，焦点靠稳定 key 保留，避免「加载时焦点逃到左侧 tab、加载完又被拉回网格首项」的问题
        // 酷狗直接展示歌曲（directSongs）时，加载中转圈单独处理（下方分支）
        val showSongs = selected != null || directSongs
        if (loading && items.isEmpty() && !showSongs) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LXPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "加载中...", fontSize = 15.sp, color = Color.Gray)
                }
            }
        } else if (directSongs && loading && songs.isEmpty()) {
            // 直接展示歌曲模式（酷狗热门歌曲）的加载中转圈
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LXPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "加载中...", fontSize = 15.sp, color = Color.Gray)
                }
            }
        } else if (selected == null && !directSongs) {
            // 列表视图（榜单名 / 歌单列表）
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "该平台暂不支持或暂无数据",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "可在设置中切换默认音乐平台",
                            fontSize = 13.sp,
                            color = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                // 网格外套一个非焦点容器（不调用 focusable，避免成为焦点停靠点）；
                // 网格不再自动抢焦：初始/切 tab 时焦点停在左侧导航栏，遥控器右键由 Compose 默认
                // 空间遍历自然进入网格第一项，符合「按右再进入内容」的使用习惯
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    LazyVerticalGrid(
                        // 5 列（2.6）：比 4 列时卡片更小，电视上能完整显示更多行
                        columns = GridCells.Fixed(5),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        state = gridState,
                        // 快速方向键滚动的掉帧问题已由 RemoteImage 的并发解码信号量(Semaphore=2)
                        // 与更小的解码尺寸(maxDimension=512/256)解决，故此处不依赖 beyondBoundsItemCount
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 稳定 key（id）：列表滚动/数据更新时避免 item 状态错乱与多余重组
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            // 2.8 返回列表时聚焦回原卡片：每张卡片登记 FocusRequester
                            val cardRequester = remember(item.id) { FocusRequester() }
                            SideEffect { cardFocusRequesters[index] = cardRequester }
                            BrowseCard(
                                item = item,
                                index = index,
                                onClick = {
                                    // 2.8 记录进入详情前的位置（返回时聚焦回这张卡片）
                                    hasOpenedDetail = true
                                    lastOpenedCardIndex = index
                                    onItemClick(item)
                                },
                                // 列表首项承载「右键进入」焦点请求器；首列卡片拦截左键返回选中 tab
                                contentEnterRequester = if (index == firstVisibleCardIndex) contentEnterRequester else null,
                                onExitToNav = onExitToNav,
                                interceptLeftExit = index % 5 == 0,
                                onCardFocused = { idx -> focusedCardIndex = idx },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(cardRequester)
                            )
                        }
                        // 底部加载更多提示（稳定 key：防止 loading 项插入/移除导致 grid slot 错位、焦点项被重建）
                        if (loading) {
                            item(key = "browse_loading_more", span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = LXPrimary,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 歌曲列表视图
            when {
                // 歌单/榜单歌曲加载中：立即显示转圈（与搜索歌单详情一致），避免闪现旧数据或空状态
                loading && songs.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = LXPrimary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = "加载中...", fontSize = 15.sp, color = Color.Gray)
                        }
                    }
                }

                songs.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MusicOff,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                // 超时/异常失败（browseSongsError）与平台不支持/空歌单区分展示
                                text = browseSongsError ?: "暂不支持获取歌曲列表",
                                fontSize = 16.sp,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (browseSongsError != null) "可返回重试" else "可在设置中切换默认音乐平台",
                                fontSize = 13.sp,
                                color = Color.Gray.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                else -> {
                LazyColumn(
                    // 仍绑定 songListState：用于 scrollToItem 重置与首行焦点挂载；分页已由焦点行号驱动，滚动本身不再用于触发续拉
                    state = songListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(songs) { index, song ->
                        BrowseSongRow(
                            index = index,
                            song = song,
                            isFavorite = favoriteSongIds.contains(song.id),
                            onToggleFavorite = { onToggleSongFavorite(song) },
                            // ① 仅播放本首、停留在当前页（不进入播放页）
                            onPlayStay = { onPlaySongStay(song) },
                            // ② 播放本首并进入播放页（整列表作为播放队列）
                            onPlayOpen = { onSongClick(song) },
                            // 歌曲列表为单列，首个按钮左键即返回选中 tab（由按钮①处理）
                            onExitToNav = onExitToNav,
                            // 首个歌曲行承载「右键进入」焦点请求器
                            focusRequester = if (index == firstVisibleSongIndex) contentEnterRequester else null,
                            onSongFocused = { idx -> focusedSongIndex = idx },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // 加载更多时的底部提示（仅 songs 非空、即「加载更多」阶段出现；
                    // 初始加载走上方全屏转圈分支，不会同时显示）
                    if (loading) {
                        item(key = "song_loading_more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = LXPrimary,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

}

/**
 * 歌单/榜单卡片（网格）
 */
@Composable
fun BrowseCard(
    item: BrowseItem,
    onClick: () -> Unit,
    contentEnterRequester: FocusRequester? = null,
    onExitToNav: (() -> Unit)? = null,
    interceptLeftExit: Boolean = false,
    index: Int = 0,
    onCardFocused: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        // 根节点挂 onFocusChanged：本卡片获得焦点即上报其序号，用于「焦点接近网格末尾时触发加载更多」。
        modifier = modifier.onFocusChanged { if (it.hasFocus) onCardFocused(index) }
            // 网格卡片密集排列，滚动时焦点快速移动；关闭发光与过渡动画，避免逐卡跑阴影动画导致首页滚动掉帧
            .lxFocusBorder(shape = RoundedCornerShape(12.dp), glow = false, animated = false)
            // 列表首项承载「从导航栏右键进入」的焦点请求器
            .then(if (contentEnterRequester != null) Modifier.focusRequester(contentEnterRequester) else Modifier)
            // 首列卡片拦截左键：精确返回当前选中 tab（而非空间最近项），避免焦点乱跳
            .then(
                if (interceptLeftExit && onExitToNav != null) {
                    Modifier.onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft) {
                            onExitToNav()
                            true
                        } else false
                    }
                } else Modifier
            )
            .clickable { onClick() },
        // 与「设置」页卡片同色（SettingsItem 用 LXCardDark，用户手调的极淡黑半透明），
        // 浅色主区上呈浅灰半透明磨砂质感；不画静态边框，与设置页卡片外观一致（聚焦时由 lxFocusBorder 显示焦点框）。
        // 注：此前用 LXSurfaceCard.copy(alpha=0.72f) 白 72% 叠近白底，实测等于没改，故对齐 LXCardDark。
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 封面：固定高度（不再强制 1:1 正方形），缩小卡片垂直占用，
            // 保证电视上网格能完整显示多行卡片（2.6 实测 135dp 最合适，歌单/排行共用本卡片）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (!item.coverUrl.isNullOrBlank()) {
                    RemoteImage(
                        url = item.coverUrl,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        // 网格卡片小图：512px 解码足够，比默认 1024 快得多
                        maxDimension = 512,
                        placeholder = {
                            Box(
                                // 2.5 浅色主题：封面占位用更浅的灰（surfaceVariant）
                                modifier = Modifier.fillMaxSize().background(LXSurfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = LXPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    )
                } else {
                    Box(
                        // 2.5 浅色主题：封面占位用更浅的灰（surfaceVariant）
                        modifier = Modifier.fillMaxSize().background(LXSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = LXPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                // 2.5 浅色主题：LXCardDark 卡片上的深色歌单名（与设置页卡片文字一致）
                color = LXOnCardDark,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.songCount > 0) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${item.songCount} 首",
                    fontSize = 11.sp,
                    color = LXOnCardDarkSecondary
                )
            }
        }
    }
}

/**
 * 一键播放全部按钮
 */
@Composable
fun PlayAllButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            // 红底按钮，焦点边框改白色（默认红色在红底上不可见）
            .lxFocusBorder(focusedColor = FocusBorder, shape = RoundedCornerShape(8.dp), glow = false, animated = false)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = LXPrimary),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "播放全部歌曲",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * 歌曲列表行
 */
@Composable
fun BrowseSongRow(
    index: Int,
    song: Song,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onPlayStay: () -> Unit,
    onPlayOpen: () -> Unit,
    onExitToNav: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onSongFocused: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        // 根节点挂 onFocusChanged：本行任一按钮（播放/播放+跳转/收藏）获得焦点即上报行号，
        // 用于「焦点接近列表末尾时触发加载更多」——不依赖列表滚动改 layoutInfo 被发现。
        modifier = modifier.onFocusChanged { if (it.hasFocus) onSongFocused(index) },
        // 点6：深色卡片（仿播放页播放列表暗色面板），去除边框，仅遥控器焦点选中内部按钮才有边框
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 14.sp,
                color = LXOnCardDarkSecondary,
                modifier = Modifier.width(36.dp)
            )
            // 封面
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                if (!song.picUrl.isNullOrBlank()) {
                    RemoteImage(
                        url = song.picUrl,
                        contentDescription = song.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        // 44dp 小图：256px 解码足够
                        maxDimension = 256,
                        placeholder = {
                            Box(
                                modifier = Modifier.fillMaxSize().background(platformBrandColor(song.platform).copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = platformIcon(song.platform),
                                    contentDescription = null,
                                    tint = platformBrandColor(song.platform),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(platformBrandColor(song.platform).copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = platformIcon(song.platform),
                            contentDescription = null,
                            tint = platformBrandColor(song.platform),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
Text(
                text = song.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                // 点6：深色卡片上的白色歌名
                color = LXOnCardDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
                Text(
                    text = song.singer,
                    fontSize = 13.sp,
                    // 点6：深色卡片上的次文字（白70%）
                    color = LXOnCardDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 时长（与收藏/搜索歌单歌曲列表统一）
            song.duration?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    fontSize = 14.sp,
                    color = LXOnCardDarkSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))

            // 三个操作按钮（仿菠萝：选中按钮而非整行）
            // ① 播放本首，停留在当前页（不进入播放页）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
                    .lxCircleButtonFocus()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(
                        if (onExitToNav != null) {
                            Modifier.onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft) {
                                    onExitToNav()
                                    true
                                } else false
                            }
                        } else Modifier
                    )
                    .clickable { onPlayStay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = LXOnCardDark,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // ② 播放本首并进入播放页（整列表已作为播放队列）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
                    .lxCircleButtonFocus()
                    .clickable { onPlayOpen() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "播放并进入播放页",
                    tint = LXOnCardDark,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // ③ 收藏/取消收藏（可选显示）
            if (onToggleFavorite != null) {
                FavoriteButton(
                    isFavorite = isFavorite,
                    onClick = onToggleFavorite
                )
            }
        }
    }
}

/**
 * 格式化时长（与 SearchScreen.formatDuration 一致）
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * 设置页面
 */
@Composable
fun SettingsScreen(
    onNavigateToSourceManagement: () -> Unit,
    onNavigateToInterfaceSettings: () -> Unit = {},
    // 2.8 缓存管理改为独立子页面
    onNavigateToCacheManage: () -> Unit = {},
    defaultPlatform: MusicPlatform = MusicPlatform.KW,
    onDefaultPlatformChange: (MusicPlatform) -> Unit = {},
    preferredQuality: AudioQuality = AudioQuality.QUALITY_320K,
    onPreferredQualityChange: (AudioQuality) -> Unit = {},
    // 2.8 歌词设置：是否显示翻译歌词
    lyricTranslationEnabled: Boolean = true,
    onLyricTranslationEnabledChange: (Boolean) -> Unit = {},
    contentEnterRequester: FocusRequester,
    onExitToNav: () -> Unit,
    // 重复点击 tab 刷新信号：>0 且变化时滚动回顶部
    refreshTick: Int = 0,
    // 从子路由（播放源管理/界面设置）返回的信号：>0 且变化时恢复焦点到点击的卡片
    restoreTick: Int = 0,
    modifier: Modifier = Modifier
) {
    var showPlatformDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    // 2.8 歌词设置弹窗
    var showLyricsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // 设置列表滚动状态（重复点击「设置」tab 时滚动回顶部）
    val settingsScrollState = rememberScrollState()

    // 进入子路由前点击的设置项索引（rememberSaveable：从子路由返回重新组合后仍可恢复）
    var lastClickedIndex by rememberSaveable { mutableIntStateOf(-1) }
    // 各设置项的焦点请求器（用于返回后恢复焦点到点击的卡片；共 7 项：默认音乐平台/播放源管理/播放设置/歌词设置/界面设置/缓存管理/关于）
    val itemRequesters = remember { List(7) { FocusRequester() } }

    // 重复点击「设置」tab：滚动回顶部（仅 tick 真正变化时，避免组件重建误触发）
    var lastRefreshTick by remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && refreshTick != lastRefreshTick) {
            lastRefreshTick = refreshTick
            settingsScrollState.animateScrollTo(0)
        }
    }

    // 从子路由返回：恢复焦点到点击进入子页面的设置卡片
    var lastRestoreTick by remember { mutableIntStateOf(restoreTick) }
    LaunchedEffect(restoreTick) {
        if (restoreTick > 0 && restoreTick != lastRestoreTick) {
            lastRestoreTick = restoreTick
            if (lastClickedIndex in 0..6) {
                itemRequesters[lastClickedIndex].requestFocus()
            }
        }
    }

    Column(
        modifier = modifier
            .padding(24.dp)
            .fillMaxSize()
            // 内容超屏（小屏/字体放大时最后一项「关于」会被底部裁掉）→ 允许滚动
            .verticalScroll(settingsScrollState)
    ) {
        // 2.6 去除顶部「设置」标题栏（内容直接铺满）

        // 设置项列表
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsItem(
                title = "默认音乐平台",
                subtitle = "搜索、歌单、排行、发现使用的音乐平台（当前：${platformShortName(defaultPlatform)}）",
                icon = Icons.Default.LibraryMusic,
                onClick = { showPlatformDialog = true },
                focusRequester = contentEnterRequester,
                // 返回后焦点恢复用（每个设置项一个 requester）
                extraFocusRequester = itemRequesters[0],
                onExitToNav = onExitToNav
            )

            SettingsItem(
                title = "播放源管理",
                subtitle = "管理导入的播放源文件",
                icon = Icons.Default.Cloud,
                onClick = {
                    // 记录点击项：从子路由返回时恢复焦点到本卡片
                    lastClickedIndex = 1
                    onNavigateToSourceManagement()
                },
                extraFocusRequester = itemRequesters[1],
                onExitToNav = onExitToNav
            )

            SettingsItem(
                title = "播放设置",
                subtitle = "优先歌曲音质（当前：${qualityShortName(preferredQuality)}）",
                icon = Icons.Default.Equalizer,
                onClick = { showQualityDialog = true },
                extraFocusRequester = itemRequesters[2],
                onExitToNav = onExitToNav
            )

            // 2.8 歌词设置：显示翻译歌词开关
            SettingsItem(
                title = "歌词设置",
                subtitle = "是否在播放页显示歌词翻译（当前：${if (lyricTranslationEnabled) "开启" else "关闭"}）",
                icon = Icons.Default.Lyrics,
                onClick = { showLyricsDialog = true },
                extraFocusRequester = itemRequesters[3],
                onExitToNav = onExitToNav
            )

            SettingsItem(
                title = "界面设置",
                subtitle = "主题模式、主题色等界面相关设置",
                icon = Icons.Default.Palette,
                onClick = {
                    // 记录点击项：从子路由返回时恢复焦点到本卡片
                    lastClickedIndex = 4
                    onNavigateToInterfaceSettings()
                },
                extraFocusRequester = itemRequesters[4],
                onExitToNav = onExitToNav
            )

            SettingsItem(
                title = "缓存管理",
                subtitle = "查看与清理音频、封面、歌词缓存",
                icon = Icons.Default.Storage,
                onClick = {
                    // 2.8 改为独立子页面；记录点击项：从子路由返回时恢复焦点到本卡片
                    lastClickedIndex = 5
                    onNavigateToCacheManage()
                },
                extraFocusRequester = itemRequesters[5],
                onExitToNav = onExitToNav
            )

            SettingsItem(
                title = "关于",
                subtitle = "版本号、说明等",
                icon = Icons.Default.Info,
                onClick = { showAboutDialog = true },
                extraFocusRequester = itemRequesters[6],
                onExitToNav = onExitToNav
            )
        }
    }

    // 默认音乐平台选择对话框（电视遥控器友好）
    if (showPlatformDialog) {
        PlatformSelectDialog(
            currentPlatform = defaultPlatform,
            onSelect = { platform ->
                onDefaultPlatformChange(platform)
                showPlatformDialog = false
            },
            onDismiss = { showPlatformDialog = false }
        )
    }

    // 播放设置：优先音质选择对话框（电视遥控器友好）
    if (showQualityDialog) {
        QualitySelectDialog(
            currentQuality = preferredQuality,
            onSelect = { quality ->
                onPreferredQualityChange(quality)
                showQualityDialog = false
            },
            onDismiss = { showQualityDialog = false }
        )
    }

    // 2.8 歌词设置对话框：显示翻译歌词开关
    if (showLyricsDialog) {
        LyricsSettingsDialog(
            translationEnabled = lyricTranslationEnabled,
            onTranslationEnabledChange = onLyricTranslationEnabledChange,
            onDismiss = { showLyricsDialog = false }
        )
    }

    // 关于对话框：显示当前安装的版本号（versionName + versionCode），用于核对构建是否生效
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

/**
 * 关于对话框：展示应用名与当前安装版本号。
 * 版本号从 PackageManager 读取（不依赖 BuildConfig 开关），versionName 即 build.gradle.kts 的
 * versionName（如 2.5.202608071827），versionCode 为递增构建号，可据此判断是否装到了最新构建。
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
    }
    val versionCode = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString()
            else info.versionCode.toString()
        } catch (e: Exception) {
            "未知"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // 弹窗背景统一灰白（LXSurfaceDialog），与其它弹窗一致
        containerColor = LXSurfaceDialog,
        title = { Text("关于 LX Music TV", fontSize = 20.sp, color = LXTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("洛雪音乐 TV 版 · 音乐播放器", fontSize = 15.sp, color = LXTextPrimary)
                Text(
                    text = "版本号：v$versionName",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXPrimary
                )
                Text(text = "内部版本（versionCode）：$versionCode", fontSize = 14.sp, color = LXTextSecondary)
                Text(
                    text = "支持洛雪 JS 播放源 · 多平台音乐搜索/榜单/歌单",
                    fontSize = 13.sp,
                    color = LXTextSecondary
                )
                // 2.8 项目地址（GitHub 开源仓库）
                Text(
                    text = "项目地址：https://github.com/bmizhou/lx-music-tv",
                    fontSize = 13.sp,
                    color = LXPrimary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定", color = LXPrimary)
            }
        }
    )
}

/**
 * 2.8 歌词设置对话框：显示翻译歌词开关（设置页歌词设置项进入）
 */
@Composable
fun LyricsSettingsDialog(
    translationEnabled: Boolean,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LXSurfaceDialog,
        title = { Text("歌词设置", fontSize = 20.sp, color = LXTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 显示翻译歌词开关（整行可聚焦点击切换）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lxFocusBorder(
                            shape = RoundedCornerShape(8.dp),
                            glow = false,
                            animated = false,
                            unfocusedColor = Color.Transparent,
                            unfocusedWidth = 0.dp
                        )
                        .clickable { onTranslationEnabledChange(!translationEnabled) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "显示翻译歌词",
                        fontSize = 15.sp,
                        color = LXTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = onTranslationEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = LXPrimary,
                            checkedThumbColor = Color.White
                        )
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "开启后，播放页歌词下方显示翻译（需音源/接口返回翻译歌词）",
                    fontSize = 12.sp,
                    color = LXTextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定", color = LXPrimary)
            }
        }
    )
}

/**
 * 2.8 缓存管理页（独立子页面，替代原 2.7 弹窗）
 * 展示音频/封面/歌词缓存大小，提供按分类清理、全部清理，
 * 以及「清除未收藏歌曲缓存」（仅保留已收藏歌曲，含未收藏歌单中的歌曲）。
 * 2.8 优化：返回按钮固定在顶部（不随内容滚动，方向键稳定可达）；
 * 操作按钮统一为小键盘键帽样式（与搜索页键盘一致）；返回按钮按「右键」可切到悬浮播放球。
 */
@Composable
fun CacheManageScreen(
    favorites: List<Favorite>,
    onBack: () -> Unit,
    // 2.8 返回按钮按「右键」→ 聚焦右上角悬浮播放球
    onFocusFloatingBall: () -> Unit = {},
    // 2.8 音频缓存被清除后回调（用于重建播放器，避免旧 SimpleCache 失效导致播放失败）
    onCacheCleared: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var audioSize by remember { mutableStateOf(0L) }
    var coverSize by remember { mutableStateOf(0L) }
    var lyricSize by remember { mutableStateOf(0L) }
    // 2.8 音乐缓存开关（默认开启，SharedPreferences 持久化；与 PlayerService 共用 lx_settings）
    var musicCacheEnabled by remember {
        mutableStateOf(
            try {
                context.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                    .getBoolean("music_cache_enabled", true)
            } catch (e: Exception) {
                true
            }
        )
    }
    // 操作结果提示（如「已清除未收藏歌曲缓存」）
    var actionMessage by remember { mutableStateOf<String?>(null) }
    // 2.8 是否正在清除缓存（清除中显示动画提示 + 禁用重复点击）
    var isClearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 计算缓存大小（IO 线程，目录文件多时避免卡主线程）
    suspend fun refreshSizes() {
        withContext(Dispatchers.IO) {
            audioSize = CacheManager.audioCacheSize(context)
            coverSize = CacheManager.coverCacheSize(context)
            lyricSize = CacheManager.lyricCacheSize(context)
        }
    }
    LaunchedEffect(Unit) { refreshSizes() }

    // 2.8 受保护缓存 key 集合（格式 "平台key|歌曲id"，与音频缓存 key 前缀一致）：
    // 收藏单曲 + 收藏歌单的歌曲（按歌单分组累积在 SharedPreferences，取消收藏歌单即移除）——缓存均不清除
    val favoriteKeys = remember(favorites) {
        val singleKeys = favorites.map { "${it.platform.key}|${it.musicId}" }.toSet()
        val playlistKeys = try {
            val raw = context.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                .getString("favorite_playlist_song_map", "") ?: ""
            if (raw.isBlank()) emptySet()
            else raw.split(";").flatMap { entry ->
                val parts = entry.split("|", limit = 2)
                if (parts.size == 2) parts[1].split(",") else emptyList()
            }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        singleKeys + playlistKeys
    }

    // 清理动作：IO 线程执行 + 刷新大小 + 提示（2.8 清除中状态 + 防重复点击）
    // rebuildPlayer=true 的操作为「影响音频缓存的清除」：完成后通知重建播放器，
    // 避免旧 ExoPlayer 持有已释放/已删文件的 SimpleCache 导致后续播放全部失败。
    // 注意：rebuildPlayer 必须在 action 之前（trailing lambda 绑定最后一个参数 = action）
    fun doClear(label: String, rebuildPlayer: Boolean = false, action: (Context) -> Unit) {
        if (isClearing) return
        scope.launch {
            isClearing = true
            actionMessage = null
            withContext(Dispatchers.IO) {
                try { action(context) } catch (e: Exception) {}
                refreshSizes()
            }
            isClearing = false
            actionMessage = label
            if (rebuildPlayer) onCacheCleared()
        }
    }

    // 返回按钮初始焦点
    val backFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        requestInitialFocus(
            focusRequester = backFocusRequester,
            attempted = { initialFocusRequested },
            markAttempted = { initialFocusRequested = true }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LXSurfaceMain)
    ) {
        // 顶部氛围渐变叠加层
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LXAccentGradientBrush)
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定顶部导航栏（不在滚动区：方向键始终可达返回按钮）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .focusRequester(backFocusRequester)
                        .lxBackButtonFocus()
                        // 2.8 右键 → 聚焦右上角悬浮播放球
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionRight) {
                                onFocusFloatingBall()
                                true
                            } else false
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = LXTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "缓存管理",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXTextPrimary
                )
            }

            // 内容滚动区
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 2.8 音乐缓存总开关（默认开启）
                item {
                    MusicCacheToggleCard(
                        enabled = musicCacheEnabled,
                        onToggle = { new ->
                            musicCacheEnabled = new
                            try {
                                context.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("music_cache_enabled", new)
                                    .apply()
                            } catch (e: Exception) {}
                            actionMessage = if (new) "音乐缓存已开启，下次播放生效" else "音乐缓存已关闭，下次播放生效"
                        }
                    )
                }

                // 每种缓存一张卡片：左侧信息 + 右侧「清除」键帽
                item {
                    CacheManageCard(
                        label = "音频缓存",
                        hint = "播放过的歌曲文件，上限 2GB，超出自动清理最久未播放的歌曲",
                        bytes = audioSize,
                        // 2.8 上限说明移入卡片内（SimpleCache LRU 淘汰）
                        //extraHint = "上限 2GB，超出自动清理最久未播放的歌曲",
                        onClear = { doClear("已清空音频缓存", rebuildPlayer = true) { CacheManager.clearAudio(it) } }
                    )
                }
                item {
                    CacheManageCard(
                        label = "封面缓存",
                        hint = "歌曲/歌单封面图片",
                        bytes = coverSize,
                        onClear = { doClear("已清空封面缓存") { CacheManager.clearCover(it) } }
                    )
                }
                item {
                    CacheManageCard(
                        label = "歌词缓存",
                        hint = "平台歌词文本",
                        bytes = lyricSize,
                        onClear = { doClear("已清空歌词缓存") { CacheManager.clearLyric(it) } }
                    )
                }

                // 底部操作区：清除未收藏缓存 + 清除全部（并排大按钮，替代原细长键帽）
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "清理后对应内容下次使用时需重新联网获取；收藏的歌曲缓存会保留",
                            fontSize = 12.sp,
                            color = LXTextSecondary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CacheActionButton(
                                text = "清除未收藏缓存",
                                hint = "仅保留已收藏歌曲与歌单歌曲",
                                enabled = !isClearing,
                                onClick = {
                                    doClear("已清除未收藏歌曲的音频缓存", rebuildPlayer = true) {
                                        CacheManager.clearUnfavoritedAudio(it, favoriteKeys)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            CacheActionButton(
                                text = "清除全部",
                                hint = "音频 / 封面 / 歌词",
                                enabled = !isClearing,
                                onClick = { doClear("已清空全部缓存", rebuildPlayer = true) { CacheManager.clearAll(it) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 2.8 清除中提示（转圈 + 文字，淡入淡出过渡）
                        AnimatedVisibility(visible = isClearing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = LXPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "正在清除缓存...",
                                    fontSize = 13.sp,
                                    color = LXPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        // 操作结果提示（淡入淡出过渡）
                        AnimatedVisibility(visible = actionMessage != null) {
                            actionMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    fontSize = 13.sp,
                                    color = LXPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 缓存管理卡片（2.8 卡片式布局）：左侧标签/大小/说明，右侧「清除」键帽按钮
 */
@Composable
private fun CacheManageCard(
    label: String,
    hint: String,
    bytes: Long,
    onClear: () -> Unit,
    // 2.8 卡片内附加说明（如音频缓存上限）
    extraHint: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXOnCardDark
                )
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = LXOnCardDarkSecondary
                )
                extraHint?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = LXOnCardDarkSecondary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = formatCacheSize(bytes),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 最右侧清除键帽（小键盘样式）
            KeyboardKey(
                text = "清除",
                onClick = onClear,
                modifier = Modifier.width(96.dp)
            )
        }
    }
}

/** 缓存大小格式化：<1MB 显示 KB，否则 MB（保留 1 位小数） */
fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

/**
 * 2.8 音乐缓存总开关卡片：左侧标题/说明，右侧 Switch（默认开启，SharedPreferences 持久化）
 */
@Composable
private fun MusicCacheToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "音乐缓存",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXOnCardDark
                )
                Text(
                    text = "开启后播放的歌曲边播边缓存到本地，下次播放免流量",
                    fontSize = 12.sp,
                    color = LXOnCardDarkSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = LXPrimary,
                    checkedThumbColor = Color.White
                )
            )
        }
    }
}

/**
 * 2.8 缓存操作大按钮（并排布局，替代原细长键帽）：主标题 + 可选副说明，高 56dp 圆角 12dp。
 * 公开组件：播放源管理（平台配置/删除）、收藏页顶部 tab（歌曲/歌单）等复用同款样式。
 */
@Composable
fun CacheActionButton(
    text: String,
    onClick: () -> Unit,
    hint: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = when {
        highlighted -> LXPrimary
        isFocused -> LXPrimary.copy(alpha = 0.35f)
        else -> LXSurfaceVariant
    }
    val textColor = if (isFocused || highlighted) Color.White else LXTextPrimary
    val hintColor = if (isFocused || highlighted) Color.White.copy(alpha = 0.8f) else LXTextSecondary
    val borderColor = if (isFocused) FocusBorder else Color.Transparent

    Box(
        modifier = modifier
            .height(56.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (isFocused) 3.dp else 0.dp, borderColor, RoundedCornerShape(12.dp))
            // 清除中禁用：不可点击 + 半透明置灰
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
            hint?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = hintColor,
                    maxLines = 1
                )
            }
        }
    }
}

/** 音质中文简称（设置页副标题展示） */
fun qualityShortName(quality: AudioQuality): String = when (quality) {
    AudioQuality.QUALITY_128K -> "标准 128k"
    AudioQuality.QUALITY_320K -> "高品质 320k"
    AudioQuality.FLAC -> "无损 flac"
    AudioQuality.FLAC_24BIT -> "Hi-Res flac24bit"
}

/**
 * 音质选择对话框（遥控器方向键 + 确认选择）
 */
@Composable
fun QualitySelectDialog(
    currentQuality: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val qualities = listOf(
        AudioQuality.QUALITY_128K,
        AudioQuality.QUALITY_320K,
        AudioQuality.FLAC,
        AudioQuality.FLAC_24BIT
    )
    // 进入对话框时把焦点落到「当前所选项」，而非第一项（点4）
    val selectedIndex = qualities.indexOf(currentQuality).coerceAtLeast(0)
    val selectedRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        requestInitialFocus(
            focusRequester = selectedRequester,
            attempted = { initialFocusRequested },
            markAttempted = { initialFocusRequested = true }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 对话框背景统一为灰白（LXSurfaceDialog #F0F0F2，避免纯白刺眼）
        containerColor = LXSurfaceDialog,
        title = {
            Text(
                text = "选择优先歌曲音质",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                // 灰白底深色标题
                color = LXTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                qualities.forEachIndexed { index, quality ->
                    val isSelected = quality == currentQuality
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                        // focusRequester 必须在 focusable(clickable) 之前
                        .then(if (index == selectedIndex) Modifier.focusRequester(selectedRequester) else Modifier)
                        // 对话框内列表密集，关闭发光与动画：避免上下快速切换时阴影/边框动画鬼畜
                        .lxSelectorFocus(shape = RoundedCornerShape(8.dp), glow = false, animated = false)
                        .clickable { onSelect(quality) },
                    // 灰白底上：选中=浅红填充，未选中=透明（沿用对话框底色）
                    color = if (isSelected) LXPrimary.copy(alpha = 0.12f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = qualityShortName(quality),
                                fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                // 灰白底深色文字
                                color = LXTextPrimary
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = LXPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = LXTextSecondary)
            }
        },
        modifier = modifier
    )
}

/**
 * 平台选择对话框（遥控器方向键 + 确认选择）
 */
@Composable
fun PlatformSelectDialog(
    currentPlatform: MusicPlatform,
    onSelect: (MusicPlatform) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platforms = listOf(
        MusicPlatform.KW,
        MusicPlatform.KG,
        MusicPlatform.TX,
        MusicPlatform.WY,
        MusicPlatform.MG
    )
    // 进入对话框时把焦点落到「当前所选项」，而非第一项（点4）
    val selectedIndex = platforms.indexOf(currentPlatform).coerceAtLeast(0)
    val selectedRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        requestInitialFocus(
            focusRequester = selectedRequester,
            attempted = { initialFocusRequested },
            markAttempted = { initialFocusRequested = true }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 对话框背景统一为灰白（LXSurfaceDialog #F0F0F2，避免纯白刺眼）
        containerColor = LXSurfaceDialog,
        title = {
            Text(
                text = "选择默认音乐平台",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                // 灰白底深色标题
                color = LXTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                platforms.forEachIndexed { index, platform ->
                    val isSelected = platform == currentPlatform
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                        // focusRequester 必须在 focusable(clickable) 之前
                        .then(if (index == selectedIndex) Modifier.focusRequester(selectedRequester) else Modifier)
                        // 对话框内列表密集，关闭发光与动画：避免上下快速切换时阴影/边框动画鬼畜
                        .lxSelectorFocus(shape = RoundedCornerShape(8.dp), glow = false, animated = false)
                        .clickable { onSelect(platform) },
                    // 灰白底上：选中=浅红填充，未选中=透明（沿用对话框底色）
                    color = if (isSelected) LXPrimary.copy(alpha = 0.12f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = platform.displayName,
                                fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                // 灰白底深色文字
                                color = LXTextPrimary
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = LXPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = LXTextSecondary)
            }
        },
        dismissButton = {}
    )
}

/**
 * 设置项
 */
@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    // 额外的焦点请求器（如「从子路由返回后恢复焦点」用；与 focusRequester 可并存）
    extraFocusRequester: FocusRequester? = null,
    // 单列设置项：任意项左键精确返回当前选中 tab（而非空间最近项），避免焦点乱跳
    onExitToNav: (() -> Unit)? = null
) {
    // 小屏/字体放大时设置列表可滚动：聚焦本项时将其完整滚入可见区（否则「关于」等末项会被底部裁掉）
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            // focusRequester 必须在 focusable(clickable) 之前
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (extraFocusRequester != null) Modifier.focusRequester(extraFocusRequester) else Modifier)
            // 聚焦时把本卡片滚到可见（对滚动容器生效）
            .onFocusChanged { if (it.hasFocus) scope.launch { bringIntoView.bringIntoView() } }
            // 单列设置项：任意项左键精确返回当前选中 tab
            .then(
                if (onExitToNav != null) {
                    Modifier.onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft) {
                            onExitToNav()
                            true
                        } else false
                    }
                } else Modifier
            )
            // 点6：未聚焦无边框，仅焦点选中才显示边框
            .lxFocusBorder(
                shape = RoundedCornerShape(12.dp),
                unfocusedColor = Color.Transparent,
                unfocusedWidth = 0.dp
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            // 深色卡片（仿播放页播放列表暗色面板），去除边框——用户明确要求保持原样，勿改背景
            containerColor = LXCardDark
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = LXPrimary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    // 点6：深色卡片上的白色标题
                    color = LXOnCardDark
                )

                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    // 点6：深色卡片上的次文字（白70%）
                    color = LXOnCardDarkSecondary
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "进入",
                tint = LXOnCardDarkSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// 数据模型
data class NavItem(
    val title: String,
    val icon: ImageVector,
    val description: String
)