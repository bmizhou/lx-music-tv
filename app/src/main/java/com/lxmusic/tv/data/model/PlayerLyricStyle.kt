package com.lxmusic.tv.data.model

/**
 * 播放页歌词展示风格（设置页可选，持久化到 SharedPreferences）。
 * 新增风格时：在此追加枚举值 + displayName/description，
 * 并在 PlayerScreen.kt 的 PlayerMainContent 里加对应的 when 分支即可。
 */
enum class PlayerLyricStyle(val displayName: String, val description: String) {
    CLASSIC(
        displayName = "经典布局",
        description = "左侧封面+歌名歌手，右侧滚动歌词（默认）"
    ),
    CENTERED_BIG(
        displayName = "居中大字",
        description = "封面缩小置顶，歌词居中大字体滚动，沉浸感更强"
    ),
    FLYING(
        displayName = "飞词弹幕",
        description = "大小错落的整句歌词与翻译，从全屏随机位置飞入、飞出"
    )
}
