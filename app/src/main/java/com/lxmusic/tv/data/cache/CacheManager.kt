package com.lxmusic.tv.data.cache

import android.content.Context
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.lxmusic.tv.data.database.CacheItemEntity
import com.lxmusic.tv.data.database.LxMusicDatabase
import com.lxmusic.tv.data.model.AudioQuality
import com.lxmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * 2.7 统一缓存管理器（参考洛雪安卓端缓存思路）
 *
 * 缓存分四类：
 * 1. **音频缓存**：ExoPlayer `SimpleCache` + `CacheDataSource`（filesDir/lx_cache/audio）——
 *    播放时边播边写缓存，同一 URL 第二次播放直接读本地（全程只下载一次，无需手动二次下载）。
 *    上限 2GB（LRU 淘汰），设置页可统计大小/清理。
 * 2. **歌词缓存**：filesDir/lx_cache/lyric —— 平台 + 歌曲 id 的 LRC 文本
 * 3. **封面缓存**：复用 RemoteImage 的 DiskImageCache（cacheDir/remote_images，已有 600 文件上限）
 * 4. **播放 URL 短期缓存**：内存 LRU + Room 持久化（key=平台|歌曲id|音质，TTL 2 小时）——
 *    重复播放免重复请求解析接口（缓解 QQ 等平台按频率风控），且**跨会话持久**（断网重启后
 *    仍能命中持久化的 URL 去读 SimpleCache 的音频缓存，实现真离线）。
 */
object CacheManager {

    private const val AUDIO_SUB_DIR = "lx_cache/audio"
    private const val LYRIC_SUB_DIR = "lx_cache/lyric"
    // 播放 URL 短期缓存参数
    private const val URL_CACHE_MAX = 200
    private const val URL_CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    // 音频缓存上限（LRU 淘汰）
    private const val AUDIO_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024

    private var appContext: Context? = null

    /** Application.onCreate 调用（持有 applicationContext 供 Room/SimpleCache 使用） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("CacheManager 未初始化，请先在 Application.onCreate 调用 init")

    // ==================== 目录 ====================

    fun audioDir(context: Context): File =
        File(context.filesDir, AUDIO_SUB_DIR).apply { if (!exists()) mkdirs() }

    fun lyricDir(context: Context): File =
        File(context.filesDir, LYRIC_SUB_DIR).apply { if (!exists()) mkdirs() }

    // ==================== key 生成 ====================

    private fun md5(s: String): String = try {
        val md = MessageDigest.getInstance("MD5")
        md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        s.hashCode().toUInt().toString(16)
    }

    /** 歌词缓存 key（平台+歌曲id） */
    private fun lyricKey(song: Song): String = md5("${song.platform.key}|${song.id}")

    /** URL 短期缓存 key（平台+歌曲id+音质） */
    fun urlKey(song: Song, quality: AudioQuality): String =
        "${song.platform.key}|${song.id}|${quality.name}"

    // ==================== 音频缓存（ExoPlayer SimpleCache，播放时自动边播边缓存） ====================

    @Volatile
    private var audioCache: SimpleCache? = null

    /**
     * 获取音频播放缓存（App 级单例）。
     * 首次创建时清掉 v150 前「手动下载」遗留的 .audio/.tmp 文件（统一由 SimpleCache 管理）。
     */
    @Synchronized
    fun getAudioCache(context: Context): SimpleCache {
        audioCache?.let { return it }
        val dir = audioDir(context)
        // 清理旧方案遗留文件
        dir.listFiles()?.forEach {
            if (it.name.endsWith(".audio") || it.name.endsWith(".tmp")) it.delete()
        }
        val cache = SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        )
        audioCache = cache
        return cache
    }

    fun audioCacheSize(context: Context): Long = try {
        getAudioCache(context).cacheSpace
    } catch (e: Exception) {
        dirSize(audioDir(context))
    }

    /** 清空音频缓存（SimpleCache 释放后删除目录内容） */
    fun clearAudio(context: Context) {
        try {
            audioCache?.release()
        } catch (e: Exception) {
        }
        audioCache = null
        audioDir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * 清除「未收藏」歌曲的音频缓存（仅保留已收藏歌曲，2.8 缓存管理页用）。
     * 遍历 SimpleCache 的缓存 key（格式 平台|歌曲id|音质），解析出「平台|歌曲id」前缀，
     * 不在收藏集合（favoriteKeys，格式 "平台key|歌曲id"）中的删除。
     */
    fun clearUnfavoritedAudio(context: Context, favoriteKeys: Set<String>) {
        try {
            val cache = getAudioCache(context)
            cache.keys.forEach { key ->
                val parts = key.split("|")
                // 仅处理「平台|歌曲id|音质」格式的歌曲维度 key（URL 兜底 key 不含该结构）
                val prefix = if (parts.size >= 2) "${parts[0]}|${parts[1]}" else null
                if (prefix != null && prefix !in favoriteKeys) {
                    cache.removeResource(key)
                }
            }
        } catch (e: Exception) {
            // 遍历/删除异常忽略
        }
    }

    // ==================== 歌词缓存 ====================

    /** 命中返回歌词文本，未命中返回 null */
    fun getLyric(context: Context, song: Song): String? {
        val f = File(lyricDir(context), lyricKey(song) + ".lrc")
        return if (f.exists() && f.length() > 0) {
            try { f.readText(Charsets.UTF_8) } catch (e: Exception) { null }
        } else null
    }

    /** 写入歌词缓存 */
    fun putLyric(context: Context, song: Song, lyric: String) {
        try {
            File(lyricDir(context), lyricKey(song) + ".lrc").writeText(lyric, Charsets.UTF_8)
        } catch (e: Exception) {
            // 忽略写入失败
        }
    }

    // ==================== 播放 URL 短期缓存（内存 LRU + Room 持久化） ====================

    private data class UrlEntry(val url: String, val expireAt: Long)

    private val urlCache = object : LinkedHashMap<String, UrlEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UrlEntry>?): Boolean =
            size > URL_CACHE_MAX
    }

    /** 命中且未过期返回 URL，否则返回 null（内存未命中时查 Room 持久化，命中则回填内存） */
    @Synchronized
    fun getUrl(key: String): String? {
        val now = System.currentTimeMillis()
        urlCache[key]?.let { e ->
            if (now <= e.expireAt) return e.url
            urlCache.remove(key)
        }
        // Room 持久化兜底（跨会话命中）
        return try {
            val ctx = requireContext()
            val item = runBlocking {
                LxMusicDatabase.getDatabase(ctx).cacheItemDao().getValid(key, now)
            } ?: return null
            urlCache[key] = UrlEntry(item.value, item.expireAt)
            item.value
        } catch (e: Exception) {
            null
        }
    }

    /** 写入 URL 短期缓存（内存 + Room 持久化） */
    @Synchronized
    fun putUrl(key: String, url: String) {
        val now = System.currentTimeMillis()
        val expireAt = now + URL_CACHE_TTL_MS
        urlCache[key] = UrlEntry(url, expireAt)
        try {
            val ctx = requireContext()
            runBlocking {
                LxMusicDatabase.getDatabase(ctx).cacheItemDao()
                    .upsert(CacheItemEntity(key, url, expireAt))
            }
        } catch (e: Exception) {
            // 持久化失败不影响内存缓存
        }
    }

    // ==================== 统计与清理 ====================

    /** 目录占用字节数 */
    fun dirSize(dir: File): Long = try {
        if (!dir.exists()) 0L
        else dir.listFiles()?.sumOf { if (it.isFile) it.length() else dirSize(it) } ?: 0L
    } catch (e: Exception) { 0L }

    fun lyricCacheSize(context: Context): Long = dirSize(lyricDir(context))
    /** 封面缓存大小（复用 RemoteImage 的 DiskImageCache 目录） */
    fun coverCacheSize(context: Context): Long =
        dirSize(File(context.cacheDir, "remote_images"))

    /** 清理歌词缓存 */
    fun clearLyric(context: Context) {
        lyricDir(context).listFiles()?.forEach { it.delete() }
    }

    /** 清理封面缓存（复用 RemoteImage 的 DiskImageCache 清理） */
    fun clearCover(context: Context) {
        com.lxmusic.tv.presentation.component.DiskImageCache.clear(context)
    }

    /** 清理全部磁盘缓存（音频+歌词+封面） */
    fun clearAll(context: Context) {
        clearAudio(context)
        clearLyric(context)
        clearCover(context)
    }
}
