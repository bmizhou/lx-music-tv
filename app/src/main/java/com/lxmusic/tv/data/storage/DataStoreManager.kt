package com.lxmusic.tv.data.storage

import android.content.Context
import com.lxmusic.tv.data.database.*
import com.lxmusic.tv.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 数据存储管理器
 * 封装数据库操作，提供统一的API
 */
class DataStoreManager(
    private val context: Context
) {
    private val database = LxMusicDatabase.getDatabase(context)
    private val musicSourceDao = database.musicSourceDao()
    private val musicItemDao = database.musicItemDao()
    private val playHistoryDao = database.playHistoryDao()
    private val favoriteDao = database.favoriteDao()
    private val favoritePlaylistDao = database.favoritePlaylistDao()
    
    // ========== 播放源相关操作 ==========
    
    /**
     * 获取所有播放源
     */
    fun getAllSources(): Flow<List<MusicSource>> {
        return musicSourceDao.getAllSources().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    /**
     * 根据ID获取播放源
     */
    suspend fun getSourceById(id: String): MusicSource? {
        return musicSourceDao.getSourceById(id)?.toDomainModel()
    }
    
    /**
     * 获取启用的播放源
     */
    fun getEnabledSources(): Flow<List<MusicSource>> {
        return musicSourceDao.getEnabledSources().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    /**
     * 保存播放源
     */
    suspend fun saveSource(source: MusicSource) {
        musicSourceDao.insertSource(source.toEntity())
    }
    
    /**
     * 更新播放源
     */
    suspend fun updateSource(source: MusicSource) {
        musicSourceDao.updateSource(source.toEntity())
    }
    
    /**
     * 删除播放源
     */
    suspend fun deleteSource(id: String) {
        musicSourceDao.deleteSourceById(id)
    }
    
    /**
     * 设置播放源启用状态
     */
    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        val source = musicSourceDao.getSourceById(id) ?: return
        musicSourceDao.updateSource(source.copy(isEnabled = enabled, updatedAt = System.currentTimeMillis()))
    }
    
    // ========== 音乐项相关操作 ==========
    
    /**
     * 获取播放源下的音乐列表
     */
    fun getMusicItemsBySource(sourceId: String): Flow<List<MusicItem>> {
        return musicItemDao.getMusicItemsBySource(sourceId).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    /**
     * 根据ID获取音乐项
     */
    suspend fun getMusicItemById(id: String): MusicItem? {
        return musicItemDao.getMusicItemById(id)?.toDomainModel()
    }
    
    /**
     * 保存音乐项
     */
    suspend fun saveMusicItem(item: MusicItem) {
        musicItemDao.insertMusicItem(item.toEntity())
    }
    
    /**
     * 批量保存音乐项
     */
    suspend fun saveMusicItems(items: List<MusicItem>) {
        musicItemDao.insertMusicItems(items.map { it.toEntity() })
    }
    
    // ========== 播放历史相关操作 ==========
    
    /**
     * 获取播放历史
     */
    fun getPlayHistory(limit: Int = 100): Flow<List<PlayHistory>> {
        return playHistoryDao.getPlayHistory(limit).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    /**
     * 添加播放历史
     */
    suspend fun addPlayHistory(history: PlayHistory) {
        playHistoryDao.insertPlayHistory(history.toEntity())
    }
    
    /**
     * 清空播放历史
     */
    suspend fun clearPlayHistory() {
        playHistoryDao.clearPlayHistory()
    }
    
    // ========== 收藏相关操作 ==========
    
    /**
     * 获取所有收藏
     */
    fun getAllFavorites(): Flow<List<Favorite>> {
        return favoriteDao.getAllFavorites().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }
    
    /**
     * 检查是否已收藏
     */
    suspend fun isFavorite(musicId: String): Boolean {
        return favoriteDao.isFavorite(musicId)
    }
    
    /**
     * 添加收藏
     */
    suspend fun addFavorite(favorite: Favorite) {
        favoriteDao.insertFavorite(favorite.toEntity())
    }
    
    /**
     * 删除收藏
     */
    suspend fun removeFavorite(musicId: String) {
        favoriteDao.deleteFavoriteByMusicId(musicId)
    }

    // ========== 收藏歌单相关操作 ==========

    /**
     * 获取所有收藏的歌单
     */
    fun getAllFavoritePlaylists(): Flow<List<FavoritePlaylist>> {
        return favoritePlaylistDao.getAllFavoritePlaylists().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * 检查是否已收藏歌单
     */
    suspend fun isFavoritePlaylist(playlistId: String): Boolean {
        return favoritePlaylistDao.isFavoritePlaylist(playlistId)
    }

    /**
     * 添加收藏歌单
     */
    suspend fun addFavoritePlaylist(favoritePlaylist: FavoritePlaylist) {
        favoritePlaylistDao.insertFavoritePlaylist(favoritePlaylist.toEntity())
    }

    /**
     * 删除收藏歌单
     */
    suspend fun removeFavoritePlaylist(playlistId: String) {
        favoritePlaylistDao.deleteFavoritePlaylistById(playlistId)
    }
}

// ========== 扩展函数：实体与领域模型转换 ==========

private fun MusicSourceEntity.toDomainModel(): MusicSource {
    return MusicSource(
        id = id,
        name = name,
        description = description,
        version = version,
        author = author,
        homepage = homepage,
        scriptContent = scriptContent,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun MusicSource.toEntity(): MusicSourceEntity {
    return MusicSourceEntity(
        id = id,
        name = name,
        description = description,
        version = version,
        author = author,
        homepage = homepage,
        scriptContent = scriptContent,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun MusicItemEntity.toDomainModel(): MusicItem {
    return MusicItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        duration = duration,
        platform = platform,
        sourceId = sourceId,
        url = url,
        picUrl = picUrl,
        lyricUrl = lyricUrl
    )
}

private fun MusicItem.toEntity(): MusicItemEntity {
    return MusicItemEntity(
        id = id,
        name = name,
        artist = artist,
        album = album,
        duration = duration,
        platform = platform,
        sourceId = sourceId,
        url = url,
        picUrl = picUrl,
        lyricUrl = lyricUrl
    )
}

private fun PlayHistoryEntity.toDomainModel(): PlayHistory {
    return PlayHistory(
        musicId = musicId,
        musicName = musicName,
        artist = artist,
        platform = MusicPlatform.valueOf(platform),
        playedAt = playedAt,
        duration = duration,
        sourceId = sourceId
    )
}

private fun PlayHistory.toEntity(): PlayHistoryEntity {
    return PlayHistoryEntity(
        musicId = musicId,
        musicName = musicName,
        artist = artist,
        platform = platform.name,
        playedAt = playedAt,
        duration = duration,
        sourceId = sourceId
    )
}

private fun FavoriteEntity.toDomainModel(): Favorite {
    return Favorite(
        musicId = musicId,
        musicName = musicName,
        artist = artist,
        platform = MusicPlatform.valueOf(platform),
        addedAt = addedAt,
        sourceId = sourceId,
        picUrl = picUrl,
        albumName = albumName,
        duration = duration
    )
}

private fun Favorite.toEntity(): FavoriteEntity {
    return FavoriteEntity(
        musicId = musicId,
        musicName = musicName,
        artist = artist,
        platform = platform.name,
        addedAt = addedAt,
        sourceId = sourceId,
        picUrl = picUrl,
        albumName = albumName,
        duration = duration
    )
}

private fun FavoritePlaylistEntity.toDomainModel(): FavoritePlaylist {
    return FavoritePlaylist(
        playlistId = playlistId,
        name = name,
        platform = try { MusicPlatform.valueOf(platform) } catch (e: Exception) { MusicPlatform.LOCAL },
        coverUrl = coverUrl,
        songCount = songCount,
        creator = creator,
        addedAt = addedAt
    )
}

private fun FavoritePlaylist.toEntity(): FavoritePlaylistEntity {
    return FavoritePlaylistEntity(
        playlistId = playlistId,
        name = name,
        platform = platform.name,
        coverUrl = coverUrl,
        songCount = songCount,
        creator = creator,
        addedAt = addedAt
    )
}