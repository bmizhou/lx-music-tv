package com.lxmusic.tv.data.model

/**
 * 搜索类型（歌曲搜索 / 歌单搜索）
 */
enum class SearchType(val displayName: String) {
    SONG("歌曲"),
    PLAYLIST("歌单")
}

/**
 * 播放模式
 */
enum class PlayMode(val displayName: String) {
    SEQUENCE("顺序播放"),
    RANDOM("随机播放"),
    LOOP_SINGLE("单曲循环")
}

/**
 * 搜索结果
 */
data class SearchResult(
    val songs: List<Song>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

/**
 * 歌曲信息
 */
data class Song(
    val id: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val albumId: String?,
    val picUrl: String?,
    val duration: Long?,
    val platform: MusicPlatform,
    val quality: List<AudioQuality> = emptyList()
) {
    /**
     * 转换为MusicInfo
     */
    fun toMusicInfo(): MusicInfo {
        return MusicInfo(
            songmid = id,
            name = name,
            singer = singer,
            albumName = albumName,
            albumId = albumId,
            picUrl = picUrl,
            duration = duration,
            platform = platform
        )
    }
}

/**
 * 歌单
 */
data class Playlist(
    val id: String,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val songCount: Int,
    val platform: MusicPlatform,
    val creator: String? = null
)

/**
 * 浏览数据类型（发现/歌单/排行页面）
 */
enum class BrowseType {
    RECOMMEND,  // 推荐歌单（发现页）
    PLAYLIST,   // 歌单广场
    RANKING     // 排行榜
}

/**
 * 浏览项（排行榜/歌单通用，发现/歌单/排行页面使用）
 */
data class BrowseItem(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val songCount: Int = 0,
    val description: String? = null
)

/**
 * 排行榜
 */
data class Ranking(
    val id: String,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val platform: MusicPlatform,
    val updateTime: Long? = null
)

/**
 * 搜索历史
 */
data class SearchHistory(
    val keyword: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 播放历史
 */
data class PlayHistory(
    val musicId: String,
    val musicName: String,
    val artist: String,
    val platform: MusicPlatform,
    val playedAt: Long = System.currentTimeMillis(),
    val duration: Long,
    val sourceId: String
)

/**
 * 收藏夹
 */
data class Favorite(
    val musicId: String,
    val musicName: String,
    val artist: String,
    val platform: MusicPlatform,
    val addedAt: Long = System.currentTimeMillis(),
    val sourceId: String,
    val picUrl: String? = null,
    val albumName: String? = null,
    val duration: Long? = null
)

/**
 * 收藏的歌单
 */
data class FavoritePlaylist(
    val playlistId: String,
    val name: String,
    val platform: MusicPlatform,
    val coverUrl: String? = null,
    val songCount: Int = 0,
    val creator: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    /**
     * 转换为歌单（用于打开加载歌曲）
     */
    fun toPlaylist(): Playlist {
        return Playlist(
            id = playlistId,
            name = name,
            description = null,
            coverUrl = coverUrl,
            songCount = songCount,
            platform = platform,
            creator = creator
        )
    }
}

/**
 * 音质选项
 */
data class QualityOption(
    val quality: AudioQuality,
    val displayName: String,
    val isAvailable: Boolean = true,
    val fileSize: String? = null
)

/**
 * 播放队列项
 */
data class PlayQueueItem(
    val song: Song,
    val isCurrent: Boolean = false,
    val playUrl: String? = null,
    val quality: AudioQuality = AudioQuality.QUALITY_320K
)

/**
 * 搜索建议
 */
data class SearchSuggestion(
    val keyword: String,
    val type: SuggestionType
)

/**
 * 建议类型
 */
enum class SuggestionType {
    HISTORY,    // 搜索历史
    HOT,        // 热门搜索
    RELATED     // 相关搜索
}

/**
 * 分页信息
 */
data class PaginationInfo(
    val currentPage: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int
) {
    companion object {
        fun create(currentPage: Int, pageSize: Int, totalItems: Int): PaginationInfo {
            val totalPages = (totalItems + pageSize - 1) / pageSize
            return PaginationInfo(
                currentPage = currentPage,
                pageSize = pageSize,
                totalItems = totalItems,
                totalPages = totalPages
            )
        }
    }
}

/**
 * API响应包装
 */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val errorCode: Int? = null
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(success = true, data = data)
        }

        fun <T> error(message: String, errorCode: Int? = null): ApiResponse<T> {
            return ApiResponse(success = false, message = message, errorCode = errorCode)
        }
    }
}

/**
 * 搜索参数
 */
data class SearchParams(
    val keyword: String,
    val page: Int = 1,
    val pageSize: Int = 20,
    val platform: MusicPlatform? = null
)

/**
 * 播放参数
 */
data class PlayParams(
    val song: Song,
    val quality: AudioQuality = AudioQuality.QUALITY_320K,
    val sourceId: String? = null
)

/**
 * 音乐项
 */
data class MusicItem(
    val id: String,
    val name: String,
    val artist: String,
    val album: String?,
    val duration: Long,
    val platform: String,
    val sourceId: String,
    val url: String?,
    val picUrl: String?,
    val lyricUrl: String?,
    val createdAt: Long = System.currentTimeMillis()
)