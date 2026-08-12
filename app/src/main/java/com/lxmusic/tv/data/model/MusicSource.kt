package com.lxmusic.tv.data.model

/**
 * 音乐源数据模型（领域模型）
 */
data class MusicSource(
    val id: String,
    val name: String,
    val description: String?,
    val version: String?,
    val author: String?,
    val homepage: String?,
    val scriptContent: String,
    val isEnabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // 2.8 启用时间（优先级 = 启用顺序）：启用时记录，禁用置 null；旧数据 null 用 updatedAt 兜底
    val enabledAt: Long? = null
)

/**
 * 音乐源支持的平台
 */
enum class MusicPlatform(val key: String, val defaultDisplayName: String) {
    KW("kw", "酷我音乐"),
    KG("kg", "酷狗音乐"),
    TX("tx", "QQ音乐"),
    WY("wy", "网易云音乐"),
    MG("mg", "咪咕音乐"),
    LOCAL("local", "本地音乐");

    /**
     * 平台显示名（走 [PlatformNameConfig]：开关开启后切换为洛雪音乐音源风格名称，如 小秋音乐）
     */
    val displayName: String
        get() = PlatformNameConfig.displayName(this)
}

/**
 * 平台显示名称配置（2.8）
 *
 * ⚠️ 将 [USE_LX_STYLE_NAMES] 手动改为 `true` 后，全站音乐平台名称切换为洛雪音乐 PC 端
 * 的平台别名（见 lx-music-desktop src/lang/zh-cn.json 的 source_alias_*）：
 * 酷我→小蜗音乐、酷狗→小枸音乐、QQ音乐→小秋音乐、网易云→小芸音乐、咪咕→小蜜音乐。
 * 名称映射表可自行修改。
 */
object PlatformNameConfig {

    /** 是否使用洛雪音乐风格平台名称（手动修改） */
    const val USE_LX_STYLE_NAMES: Boolean = true

    /** 洛雪风格平台全名映射（对齐 lx-music-desktop 别名，可自行修改） */
    private val lxStyleNames: Map<MusicPlatform, String> = mapOf(
        MusicPlatform.KW to "小蜗音乐",
        MusicPlatform.KG to "小枸音乐",
        MusicPlatform.TX to "小秋音乐",
        MusicPlatform.WY to "小芸音乐",
        MusicPlatform.MG to "小蜜音乐"
    )

    /** 洛雪风格平台短名映射（卡片角标等，可自行修改） */
    private val lxStyleShortNames: Map<MusicPlatform, String> = mapOf(
        MusicPlatform.KW to "小蜗",
        MusicPlatform.KG to "小枸",
        MusicPlatform.TX to "小秋",
        MusicPlatform.WY to "小芸",
        MusicPlatform.MG to "小蜜"
    )

    fun displayName(platform: MusicPlatform): String =
        if (USE_LX_STYLE_NAMES) lxStyleNames[platform] ?: platform.defaultDisplayName
        else platform.defaultDisplayName

    fun shortName(platform: MusicPlatform): String =
        if (USE_LX_STYLE_NAMES) lxStyleShortNames[platform] ?: defaultShortName(platform)
        else defaultShortName(platform)

    private fun defaultShortName(platform: MusicPlatform): String = when (platform) {
        MusicPlatform.KW -> "酷我"
        MusicPlatform.KG -> "酷狗"
        MusicPlatform.TX -> "QQ"
        MusicPlatform.WY -> "网易云"
        MusicPlatform.MG -> "咪咕"
        MusicPlatform.LOCAL -> "本地"
    }
}

/**
 * 音乐源支持的音质
 */
enum class AudioQuality(val key: String, val displayName: String) {
    QUALITY_128K("128k", "标准"),
    QUALITY_320K("320k", "高品质"),
    FLAC("flac", "无损"),
    FLAC_24BIT("flac24bit", "Hi-Res")
}

/**
 * 音乐信息
 */
data class MusicInfo(
    val songmid: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val albumId: String?,
    val picUrl: String?,
    val duration: Long?,
    val platform: MusicPlatform
)

/**
 * 播放源初始化信息
 */
data class SourceInitInfo(
    val sources: Map<MusicPlatform, SourcePlatformInfo>,
    val openDevTools: Boolean = false
)

/**
 * 源平台信息
 */
data class SourcePlatformInfo(
    val name: String,
    val type: String = "music",
    val actions: List<String>,
    val qualitys: List<AudioQuality>
)

/**
 * 歌词信息
 */
data class LyricsInfo(
    val lyric: String?,
    val tlyric: String?,  // 翻译歌词
    val rlyric: String?,  // 罗马音歌词
    val lxlyric: String?  // lx逐字歌词
)

/**
 * HTTP服务器状态
 */
data class HttpServerState(
    val isRunning: Boolean,
    val port: Int,
    val accessUrl: String?,
    val connectedDevices: Int = 0
)