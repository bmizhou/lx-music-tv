package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.data.model.MusicSource
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.lxFocusBorder
import com.lxmusic.tv.presentation.component.lxSelectorFocus
import com.lxmusic.tv.presentation.component.requestInitialFocus
import com.lxmusic.tv.presentation.theme.LXAccentGradientBrush
import com.lxmusic.tv.presentation.theme.LXBorder
import com.lxmusic.tv.presentation.theme.LXCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDark
import com.lxmusic.tv.presentation.theme.LXOnCardDarkSecondary
import com.lxmusic.tv.presentation.theme.LXPrimary
import com.lxmusic.tv.presentation.theme.LXSuccess
import com.lxmusic.tv.presentation.theme.LXSurfaceDialog
import com.lxmusic.tv.presentation.theme.LXSurfaceMain
import com.lxmusic.tv.presentation.theme.LXSurfaceVariant
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXTextSecondary

/**
 * 播放源管理界面
 * 管理导入的播放源文件，启动HTTP服务器
 */
@Composable
fun SourceManagementScreen(
    sources: List<MusicSource>,
    serverRunning: Boolean,
    serverUrl: String?,
    onToggleServer: () -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
    onDeleteSource: (String) -> Unit,
    onBack: () -> Unit,
    onGetSourcePlatforms: (String) -> Set<String> = { emptySet() },
    onSetSourcePlatforms: (String, Set<String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf<MusicSource?>(null) }
    // 平台配置弹窗状态：当前配置的源 + 平台选择集合
    var platformConfigSource by remember { mutableStateOf<MusicSource?>(null) }
    // 播放源排序：启用的源排在前面（优先级从高到低），未启用的排在后面
    val orderedSources = remember(sources) {
        sources.filter { it.isEnabled } + sources.filter { !it.isEnabled }
    }
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

    // 全站主题由 MainActivity 根部的 LXMusicTheme 统一提供，此处不再嵌套 LXMusicTheme
    // 整页：浅色基底 #F5F5F7 + 顶部品牌红氛围渐变（与主/侧栏一致，2.5 浅色主题）
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LXSurfaceMain)
    ) {
            // 顶部氛围渐变叠加层（固定不随列表滚动，仿主页主区）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(LXAccentGradientBrush)
            )
            // 整页 LazyColumn：手机上内容多时可整体滚动，避免控件挤在一起
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // 顶部导航栏
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            // focusRequester 必须在 focusable(IconButton) 之前
                            .focusRequester(backFocusRequester)
                            .lxBackButtonFocus()
                    ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        // 2.5 浅色主题：浅底上的深色返回箭头（lxBackButtonFocus 提供焦点边框）
                        tint = LXTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = "播放源管理",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        // 2.5 浅色主题：浅底深色标题
                        color = LXTextPrimary
                    )
                }
            }

            // HTTP服务器状态
            item {
                ServerStatusCard(
                    isRunning = serverRunning,
                    serverUrl = serverUrl,
                    onToggle = onToggleServer,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 播放源列表（启用的源按启动顺序排最前 = 优先级从高到低，未启用的排后）
            if (sources.isEmpty()) {
                item { EmptyState(modifier = Modifier.fillMaxWidth().height(320.dp)) }
            } else {
                // 说明：优先级 = 列表顺序
                item {
                    Text(
                        text = "启用的音源按列表顺序作为播放优先级（越靠前优先级越高），播放失败会自动切换到下一个音源",
                        fontSize = 13.sp,
                        color = LXTextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                itemsIndexed(orderedSources) { index, source ->
                    SourceItem(
                        source = source,
                        // 启用源显示优先级序号（1 = 最高）
                        priority = if (source.isEnabled) index + 1 else null,
                        onToggleEnabled = { enabled -> onToggleSource(source.id, enabled) },
                        onDelete = { showDeleteDialog = source },
                        onConfigurePlatforms = { platformConfigSource = source },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 删除确认对话框
        showDeleteDialog?.let { source ->
            DeleteConfirmDialog(
                sourceName = source.name,
                onConfirm = {
                    onDeleteSource(source.id)
                    showDeleteDialog = null
                },
                onDismiss = { showDeleteDialog = null }
            )
        }

        // 平台配置对话框
        platformConfigSource?.let { source ->
            PlatformConfigDialog(
                sourceName = source.name,
                selectedPlatforms = onGetSourcePlatforms(source.id),
                onConfirm = { platforms ->
                    onSetSourcePlatforms(source.id, platforms)
                    platformConfigSource = null
                },
                onDismiss = { platformConfigSource = null }
            )
        }
        }
}

/**
 * 服务器状态卡片
 */
@Composable
fun ServerStatusCard(
    isRunning: Boolean,
    serverUrl: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        // 2.5 浅色主题：深色卡片（仿播放页播放列表），内部文字浅色
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isRunning) LXSuccess else LXOnCardDarkSecondary)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "HTTP服务器",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    // 2.5 浅色主题：深卡片上的浅色文字
                    color = LXOnCardDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier
                        // 与下方音源启用开关保持一致：常态透明边框，仅聚焦时主题色边框
                        .lxSelectorFocus(shape = RoundedCornerShape(10.dp), glow = false, animated = false),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = LXPrimary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = LXOnCardDarkSecondary
                    )
                )
            }

            if (isRunning && serverUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    // 2.5 浅色主题：深卡片上的次级面板（白色半透明叠加）
                    .background(Color(0x22FFFFFF))
                    .clickable { }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = LXPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = serverUrl,
                        fontSize = 14.sp,
                        color = LXPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = LXOnCardDarkSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "在手机或电脑浏览器中打开上述地址，即可上传和管理播放源",
                    fontSize = 12.sp,
                    // 2.5 浅色主题：深卡片上的次级文字（白70%）
                    color = LXOnCardDarkSecondary
                )
            }
        }
    }
}

/**
 * 播放源列表项
 * 两行紧凑布局：第一行 图标+信息+启用开关；第二行 平台配置+删除按钮
 * 手机上不拥挤，均可点击
 */
@Composable
fun SourceItem(
    source: MusicSource,
    priority: Int? = null,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onConfigurePlatforms: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        // 卡片自身不设焦点节点：若卡片 focusable 会拦截方向键，导致遥控器
        // 无法把焦点移入卡片内部的「启用/平台配置/删除」操作（需按两次确定）。
        // 焦点直接落在内部三个操作控件上，方向键即可在它们之间切换。
        modifier = modifier,
        // 2.5 浅色主题：深色卡片（仿播放页播放列表），内部文字浅色
        colors = CardDefaults.cardColors(containerColor = LXCardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp)
        ) {
            // 第一行：图标 + 信息 + 启用开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // 2.5 浅色主题：深卡片上的次级图标底（白色半透明叠加）
                    .background(Color(0x22FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = LXPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            // 2.5 浅色主题：深卡片上的浅色文字
                            color = LXOnCardDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        // 优先级徽标（启用源按列表顺序 1=最高）
                        priority?.let { p ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(LXPrimary.copy(alpha = 0.25f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "优先级 $p",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LXPrimary
                                )
                            }
                        }
                    }
                    source.description?.let { desc ->
                        Text(
                            text = desc,
                            fontSize = 13.sp,
                            // 2.5 浅色主题：深卡片上的次级文字（白70%）
                            color = LXOnCardDarkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        source.version?.let { ver ->
                            Text(text = "v$ver", fontSize = 12.sp, color = LXSuccess)
                        }
                        source.author?.let { auth ->
                            Text(text = "by $auth", fontSize = 12.sp, color = LXOnCardDarkSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 启用开关（第一行右侧）
                Switch(
                    checked = source.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier
                        // 常态透明边框，仅聚焦时亮红边框（避免常显一圈多余边框）
                        // 关闭发光与动画：小按钮密集排列时发光/动画会背景异常
                        .lxSelectorFocus(shape = RoundedCornerShape(10.dp), glow = false, animated = false),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = LXPrimary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = LXOnCardDarkSecondary
                    )
                )
            }

            // 第二行：平台配置 + 删除（紧凑排列）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onConfigurePlatforms,
                    modifier = Modifier
                        .height(36.dp)
                        // 常态透明边框，仅聚焦时亮红边框；关闭发光/动画
                        .lxSelectorFocus(shape = RoundedCornerShape(6.dp), glow = false, animated = false),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LXPrimary),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, LXPrimary.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "平台配置", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .height(36.dp)
                        // 常态透明边框，仅聚焦时亮红边框；关闭发光/动画
                        .lxSelectorFocus(shape = RoundedCornerShape(6.dp), glow = false, animated = false),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LXPrimary),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, LXPrimary.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "删除", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 播放源平台配置对话框
 * 勾选该源对哪些平台生效；全部不勾 = 对所有平台生效
 */
@Composable
fun PlatformConfigDialog(
    sourceName: String,
    selectedPlatforms: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // 可选平台（对应 MusicPlatform.key；名称走 displayName，跟随 PlatformNameConfig 别名配置）
    val platforms = listOf(
        MusicPlatform.KW.key to MusicPlatform.KW.displayName,
        MusicPlatform.KG.key to MusicPlatform.KG.displayName,
        MusicPlatform.TX.key to MusicPlatform.TX.displayName,
        MusicPlatform.WY.key to MusicPlatform.WY.displayName,
        MusicPlatform.MG.key to MusicPlatform.MG.displayName
    )
    var selected by remember { mutableStateOf(selectedPlatforms) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            // 2.5 浅色主题：灰白底对话框（LXSurfaceDialog #F0F0F2）+ 浅边框
            colors = CardDefaults.cardColors(containerColor = LXSurfaceDialog),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, LXBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    text = "平台配置",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    // 2.5 浅色主题：白底深色标题
                    color = LXTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sourceName,
                    fontSize = 14.sp,
                    color = LXTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "勾选该源生效的平台；全部不勾选 = 对所有平台生效",
                    fontSize = 12.sp,
                    color = LXTextSecondary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 平台开关列表（遥控器：方向键 + 确认切换）
                // 用 verticalScroll 保证小屏/高度不足时可滚动看到全部平台与按钮
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    platforms.forEach { (key, name) ->
                        val isChecked = selected.contains(key)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    selected = if (isChecked) selected - key else selected + key
                                }
                                .lxFocusBorder(shape = RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(
                                // 2.5 浅色主题：选中=浅红填充，未选中=浅灰表面，均不含常驻边框
                                containerColor = if (isChecked) LXPrimary.copy(alpha = 0.15f) else LXSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isChecked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isChecked) LXPrimary else LXTextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = name,
                                    fontSize = 16.sp,
                                    // 2.5 浅色主题：白底深色文字
                                    color = LXTextPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        // 2.5 浅色主题：浅灰底 + 深色文字
                        colors = ButtonDefaults.buttonColors(containerColor = LXSurfaceVariant, contentColor = LXTextPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "取消", fontSize = 15.sp)
                    }
                    Button(
                        onClick = { onConfirm(selected) },
                        modifier = Modifier.weight(1f),
                        // 2.5 浅色主题：品牌红按钮（白字）
                        colors = ButtonDefaults.buttonColors(containerColor = LXPrimary, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "保存", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 空状态
 */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = "无播放源",
            tint = LXTextSecondary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无播放源",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            // 2.5 浅色主题：浅底深色文字
            color = LXTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "启动HTTP服务器后，在手机/电脑浏览器中打开页面地址即可上传JS播放源文件",
            fontSize = 14.sp,
            color = LXTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 删除确认对话框
 */
@Composable
fun DeleteConfirmDialog(
    sourceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "确认删除", fontWeight = FontWeight.Bold) },
        text = { Text("确定要删除播放源 \"$sourceName\" 吗？") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = LXPrimary)
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
        // 2.5 浅色主题：灰白底对话框（LXSurfaceDialog #F0F0F2）+ 深色文字
        containerColor = LXSurfaceDialog,
        titleContentColor = LXTextPrimary,
        textContentColor = LXTextPrimary
    )
}
