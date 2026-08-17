package com.lxmusic.tv

import android.app.Application
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.database.LxMusicDatabase
import com.lxmusic.tv.data.source.SourceManagerImpl
import com.lxmusic.tv.data.storage.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用程序类
 * 负责初始化全局单例
 */
class LXMusicApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var database: LxMusicDatabase
        private set

    lateinit var dataStoreManager: DataStoreManager
        private set

    lateinit var sourceManager: SourceManagerImpl
        private set

    override fun onCreate() {
        super.onCreate()

        // 初始化缓存管理器（音频 SimpleCache / URL 持久化缓存依赖 applicationContext）
        CacheManager.init(this)

        // 2.8 异常日志导出：接管未捕获异常，崩溃堆栈写入 lx_logs/crash.log（设置页可经 Web /log 导出）
        com.lxmusic.tv.data.log.LogExporter.init(this)

        // 初始化数据库
        database = LxMusicDatabase.getDatabase(this)

        // 初始化数据存储管理器
        dataStoreManager = DataStoreManager(this)

        // 初始化播放源管理器
        sourceManager = SourceManagerImpl(this)

        // 异步加载已保存的播放源并初始化JS引擎
        applicationScope.launch(Dispatchers.IO) {
            sourceManager.init(dataStoreManager)
        }
    }
}
