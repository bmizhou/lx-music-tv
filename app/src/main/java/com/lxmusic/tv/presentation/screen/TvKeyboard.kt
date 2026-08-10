package com.lxmusic.tv.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXSurfaceVariant
import com.lxmusic.tv.presentation.theme.LXTextPrimary
import com.lxmusic.tv.presentation.theme.LXPrimary

/**
 * TV 内置小键盘
 * 6列布局：字母两行（A-F, G-L），第三行M-R，第四行S-X，第五行Y-Z,1-4，第六行5-0
 * 底部功能键：清空、后退、搜索
 * 全部可焦点导航，适合遥控器操作
 */
@Composable
fun TvKeyboard(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showNumbers: Boolean = true, // 保留参数以兼容，但忽略，总是显示数字
    firstKeyRequester: FocusRequester? = null, // 跨列导航返回键盘时的落点（绑定第一个字母键）
    // 键盘「右边缘键」聚焦状态回调：仅当焦点在每行最右列或底部「搜索」键上时为 true。
    // 供搜索页跨列导航判断——只有右边缘键的右键才跨列跳到联想区域，
    // 键盘内部的左右导航（如 A→B）仍由焦点系统处理，不拦截。
    onFocusAtRightEdgeChange: (Boolean) -> Unit = {}
) {
    // 键盘布局：6行，每行6个键
    val rows = listOf(
        "ABCDEF",
        "GHIJKL",
        "MNOPQR",
        "STUVWX",
        "YZ1234",
        "567890"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 字母和数字行
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row.forEachIndexed { colIndex, ch ->
                    val isRightEdge = colIndex == row.length - 1
                    KeyboardKey(
                        text = ch.toString(),
                        onClick = { onKey(ch.toString()) },
                        modifier = Modifier
                            .weight(1f)
                            // focusRequester 必须在 focusable(clickable) 之前
                            .then(
                                if (rowIndex == 0 && colIndex == 0 && firstKeyRequester != null) {
                                    Modifier.focusRequester(firstKeyRequester)
                                } else {
                                    Modifier
                                }
                            )
                            // 每个键聚焦时都上报是否处于右边缘：
                            // 焦点离开右边缘键后也能正确上报 false（否则状态残留导致键盘内无法右移）
                            .onFocusChanged { onFocusAtRightEdgeChange(it.isFocused && isRightEdge) }
                    )
                }
            }
        }

        // 功能键一行（「搜索」键在最右端 = 右边缘）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            KeyboardKey(
                text = "清空",
                onClick = onClear,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusAtRightEdgeChange(false) }
            )
            KeyboardKey(
                text = "删除",
                onClick = onBackspace,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusAtRightEdgeChange(false) }
            )
            KeyboardKey(
                text = "搜索",
                onClick = onSearch,
                highlighted = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusAtRightEdgeChange(true) }
            )
        }
    }
}

/**
 * 键盘单个按键
 */
@Composable
fun KeyboardKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    // 2.5 浅色主题：字母/数字键浅灰底深字；聚焦浅红底白字；搜索键红底白字
    val bgColor = when {
        highlighted -> LXPrimary
        isFocused -> LXPrimary.copy(alpha = 0.35f)
        else -> LXSurfaceVariant
    }
    val textColor = if (isFocused || highlighted) Color.White else LXTextPrimary
    // 焦点边框：仅聚焦时显示统一 FocusBorder（深色）；未聚焦不画边框（去掉高亮「搜索」键的白色边框）
    val borderColor = if (isFocused) FocusBorder else Color.Transparent

    Box(
        modifier = modifier
            .height(34.dp)
            // onFocusChanged 必须先于被观察的 focusable（clickable 提供焦点节点）
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(if (isFocused) 3.dp else 0.dp, borderColor, RoundedCornerShape(6.dp))
            // 聚焦不放大，避免超出按键边框范围（焦点靠亮红边框 + 底色变化体现）
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (isFocused || highlighted) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}