package com.lxmusic.tv.presentation.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lxmusic.tv.presentation.theme.LXPrimary
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 图片内存缓存（LRU，按字节计 24MB）
 * 替换早期「ConcurrentHashMap + 满 200 全清」的简单实现：
 * 全清会导致列表滚动时所有已加载图片瞬间失效、全部重新解码，是 TV 端滚动卡顿的元凶之一。
 */
object ImageCache {
    private const val MAX_MEMORY_BYTES = 24L * 1024 * 1024

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_BYTES.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(url: String): Bitmap? = memoryCache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        if (bitmap.byteCount > MAX_MEMORY_BYTES / 4) return // 单张超大图不入缓存
        memoryCache.put(url, bitmap)
    }
}

/**
 * 图片磁盘缓存（缓存网络原始字节，decode 时再子采样）
 * 避免冷启动后列表图片全部重新下载（无磁盘缓存是 TV 端滚动卡顿的另一元凶）。
 */
object DiskImageCache {
    private const val MAX_FILES = 600

    private fun dir(context: Context): File {
        val d = File(context.cacheDir, "remote_images")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** URL → 缓存文件名（MD5） */
    private fun key(url: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toUInt().toString(16)
        }
    }

    /** 命中返回缓存文件，未命中返回 null */
    fun get(context: Context, url: String): File? {
        val f = File(dir(context), key(url))
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 写入磁盘缓存；文件过多时清理最旧的一半 */
    fun put(context: Context, url: String, bytes: ByteArray) {
        try {
            val d = dir(context)
            // 超过上限：删除最旧一半（按最后修改时间排序）
            if (d.listFiles()?.size ?: 0 > MAX_FILES) {
                d.listFiles()
                    ?.sortedBy { it.lastModified() }
                    ?.take(d.listFiles()!!.size / 2)
                    ?.forEach { it.delete() }
            }
            File(d, key(url)).writeBytes(bytes)
        } catch (e: Exception) {
            // 缓存失败不影响展示
        }
    }

    /** 清空磁盘图片缓存（2.7 设置页「缓存管理」清理入口调用） */
    fun clear(context: Context) {
        try {
            dir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // 忽略
        }
    }
}

/**
 * 轻量网络图片加载组件（2.0 性能优化版）
 * OkHttp 下载 + 磁盘缓存 + LRU 内存缓存 + 子采样解码，对标 Glide 的三级缓存思路：
 * 1. 内存缓存（LRU 24MB）→ 2. 磁盘缓存 → 3. 网络下载
 * 解码时按最大边子采样（inSampleSize），大幅降低 decode 耗时与内存占用
 * （列表小图、封面图 300~680px 均足够，背景大图本身用于模糊处理不需要更高分辨率）。
 *
 * 并发控制：解码用全局信号量限流（低端 TV 上多张图同时解码会抢占 CPU 导致列表滚动掉帧）。
 *
 * @param url 图片地址（为空/加载失败时显示 placeholder 或默认占位）
 * @param contentScale 图片缩放模式
 * @param maxDimension 解码后最大边长（px）。列表小图传小值（如 512）解码更快；默认 1024
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: (@Composable () -> Unit)? = null,
    maxDimension: Int = 1024
) {
    val context = LocalContext.current.applicationContext
    var imageBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            loadFailed = true
            return@LaunchedEffect
        }
        // 1. 内存缓存
        ImageCache.get(url)?.let {
            imageBitmap = it.asImageBitmap()
            return@LaunchedEffect
        }
        try {
            val bytes = withContext(Dispatchers.IO) {
                // 2. 磁盘缓存
                DiskImageCache.get(context, url)?.let { return@withContext it.readBytes() }
                // 3. 网络下载
                val client = RemoteImageClient.httpClient
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                // 按图片域名匹配防盗链 Referer（各平台封面防盗链策略不同）
                when {
                    url.contains("music.126.net") || url.contains("163.com") ->
                        requestBuilder.header("Referer", "https://music.163.com/")
                    url.contains("y.gtimg.cn") || url.contains("qq.com") ->
                        requestBuilder.header("Referer", "https://y.qq.com/")
                    url.contains("kugou.com") ->
                        requestBuilder.header("Referer", "https://www.kugou.com/")
                    url.contains("kuwo.cn") ->
                        requestBuilder.header("Referer", "https://www.kuwo.cn/")
                    url.contains("migu.cn") || url.contains("miguvideo") ->
                        requestBuilder.header("Referer", "https://music.migu.cn/")
                }
                val resp = client.newCall(requestBuilder.build()).execute()
                resp.use {
                    if (it.isSuccessful) it.body?.bytes() ?: ByteArray(0) else ByteArray(0)
                }
            }
            if (bytes.isEmpty()) {
                loadFailed = true
                return@LaunchedEffect
            }
            // 写磁盘缓存（IO 线程）
            withContext(Dispatchers.IO) {
                DiskImageCache.put(context, url, bytes)
            }
            // 子采样解码（IO 线程，全局信号量限流：低端 TV 并发解码打满 CPU 是列表滚动掉帧主因）
            val bmp = withContext(Dispatchers.IO) {
                decodeSemaphore.withPermit {
                    decodeSampled(bytes, maxDimension)
                }
            }
            if (bmp != null) {
                ImageCache.put(url, bmp)
                imageBitmap = bmp.asImageBitmap()
                return@LaunchedEffect
            }
            loadFailed = true
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else if (placeholder != null) {
        placeholder()
    } else {
        // 默认占位
        Box(
            modifier = modifier.background(Color(0xFF16213E)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = contentDescription,
                tint = LXPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 按目标最大边长做 inSampleSize 子采样解码：
 * 先读 bounds，再把最大边缩放到不超过 [maxDim] 的 2 的幂采样倍数，
 * 显著降低解码耗时与内存（大图原尺寸解码在低端 TV 上会明显掉帧）。
 */
private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
    return try {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        var sample = 1
        while (boundsOpts.outWidth / (sample * 2) >= maxDim ||
            boundsOpts.outHeight / (sample * 2) >= maxDim
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        null
    }
}

/**
 * 解码并发信号量：同时最多 2 张图解码。
 * 列表快速滚动时会瞬间组合大量新卡片，若每张图都立即解码，
 * 低端 TV 的 CPU 会被 decode 打满导致主线程卡顿；排队逐个解码对体验影响很小。
 */
private val decodeSemaphore = Semaphore(permits = 2)

/**
 * 远程图片 HTTP 客户端单例
 */
object RemoteImageClient {
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
