package com.lxmusic.tv.presentation.lyrics

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 歌词显示组件
 * 用于在TV端显示同步歌词
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    onLineClick: ((Int) -> Unit)? = null
) {
    val parser = LyricsParser()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // 计算当前播放行索引
    val currentLineIndex = remember(currentPositionMs, lyrics) {
        parser.findCurrentLineIndex(lyrics, currentPositionMs)
    }
    
    // 自动滚动到当前行
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(
                    index = currentLineIndex,
                    scrollOffset = -200 // 偏移量，使当前行居中
                )
            }
        }
    }
    
    // 如果没有歌词，显示提示信息
    if (!lyrics.hasLyrics) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无歌词",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    // 歌词列表
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isCurrentLine = index == currentLineIndex
            val isPastLine = index < currentLineIndex
            
            LyricLineItem(
                line = line,
                isCurrentLine = isCurrentLine,
                isPastLine = isPastLine,
                onClick = {
                    onLineClick?.invoke(index)
                }
            )
            
            // 行间距
            if (index < lyrics.lines.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 单行歌词组件
 */
@Composable
fun LyricLineItem(
    line: LyricLine,
    isCurrentLine: Boolean,
    isPastLine: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    // 动画效果
    val scale by animateFloatAsState(
        targetValue = if (isCurrentLine) 1.1f else 1.0f,
        animationSpec = tween(300),
        label = "lyric_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = when {
            isCurrentLine -> 1.0f
            isPastLine -> 0.5f
            else -> 0.7f
        },
        animationSpec = tween(300),
        label = "lyric_alpha"
    )
    
    val fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal
    
    val textColor = when {
        isCurrentLine -> MaterialTheme.colorScheme.primary
        isPastLine -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .focusable()
            .onFocusChanged { /* 处理焦点变化 */ }
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 主歌词
        Text(
            text = line.content,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = fontWeight,
                fontSize = if (isCurrentLine) 24.sp else 18.sp
            ),
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        
        // 翻译歌词
        if (line.hasTranslation && isCurrentLine) {
            Text(
                text = line.translation ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 歌词进度条组件
 */
@Composable
fun LyricsProgressBar(
    lyrics: Lyrics,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    onSeek: ((Long) -> Unit)? = null
) {
    val parser = LyricsParser()
    
    if (!lyrics.hasLyrics) return
    
    // 获取最后一行的时间作为总时长
    val totalDurationMs = lyrics.lines.lastOrNull()?.timeMs ?: 0L
    
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 时间显示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = LyricsParser.formatTime(currentPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = LyricsParser.formatTime(totalDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // 进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .padding(horizontal = 16.dp)
        ) {
            // 这里可以放置进度条组件
        }
        
        // 歌词时间标记
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            lyrics.lines.take(5).forEach { line ->
                Text(
                    text = LyricsParser.formatTime(line.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 歌词搜索组件
 */
@Composable
fun LyricsSearchView(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 这里可以放置搜索框组件
    // 用于搜索歌词
}