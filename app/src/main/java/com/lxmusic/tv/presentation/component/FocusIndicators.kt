package com.lxmusic.tv.presentation.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxmusic.tv.presentation.theme.FocusBorder
import com.lxmusic.tv.presentation.theme.LXPrimary
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 将 Shape.createOutline 得到的 Outline 转为可描边的 Path。
 */
private fun outlineToPath(outline: Outline): Path {
    return Path().apply {
        when (outline) {
            is Outline.Generic -> addPath(outline.path)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Rectangle -> addRect(outline.rect)
        }
    }
}

/**
 * 将描边轮廓向内（+）收缩或向外（-）扩张指定像素。
 * 注意：传【正数】= 向内收缩（本文件统一传 stroke/2，使描边外边缘贴合 shape 外边缘）。
 */
private fun Outline.insetBy(px: Float): Outline {
    return when (this) {
        is Outline.Rounded -> {
            val r = roundRect
            Outline.Rounded(
                RoundRect(
                    left = r.left + px,
                    top = r.top + px,
                    right = r.right - px,
                    bottom = r.bottom - px,
                    topLeftCornerRadius = r.topLeftCornerRadius.shrink(px),
                    topRightCornerRadius = r.topRightCornerRadius.shrink(px),
                    bottomRightCornerRadius = r.bottomRightCornerRadius.shrink(px),
                    bottomLeftCornerRadius = r.bottomLeftCornerRadius.shrink(px)
                )
            )
        }
        is Outline.Rectangle -> Outline.Rectangle(
            Rect(
                left = rect.left + px,
                top = rect.top + px,
                right = rect.right - px,
                bottom = rect.bottom - px
            )
        )
        is Outline.Generic -> this // 自定义路径退化处理，保持原样
    }
}

/**
 * 缩小圆角半径（收缩描边时同步缩小，保证圆角弧度贴合）。
 */
private fun CornerRadius.shrink(px: Float): CornerRadius =
    CornerRadius(x = max(0f, this.x - px), y = max(0f, this.y - px))

/**
 * 安全的初始焦点请求：等一小段时间（组合与 attach 完成后）再 requestFocus，
 * 若首次失败则重试一次。
 */
suspend fun requestInitialFocus(
    focusRequester: FocusRequester,
    attempted: () -> Boolean,
    markAttempted: () -> Unit
) {
    if (attempted()) return
    markAttempted()
    // 第一次：组合提交后立即尝试（通常已 attach）
    try {
        focusRequester.requestFocus()
    } catch (_: IllegalStateException) {
        // focusRequester 尚未初始化（attach 未完成），稍后重试
    }
    delay(80)
    // 第二次：若首次可能未生效，再确认请求一次（幂等，焦点已在则无副作用）
    try {
        focusRequester.requestFocus()
    } catch (_: IllegalStateException) {
        // 仍失败则放弃，交由用户按确定键由系统兜底落焦点
    }
}

/**
 * 列表卡片 / 按钮 / 行项 的统一焦点边框。
 * 修复：① 描边轮廓向内收缩 stroke/2（原来向外扩张导致边框画出边界、四角露直角残留）；
 *       ② 增加 clip(shape)，裁掉内容/背景的直角，杜绝直角穿出红框。
 */
@Composable
fun Modifier.lxFocusBorder(
    focusedColor: Color = FocusBorder,
    unfocusedColor: Color = FocusBorder.copy(alpha = 0.18f),
    focusedWidth: Dp = 3.dp,
    unfocusedWidth: Dp = 1.dp,
    focusedScale: Float = 1.0f,
    glow: Boolean = true,
    animated: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp)
): Modifier {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by if (animated) {
        animateColorAsState(
            targetValue = if (isFocused) focusedColor else unfocusedColor,
            animationSpec = tween(durationMillis = 180),
            label = "focus_border_color"
        )
    } else {
        rememberUpdatedState(if (isFocused) focusedColor else unfocusedColor)
    }
    val borderWidth by if (animated) {
        animateDpAsState(
            targetValue = if (isFocused) focusedWidth else unfocusedWidth,
            animationSpec = tween(durationMillis = 180),
            label = "focus_border_width"
        )
    } else {
        rememberUpdatedState(if (isFocused) focusedWidth else unfocusedWidth)
    }

    return this
        // onFocusChanged 必须先于被观察的 focusable（调用方的 clickable 提供焦点节点）
        .onFocusChanged { isFocused = it.isFocused }
        // ★ 修复1：裁剪内容到圆角，杜绝背景直角从边框四角外穿出
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (borderWidth > 0.dp) {
                val stroke = borderWidth.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                // ★ 修复2：传【正数】向内收缩 stroke/2，描边外边缘恰好贴合 shape 外边缘
                val insetOutline = outline.insetBy(stroke / 2f)
                drawPath(
                    path = outlineToPath(insetOutline),
                    color = borderColor,
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
}

/**
 * 圆形按钮统一聚焦样式（一次性绘制填充 + 边框，根除「边框与中间色空带」）。
 *
 * 旧方案把填充圆（drawBehind）与焦点环（drawWithContent）分成两个修饰符、分属不同图形层，
 * 实测在部分按钮上填充圆与焦点环之间仍存在可见空带；且 IconButton 的最小交互尺寸会改写
 * size() 导致不同按钮实际尺寸/边框粗细不一致。
 *
 * 本修饰符改为在单个 drawWithContent 内、按同一圆心与同一半径绘制：
 *   ① 填充圆：半径 = 盒子短边/2，正好填满圆形裁剪区；
 *   ② 焦点环：描边居中在 (r - stroke/2)，外边缘恰好落在圆形盒子边缘，与填充圆完全重合，无间隙。
 * 未聚焦时不绘制，去除常驻背景。统一裁剪为圆形，内容（图标）超出圆范围被裁掉。
 *
 * @param fillColor 聚焦填充色（默认品牌红 0.18f）
 * @param strokeColor 焦点环颜色（默认 FocusBorder，黑半透明）
 * @param strokeWidth 焦点环线宽（默认 3.dp，所有圆形按钮统一，保证边框粗细一致）
 */
@Composable
fun Modifier.lxCircleButtonFocus(
    fillColor: Color = LXPrimary.copy(alpha = 0.18f),
    strokeColor: Color = FocusBorder,
    strokeWidth: Dp = 3.dp
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    return this
        // 统一裁剪为圆形：内容（图标）与填充/环都被裁进圆内，杜绝直角穿出
        .clip(CircleShape)
        .onFocusChanged { isFocused = it.isFocused }
        .drawWithContent {
            // ① 填充圆：先画在内容（图标）之下，避免遮住图标
            if (isFocused) {
                val r = size.minDimension / 2f
                drawCircle(color = fillColor, radius = r)
            }
            // 图标内容（被圆形裁剪，居中显示）
            drawContent()
            // ② 焦点环：画在内容之上，描边居中在 (r - stroke/2)，外边缘 = r，
            //    与填充圆边缘完全重合（无空带），且只在边缘、不遮挡中央图标
            if (isFocused) {
                val r = size.minDimension / 2f
                val stroke = strokeWidth.toPx()
                drawCircle(
                    color = strokeColor,
                    radius = r - stroke / 2f,
                    style = Stroke(width = stroke)
                )
            }
        }
}

/**
 * 左上角圆形返回按钮的统一焦点样式。
 */
@Composable
fun Modifier.lxBackButtonFocus(
    focusedColor: Color = FocusBorder,
    glow: Boolean = false,
    animated: Boolean = false
): Modifier {
    return lxFocusBorder(
        focusedColor = focusedColor,
        unfocusedColor = Color.Transparent,
        unfocusedWidth = 0.dp,
        focusedScale = 1.0f,
        glow = glow,
        animated = animated,
        shape = CircleShape
    )
}

/**
 * 单选标签 / 平台芯片 的统一焦点样式。
 */
@Composable
fun Modifier.lxSelectorFocus(
    focusedColor: Color = FocusBorder,
    focusedWidth: Dp = 3.dp,
    focusedScale: Float = 1.0f,
    glow: Boolean = true,
    animated: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp)
): Modifier {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by if (animated) {
        animateColorAsState(
            targetValue = if (isFocused) focusedColor else Color.Transparent,
            animationSpec = tween(durationMillis = 180),
            label = "selector_focus_color"
        )
    } else {
        rememberUpdatedState(if (isFocused) focusedColor else Color.Transparent)
    }

    return this
        .onFocusChanged { isFocused = it.isFocused }
        // ★ 同样：裁剪 + 向内收缩
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (focusedWidth > 0.dp) {
                val stroke = focusedWidth.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                val insetOutline = outline.insetBy(stroke / 2f)
                drawPath(
                    path = outlineToPath(insetOutline),
                    color = borderColor,
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
}