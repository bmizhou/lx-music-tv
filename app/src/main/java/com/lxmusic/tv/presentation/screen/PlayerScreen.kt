package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import android.widget.Toast
import com.lxmusic.tv.data.model.AudioQuality
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.data.model.PlayMode
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.component.RemoteImage
import com.lxmusic.tv.presentation.component.requestInitialFocus
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXCardDark
import com.lxmusic.tv.presentation.theme.LXPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

// 播放页遥控器手势阈值（仅在主区、未弹出控制栏/列表时生效，参考菠萝音乐）
private const val LONG_PRESS_MS = 400L      // 长按起点：按住超过此值进入快进/快退
private const val DOUBLE_TAP_MS = 450L      // 双击窗口：两次按下间隔小于此值判定为双击切歌
private const val SEEK_STEP_MS = 10000L     // 长按快进/快退每步步进 10s
private const val SEEK_REPEAT_MS = 250L     // 长按快进/快退重复间隔 250ms
private const val GESTURE_TOAST_THROTTLE_MS = 3000L  // 左右键手势提示节流：同方向 3s 内只提示一次，避免双击/连续操作刷屏

/** 左右键长按/双击状态（用普通可变持有，避免每次按键触发重组） */
private class HoldState {
    var held = false
    var longActive = false
    var lastTapTime = 0L
}

/**
 * 播放界面（2.0 重构 v3，仿 boluofan/music-tv）
 * ---------------------------------------------------------------
 * 全面对齐菠萝音乐播放页：
 * - 无顶部栏（无返回键 + 无播放状态文字），TV 遥控器返回键由系统 Back 触发 popBackStack
 * - 毛玻璃动态背景（封面放大模糊 + 深色遮罩）
 * - 主区：左侧封面 + 歌名 + 歌手，右侧滚动歌词（常驻焦点节点）
 * - 底部控制条「按需弹出」（默认隐藏，仿菠萝交互）：
 *     按「下」键弹出并聚焦进度条；焦点到按钮排后再按「下」键收起；
 *     5 秒无操作自动收起；焦点交还主区
 *     进度条 = 菠萝风格：时间显示在进度条上方，聚焦时为可拖动白色圆环 thumb
 *     按钮排 [歌单] [⏮ ⏸ ⏭] [收藏 模式]   全部 focusable，选中圆圈高亮（无永久底色）
 * - 播放列表面板改为左侧抽屉（占屏 60% 宽，从左滑入覆盖），对齐 菠萝 风格
 */
@Composable
fun PlayerScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    progress: Float,
    totalDurationMs: Long = 0L,
    currentLyric: String? = null,
    // 2.8 翻译歌词 + 是否显示开关（设置页歌词设置）
    currentLyricTranslation: String? = null,
    lyricTranslationEnabled: Boolean = true,
    // 2.8 当前实际播放音质（降级重试后的真实音质；null 表示未知/未播放）
    currentPlayQuality: AudioQuality? = null,
    playlist: List<Song> = emptyList(),
    playMode: PlayMode = PlayMode.SEQUENCE,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onPlayModeChange: (PlayMode) -> Unit = {},
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onBack: () -> Unit,
    onSongSelect: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showPlaylist by remember { mutableStateOf(false) }
    // 控制栏是否弹出（默认隐藏，按「下」键弹出）
    var showControls by remember { mutableStateOf(false) }
    // 播放模式选择弹窗（点击模式按钮弹出，取代「点击循环切换模式」）
    var showPlayModeMenu by remember { mutableStateOf(false) }
    // 自动隐藏计时器：控制栏显示后 5 秒无操作自动收起；任何按键按下都会重置
    var controlsTick by remember { mutableStateOf(0) }

    // 长按/双击手势状态（用普通可变持有，避免每次按键触发重组）
    val scope = rememberCoroutineScope()
    val leftHold = remember { HoldState() }
    val rightHold = remember { HoldState() }
    // 左右键手势 Toast 提示节流时间戳（0=左键, 1=右键；普通可变持有，避免重组）
    val gestureToastTimes = remember { longArrayOf(0L, 0L) }
    val context = LocalContext.current
    // 最新播放进度（长按快进/快退循环内读取，避免捕获陈旧值；仅在协程外读取，不触发额外重组）
    val latestProgress = remember { mutableStateOf(progress) }
    latestProgress.value = progress

    // 焦点：主区常驻焦点（控制栏隐藏时焦点落这里）+ 进度条焦点 + 播放/暂停焦点
    val mainFocusRequester = remember { FocusRequester() }
    val progressFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    // 初始焦点：落到主内容区（控制栏默认隐藏，不能聚焦隐藏组件）
    LaunchedEffect(Unit) {
        requestInitialFocus(
            focusRequester = mainFocusRequester,
            attempted = { initialFocusRequested },
            markAttempted = { initialFocusRequested = true }
        )
    }

    // 弹出后稍等组合完成，再把焦点落到播放/暂停按键（用户要求默认聚焦播放暂停）
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(160)
            playPauseFocusRequester.requestFocus()
        }
    }

    // 自动隐藏：显示后 5 秒无操作自动收起，并交还焦点给主区
    // （播放模式弹窗打开期间不打断，避免控制栏被收起导致弹窗丢失）
    LaunchedEffect(showControls, controlsTick, showPlayModeMenu) {
        if (showControls && !showPlayModeMenu) {
            delay(5000)
            showControls = false
            mainFocusRequester.requestFocus()
        }
    }

    // 播放页保持屏幕常亮
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 收起控制栏（焦点交还主区）
    val hideControls: () -> Unit = {
        if (showControls) {
            showControls = false
            mainFocusRequester.requestFocus()
        }
    }

    // 列表抽屉打开时，拦截系统返回键 = 关闭抽屉（避免直接退出播放页）
    BackHandler(enabled = showPlaylist) { showPlaylist = false }

    // 控制栏弹出时，返回键先收起控制栏（不退出播放页）
    // 注：注册在播放模式弹窗 handler 之前（后注册的 BackHandler 优先触发），
    // 弹窗打开时返回键仍先关弹窗；控制栏与列表抽屉互斥（开列表前已 hideControls）
    BackHandler(enabled = showControls) { hideControls() }

    // 播放模式弹窗打开时，返回键关闭弹窗（焦点交还控制栏播放/暂停，不退出播放页）
    BackHandler(enabled = showPlayModeMenu) {
        showPlayModeMenu = false
        playPauseFocusRequester.requestFocus()
    }

    // 全站主题由 MainActivity 根部的 LXMusicTheme 统一提供，此处不再嵌套 LXMusicTheme
    //（播放页保持自身硬编码暗色，不受浅/深主题影响）
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
        .onPreviewKeyEvent { ev ->
            // 仅处理按下/抬起，忽略其余类型事件
            if (ev.type != KeyEventType.KeyDown && ev.type != KeyEventType.KeyUp) {
                return@onPreviewKeyEvent false
            }
            // 任何按键按下都重置自动隐藏计时（用户正在操作）
            if (ev.type == KeyEventType.KeyDown && showControls) controlsTick++

            // 菜单键：唤出/收起播放列表（独立于控制栏）
            if (ev.key == Key.Menu) {
                if (ev.type == KeyEventType.KeyDown) {
                    if (showPlaylist) {
                        showPlaylist = false
                    } else {
                        hideControls()
                        showPlaylist = true
                    }
                }
                return@onPreviewKeyEvent true
            }

            // 仅主区（控制栏/列表抽屉均未弹出）响应左右键手势
            if (showControls || showPlaylist) return@onPreviewKeyEvent false

            // 左右键：主区未弹控制栏时，长按快进/快退、双击切换上一曲/下一曲（参考菠萝音乐）
            if (ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight) {
                val backward = ev.key == Key.DirectionLeft
                val st = if (backward) leftHold else rightHold
                if (ev.type == KeyEventType.KeyDown) {
                    if (!st.held) {
                        st.held = true
                        st.longActive = false
                        // 手势提示：告知用户「双击切歌 / 长按快进快退」（节流：同方向 3s 内仅提示一次，
                        // 避免双击或连续操作时 Toast 刷屏遮挡画面）
                        val nowMs = System.currentTimeMillis()
                        val slot = if (backward) 0 else 1
                        if (nowMs - gestureToastTimes[slot] > GESTURE_TOAST_THROTTLE_MS) {
                            gestureToastTimes[slot] = nowMs
                            Toast.makeText(
                                context,
                                if (backward) "左键：双击上一曲 · 长按快退" else "右键：双击下一曲 · 长按快进",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        scope.launch {
                            delay(LONG_PRESS_MS)
                            if (st.held) {
                                st.longActive = true
                                // 长按快进/快退：每 SEEK_REPEAT_MS 步进一次，直到松开
                                while (st.held) {
                                    val total = totalDurationMs
                                    if (total > 0L) {
                                        val cur = (latestProgress.value * total).toLong()
                                        val target = if (backward) {
                                            (cur - SEEK_STEP_MS).coerceAtLeast(0L)
                                        } else {
                                            (cur + SEEK_STEP_MS).coerceAtMost(total)
                                        }
                                        onSeek(target.toFloat() / total)
                                    }
                                    delay(SEEK_REPEAT_MS)
                                }
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                } else { // KeyUp
                    if (st.held) {
                        st.held = false
                        if (st.longActive) {
                            st.longActive = false
                            return@onPreviewKeyEvent true
                        }
                        // 双击判定：两次抬起间隔在窗口内则切换曲目
                        val now = System.currentTimeMillis()
                        if (st.lastTapTime != 0L && now - st.lastTapTime <= DOUBLE_TAP_MS) {
                            st.lastTapTime = 0L
                            if (backward) onPrevious() else onNext()
                        } else {
                            st.lastTapTime = now
                        }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
            }

            // 主区按「下」键弹出控制栏
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                showControls = true
                return@onPreviewKeyEvent true
            }
            false
        }
        ) {
            // 毛玻璃动态背景
            GlassBackground(currentSong)

            // 主内容铺满全屏（控制栏改为顶层覆盖，弹出时不挤压封面/歌词）
            PlayerMainContent(
                currentSong = currentSong,
                currentLyric = currentLyric,
                // 2.8 翻译歌词透传（开关开启且有翻译时）
                currentLyricTranslation = if (lyricTranslationEnabled) currentLyricTranslation else null,
                // 2.8 实际播放音质
                currentPlayQuality = currentPlayQuality,
                progress = progress,
                totalDurationMs = totalDurationMs,
                focusRequester = mainFocusRequester,
                modifier = Modifier.fillMaxSize()
            )

            // 底部控制条（按需弹出，默认隐藏；顶层覆盖，背景完全透明不遮挡内容）
            if (showControls) {
                PlayerBottomBar(
                    progress = progress,
                    totalDurationMs = totalDurationMs,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    isFavorite = isFavorite,
                    playMode = playMode,
                    onSeek = onSeek,
                    // 打开抽屉前先收起控制栏，避免自动隐藏计时器把焦点抢回被遮住的主区
                    onShowPlaylist = {
                        hideControls()
                        showPlaylist = true
                    },
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onToggleFavorite = onToggleFavorite,
                    onPlayModeChange = onPlayModeChange,
                    onPlayModeMenuToggle = { showPlayModeMenu = !showPlayModeMenu },
                    onRequestHide = hideControls,
                    progressFocusRequester = progressFocusRequester,
                    playPauseFocusRequester = playPauseFocusRequester,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // 左侧抽屉式播放列表面板
            if (showPlaylist) {
                PlaylistDrawer(
                    playlist = playlist,
                    currentSongId = currentSong?.id,
                    onSongSelect = { song ->
                        onSongSelect(song)
                        showPlaylist = false
                        // 选歌后焦点交还主区（控制栏保持隐藏）
                        mainFocusRequester.requestFocus()
                    },
                    onDismiss = { showPlaylist = false }
                )
            }

            // 播放模式选择弹窗（点击模式按钮弹出，取代点击循环切换 + Toast）
            if (showPlayModeMenu) {
                PlayModePopup(
                    current = playMode,
                    onSelect = { mode ->
                        onPlayModeChange(mode)
                        showPlayModeMenu = false
                        progressFocusRequester.requestFocus()
                    },
                    onDismiss = {
                        showPlayModeMenu = false
                        progressFocusRequester.requestFocus()
                    }
                )
            }
        }
}

/**
 * 毛玻璃动态背景（封面图放大 + 模糊 + 深色遮罩渐变）
 */
@Composable
private fun GlassBackground(currentSong: Song?) {
    Box(modifier = Modifier.fillMaxSize()) {
        val picUrl = currentSong?.picUrl
        if (!picUrl.isNullOrBlank()) {
            RemoteImage(
                url = picUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.2f)
                    .blur(radius = 40.dp),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xE61A1A2E),
                            Color(0xCC1A1A2E),
                            Color(0xF21A1A2E)
                        )
                    )
                )
        )
    }
}

/**
 * 中间左右布局：左侧封面 + 歌名歌手 / 右侧歌词
 * 整个主区作为常驻焦点节点（控制栏隐藏时焦点落这里，按「下」键弹出控制栏）
 */
@Composable
private fun PlayerMainContent(
    currentSong: Song?,
    currentLyric: String?,
    // 2.8 翻译歌词（开关已过滤，null 表示不显示）
    currentLyricTranslation: String? = null,
    // 2.8 当前实际播放音质（真实音质，非设置偏好）
    currentPlayQuality: AudioQuality? = null,
    progress: Float,
    totalDurationMs: Long,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .focusRequester(focusRequester)
            .focusable(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：封面 + 歌名 + 歌手（垂直居中堆叠）
        Column(
            modifier = Modifier.weight(0.42f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GlassAlbumCover(
                currentSong = currentSong,
                modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(1f)
            )
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = currentSong?.name ?: "未在播放",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = currentSong?.singer ?: "选择一首歌开始播放",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            // 2.8 歌手下方显示当前真实播放音质（ExoPlayer 解析；解析前/未知格式显示「未知」）
            if (currentSong != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = currentPlayQuality?.let {
                        when (it) {
                            AudioQuality.QUALITY_128K -> "标准 128K"
                            AudioQuality.QUALITY_320K -> "高品质 320K"
                            AudioQuality.FLAC -> "无损 flac"
                            AudioQuality.FLAC_24BIT -> "Hi-Res flac24bit"
                        }
                    } ?: "未知",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f),
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 右侧：歌词（2.8 翻译歌词：开关已在 PlayerScreen 层过滤，null 表示不显示）
        LyricPanel(
            currentSong = currentSong,
            lyric = currentLyric,
            translation = currentLyricTranslation,
            currentTimeMs = (progress * totalDurationMs).toLong(),
            modifier = Modifier.weight(0.58f).fillMaxHeight().padding(vertical = 24.dp)
        )
    }
}

/**
 * 底部控制条（按需弹出）：进度条 + 时间 + 按钮排
 * 对齐菠萝：上进度条，下 [歌单] [⏮ ⏸ ⏭] [收藏 模式]
 * 焦点到按钮排后再按「下」键 → 收起控制栏（onRequestHide，焦点交还主区）
 */
@Composable
private fun PlayerBottomBar(
    progress: Float,
    totalDurationMs: Long,
    currentSong: Song?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    playMode: PlayMode,
    onSeek: (Float) -> Unit,
    onShowPlaylist: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    onPlayModeChange: (PlayMode) -> Unit,
    onPlayModeMenuToggle: () -> Unit,
    onRequestHide: () -> Unit,
    progressFocusRequester: FocusRequester,
    playPauseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    // 播放模式图标（按钮展示当前模式，点击弹出选择弹窗）
    val modeIcon = when (playMode) {
        PlayMode.SEQUENCE -> Icons.Default.Repeat
        PlayMode.RANDOM -> Icons.Default.Shuffle
        PlayMode.LOOP_SINGLE -> Icons.Default.RepeatOne
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 顶层覆盖 + 完全透明背景：不遮挡封面与歌词（仿菠萝 layoutBottomControls 浮层）
            .padding(horizontal = 56.dp)
            .padding(top = 32.dp, bottom = 24.dp)
    ) {
        // ===== 进度条（参考菠萝音乐：时间在上方 + 聚焦圆环 thumb）=====
        PlayerProgressBar(
            progress = progress,
            totalDurationMs = totalDurationMs,
            onSeek = onSeek,
            onRequestHide = onRequestHide,
            focusRequester = progressFocusRequester
        )

        Spacer(modifier = Modifier.height(22.dp))

        // ===== 按钮排（焦点在按钮排时按「下」键收起控制栏）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { ev ->
                    // 按钮排是最底一行：再按「下」键收起控制栏（事件在子节点导航前拦截）
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                        onRequestHide()
                        true
                    } else {
                        false
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: 歌单
            ControlCircleButton(
                onClick = onShowPlaylist,
                contentDescription = "播放列表"
            ) { _ ->
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // CENTER: 上一曲 / 播放暂停 / 下一曲
            ControlCircleButton(
                onClick = onPrevious,
                contentDescription = "上一曲"
            ) { _ ->
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.width(28.dp))

            ControlCircleButton(
                onClick = onPlayPause,
                contentDescription = if (isPlaying) "暂停" else "播放",
                size = 68.dp,
                iconSize = 40.dp,
                focusRequester = playPauseFocusRequester
            ) { _ ->
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.width(28.dp))

            ControlCircleButton(
                onClick = onNext,
                contentDescription = "下一曲"
            ) { _ ->
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // RIGHT: 收藏 + 播放模式（聚焦时统一白图标，非聚焦时按状态着色）
            ControlCircleButton(
                onClick = { onToggleFavorite?.invoke() },
                contentDescription = if (isFavorite) "取消收藏" else "收藏"
            ) { focused ->
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (focused) Color.White else if (isFavorite) LXPrimary else Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            ControlCircleButton(
                onClick = onPlayModeMenuToggle,
                contentDescription = "播放模式：${playMode.displayName}，点击选择"
            ) { focused ->
                Icon(
                    imageVector = modeIcon,
                    contentDescription = null,
                    tint = if (focused) Color.White
                    else if (playMode == PlayMode.SEQUENCE) Color.White.copy(alpha = 0.75f)
                    else LXPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * 播放页进度条（参考 boluofan/music-tv 菠萝音乐拖动样式）：
 * - thumb 上方浮一个白色圆角气泡，显示「当前时间/总时长」（如 0:48/4:18），
 *   水平位置跟随 thumb 移动，左右贴边时自动内缩避免溢出进度条
 * - 轨道：4dp 半透明白细条 + 白色进度填充
 * - thumb：未聚焦 = 小白点(5dp)；聚焦 = 白色描边圆 + 中心红点 + 红色光晕
 * - 左右键 ±10s / 媒体键 ±30s / 触摸点击定位；「上」键收起控制栏
 * - 主题色统一为 LXPrimary 红（与全应用焦点系统一致；菠萝原图是蓝，
 *   我们保留品牌色红，保证进度条/焦点红/按钮红三处统一）
 */
@Composable
private fun PlayerProgressBar(
    progress: Float,
    totalDurationMs: Long,
    onSeek: (Float) -> Unit,
    onRequestHide: () -> Unit,
    focusRequester: FocusRequester
) {
    val stepMs = max(10000L, totalDurationMs.coerceAtLeast(1L) / 30)
    val isSeekable = totalDurationMs > 0L
    var isFocused by remember { mutableStateOf(false) }
    // 进度条实际宽度（Canvas onSizeChanged 回调写入），用于气泡水平定位与贴边内缩
    var trackWidthPx by remember { mutableStateOf(0) }
    val currentMs = (progress * totalDurationMs).toLong()

    // 几何参数（统一管理）
    val bubbleWidthDp = 82.dp      // 白色气泡宽度（容纳 "mm:ss / mm:ss"）
    val bubbleHeightDp = 28.dp     // 气泡高度
    val bubbleGapDp = 8.dp         // 气泡底到 thumb 中心的间距
    val thumbDiameterDp = 22.dp    // thumb 外径（聚焦时）
    val trackHeight = 4.dp

    // Dp→Px 转换需在 Density 上下文进行（Composable 体内无 DrawScope，必须用 LocalDensity）
    val density = LocalDensity.current
    val thumbDiameterPx = with(density) { thumbDiameterDp.toPx() }
    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }

    // 气泡水平位置：跟随 thumb 中心，贴边时内缩到 [0, trackWidthPx - bubbleWidthPx]
    val p = progress.coerceIn(0f, 1f)
    val thumbCenterX = if (trackWidthPx > 0) {
        val thumbR = thumbDiameterPx / 2f
        (trackWidthPx * p).coerceIn(thumbR, trackWidthPx - thumbR)
    } else 0f
    val bubbleOffsetX = if (trackWidthPx > 0) {
        (thumbCenterX - bubbleWidthPx / 2f)
            .coerceIn(0f, (trackWidthPx - bubbleWidthPx).coerceAtLeast(0f))
    } else 0f

    // 整体纵向布局：气泡(上) → 间距 → 进度条 Canvas → 间距 → 底部时间行(下)
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // ===== 气泡行：固定高度 Box 承载可水平偏移的气泡 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bubbleHeightDp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(bubbleOffsetX.toInt(), 0) }
                    .width(bubbleWidthDp)
                    .height(bubbleHeightDp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "${formatTimeMs(currentMs)}/${formatTimeMs(totalDurationMs)}",
                        fontSize = 12.sp,
                        color = Color(0xFF1A1A2E),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(bubbleGapDp))

        // ===== 进度条 Canvas：高度 = thumb 直径 =====
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbDiameterDp)
                .onSizeChanged { trackWidthPx = it.width }
                .onFocusChanged { isFocused = it.isFocused }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown || !isFocused) return@onPreviewKeyEvent false
                    val cur = (progress * totalDurationMs).toLong()
                    when (ev.key) {
                        Key.DirectionRight, Key.MediaFastForward -> {
                            if (isSeekable) onSeek(
                                (cur + stepMs).coerceAtMost(totalDurationMs).toFloat() / totalDurationMs.toFloat()
                            )
                            true
                        }
                        Key.DirectionLeft, Key.MediaRewind -> {
                            if (isSeekable) onSeek(
                                (cur - stepMs).coerceAtLeast(0L).toFloat() / totalDurationMs.toFloat()
                            )
                            true
                        }
                        // 「上」键：收起控制栏
                        Key.DirectionUp -> {
                            onRequestHide()
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (size.width > 0 && isSeekable) {
                            onSeek((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                }
        ) {
            val trackY = size.height / 2f
            val trackHalf = trackHeight.toPx() / 2f

            // 背景轨道（半透明白细条）
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(0f, trackY - trackHalf),
                size = Size(size.width, trackHeight.toPx()),
                cornerRadius = CornerRadius(trackHalf, trackHalf)
            )
            // 进度填充（白色）
            if (p > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(0f, trackY - trackHalf),
                    size = Size(size.width * p, trackHeight.toPx()),
                    cornerRadius = CornerRadius(trackHalf, trackHalf)
                )
            }

            // thumb：未聚焦小白点 / 聚焦 白色描边圆 + 中心红点(15dp) + 红色光晕（仿菠萝）
            val thumbR = if (isFocused) thumbDiameterDp.toPx() / 2f else 5.dp.toPx()
            val cx = (size.width * p).coerceIn(thumbR, size.width - thumbR)
            if (isFocused) {
                // 红色光晕（半透明 LXPrimary 大圆，最外层）
                drawCircle(
                    color = LXPrimary.copy(alpha = 0.28f),
                    radius = thumbR * 1.7f,
                    center = Offset(cx, trackY)
                )
                // 白色描边圆环
                drawCircle(
                    color = Color.White,
                    radius = thumbR,
                    center = Offset(cx, trackY),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                // 中心红点（主题色，直径 15dp）
                drawCircle(
                    color = LXPrimary,
                    radius = 15.dp.toPx() / 2f,
                    center = Offset(cx, trackY)
                )
            } else {
                drawCircle(
                    color = Color.White,
                    radius = thumbR,
                    center = Offset(cx, trackY)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ===== 底部时间行：左=当前播放时间，右=总时长 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTimeMs(currentMs),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = formatTimeMs(totalDurationMs),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * 底部控制条圆形按钮（仿菠萝 boluofan/music-tv btn_focused_circle）：
 * - 聚焦时 = 内部半透明红实心圆 + 最外层 15 像素黑色半透明圆环(FocusBorder) + 白色图标
 * - 单一焦点来源：clickable 自带 focusable + 共享 interactionSource
 *   （去掉手动 focusable，避免 clickable 内置 focusable 与显式 focusable 双焦点节点
 *    导致 Enter 键第一次被用于"焦点稳定"，第二次才触发 onClick 的"点两次"问题）
 * - content 接收 isFocused 让调用方根据聚焦态决定图标颜色（聚焦统一白）
 */
@Composable
private fun ControlCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    tintIcon: Color = Color.White,
    focusRequester: FocusRequester? = null,
    content: @Composable (isFocused: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(size)
            // 注意：这里不要 .clip(CircleShape)，否则会把超出圆边的白环硬裁剪出锯齿边；
            // 绘制全部落在 Box 尺寸内，由 Canvas 默认抗锯齿处理即可
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isFocused) {
            // 聚焦态：半透明黑色实心圆 + 最外层 15 像素黑色半透明圆环 FocusBorder（Canvas 按像素绘制，默认抗锯齿）
            Canvas(modifier = Modifier.matchParentSize()) {
                // 注意：this.size 是 Canvas 的 DrawScope.size（几何 Size），
                // 不能写成 size（会被函数的 size: Dp 参数遮蔽，导致 minDimension 解析失败）
                val r = this.size.minDimension / 2f
                val ring = 15f
                val half = ring / 2f
                // 半透明黑色实心圆：半径扩到整圆 r，使中间填充与外环(FocusBorder)边缘贴合，
                // 消除中间色与边框之间的空带（点：圆形按钮边框与中间色的小间隔）
                drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = r)
                // 外环：居中于 r - half，strokes 宽度 15px → 跨度 [r-15, r]，完整落在 Box 内。
                // 颜色统一为黑色半透明 FocusBorder（仿播放页播放列表暗色面板，与全站焦点边框一致）
                drawCircle(
                    color = FocusBorder,
                    radius = r - half,
                    style = Stroke(width = ring)
                )
            }
        }
        Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            content(isFocused)
        }
    }
}

/**
 * 播放模式选择弹窗（点击底部「模式」按钮弹出，取代「点击循环切换 + Toast」）
 * - 右下角浮层，抬升到控制栏上方，半透明遮罩（仅供视觉，点击由返回键/选中关闭）
 * - 三个选项：顺序播放 / 随机播放 / 单曲循环，当前模式高亮 + 勾选
 * - 打开时焦点自动落到当前模式项；方向键在三项间导航，确认即生效并关闭
 */
@Composable
private fun PlayModePopup(
    current: PlayMode,
    onSelect: (PlayMode) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = listOf(
        PlayMode.SEQUENCE to "顺序播放",
        PlayMode.RANDOM to "随机播放",
        PlayMode.LOOP_SINGLE to "单曲循环"
    )
    // 每项独立焦点，弹窗内用显式上下键导航，杜绝方向键焦点逃逸到弹窗后的控制栏
    val requesters = remember { List(modes.size) { FocusRequester() } }
    var focusedIndex by remember {
        mutableStateOf(modes.indexOfFirst { it.first == current }.coerceAtLeast(0))
    }
    LaunchedEffect(Unit) {
        delay(120)
        requesters[focusedIndex].requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 半透明遮罩：仅视觉提示 + 触摸点击关闭（不抢焦点，避免干扰弹窗内导航）
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.BottomEnd
    ) {
        // 菜单卡片：锚定右下角，抬升到控制栏上方
        Column(
            modifier = Modifier
                .padding(end = 56.dp, bottom = 104.dp)
                .background(Color(0xE61A1A2E), shape = RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(vertical = 8.dp)
                // 弹窗内方向键完全接管：上/下在三项间移动，左/右消费（不逃逸）
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionUp -> {
                            focusedIndex = (focusedIndex - 1).coerceAtLeast(0)
                            requesters[focusedIndex].requestFocus()
                            true
                        }
                        Key.DirectionDown -> {
                            focusedIndex = (focusedIndex + 1).coerceAtMost(modes.size - 1)
                            requesters[focusedIndex].requestFocus()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
        ) {
            modes.forEachIndexed { idx, (mode, label) ->
                PlayModeItem(
                    icon = when (mode) {
                        PlayMode.SEQUENCE -> Icons.Default.Repeat
                        PlayMode.RANDOM -> Icons.Default.Shuffle
                        PlayMode.LOOP_SINGLE -> Icons.Default.RepeatOne
                    },
                    label = label,
                    selected = mode == current,
                    focusRequester = requesters[idx],
                    onClick = { onSelect(mode) }
                )
            }
        }
    }
}

/**
 * 播放模式弹窗的单项（图标 + 名称 + 当前勾选）
 * 单一焦点来源：clickable 自带 focusable + 共享 interactionSource（与 ControlCircleButton 同款，避免双焦点）
 */
@Composable
private fun PlayModeItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .width(200.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(
                when {
                    isFocused -> Color.White.copy(alpha = 0.14f)
                    selected -> LXPrimary.copy(alpha = 0.18f)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) LXPrimary else Color.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = if (selected) LXPrimary else Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = LXPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 左侧抽屉式播放列表面板（仿菠萝：从左滑入，占屏 42% 宽）
 */
@Composable
private fun PlaylistDrawer(
    playlist: List<Song>,
    currentSongId: String?,
    onSongSelect: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    // 正在播放的歌曲在列表中的位置（不在列表/未播放则 -1，兜底第一项）
    val currentIndex = playlist.indexOfFirst { it.id == currentSongId }

    // 抽屉打开时：列表定位到正在播放的那首歌（而非第一首），焦点也直接落在它上面
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    val currentSongFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        // 双保险：滚动到当前歌曲（initialFirstVisibleItemIndex 首次组合已生效，此处对长列表再兜底一次）
        listState.scrollToItem(currentIndex.coerceAtLeast(0))
        currentSongFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                // 返回键 1 次直接关闭播放列表：TV 上焦点停在列表项时，默认返回键会先被焦点系统
                // 当作「取消选中」消耗，导致要按 2 次才能关——这里在预览阶段拦截消费
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            // 仿菠萝 bg_drawer_dim：遮罩几乎全透明，露出毛玻璃背景
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.42f)
                // 面板几乎全透明（菠萝抽屉无背景色），列表行自身背景/文字保证可读
                .background(Color(0x34000000))
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .clickable(enabled = false, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {})
        ) {
            // 标题
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "播放列表",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${playlist.size} 首",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (playlist.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "播放列表为空", fontSize = 16.sp, color = Color.Gray)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(playlist, key = { _, song -> song.id }) { index, song ->
                        val isCurrent = song.id == currentSongId
                        DrawerSongRow(
                            song = song,
                            index = index,
                            isCurrent = isCurrent,
                            focusRequester = if (index == currentIndex) currentSongFocus else null,
                            onClick = { onSongSelect(song) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 抽屉里的歌曲行（仿菠萝：序号 + 小封面 + 歌名/歌手，播放中红色 + Equalizer 图标）
 */
@Composable
private fun DrawerSongRow(
    song: Song,
    index: Int = 0,
    isCurrent: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)  // 仿菠萝 item_drawer_song.xml：行高 64dp
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    // 遥控焦点选中：主题色浅透底（与设置页选中卡片一致，非纯色）
                    isFocused -> LXPrimary.copy(alpha = 0.40f)
                    // 未选中：比全局 LXCardDark 更实的半透明黑（播放页恒暗色；卡片太透会看不清与背景的区分）
                    else -> Color(0xA51A1A1A)
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // clickable 自带 focusable，与 interactionSource 共享状态（去掉手动 focusable，修复"点 2 次"）
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号（便于核对播放列表歌曲数量，如酷狗整列表补拉是否完整）
        Text(
            text = "${index + 1}",
            fontSize = 14.sp,
            color = if (isCurrent) LXPrimary else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.width(36.dp)
        )

        // 左侧：44dp 封面（播放中叠半透明黑 + 居中 Equalizer 三柱脉冲动画）
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(platformBrandColor(song.platform).copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            if (!song.picUrl.isNullOrBlank()) {
                RemoteImage(
                    url = song.picUrl,
                    contentDescription = song.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    maxDimension = 128,
                    placeholder = {
                        Icon(
                            imageVector = platformIcon(song.platform),
                            contentDescription = null,
                            tint = platformBrandColor(song.platform),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            } else {
                Icon(
                    imageVector = platformIcon(song.platform),
                    contentDescription = null,
                    tint = platformBrandColor(song.platform),
                    modifier = Modifier.size(18.dp)
                )
            }
            // 播放中：封面叠 #66000000 + 居中 Equalizer 三柱脉冲动画（仿菠萝 anim_equalizer）
            if (isCurrent) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000)))
                EqualizerBars(
                    modifier = Modifier.height(16.dp),
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // 中：歌名 + 歌手（仿菠萝：16sp bold + 12sp）
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    // 正在播放：主题色字区分（未聚焦也突出，随主题色切换）
                    isCurrent -> LXPrimary
                    // 其余（聚焦/普通）：白字（播放页恒暗色，保持可读）
                    else -> Color.White
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.singer,
                fontSize = 12.sp,
                color = when {
                    // 焦点选中：白色 85%（主题色底上可读）
                    isFocused -> Color.White.copy(alpha = 0.85f)
                    // 普通 & 正在播放：白色 70%
                    else -> Color(0xB3FFFFFF)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右：chevron 箭头（聚焦时显示，红色提示可点击）
        AnimatedVisibility(
            visible = isFocused,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = LXPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Equalizer 三柱脉冲动画（仿菠萝 anim_equalizer）
 * 三根白色竖条不同步高度脉冲，模拟音频均衡器
 */
@Composable
fun EqualizerBars(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    barWidth: androidx.compose.ui.unit.Dp = 3.dp,
    gap: androidx.compose.ui.unit.Dp = 2.dp
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val p1 by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse),
        label = "p1"
    )
    val p2 by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, delayMillis = 150, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse),
        label = "p2"
    )
    val p3 by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, delayMillis = 300, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse),
        label = "p3"
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        EqualizerBar(barWidth, p1, color)
        EqualizerBar(barWidth, p2, color)
        EqualizerBar(barWidth, p3, color)
    }
}

@Composable
fun EqualizerBar(
    width: androidx.compose.ui.unit.Dp,
    heightFraction: Float,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(width = width, height = (14.dp.value * heightFraction).coerceAtLeast(4f).dp)
            .background(color, RoundedCornerShape(1.dp))
    )
}

/**
 * 大方形圆角封面（无旋转动画）
 */
@Composable
private fun GlassAlbumCover(
    currentSong: Song?,
    modifier: Modifier = Modifier
) {
    val platform = currentSong?.platform ?: MusicPlatform.KW
    val brandColor = platformBrandColor(platform)
    val startChar = currentSong?.name?.take(1) ?: "♪"
    val picUrl = currentSong?.picUrl

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        brandColor.copy(alpha = 0.75f),
                        brandColor.copy(alpha = 0.45f),
                        Color(0xFF1A1A2E)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (currentSong == null) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "专辑封面",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(96.dp)
            )
        } else if (!picUrl.isNullOrBlank()) {
            RemoteImage(
                url = picUrl,
                contentDescription = currentSong.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = {
                    Text(
                        text = startChar,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            )
        } else {
            Text(
                text = startChar,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
        Icon(
            imageVector = platformIcon(platform),
            contentDescription = platformShortName(platform),
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .size(28.dp)
                .align(Alignment.TopEnd)
                .padding(10.dp)
        )
    }
}

/**
 * 右侧歌词面板
 */
@Composable
private fun LyricPanel(
    currentSong: Song?,
    lyric: String?,
    // 2.8 翻译歌词（设置页开关控制是否传入；null 表示无翻译或已关闭）
    translation: String? = null,
    currentTimeMs: Long,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val lyrics = remember(lyric, translation) { parseLrcWithTranslation(lyric, translation) }
        if (lyrics.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (currentSong == null) "暂无播放" else "暂无歌词",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            ScrollingLyrics(
                lyrics = lyrics,
                currentTimeMs = currentTimeMs,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 滚动歌词（瞬时跳转，避免动画叠加）
 */
@Composable
private fun ScrollingLyrics(
    lyrics: List<LyricLine>,
    currentTimeMs: Long,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(lyrics, currentTimeMs) {
        lyrics.indexOfLast { it.timeMs <= currentTimeMs }
    }
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.scrollToItem(index = (currentIndex - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // key 用索引：歌词中可能存在多条同时间戳的行（如多行 [00:00.00]），
        // 用 timeMs 做 key 会重复抛 IllegalArgumentException（v168 日语歌实测闪退）
        itemsIndexed(lyrics, key = { index, _ -> index }) { _, line ->
            val isCurrent = line.timeMs == lyrics.getOrNull(currentIndex)?.timeMs
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = line.text.ifBlank { "♪" },
                    fontSize = if (isCurrent) 22.sp else 16.sp,
                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.45f),
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // 2.8 翻译歌词（当前行高亮时同步加粗加亮，其余行次级白）
                line.translation?.takeIf { it.isNotBlank() }?.let { trans ->
                    Text(
                        text = trans,
                        fontSize = if (isCurrent) 14.sp else 12.sp,
                        color = if (isCurrent) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.32f),
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** 歌词行（2.8 增加 translation：翻译歌词，设置页可开关） */
data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

/** 解析 LRC 歌词 */
fun parseLrc(lrc: String?): List<LyricLine> {
    if (lrc.isNullOrBlank()) return emptyList()
    val lines = mutableListOf<LyricLine>()
    val regex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]")
    lrc.lineSequence().forEach { line ->
        if (line.isBlank()) return@forEach
        val matches = regex.findAll(line)
        if (!matches.any()) return@forEach
        val text = line.replace(regex, "").trim()
        for (m in matches) {
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val fracStr = m.groupValues[3]
            val frac = when (fracStr.length) {
                3 -> fracStr.toLongOrNull() ?: 0L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100
                else -> 0L
            }
            lines.add(LyricLine(min * 60000 + sec * 1000 + frac, text))
        }
    }
    return lines.sortedBy { it.timeMs }
}

/**
 * 2.8 解析带翻译的歌词：主歌词 + 翻译歌词按时间戳合并
 * @param translationLrc 翻译歌词（LRC）；为空时等价于 [parseLrc]
 * 注意：网易云等平台原文与翻译时间戳常有毫秒级差异（如 [00:12.34] vs [00:12.36]），
 * 不能精确相等匹配——对每个原文行找「时间差 ≤ 600ms 的最近翻译行」，否则当前行翻译会漏配。
 */
fun parseLrcWithTranslation(lrc: String?, translationLrc: String?): List<LyricLine> {
    if (translationLrc.isNullOrBlank()) return parseLrc(lrc)
    val mainLines = parseLrc(lrc)
    val translationLines = parseLrc(translationLrc)
    if (mainLines.isEmpty() || translationLines.isEmpty()) return mainLines
    // 容差窗口 ±600ms（歌词行级毫秒差异的常见范围）
    val tolerance = 600L
    return mainLines.map { line ->
        val trans = translationLines
            .filter { it.timeMs in (line.timeMs - tolerance)..(line.timeMs + tolerance) }
            .minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
            ?.text
        line.copy(translation = trans)
    }
}

/** 格式化时间（毫秒） */
private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}