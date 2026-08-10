package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.data.model.Playlist
import com.lxmusic.tv.data.model.SearchType
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.lxFocusBorder
import com.lxmusic.tv.presentation.component.requestInitialFocus
import com.lxmusic.tv.presentation.theme.LXAccentGradientBrush
import com.lxmusic.tv.presentation.theme.LXSurfaceMain
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXTextSecondary
import com.lxmusic.tv.presentation.theme.LXPrimary

/**
 * 搜索结果页（独立页面）
 *
 * 搜索页（键盘 + 联想 + 热门）确认搜索后导航到此页展示最终结果，
 * 避免结果与输入控件挤在同一屏。支持歌曲/歌单两种类型切换、歌单详情内查看歌曲。
 *
 * 展示优先级：歌单详情 > 加载中 > 歌单结果 > 歌曲结果 > 无结果提示
 */
@Composable
fun SearchResultScreen(
    searchQuery: String = "",
    searchType: SearchType = SearchType.SONG,
    searchPlatform: MusicPlatform = MusicPlatform.KW,
    searchResults: List<Song> = emptyList(),
    playlistResults: List<Playlist> = emptyList(),
    playlistSongs: List<Song>? = null,
    isPlaylistSongsLoading: Boolean = false,
    playlistSongsError: String? = null,
    // 歌单详情分页续拉（酷狗）：焦点触底加载下一页
    playlistSongsHasMore: Boolean = false,
    playlistSongsLoadingMore: Boolean = false,
    onLoadMorePlaylistSongs: () -> Unit = {},
    isSearching: Boolean = false,
    searchError: String? = null,
    searchTriggered: Boolean = false,
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    onBack: () -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onBackFromPlaylistSongs: () -> Unit,
    onSongClick: (Song) -> Unit,
    onPlaySongStay: (Song) -> Unit = {},
    isSongFavorite: (String) -> Boolean = { false },
    onToggleSongFavorite: (Song) -> Unit = {},
    isPlaylistFavorite: (String) -> Boolean = { false },
    onTogglePlaylistFavorite: (Playlist) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 进入页面时把焦点落到返回按钮，避免初始无焦点导致方向键失灵
    val backFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        requestInitialFocus(
            focusRequester = backFocusRequester,
            attempted = { initialFocusRequested },
            markAttempted = { initialFocusRequested = true }
        )
    }

    // 歌单歌曲列表层级：遥控器返回键优先回到「歌单结果列表」（退一级），
    // 否则会冒泡到 nav 返回栈直接退出搜索页，跳过歌单结果层。
    if (playlistSongs != null || isPlaylistSongsLoading) {
        BackHandler { onBackFromPlaylistSongs() }
    }

    // 全站主题由 MainActivity 根部的 LXMusicTheme 统一提供，此处不再嵌套 LXMusicTheme
    Column(
        modifier = modifier
            .fillMaxSize()
            // 2.5 浅色主题：实色基底 + 顶部氛围渐变（仿 EchoMusic）
            .background(LXSurfaceMain)
            .background(LXAccentGradientBrush)
            .padding(24.dp)
    ) {
            // ===== 顶部导航栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    // 歌单歌曲列表层级：返回应回到「歌单结果列表」（退一级），而非直接退出搜索页。
                    // 原本此处恒为 onBack（popBackStack 退出整页），会跳过歌单结果层；
                    // 现在与下方 PlaylistSongsView 内部「返回歌单列表」按钮合并为顶部这一个返回键。
                    onClick = if (playlistSongs != null || isPlaylistSongsLoading) onBackFromPlaylistSongs else onBack,
                    modifier = Modifier
                        .size(48.dp)
                        // focusRequester 必须在 focusable(IconButton) 之前
                        .focusRequester(backFocusRequester)
                        .lxBackButtonFocus()
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = LXTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 标题：搜索词 + 平台
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (searchType == SearchType.PLAYLIST) "歌单「${searchQuery}」" else "歌曲「${searchQuery}」",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = LXTextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "搜索平台：", fontSize = 13.sp, color = LXTextSecondary)
                        Icon(
                            imageVector = platformIcon(searchPlatform),
                            contentDescription = null,
                            tint = platformBrandColor(searchPlatform),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = platformShortName(searchPlatform),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = platformBrandColor(searchPlatform)
                        )
                    }
                }

                // 类型切换（歌曲 / 歌单）
                // 右侧预留空间给右上角悬浮「正在播放」按钮（FloatingNowPlayingButton），
                // 避免两者重叠遮挡（见 MainActivity 的 TopEnd 浮球）。
                SearchTypeSelector(
                    searchType = searchType,
                    onTypeSelected = onSearchTypeChange,
                    modifier = Modifier.width(220.dp)
                )
                // 预留浮球宽度（约 48dp 浮球 + 20dp 外边距 + 余量）
                Spacer(modifier = Modifier.width(84.dp))
            }

            // ===== 搜索错误提示 =====
            if (searchError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    // 2.5 浅色主题：浅红底 + 红字（替代原暗红卡片）
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = LXPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = searchError,
                            fontSize = 14.sp,
                            color = LXPrimary
                        )
                    }
                }
            }

            // ===== 结果内容区 =====
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    // 歌单详情（歌单内歌曲列表，含加载中转圈 / 失败提示）
                    playlistSongs != null || isPlaylistSongsLoading -> {
                        PlaylistSongsView(
                            songs = playlistSongs ?: emptyList(),
                            isLoading = isPlaylistSongsLoading,
                            errorMessage = playlistSongsError,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            isFavorite = isSongFavorite,
                            onToggleFavorite = onToggleSongFavorite,
                            onSongClick = onSongClick,
                            onPlaySongStay = onPlaySongStay,
                            onLoadMore = onLoadMorePlaylistSongs,
                            hasMore = playlistSongsHasMore,
                            loadingMore = playlistSongsLoadingMore,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 加载中
                    isSearching -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = LXPrimary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(text = "搜索中...", fontSize = 16.sp, color = Color.Gray)
                            }
                        }
                    }

                    // 歌单搜索结果
                    searchType == SearchType.PLAYLIST && playlistResults.isNotEmpty() -> {
                        PlaylistResults(
                            results = playlistResults,
                            isFavorite = isPlaylistFavorite,
                            onToggleFavorite = onTogglePlaylistFavorite,
                            onPlaylistClick = onPlaylistClick,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 歌曲搜索结果
                    searchResults.isNotEmpty() -> {
                        SearchResults(
                            results = searchResults,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            isFavorite = isSongFavorite,
                            onToggleFavorite = onToggleSongFavorite,
                            onSongClick = onSongClick,
                            onPlaySongStay = onPlaySongStay,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 无结果
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = "无结果",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = when {
                                        !searchTriggered -> "输入关键词开始搜索"
                                        searchType == SearchType.PLAYLIST -> "未找到相关歌单"
                                        else -> "未找到相关歌曲"
                                    },
                                    fontSize = 18.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
}
