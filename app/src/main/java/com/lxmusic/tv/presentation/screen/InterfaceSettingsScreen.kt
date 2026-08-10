package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmusic.tv.presentation.component.lxBackButtonFocus
import com.lxmusic.tv.presentation.component.lxSelectorFocus
import com.lxmusic.tv.presentation.theme.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler

/**
 * 界面设置页（设置 - 界面设置 跳转而来）
 *
 * 提供：
 * - 主题模式：浅色模式 / 深色模式（即时生效，持久化）
 * - 主题色：赤焰红 / 暮色紫 / 靛青（实际生效，切换后全站强调色随之改变）
 *
 * 主题令牌由 LXMusicTheme 在切换时写入 currentLXTheme，故本页在浅色/深色模式下自动适配。
 */
@Composable
fun InterfaceSettingsScreen(
    themeMode: LXThemeMode,
    themeColor: LXThemeColor,
    onThemeModeChange: (LXThemeMode) -> Unit,
    onThemeColorChange: (LXThemeColor) -> Unit,
    onBack: () -> Unit,
) {
    val backRequester = remember { FocusRequester() }

    // 遥控器返回键：返回上一页（与顶部返回箭头一致）
    BackHandler { onBack() }

    // 初始焦点落在顶部返回箭头（仿 source_management 等子页）
    LaunchedEffect(Unit) { backRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize().background(LXSurfaceMain)) {
        // 顶部氛围渐变（与全站主区一致，主题切换自动适配）
        Box(modifier = Modifier.matchParentSize().background(LXAccentGradientBrush))

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // 顶部：返回箭头 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .lxBackButtonFocus(focusedColor = FocusBorder, glow = false, animated = false)
                        .focusRequester(backRequester)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = LXTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "界面设置",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = LXTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // ===================== 主题模式 =====================
                item {
                    SectionTitle("主题模式")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LXThemeMode.values().forEach { mode ->
                            ThemeModeCard(
                                mode = mode,
                                selected = themeMode == mode,
                                modifier = Modifier.weight(1f),
                                onSelect = onThemeModeChange
                            )
                        }
                    }
                }

                // ===================== 主题色 =====================
                item {
                    SectionTitle("主题色")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LXThemeColor.values().forEach { color ->
                            ThemeColorCard(
                                color = color,
                                selected = themeColor == color,
                                modifier = Modifier.weight(1f),
                                onSelect = onThemeColorChange
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分区标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = LXTextPrimary
    )
}

/** 主题模式单选卡片（浅色 / 深色） */
@Composable
private fun ThemeModeCard(
    mode: LXThemeMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (LXThemeMode) -> Unit,
) {
    val (label, desc) = when (mode) {
        LXThemeMode.LIGHT -> "浅色模式" to "明亮清爽"
        LXThemeMode.DARK -> "深色模式" to "暗色护眼"
    }
    Surface(
        modifier = modifier
            .height(96.dp)
            // 焦点边框（仿全站 lxSelectorFocus）；选中态用浅红底 + 勾选图标区分
            .lxSelectorFocus(shape = RoundedCornerShape(12.dp), glow = false, animated = false)
            .clickable { onSelect(mode) },
        // 通用卡片样式：未选中用半透明深色卡片 LXCardDark（与全站列表卡片一致），选中用浅红底
        color = if (selected) LXPrimary.copy(alpha = 0.12f) else LXCardDark,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) LXPrimary else LXOnCardDark
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = LXPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = LXOnCardDarkSecondary
                )
        }
    }
}

/** 主题色单选卡片（赤焰红 / 暮色紫 / 靛青）；实际生效，选中态以该主题强调色高亮 */
@Composable
private fun ThemeColorCard(
    color: LXThemeColor,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (LXThemeColor) -> Unit,
) {
    Surface(
        modifier = modifier
            .height(110.dp)
            .lxSelectorFocus(shape = RoundedCornerShape(12.dp), glow = false, animated = false)
            .clickable { onSelect(color) },
        // 通用卡片样式：未选中用半透明深色卡片 LXCardDark（与全站列表卡片一致），选中用浅红底
        color = if (selected) color.swatch.copy(alpha = 0.12f) else LXCardDark,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 真实色块预览（主题色实际生效，选中态以该强调色高亮）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.swatch, CircleShape)
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = color.label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) color.swatch else LXOnCardDark
                )
            if (selected) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "已选择",
                    fontSize = 11.sp,
                    color = LXOnCardDarkSecondary
                )
            }
        }
    }
}
