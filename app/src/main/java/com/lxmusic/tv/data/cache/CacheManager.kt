package com.lxmusic.tv.data.cache

import android.content.Context
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.cache.ContentMetadata
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.lxmusic.tv.data.database.CacheItemEntity
import com.lxmusic.tv.data.database.LxMusicDatabase
import com.lxmusic.tv.data.model.AudioQuality
import com.lxmusic.tv.data.model.Song
import com.lxmusic.tv.service.player.PlayerService
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
    // URL 新鲜度 TTL（2h）：TTL 内视为新鲜可直接播放；过期后若本地 SimpleCache 有该歌曲
    // 音频缓存仍可离线播放（getUrl 返回含过期条目 + hasAudioCache 判断，见 getUrl/isUrlFresh），
    // 仅「过期且无本地音频」才重新解析（有网自愈）。URL 失效由 onPlaybackError 移除缓存并重新解析兜底。
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
    fun clearCompletedMarks() {
        try {
            appContext?.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                ?.edit()
                ?.remove("music_cache_completed")
                ?.apply()
        } catch (e: Exception) {
        }
    }

    /** 移除指定缓存 key 的完整标记（clearUnfavoritedAudio 删除部分歌曲缓存后调用） */
    fun clearCompletedMarks(keys: Set<String>) {
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

    /**
     * 命中返回 URL（**含过期条目**）：断网离线播放时，URL 即使过期也能用——
     * 只要本地 SimpleCache 有该歌曲音频缓存，CacheDataSource 读本地不触网即可播放。
     * URL 新鲜度由 isUrlFresh 判断；过期且无本地音频的 URL 由播放层放弃并重新解析。
     * 内存未命中时查 Room 持久化（不过滤时间），命中则回填内存。
     */
    @Synchronized
    fun getUrl(key: String): String? {
        urlCache[key]?.let { return it.url }
        // Room 持久化兜底（跨会话命中，含过期条目）
        return try {
            val ctx = requireContext()
            val item = runBlocking {
                LxMusicDatabase.getDatabase(ctx).cacheItemDao().getByKey(key)
            } ?: return null
            urlCache[key] = UrlEntry(item.value, item.expireAt)
            item.value
        } catch (e: Exception) {
            null
        }
    }

    /** URL 缓存是否仍在有效期内（TTL 内=在线可直接播放；过期但本地有音频缓存仍可离线播放，见 getUrl） */
    @Synchronized
    fun isUrlFresh(key: String): Boolean {
        val now = System.currentTimeMillis()
        urlCache[key]?.let { return now <= it.expireAt }
        return try {
            val ctx = requireContext()
            runBlocking {
                LxMusicDatabase.getDatabase(ctx).cacheItemDao().getValid(key, now)
            } != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 本地 SimpleCache 是否已有该歌曲 key 的有效音频缓存分片（true=可离线播放，URL 过期也无妨）
     *
     * ⚠️ 2.9 离线治本修复：原实现为 `cache.isCached(key, 0, Long.MAX_VALUE)`，
     * 由于没有任何音频文件能达到 Long.MAX_VALUE 长度，该判定**永远返回 false**，
     * 导致「URL 缓存过期（>2h）后即便本地完整缓存仍在盘上也判定为无缓存」→ 必须联网重新解析 URL 才能播放，
     * 这正是「离线几天后缓存歌曲无法播放、必须联网重载一次 URL」的根本原因。
     * 现改为「完整缓存判定 + 有效分片体积检查」。
     */
    fun hasAudioCache(key: String): Boolean {
        if (key.isBlank()) return false
        return try {
            val ctx = requireContext()
            if (isFullyCached(ctx, key)) {
                return true
            }
            val spans = getAudioCache(ctx).getCachedSpans(key)
            spans != null && spans.isNotEmpty() && spans.sumOf { it.length } >= 200 * 1024L
        } catch (e: Exception) {
            false
        }
    }

    // ========== 2.9 完整缓存判定（离线可播性核心） ==========

    /** 检查指定缓存 key 是否已标记为完整缓存（播放器完整听完 STATE_ENDED 写入） */
    fun isCacheCompleted(context: Context, key: String): Boolean {
        return try {
            context.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                .getStringSet("music_cache_completed", emptySet())?.contains(key) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** 记录指定缓存 key 为完整缓存（已包含则跳过，消除高频冗余写盘） */
    fun markCacheCompleted(context: Context, key: String?) {
        if (key.isNullOrBlank()) return
        try {
            val prefs = context.getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
            val set = (prefs.getStringSet("music_cache_completed", emptySet()) ?: emptySet()).toMutableSet()
            if (set.contains(key)) return
            if (set.size >= 500) set.clear()
            set.add(key)
            prefs.edit().putStringSet("music_cache_completed", set).apply()
        } catch (e: Exception) {
        }
    }

    /**
     * 严格检查指定 key 是否已 100% 完整缓存（整首歌曲已在本地磁盘）
     * 判定条件：
     * 1. 标记检查（核心优先）：已被 markCacheCompleted 标记（播放完毕 STATE_ENDED），且本地有效分片 >= 200KB；
     * 2. 物理检查：Content-Length > 0 且已缓存满（或容差：差额 <= 64KB 且覆盖率 >= 98%），判定完整并补标记；
     * 3. 自动识别：分块传输（无 Content-Length）且从 0 字节起缓冲至音质基准体积，自动补全标记。
     */
    fun isFullyCached(context: Context, key: String): Boolean {
        if (key.isBlank()) return false
        return try {
            val cache = getAudioCache(context)
            val spans = try { cache.getCachedSpans(key) } catch (_: Exception) { null }
            val bytes = spans?.sumOf { it.length } ?: 0L
            // 物理过滤：无分片或总字节数不足 200KB 绝非完整单曲
            if (bytes < 200 * 1024L) return false

            // 准则 1：明确已完整播放标记（STATE_ENDED 听完全曲），最权威凭据
            if (isCacheCompleted(context, key)) {
                return true
            }

            // 准则 2：Content-Length 存在时的物理检查（含末尾 Padding/ID3v1 容差自愈）
            val metadata = try { cache.getContentMetadata(key) } catch (_: Exception) { null }
            val contentLength = if (metadata != null) ContentMetadata.getContentLength(metadata) else -1L
            if (contentLength > 0L) {
                if (cache.isCached(key, 0, contentLength)) {
                    markCacheCompleted(context, key)
                    return true
                }
                val diff = contentLength - bytes
                if (diff <= 64 * 1024L && (bytes.toDouble() / contentLength) >= 0.98) {
                    markCacheCompleted(context, key)
                    return true
                }
                return false
            }

            // 准则 3：分块传输自动识别（必须从 0 字节起 + 达到音质基准体积）
            if (spans != null && spans.isNotEmpty()) {
                val hasStart = spans.any { it.position == 0L }
                if (hasStart) {
                    val q = parseCacheKey(key)?.quality
                    val minBytes = when (q) {
                        AudioQuality.FLAC_24BIT.name -> 12 * 1024 * 1024L
                        AudioQuality.FLAC.name -> 7 * 1024 * 1024L
                        AudioQuality.QUALITY_320K.name -> 4 * 1024 * 1024L
                        AudioQuality.QUALITY_128K.name -> 1500 * 1024L
                        else -> 3 * 1024 * 1024L
                    }
                    if (bytes >= minBytes) {
                        markCacheCompleted(context, key)
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 清理未完成的半截残缺碎片（应用启动时空闲期调用）
     * ⚠️ 必须跳过当前正在播放/写入的活跃 key，否则会把正在边播边写的缓存物理删除，
     * 触发 CacheDataSource FLAG_IGNORE_CACHE_ON_ERROR 导致后续全部歌曲失去缓存能力。
     */
    fun cleanupIncompleteCache(context: Context) {
        try {
            val cache = getAudioCache(context)
            val keys = try { cache.keys.toSet() } catch (_: Exception) { emptySet() }
            val removedKeys = mutableSetOf<String>()
            val activeKey = PlayerService.activePlayingCacheKey
            for (key in keys) {
                if (!activeKey.isNullOrBlank() && key == activeKey) continue
                val spans = try { cache.getCachedSpans(key) } catch (_: Exception) { null }
                val bytes = spans?.sumOf { it.length } ?: 0L
                if (bytes <= 0L) {
                    try {
                        cache.removeResource(key)
                        removeUrl(key)
                    } catch (_: Exception) {}
                    continue
                }
                if (!isFullyCached(context, key)) {
                    try {
                        cache.removeResource(key)
                        removeUrl(key)
                        removedKeys.add(key)
                    } catch (_: Exception) {}
                }
            }
            if (removedKeys.isNotEmpty()) {
                clearCompletedMarks(removedKeys)
                android.util.Log.i("CacheManager", "已自动清理 ${removedKeys.size} 个异常退出残留的未完成缓存碎片")
            }
        } catch (e: Exception) {
            android.util.Log.e("CacheManager", "清理未完成缓存碎片失败", e)
        }
    }

    /**
     * 检查歌曲在本地是否已有任意音质的 100% 完整缓存。
     * 严格高品质优先：FLAC_24BIT > FLAC > 320K > 128K；命中即返回该音质（供播放层直接用缓存音质播放）。
     */
    fun findCachedQuality(context: Context, song: Song): AudioQuality? {
        val qualities = listOf(
            AudioQuality.FLAC_24BIT,
            AudioQuality.FLAC,
            AudioQuality.QUALITY_320K,
            AudioQuality.QUALITY_128K
        )
        for (q in qualities) {
            val key = songCacheKey(song, q)
            if (isFullyCached(context, key)) {
                val spans = try { getAudioCache(context).getCachedSpans(key) } catch (_: Exception) { null }
                val bytes = spans?.sumOf { it.length } ?: 0L
                if (bytes > 0) {
                    val finalQuality = calibrateQuality(q, bytes)
                    cleanupOtherQualityCaches(context, song, q)
                    saveActualQuality(song.platform.key, song.id, finalQuality)
                    return finalQuality
                }
            }
        }
        val actual = getActualQuality(context, song.platform.key, song.id)
        if (actual != null && isFullyCached(context, songCacheKey(song, actual))) {
            return actual
        }
        return null
    }

    /** 清理同一歌曲名下除保留音质外的其他历史/陈旧缓存分片（跳过活跃播放 key） */
    fun cleanupOtherQualityCaches(context: Context, song: Song, keepQuality: AudioQuality) {
        try {
            val cache = getAudioCache(context)
            val allKeys = try { cache.keys.toSet() } catch (_: Exception) { emptySet() }
            val keepKey = songCacheKey(song, keepQuality)
            val prefix = "${song.platform.key}|${song.id}|"
            val toRemove = mutableSetOf<String>()
            val activeKey = PlayerService.activePlayingCacheKey
            for (k in allKeys) {
                if (k.startsWith(prefix) && k != keepKey) {
                    if (!activeKey.isNullOrBlank() && k == activeKey) continue
                    toRemove.add(k)
                }
            }
            for (k in toRemove) {
                try { cache.removeResource(k) } catch (_: Exception) {}
                removeUrl(k)
            }
            if (toRemove.isNotEmpty()) {
                clearCompletedMarks(toRemove)
            }
        } catch (_: Exception) {}
    }

    /** 记录歌曲的实际音频音质（PlayerService 解码探测到真实格式后写入） */
    fun saveActualQuality(platformKey: String, musicId: String, quality: AudioQuality) {
        try {
            val ctx = appContext ?: return
            val prefs = ctx.getSharedPreferences("lx_song_qualities", Context.MODE_PRIVATE)
            prefs.edit().putString("${platformKey}|${musicId}", quality.name).apply()
        } catch (e: Exception) {
        }
    }

    /** 获取歌曲已记录的实际音质 */
    fun getActualQuality(context: Context, platformKey: String, musicId: String): AudioQuality? {
        return try {
            val prefs = context.getSharedPreferences("lx_song_qualities", Context.MODE_PRIVATE)
            val name = prefs.getString("${platformKey}|${musicId}", null) ?: return null
            runCatching { AudioQuality.valueOf(name) }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 物理体积与码率自愈校准：纠正音源降级（请求 Hi-Res 实际返回 320k）导致的音质虚标
     */
    fun calibrateQuality(candidateQuality: AudioQuality?, bytes: Long): AudioQuality {
        val mb = bytes / (1024.0 * 1024.0)
        return when (candidateQuality) {
            AudioQuality.FLAC_24BIT -> when {
                mb >= 30.0 -> AudioQuality.FLAC_24BIT
                mb >= 12.0 -> AudioQuality.FLAC
                mb >= 5.5 -> AudioQuality.QUALITY_320K
                else -> AudioQuality.QUALITY_128K
            }
            AudioQuality.FLAC -> when {
                mb >= 10.0 -> AudioQuality.FLAC
                mb >= 5.5 -> AudioQuality.QUALITY_320K
                else -> AudioQuality.QUALITY_128K
            }
            AudioQuality.QUALITY_320K -> when {
                mb >= 4.5 -> AudioQuality.QUALITY_320K
                else -> AudioQuality.QUALITY_128K
            }
            AudioQuality.QUALITY_128K -> when {
                mb >= 35.0 -> AudioQuality.FLAC_24BIT
                mb >= 12.0 -> AudioQuality.FLAC
                mb >= 5.5 -> AudioQuality.QUALITY_320K
                else -> AudioQuality.QUALITY_128K
            }
            null -> when {
                mb >= 35.0 -> AudioQuality.FLAC_24BIT
                mb >= 12.0 -> AudioQuality.FLAC
                mb >= 5.5 -> AudioQuality.QUALITY_320K
                else -> AudioQuality.QUALITY_128K
            }
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

    // ==================== 2.9 单曲缓存扫描与管理（本地 Tab 用） ====================

    /** 已缓存歌曲条目信息 */
    data class CachedSongItem(
        val cacheKey: String,
        val musicId: String,
        val platformKey: String,
        val quality: String,
        val sizeBytes: Long,
        val name: String,
        val singer: String,
        val picUrl: String? = null
    )

    /**
     * 解析缓存 key（结构：platformKey|musicId|quality）
     * musicId 可能含特殊字符，取首/尾分隔符精确切分
     */
    data class ParsedCacheKey(
        val platformKey: String,
        val musicId: String,
        val quality: String
    )

    fun parseCacheKey(key: String): ParsedCacheKey? {
        if (key.isBlank()) return null
        val firstPipe = key.indexOf('|')
        if (firstPipe <= 0) return null
        val lastPipe = key.lastIndexOf('|')
        if (lastPipe <= firstPipe) {
            return ParsedCacheKey(key.substring(0, firstPipe), key.substring(firstPipe + 1), "")
        }
        return ParsedCacheKey(
            key.substring(0, firstPipe),
            key.substring(firstPipe + 1, lastPipe),
            key.substring(lastPipe + 1)
        )
    }

    /** 记录歌曲元数据（供本地列表按 cacheKey 逆向还原歌名/歌手/封面） */
    fun saveSongMeta(song: Song) {
        try {
            val ctx = appContext ?: return
            val prefs = ctx.getSharedPreferences("lx_song_metas", Context.MODE_PRIVATE)
            val meta = "${song.name}\t${song.singer}\t${song.picUrl ?: ""}"
            prefs.edit().putString("${song.platform.key}|${song.id}", meta).apply()
        } catch (e: Exception) {
        }
    }

    /**
     * 获取所有已完整缓存的歌曲列表（含占用大小与歌曲信息，按体积降序）
     * 严格过滤：仅返回 100% 完整缓存的歌曲；未完成残片仅跳过，绝不在此物理删除
     * （避免误删后台正在缓冲/播放的音频数据）
     */
    suspend fun getCachedSongs(context: Context): List<CachedSongItem> {
        val distinctMap = mutableMapOf<String, CachedSongItem>()
        try {
            val cache = getAudioCache(context)
            val keys = try { cache.keys.toSet() } catch (e: Exception) { emptySet() }
            val prefs = context.getSharedPreferences("lx_song_metas", Context.MODE_PRIVATE)
            val db = LxMusicDatabase.getDatabase(context)

            for (key in keys) {
                val parsed = parseCacheKey(key) ?: continue
                val platformKey = parsed.platformKey
                val musicId = parsed.musicId
                val quality = parsed.quality

                val spans = try { cache.getCachedSpans(key) } catch (e: Exception) { null }
                val bytes = spans?.sumOf { it.length } ?: 0L
                if (bytes <= 0L) continue
                if (!isFullyCached(context, key)) continue

                var songName: String? = null
                var singer: String? = null
                var picUrl: String? = null

                val cachedMeta = prefs.getString("${platformKey}|${musicId}", null)
                if (!cachedMeta.isNullOrBlank()) {
                    val metaParts = cachedMeta.split("\t")
                    if (metaParts.isNotEmpty()) songName = metaParts[0]
                    if (metaParts.size >= 2) singer = metaParts[1]
                    if (metaParts.size >= 3 && metaParts[2].isNotBlank()) picUrl = metaParts[2]
                }
                if (songName == null) {
                    val fav = db.favoriteDao().getFavoriteByMusicId(musicId)
                    if (fav != null) {
                        songName = fav.musicName
                        singer = fav.artist
                        picUrl = fav.picUrl
                    }
                }
                if (songName == null) {
                    val history = db.playHistoryDao().getPlayHistoryByMusicId(musicId)
                    if (history != null) {
                        songName = history.musicName
                        singer = history.artist
                    }
                }
                if (songName == null) {
                    val item = db.musicItemDao().getMusicItemById(musicId)
                    if (item != null) {
                        songName = item.name
                        singer = item.artist
                        picUrl = item.picUrl
                    }
                }

                val finalName = if (!songName.isNullOrBlank()) songName else "未知歌曲 ($musicId)"
                val finalSinger = if (!singer.isNullOrBlank()) singer else platformKey.uppercase()
                val savedActualQuality = getActualQuality(context, platformKey, musicId)
                val rawCandidateQuality = runCatching { AudioQuality.valueOf(quality) }.getOrNull()
                val calibratedQuality = calibrateQuality(savedActualQuality ?: rawCandidateQuality, bytes)

                val item = CachedSongItem(
                    cacheKey = key,
                    musicId = musicId,
                    platformKey = platformKey,
                    quality = calibratedQuality.name,
                    sizeBytes = bytes,
                    name = finalName,
                    singer = finalSinger,
                    picUrl = picUrl
                )

                // 聚合去重：同歌曲保留体积最大（最高音质）版本，安全淘汰被替代的陈旧版本
                val songKey = "${platformKey}|${musicId}"
                val existing = distinctMap[songKey]
                val activeKey = PlayerService.activePlayingCacheKey
                if (existing == null) {
                    distinctMap[songKey] = item
                } else if (item.sizeBytes > existing.sizeBytes) {
                    if (existing.cacheKey != activeKey) {
                        try { cache.removeResource(existing.cacheKey) } catch (_: Exception) {}
                        removeUrl(existing.cacheKey)
                        clearCompletedMarks(setOf(existing.cacheKey))
                    }
                    distinctMap[songKey] = item
                } else {
                    if (key != activeKey) {
                        try { cache.removeResource(key) } catch (_: Exception) {}
                        removeUrl(key)
                        clearCompletedMarks(setOf(key))
                    }
                }
            }
            return distinctMap.values.sortedByDescending { it.sizeBytes }
        } catch (e: Exception) {
            android.util.Log.e("CacheManager", "扫描已缓存歌曲失败", e)
        }
        return emptyList()
    }

    /** 精准删除单首歌曲的音频缓存及相关记录（含同一首歌的所有音质版本） */
    fun removeCachedSong(context: Context, cacheKey: String) {
        try {
            val parsed = parseCacheKey(cacheKey)
            val cache = getAudioCache(context)
            val allKeys = try { cache.keys.toSet() } catch (_: Exception) { emptySet() }
            val targetKeys = mutableSetOf<String>()
            targetKeys.add(cacheKey)
            if (parsed != null) {
                for (k in allKeys) {
                    val p = parseCacheKey(k)
                    if (p != null && p.platformKey == parsed.platformKey && p.musicId == parsed.musicId) {
                        targetKeys.add(k)
                    }
                }
            }
            for (k in targetKeys) {
                try { cache.removeResource(k) } catch (_: Exception) {}
                removeUrl(k)
            }
            clearCompletedMarks(targetKeys)
            if (parsed != null) {
                try {
                    context.getSharedPreferences("lx_song_qualities", Context.MODE_PRIVATE)
                        .edit()
                        .remove("${parsed.platformKey}|${parsed.musicId}")
                        .apply()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("CacheManager", "删除歌曲缓存失败: $cacheKey", e)
        }
    }

    /** 格式化缓存大小为易读文本（B / KB / MB / GB） */
    fun formatCacheSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.CHINA, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.CHINA, "%.2f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.CHINA, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
