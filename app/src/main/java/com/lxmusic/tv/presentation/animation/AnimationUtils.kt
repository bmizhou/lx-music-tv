package com.lxmusic.tv.presentation.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme

/**
 * 动画工具类
 * 提供TV端界面动画效果
 */
object AnimationUtils {
    
    // ========== 页面转场动画 ==========
    
    /**
     * 淡入淡出转场动画
     */
    fun fadeTransition(): ContentTransform {
        return ContentTransform(
            fadeIn(animationSpec = tween(300)),
            fadeOut(animationSpec = tween(300))
        )
    }
    
    /**
     * 滑动转场动画（从右向左）
     */
    fun slideInFromRightTransition(): ContentTransform {
        return ContentTransform(
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(400)
            ),
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / 3 },
                animationSpec = tween(400)
            )
        )
    }
    
    /**
     * 滑动转场动画（从左向右）
     */
    fun slideInFromLeftTransition(): ContentTransform {
        return ContentTransform(
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(400)
            ),
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth / 3 },
                animationSpec = tween(400)
            )
        )
    }
    
    /**
     * 缩放转场动画
     */
    fun scaleTransition(): ContentTransform {
        return ContentTransform(
            scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(300)
            ),
            scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(300)
            )
        )
    }
    
    // ========== 焦点动画 ==========
    
    /**
     * 焦点缩放动画
     */
    @Composable
    fun FocusScaleAnimation(
        focusedScale: Float = 1.05f,
        unfocusedScale: Float = 1.0f,
        content: @Composable () -> Unit
    ) {
        var isFocused by remember { mutableStateOf(false) }
        
        val scale by animateFloatAsState(
            targetValue = if (isFocused) focusedScale else unfocusedScale,
            animationSpec = tween(200),
            label = "focus_scale"
        )
        
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                }
                .focusable()
        ) {
            content()
        }
    }
    
    /**
     * 焦点高亮动画
     */
    @Composable
    fun FocusHighlightAnimation(
        focusedAlpha: Float = 1.0f,
        unfocusedAlpha: Float = 0.7f,
        content: @Composable () -> Unit
    ) {
        var isFocused by remember { mutableStateOf(false) }
        
        val alpha by animateFloatAsState(
            targetValue = if (isFocused) focusedAlpha else unfocusedAlpha,
            animationSpec = tween(200),
            label = "focus_alpha"
        )
        
        Box(
            modifier = Modifier
                .graphicsLayer {
                    this.alpha = alpha
                }
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                }
                .focusable()
        ) {
            content()
        }
    }
    
    /**
     * 焦点边框动画
     */
    @Composable
    fun FocusBorderAnimation(
        focusedBorderColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
        borderWidth: Int = 2,
        content: @Composable () -> Unit
    ) {
        var isFocused by remember { mutableStateOf(false) }
        
        val borderColor by animateColorAsState(
            targetValue = if (isFocused) focusedBorderColor else unfocusedBorderColor,
            animationSpec = tween(200),
            label = "focus_border_color"
        )
        
        Box(
            modifier = Modifier
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                }
                .focusable()
        ) {
            content()
        }
    }
    
    // ========== 列表项动画 ==========
    
    /**
     * 列表项滑入动画
     */
    @Composable
    fun ListItemSlideIn(
        index: Int,
        delayPerItem: Int = 50,
        content: @Composable () -> Unit
    ) {
        val offsetX by animateIntOffsetAsState(
            targetValue = IntOffset(0, 0),
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = index * delayPerItem,
                easing = FastOutSlowInEasing
            ),
            label = "list_item_offset"
        )
        
        Box(
            modifier = Modifier.offset { offsetX }
        ) {
            content()
        }
    }
    
    /**
     * 列表项淡入动画
     */
    @Composable
    fun ListItemFadeIn(
        index: Int,
        delayPerItem: Int = 50,
        content: @Composable () -> Unit
    ) {
        val alpha by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = index * delayPerItem,
                easing = FastOutSlowInEasing
            ),
            label = "list_item_alpha"
        )
        
        Box(
            modifier = Modifier.graphicsLayer { this.alpha = alpha }
        ) {
            content()
        }
    }
    
    // ========== 播放控制动画 ==========
    
    /**
     * 播放/暂停按钮动画
     */
    @Composable
    fun PlayPauseAnimation(
        isPlaying: Boolean,
        size: Int = 48
    ) {
        val rotation by animateFloatAsState(
            targetValue = if (isPlaying) 0f else 0f,
            animationSpec = tween(300),
            label = "play_pause_rotation"
        )
        
        val scale by animateFloatAsState(
            targetValue = if (isPlaying) 1f else 0.9f,
            animationSpec = tween(300),
            label = "play_pause_scale"
        )
        
        Box(
            modifier = Modifier
                .size(size.dp)
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            // 这里可以放置播放/暂停图标
        }
    }
    
    /**
     * 进度条动画
     */
    @Composable
    fun ProgressBarAnimation(
        progress: Float,
        modifier: Modifier = Modifier
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = tween(300),
            label = "progress_bar"
        )
        
        Box(
            modifier = modifier
        ) {
            // 这里可以放置进度条组件
        }
    }
    
    // ========== 遥控器操作反馈动画 ==========
    
    /**
     * 按键反馈动画
     */
    @Composable
    fun KeyPressFeedback(
        onPress: Boolean,
        content: @Composable () -> Unit
    ) {
        val scale by animateFloatAsState(
            targetValue = if (onPress) 0.95f else 1f,
            animationSpec = tween(100),
            label = "key_press_scale"
        )
        
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        ) {
            content()
        }
    }
    
    /**
     * 方向键导航动画
     */
    @Composable
    fun DirectionalNavigationAnimation(
        direction: NavigationDirection,
        content: @Composable () -> Unit
    ) {
        val offsetX by animateIntOffsetAsState(
            targetValue = when (direction) {
                NavigationDirection.LEFT -> IntOffset(-10, 0)
                NavigationDirection.RIGHT -> IntOffset(10, 0)
                else -> IntOffset(0, 0)
            },
            animationSpec = tween(150),
            label = "directional_offset_x"
        )
        
        val offsetY by animateIntOffsetAsState(
            targetValue = when (direction) {
                NavigationDirection.UP -> IntOffset(0, -10)
                NavigationDirection.DOWN -> IntOffset(0, 10)
                else -> IntOffset(0, 0)
            },
            animationSpec = tween(150),
            label = "directional_offset_y"
        )
        
        Box(
            modifier = Modifier.offset {
                IntOffset(offsetX.x + offsetY.x, offsetX.y + offsetY.y)
            }
        ) {
            content()
        }
    }
}

/**
 * 导航方向枚举
 */
enum class NavigationDirection {
    UP, DOWN, LEFT, RIGHT, CENTER
}