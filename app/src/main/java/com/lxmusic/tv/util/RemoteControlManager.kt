package com.lxmusic.tv.util

import android.view.KeyEvent

/**
 * 遥控器操作管理器
 * 处理TV遥控器的按键事件
 */
class RemoteControlManager {

    /**
     * 按键事件回调接口
     */
    interface KeyEventListener {
        fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean
        fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean
    }

    private val listeners = mutableListOf<KeyEventListener>()

    /**
     * 添加按键事件监听器
     */
    fun addKeyListener(listener: KeyEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 移除按键事件监听器
     */
    fun removeKeyListener(listener: KeyEventListener) {
        listeners.remove(listener)
    }

    /**
     * 处理按键按下事件
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        for (listener in listeners) {
            if (listener.onKeyDown(keyCode, event)) {
                return true
            }
        }
        return false
    }

    /**
     * 处理按键释放事件
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        for (listener in listeners) {
            if (listener.onKeyUp(keyCode, event)) {
                return true
            }
        }
        return false
    }

    companion object {
        // 遥控器按键映射
        const val KEYCODE_DPAD_UP = KeyEvent.KEYCODE_DPAD_UP
        const val KEYCODE_DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
        const val KEYCODE_DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val KEYCODE_DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val KEYCODE_DPAD_CENTER = KeyEvent.KEYCODE_DPAD_CENTER
        const val KEYCODE_DPAD_OK = KeyEvent.KEYCODE_DPAD_CENTER
        const val KEYCODE_BACK = KeyEvent.KEYCODE_BACK
        const val KEYCODE_MENU = KeyEvent.KEYCODE_MENU
        const val KEYCODE_VOLUME_UP = KeyEvent.KEYCODE_VOLUME_UP
        const val KEYCODE_VOLUME_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN
        const val KEYCODE_PLAY_PAUSE = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        const val KEYCODE_MEDIA_NEXT = KeyEvent.KEYCODE_MEDIA_NEXT
        const val KEYCODE_MEDIA_PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS

        /**
         * 获取按键名称
         */
        fun getKeyName(keyCode: Int): String {
            return when (keyCode) {
                KEYCODE_DPAD_UP -> "上"
                KEYCODE_DPAD_DOWN -> "下"
                KEYCODE_DPAD_LEFT -> "左"
                KEYCODE_DPAD_RIGHT -> "右"
                KEYCODE_DPAD_CENTER, KEYCODE_DPAD_OK -> "确认"
                KEYCODE_BACK -> "返回"
                KEYCODE_MENU -> "菜单"
                KEYCODE_VOLUME_UP -> "音量+"
                KEYCODE_VOLUME_DOWN -> "音量-"
                KEYCODE_PLAY_PAUSE -> "播放/暂停"
                KEYCODE_MEDIA_NEXT -> "下一曲"
                KEYCODE_MEDIA_PREVIOUS -> "上一曲"
                else -> "未知按键($keyCode)"
            }
        }

        /**
         * 判断是否是导航按键
         */
        fun isNavigationKey(keyCode: Int): Boolean {
            return keyCode in listOf(
                KEYCODE_DPAD_UP,
                KEYCODE_DPAD_DOWN,
                KEYCODE_DPAD_LEFT,
                KEYCODE_DPAD_RIGHT,
                KEYCODE_DPAD_CENTER,
                KEYCODE_DPAD_OK
            )
        }

        /**
         * 判断是否是媒体控制按键
         */
        fun isMediaKey(keyCode: Int): Boolean {
            return keyCode in listOf(
                KEYCODE_PLAY_PAUSE,
                KEYCODE_MEDIA_NEXT,
                KEYCODE_MEDIA_PREVIOUS
            )
        }

        /**
         * 判断是否是音量控制按键
         */
        fun isVolumeKey(keyCode: Int): Boolean {
            return keyCode in listOf(
                KEYCODE_VOLUME_UP,
                KEYCODE_VOLUME_DOWN
            )
        }
    }
}

/**
 * 焦点管理器
 * 管理TV端的焦点导航
 */
class FocusManager {

    /**
     * 焦点方向
     */
    enum class FocusDirection {
        UP, DOWN, LEFT, RIGHT, CENTER
    }

    /**
     * 焦点变化监听器
     */
    interface FocusChangeListener {
        fun onFocusChanged(itemId: String, direction: FocusDirection)
    }

    private val listeners = mutableListOf<FocusChangeListener>()
    private var currentFocusIndex: Int = 0
    private val focusableItems = mutableListOf<String>()

    /**
     * 添加焦点变化监听器
     */
    fun addFocusChangeListener(listener: FocusChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 移除焦点变化监听器
     */
    fun removeFocusChangeListener(listener: FocusChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 设置可聚焦项目列表
     */
    fun setFocusableItems(items: List<String>) {
        focusableItems.clear()
        focusableItems.addAll(items)
        currentFocusIndex = 0
    }

    /**
     * 移动焦点
     */
    fun moveFocus(direction: FocusManager.FocusDirection): Boolean {
        if (focusableItems.isEmpty()) return false

        val newIndex = when (direction) {
            FocusManager.FocusDirection.UP -> {
                if (currentFocusIndex > 0) currentFocusIndex - 1 else focusableItems.size - 1
            }
            FocusManager.FocusDirection.DOWN -> {
                if (currentFocusIndex < focusableItems.size - 1) currentFocusIndex + 1 else 0
            }
            FocusManager.FocusDirection.LEFT -> {
                if (currentFocusIndex > 0) currentFocusIndex - 1 else focusableItems.size - 1
            }
            FocusManager.FocusDirection.RIGHT -> {
                if (currentFocusIndex < focusableItems.size - 1) currentFocusIndex + 1 else 0
            }
            FocusManager.FocusDirection.CENTER -> {
                currentFocusIndex
            }
        }

        if (newIndex != currentFocusIndex) {
            currentFocusIndex = newIndex
            val itemId = focusableItems[currentFocusIndex]
            notifyFocusChanged(itemId, direction)
            return true
        }

        return false
    }

    /**
     * 设置当前焦点索引
     */
    fun setCurrentIndex(index: Int) {
        if (index in focusableItems.indices) {
            currentFocusIndex = index
        }
    }

    /**
     * 获取当前焦点项目ID
     */
    fun getCurrentFocusItem(): String? {
        return if (focusableItems.isNotEmpty() && currentFocusIndex in focusableItems.indices) {
            focusableItems[currentFocusIndex]
        } else {
            null
        }
    }

    /**
     * 获取当前焦点索引
     */
    fun getCurrentIndex(): Int = currentFocusIndex

    /**
     * 通知焦点变化
     */
    private fun notifyFocusChanged(itemId: String, direction: FocusManager.FocusDirection) {
        for (listener in listeners) {
            listener.onFocusChanged(itemId, direction)
        }
    }
}

/**
 * 按键重复检测器
 * 用于检测长按和重复按键
 */
class KeyRepeatDetector {

    private var lastKeyCode: Int = 0
    private var lastKeyTime: Long = 0
    private var repeatCount: Int = 0

    private val repeatListener: ((Int, Int) -> Unit)? = null

    /**
     * 检测按键重复
     * @param keyCode 按键代码
     * @return 重复次数，0表示首次按下
     */
    fun detectRepeat(keyCode: Int): Int {
        val currentTime = System.currentTimeMillis()
        
        if (keyCode == lastKeyCode && currentTime - lastKeyTime < REPEAT_THRESHOLD_MS) {
            repeatCount++
        } else {
            repeatCount = 0
        }
        
        lastKeyCode = keyCode
        lastKeyTime = currentTime
        
        return repeatCount
    }

    /**
     * 重置检测器
     */
    fun reset() {
        lastKeyCode = 0
        lastKeyTime = 0
        repeatCount = 0
    }

    companion object {
        private const val REPEAT_THRESHOLD_MS = 500L // 重复阈值（毫秒）
    }
}

/**
 * 遥控器操作处理器
 * 集成按键事件、焦点管理和重复检测
 */
class RemoteControlHandler {

    private val remoteControlManager = RemoteControlManager()
    private val focusManager = FocusManager()
    private val keyRepeatDetector = KeyRepeatDetector()

    private var keyEventListener: RemoteControlManager.KeyEventListener? = null
    private var focusChangeListener: FocusManager.FocusChangeListener? = null

    /**
     * 初始化遥控器操作
     */
    fun init() {
        keyEventListener = object : RemoteControlManager.KeyEventListener {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                return handleKeyDown(keyCode, event)
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                return handleKeyUp(keyCode, event)
            }
        }

        focusChangeListener = object : FocusManager.FocusChangeListener {
            override fun onFocusChanged(itemId: String, direction: FocusManager.FocusDirection) {
                handleFocusChanged(itemId, direction)
            }
        }

        remoteControlManager.addKeyListener(keyEventListener!!)
        focusManager.addFocusChangeListener(focusChangeListener!!)
    }

    /**
     * 处理按键按下事件
     */
    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 检测重复按键
        val repeatCount = keyRepeatDetector.detectRepeat(keyCode)
        
        return when {
            // 导航按键
            RemoteControlManager.isNavigationKey(keyCode) -> {
                handleNavigationKey(keyCode, repeatCount)
            }
            // 媒体控制按键
            RemoteControlManager.isMediaKey(keyCode) -> {
                handleMediaKey(keyCode)
            }
            // 音量控制按键
            RemoteControlManager.isVolumeKey(keyCode) -> {
                handleVolumeKey(keyCode)
            }
            // 返回键
            keyCode == RemoteControlManager.KEYCODE_BACK -> {
                handleBackKey()
            }
            // 菜单键
            keyCode == RemoteControlManager.KEYCODE_MENU -> {
                handleMenuKey()
            }
            else -> false
        }
    }

    /**
     * 处理按键释放事件
     */
    private fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 大部分按键释放事件不需要特殊处理
        return false
    }

    /**
     * 处理导航按键
     */
    private fun handleNavigationKey(keyCode: Int, repeatCount: Int): Boolean {
        val direction = when (keyCode) {
            RemoteControlManager.KEYCODE_DPAD_UP -> FocusManager.FocusDirection.UP
            RemoteControlManager.KEYCODE_DPAD_DOWN -> FocusManager.FocusDirection.DOWN
            RemoteControlManager.KEYCODE_DPAD_LEFT -> FocusManager.FocusDirection.LEFT
            RemoteControlManager.KEYCODE_DPAD_RIGHT -> FocusManager.FocusDirection.RIGHT
            RemoteControlManager.KEYCODE_DPAD_CENTER, 
            RemoteControlManager.KEYCODE_DPAD_OK -> FocusManager.FocusDirection.CENTER
            else -> return false
        }

        return focusManager.moveFocus(direction)
    }

    /**
     * 处理媒体控制按键
     */
    private fun handleMediaKey(keyCode: Int): Boolean {
        // 这里应该调用播放服务的控制方法
        // 实际实现中需要依赖注入播放服务
        return true
    }

    /**
     * 处理音量控制按键
     */
    private fun handleVolumeKey(keyCode: Int): Boolean {
        // 这里应该调用音量控制
        // 实际实现中需要获取AudioManager
        return true
    }

    /**
     * 处理返回键
     */
    private fun handleBackKey(): Boolean {
        // 这里应该处理返回逻辑
        // 例如：关闭当前页面、返回上一级等
        return true
    }

    /**
     * 处理菜单键
     */
    private fun handleMenuKey(): Boolean {
        // 这里应该显示菜单
        // 例如：显示设置菜单、操作菜单等
        return true
    }

    /**
     * 处理焦点变化
     */
    private fun handleFocusChanged(itemId: String, direction: FocusManager.FocusDirection) {
        // 这里应该更新UI的焦点状态
        // 实际实现中需要通知UI组件更新
    }

    /**
     * 获取遥控器管理器
     */
    fun getRemoteControlManager(): RemoteControlManager = remoteControlManager

    /**
     * 获取焦点管理器
     */
    fun getFocusManager(): FocusManager = focusManager

    /**
     * 释放资源
     */
    fun release() {
        keyEventListener?.let { remoteControlManager.removeKeyListener(it) }
        focusChangeListener?.let { focusManager.removeFocusChangeListener(it) }
    }
}