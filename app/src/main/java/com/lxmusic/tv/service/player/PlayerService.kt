package com.lxmusic.tv.service.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.lxmusic.tv.data.cache.CacheManager
import com.google.android.exoplayer2.upstream.TransferListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 音乐播放服务
 * 基于ExoPlayer的播放引擎
 * 前台服务（mediaPlayback）：退到后台/返回桌面时音乐继续播放
 */
class PlayerService : Service() {

    companion object {
        private const val TAG = "PlayerService"
        private const val CHANNEL_ID = "lx_music_playback"
        private const val NOTIFICATION_ID = 1001
    }

    private var exoPlayer: ExoPlayer? = null
    private val binder = LocalBinder()
    private var stateListener: OnPlayerStateListener? = null

    /**
     * 播放器内部Binder
     */
    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    /**
     * 简化的音乐信息（用于ViewModel传参）
     */
    data class MusicInfo(
        val id: String,
        val title: String,
        val artist: String,
        val url: String,
        val picUrl: String? = null,
        // 2.7 音频缓存 key（平台|歌曲id|音质）：缓存与 URL 解耦，
        // JS 源返回的 URL 变化时仍能命中本地缓存，不重新下载；为 null 时回退用 URL 做 key
        val cacheKey: String? = null
    )

    /**
     * 播放状态监听器接口
     */
    interface OnPlayerStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onPositionChanged(position: Long, duration: Long)
        fun onPlaybackCompleted()
    }

    private var currentPosition: Long = 0
    private var totalDuration: Long = 0
    // 2.7 当前播放歌曲的音频缓存 key（歌曲维度，与 URL 解耦；play() 时设置）
    @Volatile
    private var currentCacheKey: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[lifecycle] onCreate")
        createNotificationChannel()
        startForegroundService()
        initPlayer()
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放控制通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 启动前台服务（媒体播放类型）
     */
    private fun startForegroundService() {
        try {
            val notification = buildNotification("LX Music TV", "音乐播放中")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败: ${e.message}")
        }
    }

    /**
     * 构建播放通知
     */
    private fun buildNotification(title: String, content: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[lifecycle] onStartCommand flags=$flags startId=$startId")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "[lifecycle] onDestroy, exoPlayer=${if (exoPlayer != null) "非空" else "null"}")
        releasePlayer()
        super.onDestroy()
    }

    /**
     * 初始化播放器
     */
    private fun initPlayer() {
        // 浏览器 UA：部分音源 URL API（如玉宁熙的 metingapi）带反盗链，
        // 非浏览器 UA（ExoPlayer/OkHttp 默认）直接返回 418 I'm a Teapot
        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", browserUserAgent)
                        .build()
                )
            }
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        // 包装 DataSource：渐进式首请求强制追加 Range: bytes=0-
        // 背景：部分 CDN（如酷我 car-er.kuwo.cn，长青音源的跳转目标）对不带 Range 的
        // 普通 GET 返回 0 字节导致播放器一直缓冲；PC 端浏览器/HTML5 播放器默认带 Range
        // 所以 PC 正常而本应用卡住。seek 请求由 ExoPlayer 自带 Range，不受影响。
        val dataSourceFactory = DataSource.Factory {
            RangeHeaderDataSource(okHttpDataSourceFactory.createDataSource())
        }

        // 2.7 播放缓存：CacheDataSource 包装网络源，播放时边播边写入 SimpleCache，
        // 同一首歌第二次播放直接读本地缓存——全程只下载一次，无需「播放完成后再下载」。
        // 缓存 key 用「歌曲维度」（由 play() 设置 currentCacheKey，见 MusicInfo.cacheKey），
        // 与 URL 解耦：JS 源返回的 URL 变化也能命中缓存，不重新下载。
        // 播放失败（URL 过期/网络错误）时忽略缓存走网络（FLAG_IGNORE_CACHE_ON_ERROR）。
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(CacheManager.getAudioCache(this))
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setCacheKeyFactory { dataSpec ->
                currentCacheKey ?: dataSpec.uri.toString()
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d(TAG, "播放器准备就绪")
                                totalDuration = duration
                                stateListener?.onPositionChanged(currentPosition, totalDuration)
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "播放结束")
                                stateListener?.onPlaybackCompleted()
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "缓冲中...")
                            }
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "播放器空闲")
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        stateListener?.onPlaybackStateChanged(isPlaying)
                        if (isPlaying) {
                            startPositionTracking()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "播放错误: ${error.message}")
                    }
                })
            }
    }

    /**
     * 包装 DataSource：仅对首请求（position=0 且 length=UNSET）追加 Range: bytes=0-。
     * 后续 seek 请求（position>0 或 length 已定）由 ExoPlayer 自动生成正确的 Range 头，
     * 本包装直接透传，不影响拖动进度。
     */
    private class RangeHeaderDataSource(private val delegate: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val spec = if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
                // 自定义头优先于自动 Range：初始请求自动 Range 为 null，此头会生效
                dataSpec.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
            } else {
                dataSpec
            }
            return delegate.open(spec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            delegate.close()
        }
    }

    /**
     * 开始位置追踪
     */
    private fun startPositionTracking() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        currentPosition = player.currentPosition
                        totalDuration = player.duration
                        stateListener?.onPositionChanged(currentPosition, totalDuration)
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }
        handler.post(runnable)
    }

    /**
     * 播放音乐
     */
    fun play(musicInfo: MusicInfo) {
        Log.d(TAG, "[play] 收到播放请求: ${musicInfo.title}, exoPlayer=${if (exoPlayer != null) "非空" else "null"}")
        try {
            // 播放前确保播放器已初始化（退出重建后 exoPlayer 可能为 null）
            if (exoPlayer == null) {
                Log.d(TAG, "[play] exoPlayer 为 null，重新 initPlayer()")
                initPlayer()
            }
            // 2.7 设置音频缓存 key（歌曲维度）：URL 变化也能命中本地缓存
            currentCacheKey = musicInfo.cacheKey
            val mediaItem = MediaItem.fromUri(musicInfo.url)
            exoPlayer?.apply {
                setMediaItem(mediaItem)
                prepare()
                play()
            }
            // 更新前台通知标题为当前歌曲
            updateNotification(musicInfo.title, musicInfo.artist)
            Log.d(TAG, "开始播放: ${musicInfo.title} - ${musicInfo.artist}, URL: ${musicInfo.url}")
        } catch (e: Exception) {
            Log.e(TAG, "播放失败: ${e.message}")
        }
    }

    /**
     * 停止播放并彻底关闭前台服务（退出应用时调用）。
     * 区别于 [stop]：额外释放播放器、移除前台通知、停止自身服务，
     * 避免退出后服务仍常驻导致重进应用播放异常。
     */
    fun stopAndShutdown() {
        Log.d(TAG, "[stopAndShutdown] 开始, exoPlayer=${if (exoPlayer != null) "非空" else "null"}")
        try {
            exoPlayer?.stop()
        } catch (_: Exception) {}
        releasePlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "[stopAndShutdown] 已释放播放器并移除前台通知，即将 stopSelf")
        stopSelf()
    }

    /**
     * 更新前台服务通知（当前歌曲信息）
     */
    private fun updateNotification(title: String, content: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(title, content))
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}")
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * 继续播放
     */
    fun resume() {
        exoPlayer?.play()
    }

    /**
     * 停止播放
     */
    fun stop() {
        exoPlayer?.stop()
    }

    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    /**
     * 获取当前播放位置
     */
    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    /**
     * 获取总时长
     */
    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0
    }

    /**
     * 获取播放状态
     */
    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    /**
     * 设置播放状态监听器
     */
    fun setOnPlayerStateListener(listener: OnPlayerStateListener?) {
        this.stateListener = listener
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    /**
     * 释放播放器
     */
    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
