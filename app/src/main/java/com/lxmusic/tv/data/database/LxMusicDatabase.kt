package com.lxmusic.tv.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

/**
 * Room数据库类
 */
@Database(
    entities = [
        MusicSourceEntity::class,
        MusicItemEntity::class,
        PlayHistoryEntity::class,
        FavoriteEntity::class,
        FavoritePlaylistEntity::class,
        CacheItemEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class LxMusicDatabase : RoomDatabase() {
    abstract fun musicSourceDao(): MusicSourceDao
    abstract fun musicItemDao(): MusicItemDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun favoritePlaylistDao(): FavoritePlaylistDao
    abstract fun cacheItemDao(): CacheItemDao
    
    companion object {
        @Volatile
        private var INSTANCE: LxMusicDatabase? = null

        // v1 → v2：新增 favorite_playlists 表（保留原有播放源/历史/收藏数据）
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_playlists` (" +
                            "`playlistId` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`platform` TEXT NOT NULL, " +
                            "`coverUrl` TEXT, " +
                            "`songCount` INTEGER NOT NULL, " +
                            "`creator` TEXT, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`playlistId`))"
                )
            }
        }

        // v2 → v3：favorites 表补充 picUrl/albumName/duration 列（收藏歌曲显示封面用）
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `favorites` ADD COLUMN `picUrl` TEXT")
                db.execSQL("ALTER TABLE `favorites` ADD COLUMN `albumName` TEXT")
                db.execSQL("ALTER TABLE `favorites` ADD COLUMN `duration` INTEGER")
            }
        }

        // v3 → v4：新增 cache_items 表（2.7 播放 URL 短期缓存持久化，跨会话命中音频缓存）
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cache_items` (" +
                            "`key` TEXT NOT NULL, " +
                            "`value` TEXT NOT NULL, " +
                            "`expireAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`key`))"
                )
            }
        }
        
        fun getDatabase(context: Context): LxMusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LxMusicDatabase::class.java,
                    "lx_music_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * 播放源实体
 */
@Entity(tableName = "music_sources")
data class MusicSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val version: String?,
    val author: String?,
    val homepage: String?,
    val scriptContent: String,
    val isEnabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 音乐项实体
 */
@Entity(tableName = "music_items")
data class MusicItemEntity(
    @PrimaryKey val id: String,
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

/**
 * 播放历史实体
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val musicId: String,
    val musicName: String,
    val artist: String,
    val platform: String,
    val playedAt: Long = System.currentTimeMillis(),
    val duration: Long,
    val sourceId: String
)

/**
 * 收藏实体
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val musicId: String,
    val musicName: String,
    val artist: String,
    val platform: String,
    val addedAt: Long = System.currentTimeMillis(),
    val sourceId: String,
    val picUrl: String? = null,
    val albumName: String? = null,
    val duration: Long? = null
)

/**
 * 收藏歌单实体
 */
@Entity(tableName = "favorite_playlists")
data class FavoritePlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val platform: String,
    val coverUrl: String?,
    val songCount: Int,
    val creator: String?,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 播放源数据访问对象
 */
@Dao
interface MusicSourceDao {
    // 按导入时间升序（createdAt = 导入时间）：优先级 = 列表顺序，重启后保持导入时的先后顺序，
    // 不能按 updatedAt DESC（最后启用/修改的源会跑到最前面，导致重启后优先级顺序变化）
    @Query("SELECT * FROM music_sources ORDER BY createdAt ASC")
    fun getAllSources(): Flow<List<MusicSourceEntity>>
    
    @Query("SELECT * FROM music_sources WHERE id = :id")
    suspend fun getSourceById(id: String): MusicSourceEntity?
    
    @Query("SELECT * FROM music_sources WHERE isEnabled = 1")
    fun getEnabledSources(): Flow<List<MusicSourceEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: MusicSourceEntity)
    
    @Update
    suspend fun updateSource(source: MusicSourceEntity)
    
    @Delete
    suspend fun deleteSource(source: MusicSourceEntity)
    
    @Query("DELETE FROM music_sources WHERE id = :id")
    suspend fun deleteSourceById(id: String)
}

/**
 * 音乐项数据访问对象
 */
@Dao
interface MusicItemDao {
    @Query("SELECT * FROM music_items WHERE sourceId = :sourceId ORDER BY createdAt DESC")
    fun getMusicItemsBySource(sourceId: String): Flow<List<MusicItemEntity>>
    
    @Query("SELECT * FROM music_items WHERE id = :id")
    suspend fun getMusicItemById(id: String): MusicItemEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusicItem(item: MusicItemEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusicItems(items: List<MusicItemEntity>)
    
    @Update
    suspend fun updateMusicItem(item: MusicItemEntity)
    
    @Delete
    suspend fun deleteMusicItem(item: MusicItemEntity)
    
    @Query("DELETE FROM music_items WHERE sourceId = :sourceId")
    suspend fun deleteMusicItemsBySource(sourceId: String)
}

/**
 * 播放历史数据访问对象
 */
@Dao
interface PlayHistoryDao {
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun getPlayHistory(limit: Int = 100): Flow<List<PlayHistoryEntity>>
    
    @Query("SELECT * FROM play_history WHERE musicId = :musicId ORDER BY playedAt DESC LIMIT 1")
    suspend fun getPlayHistoryByMusicId(musicId: String): PlayHistoryEntity?
    
    @Insert
    suspend fun insertPlayHistory(history: PlayHistoryEntity)
    
    @Delete
    suspend fun deletePlayHistory(history: PlayHistoryEntity)
    
    @Query("DELETE FROM play_history")
    suspend fun clearPlayHistory()
}

/**
 * 收藏数据访问对象
 */
@Dao
interface FavoriteDao {
    // 先收藏的排在前面（addedAt 升序）
    @Query("SELECT * FROM favorites ORDER BY addedAt ASC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>
    
    @Query("SELECT * FROM favorites WHERE musicId = :musicId")
    suspend fun getFavoriteByMusicId(musicId: String): FavoriteEntity?
    
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE musicId = :musicId)")
    suspend fun isFavorite(musicId: String): Boolean
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorite(favorite: FavoriteEntity)
    
    @Delete
    suspend fun deleteFavorite(favorite: FavoriteEntity)
    
    @Query("DELETE FROM favorites WHERE musicId = :musicId")
    suspend fun deleteFavoriteByMusicId(musicId: String)
}

/**
 * 收藏歌单数据访问对象
 */
@Dao
interface FavoritePlaylistDao {
    // 先收藏的排在前面（addedAt 升序）
    @Query("SELECT * FROM favorite_playlists ORDER BY addedAt ASC")
    fun getAllFavoritePlaylists(): Flow<List<FavoritePlaylistEntity>>
    
    @Query("SELECT * FROM favorite_playlists WHERE playlistId = :playlistId")
    suspend fun getFavoritePlaylistById(playlistId: String): FavoritePlaylistEntity?
    
    @Query("SELECT EXISTS(SELECT 1 FROM favorite_playlists WHERE playlistId = :playlistId)")
    suspend fun isFavoritePlaylist(playlistId: String): Boolean
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoritePlaylist(favoritePlaylist: FavoritePlaylistEntity)
    
    @Delete
    suspend fun deleteFavoritePlaylist(favoritePlaylist: FavoritePlaylistEntity)
    
    @Query("DELETE FROM favorite_playlists WHERE playlistId = :playlistId")
    suspend fun deleteFavoritePlaylistById(playlistId: String)
}
/**
 * 2.7 缓存条目（播放 URL 短期缓存持久化）
 * key = 平台|歌曲id|音质；value = 解析出的播放 URL；expireAt = 过期时间戳
 */
@Entity(tableName = "cache_items")
data class CacheItemEntity(
    @PrimaryKey val key: String,
    val value: String,
    val expireAt: Long
)

/**
 * 缓存条目数据访问对象
 */
@Dao
interface CacheItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CacheItemEntity)

    @Query("SELECT * FROM cache_items WHERE `key` = :key AND expireAt > :now")
    suspend fun getValid(key: String, now: Long): CacheItemEntity?

    @Query("DELETE FROM cache_items WHERE expireAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM cache_items")
    suspend fun clearAll()
}
