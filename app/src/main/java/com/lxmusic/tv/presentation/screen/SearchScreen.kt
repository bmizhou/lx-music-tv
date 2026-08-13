package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.lxCircleButtonFocus
import com.lxmusic.tv.presentation.component.lxFocusBorder
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.data.model.PlatformNameConfig
import com.lxmusic.tv.data.model.Playlist
import com.lxmusic.tv.data.model.SearchType
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.presentation.component.RemoteImage
import com.lxmusic.tv.presentation.component.lxSelectorFocus
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXAccentGradientBrush
import com.lxmusic.tv.presentation.theme.LXCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDarkSecondary
import com.lxmusic.tv.presentation.theme.LXSurfaceMain
import com.lxmusic.tv.presentation.theme.LXSurfaceCard
import com.lxmusic.tv.presentation.theme.LXSurfaceDialog
import com.lxmusic.tv.presentation.theme.LXSurfaceVariant
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXTextSecondary
import com.lxmusic.tv.presentation.theme.LXBorder
import com.lxmusic.tv.presentation.theme.LXPrimary

/**
 * 各平台品牌色
 */
fun platformBrandColor(platform: MusicPlatform): Color {
    return when (platform) {
        MusicPlatform.KW -> Color(0xFFFF7E29) // 酷我 - 橙色
        MusicPlatform.KG -> Color(0xFF2CA3DC) // 酷狗 - 天蓝
        MusicPlatform.TX -> Color(0xFF31C27C) // QQ音乐 - 绿色
        MusicPlatform.WY -> Color(0xFFE60026) // 网易云 - 红色
        MusicPlatform.MG -> Color(0xFF3B8CFF) // 咪咕 - 蓝色
        MusicPlatform.LOCAL -> Color(0xFF9E9E9E)
    }
}

/**
 * 各平台默认图标（本地矢量图标近似品牌）
 */
fun platformIcon(platform: MusicPlatform): ImageVector {
    return when (platform) {
        MusicPlatform.KW -> Icons.Default.Star
        MusicPlatform.KG -> Icons.Default.Headphones
        MusicPlatform.TX -> Icons.Default.MusicNote
        MusicPlatform.WY -> Icons.Default.Cloud
        MusicPlatform.MG -> Icons.Default.PlayCircle
        MusicPlatform.LOCAL -> Icons.Default.Folder
    }
}

/**
 * 各平台名称（短名，走 PlatformNameConfig：开启洛雪风格后为 小秋/小芸/…）
 */
fun platformShortName(platform: MusicPlatform): String {
    return PlatformNameConfig.shortName(platform)
}

/**
 * 搜索界面
 * 支持遥控器操作的音乐搜索页面
 *
 * 三栏布局：左键盘 + 中联想 + 右热门。
 * 搜索状态（关键词/平台/类型）由 ViewModel 持有，
 * 确认搜索后导航到独立的搜索结果页（SearchResultScreen）展示最终结果。
 * 搜索类型支持「歌曲 / 歌单」
 */
/**
 * 搜索页热门搜索的接口兜底关键词（接口偶发失败时仍能呈现内容）
 */
private val HOT_FALLBACK_KEYWORDS = listOf(
    "周杰伦", "林俊杰", "陈奕迅", "薛之谦", "邓紫棋",
    "晴天", "七里香", "演员", "泡沫", "海阔天空"
)

/**
 * 安全请求焦点：requester 尚未 attach（如懒加载列表首项未组合）时静默失败，
 * 避免 requestFocus 抛 IllegalStateException 导致崩溃
 */
private fun tryRequestFocus(requester: FocusRequester): Boolean {
    return try {
        requester.requestFocus()
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 支持遥控器操作的音乐搜索页面
 *
 * 三栏布局：左键盘 + 中联想 + 右热门。
 * 搜索状态（关键词/平台/类型）由 ViewModel 持有，
 * 确认搜索后导航到独立的搜索结果页（SearchResultScreen）展示最终结果。
 * 搜索类型支持「歌曲 / 歌单」
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    searchQuery: String = "",
    searchPlatform: MusicPlatform = MusicPlatform.KW,
    searchType: SearchType = SearchType.SONG,
    hotKeywords: List<String> = emptyList(),
    suggestions: List<String> = emptyList(),
    hotSongs: List<Song> = emptyList(),
    // 搜索历史（输入为空时中间列展示，最多 10 条，最近在前）
    searchHistory: List<String> = emptyList(),
    onSearchQueryChange: (String) -> Unit,
    onSearch: (String, MusicPlatform) -> Unit,
    onSearchPlaylist: (String, MusicPlatform) -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onClearSearchHistory: () -> Unit = {},
    // 导航栏右键进入搜索页时聚焦内容首项（2.6 搜索内嵌为 tab）
    contentEnterRequester: FocusRequester? = null,
    // 2.8 HTTP 服务器地址（扫码推送弹窗显示 /search 地址 + 二维码；null=未开启服务器）
    serverUrl: String? = null,
    // 2.8 HTTP 未开启时点二维码 → 询问是否启用（调用方负责启动服务器，如 viewModel.startServer）
    onEnableServer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 2.8 扫码推送弹窗开关
    var showQrDialog by remember { mutableStateOf(false) }
    // 2.8 HTTP 未启用询问弹窗开关
    var showEnableServerDialog by remember { mutableStateOf(false) }
    // 2.8 是否在等待服务器启动后自动弹二维码（用户在询问弹窗点了「开启」）
    var pendingQrAfterServerStart by remember { mutableStateOf(false) }
    // 2.8 服务器启动完成（serverUrl 变为非空）→ 自动弹出二维码
    LaunchedEffect(serverUrl) {
        if (serverUrl != null && pendingQrAfterServerStart) {
            pendingQrAfterServerStart = false
            showQrDialog = true
        }
    }
    // 2.6 搜索页内嵌为 tab 后与其它页面一致：不再接管返回键，
    // 由主页统一逻辑处理（焦点回侧栏选中 tab → 再按返回弹退出确认）。

    // ===== 三栏跨列导航用焦点引用（显式方向键拦截，不依赖 focusGroup 的系统行为）=====
    // 键盘首键（从联想/热门左移回到键盘时的落点）
    val keyboardFirstRequester = remember { FocusRequester() }
    // 联想/历史列表第一项（键盘/热门跨列进入中间列的落点）
    val suggestionFirstRequester = remember { FocusRequester() }
    // 热门列表第一项（联想列右移 / 中间列为空时键盘右移的落点）
    val hotFirstRequester = remember { FocusRequester() }
    // 键盘焦点是否在右边缘键（每行最右列/搜索键）：为 true 时右键才跨列跳联想，
    // 为 false 时右键交给焦点系统做键盘内部导航（A→B）
    var keyboardAtRightEdge by remember { mutableStateOf(false) }
    // 顶部「歌曲/歌单」类型选择器是否停留在最右侧按钮（歌单）：为 true 时右键跨列跳联想，
    // 与键盘修复一致，避免右键跳过中间联想列直接跳到右侧热门
    var typeAtRightEdge by remember { mutableStateOf(false) }

    // 全站主题由 MainActivity 根部的 LXMusicTheme 统一提供，此处不再嵌套 LXMusicTheme
    Column(
        modifier = modifier
            .fillMaxSize()
            // 2.5 浅色主题：实色基底 + 顶部氛围渐变（仿 EchoMusic）
            .background(LXSurfaceMain)
            .background(LXAccentGradientBrush)
            .padding(24.dp)
        ) {
            // ===== 三栏布局：左键盘 + 中联想 + 右热门 =====
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // ---- 左侧：搜索框 + 类型 + 平台 + 小键盘 ----
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.4f)
                        // focusGroup：跨列导航按「键盘 → 联想 → 热门」整组跳转，
                        // 避免焦点搜索跳过中间联想列直达右侧热门
                        .focusGroup()
                        // 显式拦截右键：仅当焦点在键盘「右边缘」键（每行最右列/搜索键）时
                        // 才强制跳联想列；键盘内部的左右导航（如 A→B）仍交给焦点系统，
                        // 避免整个键盘只能选中最左一列
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionRight &&
                                (keyboardAtRightEdge || typeAtRightEdge)
                            ) {
                                // 中间列有内容（输入空且有历史 / 输入非空且有联想）→ 进中间列首项；
                                // 中间列为空 → 直接跳右侧热门首项（避免焦点落在不可见占位，需再按一次）
                                val middleHasItems =
                                    (searchQuery.isBlank() && searchHistory.isNotEmpty()) ||
                                    (searchQuery.isNotBlank() && suggestions.isNotEmpty())
                                val target = if (middleHasItems) {
                                    suggestionFirstRequester
                                } else {
                                    hotFirstRequester
                                }
                                tryRequestFocus(target)
                            } else {
                                false
                            }
                        }
                ) {
                    // 2.6 移除「搜索音乐/搜索歌单」标题

                    // 搜索词展示框（纯展示，不可聚焦）：
                    // 输入全部由下方小键盘驱动，不放在 TextField 里——聚焦的 TextField 会建立
                    // IME 连接，遥控器返回键会被系统输入法逻辑在 Compose 之前吞掉，导致无法退出搜索页
                    // 2.8 输入框缩窄（weight 0.7）+ 右侧二维码推送按钮（扫码用手机推文字到输入框）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(0.7f)
                                .clip(RoundedCornerShape(12.dp))
                                // 2.5 浅色主题：白底 + 浅边框
                                .background(LXSurfaceCard)
                                .border(2.dp, LXBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 18.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "搜索",
                                    tint = LXTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = searchQuery.ifBlank {
                                        // 2.8 文案缩短：输入框缩窄后原文案放不下（Ellipsis 截断）
                                        if (searchType == SearchType.PLAYLIST) "输入歌单关键词" else "输入歌曲名/歌手"
                                    },
                                    fontSize = 16.sp,
                                    // 2.5 浅色主题：浅色背景上深色文字，空白用次文字提示
                                    color = if (searchQuery.isBlank()) LXTextSecondary else LXTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // 二维码推送按钮：扫码打开 /search 页，手机输入文字推送到输入框（解决 TV 输入困难）。
                        // 2.8 样式与小键盘按键完全一致：未聚焦灰底无边框，聚焦红底 0.35 + 3dp 焦点边框；
                        // 未开启服务器时询问是否启用
                        var qrBtnFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .onFocusChanged { qrBtnFocused = it.isFocused }
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (qrBtnFocused) LXPrimary.copy(alpha = 0.35f) else LXSurfaceVariant
                                )
                                .border(
                                    width = if (qrBtnFocused) 3.dp else 0.dp,
                                    color = if (qrBtnFocused) FocusBorder else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    if (serverUrl != null) {
                                        showQrDialog = true
                                    } else {
                                        // 2.8 HTTP 未开启：先询问是否启用，启用成功后自动弹二维码
                                        showEnableServerDialog = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "扫码推送搜索文字",
                                tint = if (qrBtnFocused) Color.White else LXTextPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // 2.8 HTTP 未启用询问弹窗：确认后启动服务器（onEnableServer），
                    // 启动完成 serverUrl 更新 → LaunchedEffect 自动弹出二维码
                    if (showEnableServerDialog) {
                        AlertDialog(
                            onDismissRequest = { showEnableServerDialog = false },
                            containerColor = LXSurfaceDialog,
                            title = {
                                Text(
                                    text = "开启 HTTP 服务器？",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LXTextPrimary
                                )
                            },
                            text = {
                                Text(
                                    text = "扫码推送需要 HTTP 服务器支持。开启后手机/电脑打开 /search 页面，即可输入文字推送到电视搜索框。",
                                    fontSize = 14.sp,
                                    color = LXTextSecondary
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showEnableServerDialog = false
                                    pendingQrAfterServerStart = true
                                    onEnableServer()
                                }) {
                                    Text("开启", color = LXPrimary, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showEnableServerDialog = false }) {
                                    Text("取消", color = LXTextSecondary)
                                }
                            }
                        )
                    }

                    // 2.8 扫码推送弹窗：显示 /search 地址 + 二维码（需开启 HTTP 服务器）
                    if (showQrDialog) {
                        SearchQrDialog(
                            serverUrl = serverUrl,
                            onDismiss = { showQrDialog = false }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 搜索类型选择（歌曲 / 歌单，遥控器方向键 + 确认切换）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SearchTypeSelector(
                            searchType = searchType,
                            onTypeSelected = onSearchTypeChange,
                            // 跟踪类型选择器是否停在最右按钮（歌单），用于右键跨列路由到联想列
                            onFocusAtRightEdgeChange = { typeAtRightEdge = it },
                            // 导航栏右键进入搜索页时，焦点落到类型选择器首项（歌曲）
                            extraFocusRequester = contentEnterRequester,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2.6 去除「搜索平台」说明行（平台信息在左侧栏 Logo 下方展示）

                    Spacer(modifier = Modifier.height(16.dp))

                    // ===== TV 内置小键盘（字母 + 功能键，无数字）=====
                    TvKeyboard(
                        onKey = { key ->
                            if (searchQuery.length < 40) {
                                onSearchQueryChange(searchQuery + key)
                            }
                        },
                        onBackspace = { onSearchQueryChange(searchQuery.dropLast(1)) },
                        onClear = { onSearchQueryChange("") },
                        onSearch = {
                            if (searchQuery.isNotBlank()) {
                                if (searchType == SearchType.PLAYLIST) {
                                    onSearchPlaylist(searchQuery, searchPlatform)
                                } else {
                                    onSearch(searchQuery, searchPlatform)
                                }
                            }
                        },
                        showNumbers = false,
                        // 跨列导航左移回键盘时的落点（第一个字母键）
                        firstKeyRequester = keyboardFirstRequester,
                        // 跟踪键盘右边缘键的聚焦状态（决定右键是否跨列）
                        onFocusAtRightEdgeChange = { keyboardAtRightEdge = it }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.width(16.dp))

                // ---- 中间：搜索联想列表 ----
                Column(
                    modifier = Modifier
                        .weight(0.3f)
                        .fillMaxHeight()
                        .focusGroup()
                        // 显式跨列导航：中列右移 → 热门第一项；左移 → 键盘首键
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionRight -> tryRequestFocus(hotFirstRequester)
                                    Key.DirectionLeft -> tryRequestFocus(keyboardFirstRequester)
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                ) {
                    // 中间列：搜索输入为空 → 展示搜索历史（最多 10 条 + 清空按钮）；
                    // 输入非空 → 展示搜索联想；两者皆无 → 占位提示
                    val showHistory = searchQuery.isBlank() && searchHistory.isNotEmpty()
                    val showSuggestionList = searchQuery.isNotBlank() && suggestions.isNotEmpty()
                    if (showHistory) {
                        // ===== 搜索历史 =====
                        var historyHadFocus by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    // 焦点首次从外部进入历史列时，强制聚焦第一条
                                    if (focusState.hasFocus && !historyHadFocus) {
                                        suggestionFirstRequester.requestFocus()
                                    }
                                    historyHadFocus = focusState.hasFocus
                                }
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(1),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(searchHistory, key = { _, s -> s }) { index, keyword ->
                                    SearchHistoryItem(
                                        keyword = keyword,
                                        focusRequester = if (index == 0) suggestionFirstRequester else null,
                                        onClick = {
                                            onSearchQueryChange(keyword)
                                            if (searchType == SearchType.PLAYLIST) {
                                                onSearchPlaylist(keyword, searchPlatform)
                                            } else {
                                                onSearch(keyword, searchPlatform)
                                            }
                                        }
                                    )
                                }
                                // 清空搜索记录按钮：作为列表底部一项（否则 grid 外节点遥控器焦点无法到达）
                                if (searchHistory.isNotEmpty()) {
                                    item(key = "clear_history") {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // focusRequester 必须在 clickable（提供焦点节点）之前
                                                .lxFocusBorder(
                                                    shape = RoundedCornerShape(8.dp),
                                                    unfocusedColor = Color.Transparent,
                                                    unfocusedWidth = 0.dp
                                                )
                                                .clickable { onClearSearchHistory() }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "清空搜索记录",
                                                fontSize = 14.sp,
                                                color = LXTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (showSuggestionList) {
                        // ===== 搜索联想 =====
                        var suggestionGridHadFocus by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    // 焦点首次从外部进入联想列时，强制聚焦第一条
                                    if (focusState.hasFocus && !suggestionGridHadFocus) {
                                        suggestionFirstRequester.requestFocus()
                                    }
                                    suggestionGridHadFocus = focusState.hasFocus
                                }
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(1),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(suggestions, key = { _, s -> s }) { index, suggestion ->
                                    SuggestionItem(
                                        suggestion = suggestion,
                                        focusRequester = if (index == 0) suggestionFirstRequester else null,
                                        onClick = {
                                            onSearchQueryChange(suggestion)
                                            if (searchType == SearchType.PLAYLIST) {
                                                onSearchPlaylist(suggestion, searchPlatform)
                                            } else {
                                                onSearch(suggestion, searchPlatform)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // 2.6 中间列无内容时直接留白（无可聚焦节点）：
                        // 键盘右键将直接跳到右侧热门，热门左移直接回键盘，
                        // 避免焦点落在不可见占位上造成「按一下没反应」的错觉
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // ---- 右侧：热门搜索列表（接口空时用 fallback 始终呈现） ----
                Column(
                    modifier = Modifier
                        .weight(0.3f)
                        .fillMaxHeight()
                        .focusGroup()
                        // 显式跨列导航：右列左移 → 中间列第一项（联想/历史首项）；
                        // 中间列为空 → 直接回键盘首键（不落在不可见占位）
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                // 中间列是否有可聚焦内容：输入空且有历史，或输入非空且有联想
                                val middleHasItems =
                                    (searchQuery.isBlank() && searchHistory.isNotEmpty()) ||
                                    (searchQuery.isNotBlank() && suggestions.isNotEmpty())
                                val target = if (middleHasItems) {
                                    suggestionFirstRequester
                                } else {
                                    keyboardFirstRequester
                                }
                                tryRequestFocus(target)
                            } else {
                                false
                            }
                        }
                ) {
                    // 2.6 移除「最近热门」标题，仅保留内容区
                    val showHotSongs = searchPlatform == MusicPlatform.KG && hotSongs.isNotEmpty()

                    if (showHotSongs) {
                        // 酷狗：用热门歌曲列表（取 TOP500 前 10 首），点击以歌名发起搜索（与其他平台一致）
                        var hotListHadFocus by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (focusState.hasFocus && !hotListHadFocus) {
                                        hotFirstRequester.requestFocus()
                                    }
                                    hotListHadFocus = focusState.hasFocus
                                }
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(1),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(hotSongs, key = { _, s -> s.id }) { index, song ->
                                    HotSearchItem(
                                        keyword = song.name,
                                        focusRequester = if (index == 0) hotFirstRequester else null,
                                        onClick = {
                                            // 与其他平台一致：点击热门歌曲→以歌名作为关键词发起搜索，跳转到搜索结果页
                                            onSearchQueryChange(song.name)
                                            if (searchType == SearchType.PLAYLIST) {
                                                onSearchPlaylist(song.name, searchPlatform)
                                            } else {
                                                onSearch(song.name, searchPlatform)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // 始终展示：接口空时用 fallback 兜底，避免界面空白
                        val hotDisplay = hotKeywords.take(10).ifEmpty { HOT_FALLBACK_KEYWORDS }
                        var hotGridHadFocus by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    // 焦点首次从外部进入热门列时，强制聚焦第一条
                                    if (focusState.hasFocus && !hotGridHadFocus) {
                                        hotFirstRequester.requestFocus()
                                    }
                                    hotGridHadFocus = focusState.hasFocus
                                }
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(1),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(hotDisplay, key = { _, k -> k }) { index, keyword ->
                                    HotSearchItem(
                                        keyword = keyword,
                                        focusRequester = if (index == 0) hotFirstRequester else null,
                                        onClick = {
                                            onSearchQueryChange(keyword)
                                            if (searchType == SearchType.PLAYLIST) {
                                                onSearchPlaylist(keyword, searchPlatform)
                                            } else {
                                                onSearch(keyword, searchPlatform)
                                            }
                                        }
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
 * 搜索结果列表
 */
@Composable
fun SearchResults(
    results: List<Song>,
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    isFavorite: (String) -> Boolean = { false },
    onToggleFavorite: ((Song) -> Unit)? = null,
    onSongClick: (Song) -> Unit,
    onPlaySongStay: (Song) -> Unit,
    // 分页续拉（酷狗歌单详情）：可选，普通搜索结果不传则无影响
    onLoadMore: (() -> Unit)? = null,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 焦点驱动续拉：TV 上滚动不触发重组，用「焦点所在行号」判断是否接近列表末尾（与首页 BrowsePage 一致）
    var focusedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(focusedIndex) {
        if (hasMore && !loadingMore && results.isNotEmpty() && focusedIndex >= results.size - 8) {
            onLoadMore?.invoke()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        itemsIndexed(results, key = { _, song -> song.id }) { index, song ->
            SongItem(
                song = song,
                index = index,
                isCurrentPlaying = song.id == currentSongId && isPlaying,
                isFavorite = isFavorite(song.id),
                onToggleFavorite = onToggleFavorite?.let { { it(song) } },
                onPlayStay = { onPlaySongStay(song) },
                onPlayOpen = { onSongClick(song) },
                onSongFocused = { idx -> focusedIndex = idx }
            )
        }
    }
}

/**
 * 搜索类型选择器（歌曲 / 歌单）
 * 电视遥控器：方向键左右切换焦点，确认键选中。
 *
 * 与收藏页 Tab 一致：采用真实焦点驱动，每个类型直接 [focusable]，
 * 焦点态由 Compose 默认二维焦点系统管理，[lxSelectorFocus] 视觉高亮
 * 跟随真实 [isFocused]，避免早期手动 focusedIndex 与实际焦点不同步。
 */
@Composable
fun SearchTypeSelector(
    searchType: SearchType,
    onTypeSelected: (SearchType) -> Unit,
    // 跟踪是否停留在最右按钮（歌单）：用于在 SearchScreen 中拦截右键跨列路由到联想列，
    // 与小键盘右键修复保持一致（避免右键跳过中间联想列直接跳到右侧热门）
    onFocusAtRightEdgeChange: (Boolean) -> Unit = {},
    // 外部注入的焦点请求器（导航栏右键进入搜索页时聚焦类型选择器首项，2.6 内嵌 tab）
    extraFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val types = listOf(SearchType.SONG, SearchType.PLAYLIST)
    // 2.6 移除「进入时自动聚焦首项」：搜索页内嵌为 tab 后，点击 tab 时焦点应与其他 tab 一致
    // 停留在左侧导航栏，按右键才通过 extraFocusRequester（contentEnterRequester）进入本选择器

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        types.forEachIndexed { index, type ->
            val isSelected = searchType == type

            // 2.8 样式改为缓存管理-清除全部同款大按钮：选中=红底白字，未选中=灰底深字
            CacheActionButton(
                text = type.displayName,
                onClick = { onTypeSelected(type) },
                highlighted = isSelected,
                modifier = Modifier
                    .weight(1f)
                    // focusRequester 必须在 focusable(clickable) 之前
                    .then(if (index == 0 && extraFocusRequester != null) Modifier.focusRequester(extraFocusRequester) else Modifier)
                    // 跟踪最右按钮焦点：仅在最后一项聚焦时上报 true，其余项聚焦上报 false，
                    // 焦点离开（失焦）时回落到 false，保证右键跨列拦截状态与实际焦点同步
                    .onFocusChanged { fs ->
                        if (index == types.lastIndex) {
                            onFocusAtRightEdgeChange(fs.isFocused)
                        } else if (fs.isFocused) {
                            onFocusAtRightEdgeChange(false)
                        }
                    }
            )
        }
    }
}

/**
 * 歌单搜索结果列表
 */
@Composable
fun PlaylistResults(
    results: List<Playlist>,
    isFavorite: (String) -> Boolean = { false },
    onToggleFavorite: ((Playlist) -> Unit)? = null,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(results, key = { it.id }) { playlist ->
            PlaylistItem(
                playlist = playlist,
                isFavorite = isFavorite(playlist.id),
                onToggleFavorite = onToggleFavorite?.let { { it(playlist) } },
                onClick = { onPlaylistClick(playlist) }
            )
        }
    }
}

/**
 * 歌单项：封面 + 名称 + 歌曲数/创建者
 */
@Composable
fun PlaylistItem(
    playlist: Playlist,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val brandColor = platformBrandColor(playlist.platform)

    Card(
        modifier = modifier
            .fillMaxWidth(),
        // 点6：深色卡片（仿播放页播放列表暗色面板）；卡片本身不再作为焦点目标，
        // 焦点落在卡片上的操作按钮（仿 SongItem：选中按钮而非整行）
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

            // 两个操作按钮（仿 SongItem：选中按钮而非整行卡片）
            // ① 进入歌单（加载该歌单的歌曲列表）——沿用旧版 ChevronRight 图标
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

            // ② 收藏/取消收藏（可选显示）
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
 * 歌单详情歌曲列表视图（页面内返回歌单列表）
 */
@Composable
fun PlaylistSongsView(
    songs: List<Song>,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    isFavorite: (String) -> Boolean = { false },
    onToggleFavorite: ((Song) -> Unit)? = null,
    onSongClick: (Song) -> Unit,
    onPlaySongStay: (Song) -> Unit,
    // 分页续拉（酷狗歌单详情）：可选，传了才启用焦点触底加载更多
    onLoadMore: (() -> Unit)? = null,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 注：不再在列表内单独放「返回歌单列表」按钮——返回由 SearchResultScreen 顶部
        // 唯一的「返回」箭头（歌单歌曲层级会退一级回到歌单结果列表）统一处理，避免双层返回。

        when {
            // 加载中：转圈动画，不闪现失败/空（网易云等平台偶发 0.5~1s 才返回）
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = LXPrimary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "正在加载歌单歌曲...", fontSize = 16.sp, color = LXTextSecondary)
                    }
                }
            }

            // 加载失败（超时/异常）：仅超时/异常才提示，正常空歌单不在此列
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = LXPrimary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = errorMessage, fontSize = 16.sp, color = LXTextSecondary)
                    }
                }
            }

            // 真正空歌单
            songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PlaylistRemove,
                            contentDescription = null,
                            tint = LXTextSecondary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "歌单暂无歌曲", fontSize = 16.sp, color = LXTextSecondary)
                    }
                }
            }

            else -> {
                SearchResults(
                    results = songs,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onSongClick = onSongClick,
                    onPlaySongStay = onPlaySongStay,
                    onLoadMore = onLoadMore,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 热门搜索快捷入口（搜索页右侧初始面板）
 * 优先展示按平台从接口获取的热搜词；接口无数据时回退内置关键词
 */
@Composable
fun HotArtistsAndSongs(
    hotKeywords: List<String>,
    onSearchClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 接口无数据时的内置兜底关键词（歌手/歌曲混合）
    val fallbackKeywords = listOf(
        "周杰伦", "林俊杰", "陈奕迅", "薛之谦", "邓紫棋",
        "晴天", "七里香", "演员", "泡沫", "海阔天空"
    )
    val keywords = if (hotKeywords.isNotEmpty()) hotKeywords.take(20) else fallbackKeywords

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "热门搜索",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = LXTextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            keywords.forEach { keyword ->
                HotSearchTag(
                    keyword = keyword,
                    onClick = { onSearchClick(keyword) }
                )
            }
        }
    }
}

/**
 * 歌曲列表项
 */
@Composable
fun SongItem(
    song: Song,
    index: Int = 0,
    isCurrentPlaying: Boolean = false,
    isFavorite: Boolean = false,
    onPlayStay: () -> Unit,
    onPlayOpen: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onExitToNav: (() -> Unit)? = null,
    // 上报行号（TV 分页由焦点驱动：焦点接近列表末尾时触发续拉；普通列表不传则无影响）
    onSongFocused: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 点6：深色卡片（仿播放页播放列表暗色面板），播放中仅靠红色文字区分
    Card(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) onSongFocused?.invoke(index) },
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号（与首页歌单歌曲列表统一）
            Text(
                text = "${index + 1}",
                fontSize = 14.sp,
                color = LXOnCardDarkSecondary,
                modifier = Modifier.width(36.dp)
            )
            // 封面：有真实封面图则加载显示，否则显示平台品牌图标
            val brandColor = platformBrandColor(song.platform)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brandColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentPlaying) {
                    // 播放中：封面（或平台图标）+ 叠半透明黑 + Equalizer 三柱脉冲动画（移植播放页歌曲列表效果）
                    if (!song.picUrl.isNullOrBlank()) {
                        RemoteImage(
                            url = song.picUrl,
                            contentDescription = song.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = platformIcon(song.platform),
                            contentDescription = platformShortName(song.platform),
                            tint = brandColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000)))
                    EqualizerBars(
                        modifier = Modifier.height(18.dp),
                        color = Color.White
                    )
                } else if (!song.picUrl.isNullOrBlank()) {
                    RemoteImage(
                        url = song.picUrl,
                        contentDescription = song.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = platformIcon(song.platform),
                                    contentDescription = platformShortName(song.platform),
                                    tint = brandColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    )
                } else {
                    Icon(
                        imageVector = platformIcon(song.platform),
                        contentDescription = platformShortName(song.platform),
                        tint = brandColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 歌曲信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrentPlaying) LXPrimary else LXOnCardDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    // 平台标识
                    Text(
                        text = platformShortName(song.platform),
                        fontSize = 11.sp,
                        color = platformBrandColor(song.platform),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = song.singer,
                        fontSize = 14.sp,
                        color = LXOnCardDarkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 时长
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
                    // 单曲列表首项（如收藏歌单详情）左键精确返回 tab
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
 * 收藏按钮（爱心）
 * 支持触屏点击和遥控器焦点
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            // 统一圆形聚焦（填充+焦点环一次性绘制，无空带、边框粗细统一）
            .lxCircleButtonFocus()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavorite) "取消收藏" else "收藏",
            // 未收藏：深色（卡片已极淡透明、底色为浅色主区，白图标不可见），收藏态仍为品牌红
            tint = if (isFavorite) LXPrimary else LXOnCardDark,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 热门搜索标签
 */
@Composable
fun HotSearchTag(
    keyword: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        // 2.5 浅色主题：浅灰底 + 深色文字（仿 EchoMusic 标签）
        color = LXSurfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = keyword,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp,
            color = LXTextPrimary
        )
    }
}

/**
 * 格式化时长
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * 平台选择器 - 电视遥控器友好设计
 * 支持方向键导航，焦点管理清晰，适合大屏显示
 */
@Composable
fun PlatformSelector(
    selectedPlatform: MusicPlatform,
    onPlatformSelected: (MusicPlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    // 只显示支持搜索的平台（名称走 platformShortName，跟随 PlatformNameConfig 别名配置）
    val platforms = listOf(
        MusicPlatform.KW to platformShortName(MusicPlatform.KW),
        MusicPlatform.KG to platformShortName(MusicPlatform.KG),
        MusicPlatform.TX to platformShortName(MusicPlatform.TX),
        MusicPlatform.WY to platformShortName(MusicPlatform.WY),
        MusicPlatform.MG to platformShortName(MusicPlatform.MG)
    )

    Column(modifier = modifier) {
        Text(
            text = "搜索平台",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = LXTextPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            platforms.forEach { (platform, displayName) ->
                val isSelected = selectedPlatform == platform

                PlatformChip(
                    displayName = displayName,
                    isSelected = isSelected,
                    onClick = { onPlatformSelected(platform) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 平台选择芯片 - 电视遥控器友好设计
 * 支持焦点状态，适合大屏显示
 */
@Composable
fun PlatformChip(
    displayName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 2.5 浅色主题：选中红底白字，未选中浅灰底深字
    val backgroundColor = if (isSelected) LXPrimary else LXSurfaceVariant
    val textColor = if (isSelected) Color.White else LXTextPrimary

    Surface(
        modifier = modifier
            .lxSelectorFocus(shape = RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
        }
    }
}

/**
 * 搜索历史项（带历史图标，点击以该词发起搜索）
 */
@Composable
fun SearchHistoryItem(
    keyword: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // focusRequester 必须在 clickable（提供焦点节点）之前
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 通用卡片样式：未聚焦无边框，仅焦点选中显示 FocusBorder（与设置项一致）
            .lxFocusBorder(
                shape = RoundedCornerShape(8.dp),
                unfocusedColor = Color.Transparent,
                unfocusedWidth = 0.dp
            )
            .clickable { onClick() },
        // 通用卡片样式：半透明深色卡片 LXCardDark，随主题切换
        color = LXCardDark,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = LXOnCardDarkSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = keyword,
                fontSize = 16.sp,
                color = LXOnCardDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 搜索联想项
 */
@Composable
fun SuggestionItem(
    suggestion: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // focusRequester 必须在 clickable（提供焦点节点）之前
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 通用卡片样式：未聚焦无边框，仅焦点选中显示 FocusBorder（与设置项一致）；shape 与容器 8.dp 对齐，杜绝圆角异常
            .lxFocusBorder(
                shape = RoundedCornerShape(8.dp),
                unfocusedColor = Color.Transparent,
                unfocusedWidth = 0.dp
            )
            .clickable { onClick() },
        // 通用卡片样式：半透明深色卡片 LXCardDark（浅色 12.5% 黑 / 深色 31% 黑），随主题切换
        color = LXCardDark,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = LXOnCardDarkSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = suggestion,
                fontSize = 16.sp,
                color = LXOnCardDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 热门搜索项（带火焰图标）
 */
@Composable
fun HotSearchItem(
    keyword: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // focusRequester 必须在 clickable（提供焦点节点）之前
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 通用卡片样式：未聚焦无边框，仅焦点选中显示 FocusBorder（与设置项一致）；shape 与容器 8.dp 对齐，杜绝圆角异常
            .lxFocusBorder(
                shape = RoundedCornerShape(8.dp),
                unfocusedColor = Color.Transparent,
                unfocusedWidth = 0.dp
            )
            .clickable { onClick() },
        // 通用卡片样式：半透明深色卡片 LXCardDark（浅色 12.5% 黑 / 深色 31% 黑），随主题切换
        color = LXCardDark,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFFF7E29),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = keyword,
                fontSize = 16.sp,
                color = LXOnCardDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


/**
 * 2.8 扫码推送搜索文字弹窗：显示 /search 地址 + 二维码。
 * 手机/电脑扫码打开页面 → 输入文字 → 推送到 TV 搜索输入框（需开启 HTTP 服务器）。
 */
@Composable
private fun SearchQrDialog(
    serverUrl: String?,
    onDismiss: () -> Unit
) {
    val pushUrl = serverUrl?.let { "$it/search" } ?: "需先开启 HTTP 服务器"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LXSurfaceDialog,
        title = {
            Text(
                text = "扫码推送搜索文字",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = LXTextPrimary
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "手机/电脑打开下方页面，输入文字即可推送到电视搜索框，解决遥控器输入困难",
                    fontSize = 13.sp,
                    color = LXTextSecondary
                )
                if (serverUrl != null) {
                    val qr = generateQrCode(pushUrl, 300)
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "搜索推送二维码",
                            modifier = Modifier.size(280.dp)
                        )
                    }
                }
                Text(
                    text = pushUrl,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXPrimary
                )
                Text(
                    text = "需先在设置中开启 HTTP 服务器；地址为电视当前局域网 IP",
                    fontSize = 12.sp,
                    color = LXTextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = LXTextSecondary)
            }
        },
        dismissButton = {}
    )
}

/**
 * 2.8 生成二维码 ImageBitmap（zxing core，纯本地编码）
 */
private fun generateQrCode(content: String, size: Int = 300): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
