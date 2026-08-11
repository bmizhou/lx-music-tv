package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.lxmusic.tv.data.model.Favorite
import com.lxmusic.tv.data.model.FavoritePlaylist
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.data.model.Playlist
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.component.RemoteImage
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.lxSelectorFocus
import com.lxmusic.tv.presentation.component.lxCircleButtonFocus
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDarkSecondary
import com.lxmusic.tv.presentation.theme.LXSurfaceVariant
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXTextSecondary
import com.lxmusic.tv.presentation.theme.LXPrimary

/**
 * 收藏页面
 * 分为「歌曲 / 歌单」两栏，可切换（触屏点击 + 遥控器方向键）
 * - 歌曲栏：收藏的歌曲，点击播放，可取消收藏
 * - 歌单栏：收藏的歌单，点击展开歌单内歌曲列表（页内返回），可取消收藏
 */
@Composable
fun FavoritesScreen(
    favorites: List<Favorite> = emptyList(),
    favoritePlaylists: List<FavoritePlaylist> = emptyList(),
    favoriteSongIds: Set<String> = emptySet(),
    favoritePlaylistSongs: List<Song>? = null,
    favoritePlaylistLoading: Boolean = false,
    // 收藏歌单详情分页续拉（酷狗）：焦点触底加载下一页
    favoritePlaylistSongsHasMore: Boolean = false,
    favoritePlaylistSongsLoadingMore: Boolean = false,
    onLoadMoreFavoritePlaylistSongs: () -> Unit = {},
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    onPlaySong: (Song, List<Song>) -> Unit,
    onPlaySongStay: (Song, List<Song>) -> Unit,
    onToggleSongFavorite: (Song) -> Unit,
    onTogglePlaylistFavorite: (Playlist) -> Unit,
    onOpenFavoritePlaylist: (FavoritePlaylist) -> Unit,
    onBackFromFavoritePlaylistSongs: () -> Unit,
    // 收藏歌单详情歌曲行②「整列表播放」：酷狗先补拉完整列表再播（由外部绑定 VM）
    onPlayPlaylistSongsAll: (Song) -> Unit = {},
    onBack: () -> Unit,
    contentEnterRequester: FocusRequester,
    onExitToNav: () -> Unit,
    // 重复点击 tab 刷新信号：>0 且变化时退出歌单详情并回到歌曲列表（数据由 Room flow 自动更新）
    refreshTick: Int = 0,
    modifier: Modifier = Modifier
) {
    // 0 = 歌曲，1 = 歌单
    var selectedTab by remember { mutableIntStateOf(0) }

    // 重复点击「收藏」tab：退出歌单详情、回到歌曲列表首屏
    var lastRefreshTick by remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && refreshTick != lastRefreshTick) {
            lastRefreshTick = refreshTick
            onBackFromFavoritePlaylistSongs()
            selectedTab = 0
        }
    }

    // 收藏歌单详情（歌曲列表）层级：遥控器返回优先收起详情回到歌单列表，
    // 否则会冒泡到首页的退出确认对话框。加载中也拦截返回（可取消加载回到歌单列表）
    if (favoritePlaylistSongs != null || favoritePlaylistLoading) {
        BackHandler { onBackFromFavoritePlaylistSongs() }
    }

    // 2.8 进入收藏歌单详情：**详情打开即**聚焦顶部「歌曲」Tab（contentEnterRequester 挂在 Tab 首项），
    // 不等歌曲加载完成——消除「加载中焦点空白 → 按键从侧栏乱跳」的问题；用户按「下」进入歌曲列表
    LaunchedEffect(favoritePlaylistSongs != null) {
        if (favoritePlaylistSongs != null) {
            contentEnterRequester.requestFocus()
        }
    }

    // 全站主题由 MainActivity 根部的 LXMusicTheme 统一提供，此处不再嵌套 LXMusicTheme
    Column(
        modifier = modifier
            .fillMaxSize()
            // 2.5 浅色主题：根背景透明，由外层 MainContent 的实色基底 + 顶部氛围渐变提供
            .padding(24.dp)
    ) {
            // 歌曲/歌单 两栏切换
            FavoriteTabSelector(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    // 歌单详情（歌曲列表）打开时，点顶部「歌曲」直接退出详情并切到歌曲收藏列表，
                    // 不必先按返回键（原行为：详情优先显示，点了「歌曲」没反应）
                    if (tab == 0 && (favoritePlaylistSongs != null || favoritePlaylistLoading)) {
                        onBackFromFavoritePlaylistSongs()
                    }
                    selectedTab = tab
                },
                contentEnterRequester = contentEnterRequester,
                onExitToNav = onExitToNav,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            // 内容区
            if (favoritePlaylistSongs != null) {
                // 收藏歌单详情：歌曲列表
                FavoritePlaylistSongsView(
                    songs = favoritePlaylistSongs,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    isFavorite = { id -> favoriteSongIds.contains(id) },
                    onToggleFavorite = onToggleSongFavorite,
                    onSongClick = { song ->
                        // ② 整列表播放：酷狗分页未拉全时由 VM 补拉完整队列再播（避免只播已加载 30 首）
                        onPlayPlaylistSongsAll(song)
                    },
                    onPlaySongStay = { song -> onPlaySongStay(song, favoritePlaylistSongs) },
                    onExitToNav = onExitToNav,
                    onLoadMore = onLoadMoreFavoritePlaylistSongs,
                    hasMore = favoritePlaylistSongsHasMore,
                    loadingMore = favoritePlaylistSongsLoadingMore,
                    modifier = Modifier.weight(1f)
                )
            } else if (favoritePlaylistLoading) {
                // 歌单歌曲加载中：立即显示转圈动画（网易云等平台接口较慢，避免点击后空白跳变）
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = LXPrimary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "歌单加载中...",
                            fontSize = 15.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else if (selectedTab == 0) {
                // 歌曲收藏列表
                FavoriteSongsView(
                    favorites = favorites,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    onSongClick = { song ->
                        val queue = favorites.map { it.toSong() }
                        onPlaySong(song, queue)
                    },
                    onPlaySongStay = { song ->
                        val queue = favorites.map { it.toSong() }
                        onPlaySongStay(song, queue)
                    },
                    onToggleFavorite = onToggleSongFavorite,
                    onExitToNav = onExitToNav,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // 歌单收藏列表
                FavoritePlaylistsView(
                    playlists = favoritePlaylists,
                    onOpenPlaylist = onOpenFavoritePlaylist,
                    onToggleFavorite = onTogglePlaylistFavorite,
                    onExitToNav = onExitToNav,
                    modifier = Modifier.weight(1f)
                )
            }
        }
}

/**
 * 收藏页两栏切换（歌曲 / 歌单）
 * 触屏点击 + 遥控器方向键左右切换，确认键选中。
 *
 * 采用真实焦点驱动：每个 Tab 直接 [focusable]，焦点态由 Compose 默认
 * 二维焦点系统管理（左右方向键在 Tab 间移动），[lxSelectorFocus] 的视觉
 * 高亮跟随真实 [isFocused]，消除早期「手动 focusedIndex 与实际焦点不同步」
 * 导致的选中看不见、方向键错位问题。
 */
@Composable
fun FavoriteTabSelector(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    contentEnterRequester: FocusRequester? = null,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("歌曲" to Icons.Default.MusicNote, "歌单" to Icons.Default.PlaylistPlay)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        tabs.forEachIndexed { index, (title, icon) ->
            val isSelected = selectedTab == index

            Surface(
                modifier = Modifier
                    .weight(1f)
                    // 首个 Tab 承载「从导航栏右键进入」的焦点请求器
                    .then(if (index == 0 && contentEnterRequester != null) Modifier.focusRequester(contentEnterRequester) else Modifier)
                    // 首个 Tab 左键精确返回选中 tab；其余 Tab 左键由内置切换到上一个 Tab
                    .then(
                        if (index == 0 && onExitToNav != null) {
                            Modifier.onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft) {
                                    onExitToNav()
                                    true
                                } else false
                            }
                        } else Modifier
                    )
                    // 选中态为红底，默认红色焦点边框会"隐身"，改白色边框保证焦点清晰可见
                    .lxSelectorFocus(focusedColor = FocusBorder, shape = RoundedCornerShape(8.dp))
                    .clickable { onTabSelected(index) },
                color = if (isSelected) LXPrimary else LXSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    width = 2.dp,
                    color = if (isSelected) LXPrimary else Color.Transparent
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        // 选中态红底用白图标；未选中浅灰底用深图标
                        tint = if (isSelected) Color.White else LXTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        // 选中态红底用白字；未选中浅灰底用深色字
                        color = if (isSelected) Color.White else LXTextPrimary
                    )
                }
            }
        }
    }
}

/**
 * 收藏歌曲列表
 */
@Composable
fun FavoriteSongsView(
    favorites: List<Favorite>,
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    onSongClick: (Song) -> Unit,
    onPlaySongStay: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (favorites.isEmpty()) {
        FavoriteEmptyState(
            text = "还没有收藏的歌曲",
            hint = "在搜索、歌单、排行或播放页面点击 ♥ 收藏歌曲",
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(favorites, key = { _, it -> it.musicId }) { index, favorite ->
            val song = favorite.toSong()
                    FavoriteSongRow(
                        song = song,
                        index = index,
                        isCurrentPlaying = song.id == currentSongId && isPlaying,
                        onPlayStay = { onPlaySongStay(song) },
                        onPlayOpen = { onSongClick(song) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        // 单列列表：任意行左键即返回选中 tab
                        onExitToNav = onExitToNav,
                        modifier = Modifier.fillMaxWidth()
                    )
        }
    }
}

/**
 * 收藏歌曲行
 */
@Composable
fun FavoriteSongRow(
    song: Song,
    index: Int = 0,
    isCurrentPlaying: Boolean = false,
    onPlayStay: () -> Unit,
    onPlayOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        // 点6：深色卡片（仿播放页播放列表暗色面板），播放中仅靠红色文字区分
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号（仿播放页播放列表）：当前播放行红色、其余跟随主题（浅色深灰/深色白 70%，
            // 与 BrowseSongRow 一致——不可硬编码白色，浅色模式下白字在浅底上不可见）
            Text(
                text = "${index + 1}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isCurrentPlaying) LXPrimary else LXOnCardDarkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp)
            )

            // 封面（无真实封面时用平台品牌色块）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(platformBrandColor(song.platform).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (!song.picUrl.isNullOrBlank()) {
                    RemoteImage(
                        url = song.picUrl,
                        contentDescription = song.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = {
                            Icon(
                                imageVector = platformIcon(song.platform),
                                contentDescription = null,
                                tint = platformBrandColor(song.platform),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                } else {
                    Icon(
                        imageVector = platformIcon(song.platform),
                        contentDescription = null,
                        tint = platformBrandColor(song.platform),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrentPlaying) LXPrimary else LXOnCardDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        text = platformShortName(song.platform),
                        fontSize = 11.sp,
                        color = platformBrandColor(song.platform),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                Text(
                    text = song.singer,
                    fontSize = 13.sp,
                    color = LXOnCardDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                }
            }

            // 三个操作按钮（仿菠萝：选中按钮而非整行）
            // ① 播放本首，停留在当前页（不进入播放页）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
                    .lxCircleButtonFocus()
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

            // ③ 收藏/取消收藏
            FavoriteButton(
                isFavorite = true,
                onClick = onToggleFavorite
            )
        }
    }
}

/**
 * 收藏歌单列表
 */
@Composable
fun FavoritePlaylistsView(
    playlists: List<FavoritePlaylist>,
    onOpenPlaylist: (FavoritePlaylist) -> Unit,
    onToggleFavorite: (Playlist) -> Unit,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (playlists.isEmpty()) {
        FavoriteEmptyState(
            text = "还没有收藏的歌单",
            hint = "在歌单搜索或歌单广场页面点击 ♥ 收藏歌单",
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(playlists, key = { it.playlistId }) { playlist ->
            FavoritePlaylistRow(
                playlist = playlist,
                onClick = { onOpenPlaylist(playlist) },
                onToggleFavorite = { onToggleFavorite(playlist.toPlaylist()) },
                // 单列列表：任意行左键精确返回选中 tab
                onExitToNav = onExitToNav,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 收藏歌单行
 */
@Composable
fun FavoritePlaylistRow(
    playlist: FavoritePlaylist,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val brandColor = platformBrandColor(playlist.platform)

    Card(
        modifier = modifier
            .fillMaxWidth()
            // 根节点拦截左键：无论焦点落在卡片内哪个按钮，左键都精确返回当前选中 tab
            //（不拦截会走默认空间导航，焦点乱跳到其它 tab，如定位到「搜索」）
            .then(
                if (onExitToNav != null) {
                    Modifier.onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft) {
                            onExitToNav()
                            true
                        } else false
                    }
                } else Modifier
            ),
        // 点6：深色卡片（仿播放页播放列表暗色面板）；卡片本身不再作为焦点目标，
        // 焦点落在卡片上的操作按钮（仿 PlaylistItem：选中按钮而非整行卡片）
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brandColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.coverUrl.isNullOrBlank()) {
                    RemoteImage(
                        url = playlist.coverUrl,
                        contentDescription = playlist.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = {
                            Icon(
                                imageVector = Icons.Default.PlaylistPlay,
                                contentDescription = null,
                                tint = brandColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlaylistPlay,
                        contentDescription = null,
                        tint = brandColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 歌单信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = LXOnCardDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        text = platformShortName(playlist.platform),
                        fontSize = 11.sp,
                        color = brandColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = buildString {
                            if (playlist.songCount > 0) append("${playlist.songCount}首")
                            playlist.creator?.let { creator ->
                                if (isNotEmpty()) append(" · ")
                                append(creator)
                            }
                        },
                        fontSize = 14.sp,
                        color = LXOnCardDarkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 两个操作按钮（仿 PlaylistItem：选中按钮而非整行卡片）
            // ① 进入歌单（加载该歌单的歌曲列表）——沿用 ChevronRight 图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
                    .lxCircleButtonFocus()
                    .clickable { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "进入歌单歌曲列表",
                    tint = LXOnCardDark,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // ② 收藏/取消收藏
            FavoriteButton(
                isFavorite = true,
                onClick = onToggleFavorite
            )
        }
    }
}

/**
 * 收藏歌单详情歌曲列表（页内展示，返回键由 FavoritesScreen 的 BackHandler 处理）
 */
@Composable
fun FavoritePlaylistSongsView(
    songs: List<Song>,
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    isFavorite: (String) -> Boolean = { false },
    onToggleFavorite: (Song) -> Unit,
    onSongClick: (Song) -> Unit,
    onPlaySongStay: (Song) -> Unit,
    onExitToNav: (() -> Unit)? = null,
    // 分页续拉（酷狗歌单详情）：可选，传了才启用焦点触底加载更多
    onLoadMore: (() -> Unit)? = null,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 焦点驱动续拉：TV 上滚动不触发重组，用「焦点所在行号」判断是否接近列表末尾（与首页 BrowsePage 一致）
    var focusedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(focusedIndex) {
        if (hasMore && !loadingMore && songs.isNotEmpty() && focusedIndex >= songs.size - 8) {
            onLoadMore?.invoke()
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (songs.isEmpty()) {
            FavoriteEmptyState(
                text = "歌单暂无歌曲或加载失败",
                hint = "可按返回键回到收藏列表",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongItem(
                        song = song,
                        index = index,
                        isCurrentPlaying = song.id == currentSongId && isPlaying,
                        isFavorite = isFavorite(song.id),
                        onToggleFavorite = { onToggleFavorite(song) },
                        onPlayStay = { onPlaySongStay(song) },
                        onPlayOpen = { onSongClick(song) },
                        // 单列列表：任意行左键精确返回选中 tab（原来仅首项拦截，其余行左键会
                        // 走默认空间导航乱跳到其它 tab，如定位到「搜索」）
                        onExitToNav = onExitToNav,
                        onSongFocused = { idx -> focusedIndex = idx },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 收藏页空状态
 */
@Composable
fun FavoriteEmptyState(
    text: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = Color(0xFF999999),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = text, fontSize = 17.sp, color = LXTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = hint, fontSize = 13.sp, color = LXTextSecondary)
        }
    }
}

/**
 * Favorite 转 Song（收藏歌曲播放用；封面/专辑/时长字段在收藏时已持久化）
 */
private fun Favorite.toSong(): Song {
    return Song(
        id = musicId,
        name = musicName,
        singer = artist,
        albumName = albumName,
        albumId = null,
        picUrl = picUrl,
        duration = duration,
        platform = platform
    )
}
