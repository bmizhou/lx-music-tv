package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.presentation.component.RemoteImage
import com.lxmusic.tv.presentation.component.lxCircleButtonFocus
import com.lxmusic.tv.presentation.component.lxFocusBorder
import com.lxmusic.tv.presentation.theme.LXCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDarkSecondary
import com.lxmusic.tv.presentation.theme.LXPrimary
import com.lxmusic.tv.presentation.theme.LXSurfaceDialog
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXTextSecondary

/**
 * 2.9 本地缓存歌曲页面（侧栏「本地」Tab）
 * 展示本地 SimpleCache 完整缓存的全部音频文件，断网离线可直接播放；
 * 卡片提供播放、全屏以及删除按钮（删除带二次确认弹窗）。
 */
@Composable
fun LocalCacheScreen(
    songs: List<CacheManager.CachedSongItem>,
    loading: Boolean,
    onPlaySong: (CacheManager.CachedSongItem) -> Unit,
    onDeleteSong: (CacheManager.CachedSongItem) -> Unit,
    contentEnterRequester: FocusRequester? = null,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 正在待二次确认删除的歌曲条目（null 表示未打开弹窗）
    var songPendingDelete by remember { mutableStateOf<CacheManager.CachedSongItem?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        when {
            loading && songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = LXPrimary,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "正在扫描本地已缓存歌曲...",
                            fontSize = 14.sp,
                            color = LXTextSecondary
                        )
                    }
                }
            }
            songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SdCard,
                            contentDescription = null,
                            tint = LXTextSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "暂无本地离线缓存歌曲",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = LXTextPrimary
                        )
                        Text(
                            text = "在任意页面播放歌曲，完整听完后将自动缓存至本地，离线亦可随时畅听",
                            fontSize = 14.sp,
                            color = LXTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 420.dp)
                        )
                    }
                }
            }
            else -> {
                val totalBytes = remember(songs) { songs.sumOf { it.sizeBytes } }
                val totalSizeFormatted = remember(totalBytes) { CacheManager.formatCacheSize(totalBytes) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部统计信息
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "本地离线歌曲",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = LXTextPrimary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "共 ${songs.size} 首歌曲 · 占用 $totalSizeFormatted",
                                fontSize = 14.sp,
                                color = LXTextSecondary
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(songs, key = { _, item -> item.cacheKey }) { index, songItem ->
                            LocalCacheSongRow(
                                index = index,
                                songItem = songItem,
                                onPlay = { onPlaySong(songItem) },
                                onDeleteClick = { songPendingDelete = songItem },
                                focusRequester = if (index == 0) contentEnterRequester else null,
                                onExitToNav = onExitToNav,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    // 二次确认删除弹窗
    songPendingDelete?.let { item ->
        DeleteCacheConfirmDialog(
            songItem = item,
            onConfirm = {
                onDeleteSong(item)
                songPendingDelete = null
            },
            onDismiss = { songPendingDelete = null }
        )
    }
}

/**
 * 单首本地缓存歌曲行卡片
 */
@Composable
private fun LocalCacheSongRow(
    index: Int,
    songItem: CacheManager.CachedSongItem,
    onPlay: () -> Unit,
    onDeleteClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onExitToNav: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val platform = remember(songItem.platformKey) {
        MusicPlatform.entries.firstOrNull { it.key == songItem.platformKey } ?: MusicPlatform.KW
    }
    val brandColor = platformBrandColor(platform)

    Card(
        modifier = modifier
            .fillMaxWidth()
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
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
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
                if (!songItem.picUrl.isNullOrBlank()) {
                    RemoteImage(
                        url = songItem.picUrl,
                        contentDescription = songItem.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        maxDimension = 256,
                        placeholder = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(brandColor.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = platformIcon(platform),
                                    contentDescription = null,
                                    tint = brandColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brandColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = platformIcon(platform),
                            contentDescription = null,
                            tint = brandColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 歌名与歌手
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songItem.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = LXOnCardDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = platformShortName(platform),
                        fontSize = 11.sp,
                        color = brandColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = songItem.singer,
                        fontSize = 13.sp,
                        color = LXOnCardDarkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // 音质与缓存文件大小
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(
                    text = songItem.quality.replace("QUALITY_", "").uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXPrimary
                )
                Text(
                    text = CacheManager.formatCacheSize(songItem.sizeBytes),
                    fontSize = 12.sp,
                    color = LXOnCardDarkSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 操作按钮①：播放本首（首项挂载 focusRequester，支持左键精准回侧栏）
            Box(
                modifier = Modifier
                    .size(40.dp)
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
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放本首",
                    tint = LXOnCardDark,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 操作按钮②：全屏播放
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .lxCircleButtonFocus()
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "播放",
                    tint = LXOnCardDark,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 操作按钮③：删除本地离线缓存
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .lxCircleButtonFocus()
                    .clickable { onDeleteClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除缓存",
                    tint = Color(0xFFEF5350),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 删除本地离线缓存二次确认对话框
 */
@Composable
private fun DeleteCacheConfirmDialog(
    songItem: CacheManager.CachedSongItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val deleteFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialFocusRequested) {
            initialFocusRequested = true
            deleteFocusRequester.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LXSurfaceDialog,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                text = "删除离线缓存？",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = LXTextPrimary
            )
        },
        text = {
            Text(
                text = "确定要删除《${songItem.name}》- ${songItem.singer} 的离线音频缓存吗？\n（大小：${CacheManager.formatCacheSize(songItem.sizeBytes)}，删除后在断网离线状态下将无法播放）",
                fontSize = 14.sp,
                color = LXTextSecondary,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.focusRequester(deleteFocusRequester),
                onClick = onConfirm
            ) {
                Text(
                    text = "删除",
                    color = Color(0xFFE53935),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "取消",
                    color = LXTextPrimary,
                    fontSize = 15.sp
                )
            }
        }
    )
}
