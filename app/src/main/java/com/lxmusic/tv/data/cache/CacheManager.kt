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

    /**
     * 音频缓存 key（歌曲维度：平台+歌曲id+音质，与 URL 无关）。
     * JS 源返回的播放 URL 会变化，但 key 不含 URL——URL 变了照样命中同一份缓存，不重新下载。
     */
    fun songCacheKey(song: Song, quality: AudioQuality): String =
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
        // 2.8 音频缓存整体删除 → 所有「完整缓存」标记失效，同步清空（避免残留标记影响半截缓存清理）
        clearCompletedMarks()
    }

    /**
     * 2.8 删除指定缓存 key 的音频缓存（歌曲未完整播放就切换/停止/退出时，清理半截缓存）。
     * SimpleCache.removeResource 删除该 key 的全部缓存分片；key 与播放时 setCacheKey 一致（平台|歌曲id|音质）。
     */
    fun removeAudioByKey(context: Context, key: String) {
        if (key.isBlank()) return
        try {
            getAudioCache(context).removeResource(key)
        } catch (e: Exception) {
            // 缓存索引异常时忽略（下次播放自然重建）
        }
    }

    // ========== 2.8 完整缓存标记（PlayerService 写入，清缓存时同步清理，保持状态一致） ==========
    // 标记存在 SharedPreferences（lx_settings，key=music_cache_completed），记录「完整听过、缓存整首在盘」的歌曲；
    // 手动清缓存后标记必须同步移除，否则残留标记会让「曾完整缓存过的歌」在缓存被清后，
    // 重新播一半切走时不清理半截缓存（违背 v202/v203 清理逻辑）。

    /** 清空全部完整缓存标记（clearAudio/clearAll 后调用：音频缓存整体删除，所有标记失效） */
    private fun clearCompletedMarks() {
        try {
            appContext?.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                ?.edit()
                ?.remove("music_cache_completed")
                ?.apply()
        } catch (e: Exception) {
        }
    }

    /** 移除指定缓存 key 的完整标记（clearUnfavoritedAudio 删除部分歌曲缓存后调用） */
    private fun clearCompletedMarks(keys: Set<String>) {
        if (keys.isEmpty()) return
        try {
            val prefs = appContext?.getSharedPreferences("lx_settings", Context.MODE_PRIVATE) ?: return
            val set = (prefs.getStringSet("music_cache_completed", emptySet()) ?: emptySet()).toMutableSet()
            set.removeAll(keys)
            if (set.isEmpty()) {
                prefs.edit().remove("music_cache_completed").apply()
            } else {
                prefs.edit().putStringSet("music_cache_completed", set).apply()
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 清除「未收藏」歌曲的音频缓存（仅保留已收藏歌曲，2.8 缓存管理页用）。
     * 遍历 SimpleCache 的缓存 key（格式 平台|歌曲id|音质），解析出「平台|歌曲id」前缀，
     * 不在收藏集合（favoriteKeys，格式 "平台key|歌曲id"）中的删除。
     */
    fun clearUnfavoritedAudio(context: Context, favoriteKeys: Set<String>) {
        try {
            val cache = getAudioCache(context)
            val removedKeys = mutableSetOf<String>()
            cache.keys.forEach { key ->
                val parts = key.split("|")
                // 仅处理「平台|歌曲id|音质」格式的歌曲维度 key（URL 兜底 key 不含该结构）
                val prefix = if (parts.size >= 2) "${parts[0]}|${parts[1]}" else null
                if (prefix != null && prefix !in favoriteKeys) {
                    cache.removeResource(key)
                    removedKeys.add(key)
                }
            }
            // 2.8 同步移除被删歌曲的完整缓存标记（避免残留标记影响后续半截缓存清理）
            clearCompletedMarks(removedKeys)
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

    // 2.8 翻译歌词缓存（与主歌词同 key、不同扩展名；无翻译的歌曲命中返回 null）

    /** 命中返回翻译歌词文本，未命中返回 null */
    fun getLyricTranslation(context: Context, song: Song): String? {
        val f = File(lyricDir(context), lyricKey(song) + ".tlyric.lrc")
        return if (f.exists() && f.length() > 0) {
            try { f.readText(Charsets.UTF_8) } catch (e: Exception) { null }
        } else null
    }

    /** 写入翻译歌词缓存 */
    fun putLyricTranslation(context: Context, song: Song, tlyric: String) {
        try {
            File(lyricDir(context), lyricKey(song) + ".tlyric.lrc").writeText(tlyric, Charsets.UTF_8)
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

    /** 2.8 删除指定 URL 缓存（播放失败时清掉坏 URL，防止后续播放命中污染缓存） */
    @Synchronized
    fun removeUrl(key: String) {
        urlCache.remove(key)
        try {
            val ctx = requireContext()
            runBlocking {
                LxMusicDatabase.getDatabase(ctx).cacheItemDao().deleteByKey(key)
            }
        } catch (e: Exception) {
            // 删除失败忽略
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
