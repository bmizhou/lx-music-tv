package com.lxmusic.tv.data.source

import android.content.Context
import android.util.Log
import com.lxmusic.tv.data.model.*
import com.lxmusic.tv.data.storage.DataStoreManager
import com.lxmusic.tv.script.JavaScriptEngine
import com.lxmusic.tv.script.SourceExecutor
import com.lxmusic.tv.script.JavaScriptResult
import com.lxmusic.tv.script.SourceExecutionResult
import com.lxmusic.tv.service.http.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 播放源管理器实现
 * 负责管理播放源的加载、存储和切换
 */
class SourceManagerImpl(
    private val context: Context
) : SourceManager {

    companion object {
        private const val TAG = "LX-SourceManager"
    }

    private val sources = CopyOnWriteArrayList<MusicSource>()
    private val parser = ScriptParser()
    private var enabledSourceId: String? = null

    // JavaScript引擎和源执行器
    private var javaScriptEngine: JavaScriptEngine? = null
    private var sourceExecutor: SourceExecutor? = null

    // 当前加载的播放源脚本内容
    private var currentScriptContent: String? = null

    // 数据存储管理器
    private var dataStoreManager: DataStoreManager? = null

    // 数据变更通知
    private val _sourcesFlow = MutableStateFlow<List<MusicSource>>(emptyList())
    val sourcesFlow: StateFlow<List<MusicSource>> = _sourcesFlow.asStateFlow()

    /**
     * 初始化管理器
     */
    suspend fun init(dataStoreManager: DataStoreManager) {
        this.dataStoreManager = dataStoreManager
        // 从Room数据库加载播放源
        loadSourcesFromStorage()
        // 初始化JavaScript引擎
        initializeJavaScriptEngine()

        // 自动加载当前启用的播放源（若存在），避免重启后 currentScriptContent 为空
        // 导致搜索/播放提示"没有加载的播放源"
        val enabled = getEnabledSource()
        if (enabled != null && currentScriptContent == null) {
            val loadResult = loadAndExecuteSource(enabled.id)
            Log.i(TAG, "启动时自动加载播放源: ${enabled.name} -> ${if (loadResult is SourceExecutionResult.Success) "成功" else "失败: ${(loadResult as? SourceExecutionResult.Error)?.message}"}")
        }
    }

    /**
     * 初始化JavaScript引擎
     */
    private suspend fun initializeJavaScriptEngine() {
        try {
            javaScriptEngine = JavaScriptEngine()
            javaScriptEngine?.initialize()
            sourceExecutor = SourceExecutor(javaScriptEngine!!)
            Log.i(TAG, "JavaScript 引擎初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "JavaScript 引擎初始化失败: ${e.message}", e)
        }
    }

    /**
     * 从Room数据库加载播放源
     */
    private suspend fun loadSourcesFromStorage() {
        try {
            val ds = dataStoreManager ?: return
            val savedSources = ds.getAllSources().first()
            sources.clear()
            // 按导入时间（createdAt）升序：仅保持列表展示顺序（管理页/接口返回）；
            // 播放优先级 = 启用顺序（enabledAt），见 getEnabledSources
            sources.addAll(savedSources.sortedBy { it.createdAt })
            // 恢复启用的源
            enabledSourceId = savedSources.find { it.isEnabled }?.id
            _sourcesFlow.value = sources.toList()
            Log.i(TAG, "从数据库加载了 ${sources.size} 个播放源")
        } catch (e: Exception) {
            Log.e(TAG, "加载播放源失败: ${e.message}", e)
        }
    }

    /**
     * 获取所有播放源
     */
    override fun getAllSources(): List<MusicSource> {
        return sources.toList()
    }

    /**
     * 根据ID获取播放源
     */
    override fun getSourceById(id: String): MusicSource? {
        return sources.find { it.id == id }
    }

    /**
     * 添加播放源
     */
    override fun addSource(source: MusicSource): Boolean {
        return try {
            // 检查是否已存在同名源
            val existingIndex = sources.indexOfFirst { it.name == source.name }
            if (existingIndex >= 0) {
                // 更新已存在的源：**保留原 id 与 createdAt**（优先级顺序 = 导入顺序，重传同名源不改变位置，
                // 否则新 UUID 会导致数据库按 id REPLACE 插新行、旧行残留，重启后重复源 + 顺序混乱）
                val existing = sources[existingIndex]
                val updated = source.copy(
                    id = existing.id,
                    createdAt = existing.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                sources[existingIndex] = updated
                // 异步保存到数据库（按原 id 覆盖，无残留行）
                kotlinx.coroutines.runBlocking {
                    dataStoreManager?.saveSource(updated)
                }
            } else {
                // 添加新源
                sources.add(source)
                // 异步保存到数据库
                kotlinx.coroutines.runBlocking {
                    dataStoreManager?.saveSource(source)
                }
            }
            _sourcesFlow.value = sources.toList()
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存播放源失败: ${e.message}", e)
            false
        }
    }

    /**
     * 删除播放源
     */
    override fun deleteSource(id: String): Boolean {
        val removed = sources.removeAll { it.id == id }
        if (removed) {
            if (enabledSourceId == id) {
                enabledSourceId = null
            }
            kotlinx.coroutines.runBlocking {
                dataStoreManager?.deleteSource(id)
            }
            _sourcesFlow.value = sources.toList()
        }
        return removed
    }

    /**
     * 设置播放源启用状态（2.8 启用时记录 enabledAt：优先级 = 启用顺序）
     */
    override fun setSourceEnabled(id: String, enabled: Boolean): Boolean {
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return false

        val source = sources[index]
        val now = System.currentTimeMillis()
        sources[index] = source.copy(
            isEnabled = enabled,
            enabledAt = if (enabled) now else null,
            updatedAt = now
        )

        if (enabled) {
            enabledSourceId = id
        } else if (enabledSourceId == id) {
            enabledSourceId = null
        }

        kotlinx.coroutines.runBlocking {
            dataStoreManager?.setSourceEnabled(id, enabled)
        }
        _sourcesFlow.value = sources.toList()
        return true
    }

    /**
     * 获取当前启用的播放源
     */
    override fun getEnabledSource(): MusicSource? {
        return sources.find { it.id == enabledSourceId }
    }

    /**
     * 从JS脚本创建播放源
     */
    fun createSourceFromScript(scriptContent: String): MusicSource? {
        val parseResult = parser.parse(scriptContent)
        return when (parseResult) {
            is ParseResult.Success -> {
                MusicSource(
                    id = java.util.UUID.randomUUID().toString(),
                    name = parseResult.metadata.name,
                    description = parseResult.metadata.description,
                    version = parseResult.metadata.version,
                    author = parseResult.metadata.author,
                    homepage = parseResult.metadata.homepage,
                    scriptContent = scriptContent
                )
            }
            is ParseResult.Error -> null
        }
    }

    /**
     * 获取所有启用的播放源（2.8 按启用顺序排序：先启用的优先级最高。
     * 排序键 enabledAt，旧数据（数据库迁移前）为 null 时用 updatedAt 兜底——启用操作会更新 updatedAt）
     */
    fun getEnabledSources(): List<MusicSource> {
        return sources.filter { it.isEnabled }
            .sortedWith(compareBy { it.enabledAt ?: it.updatedAt })
    }

    // ========== 播放源平台配置 ==========
    // 每个源可配置对哪些平台生效（SharedPreferences 存逗号分隔的 platform key；空 = 全部平台）
    // 例：源1 配置 "tx,wy" → 只对 QQ/网易云生效；源2 未配置 → 对所有平台生效

    private fun sourcePlatformsPrefs() =
        context.getSharedPreferences("source_platforms", Context.MODE_PRIVATE)

    /**
     * 获取源启用的平台 key 集合（空集合 = 全部平台）
     */
    override fun getSourcePlatforms(sourceId: String): Set<String> {
        val raw = sourcePlatformsPrefs().getString(sourceId, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * 设置源启用的平台 key 集合（空集合 = 全部平台）
     */
    override fun setSourcePlatforms(sourceId: String, platforms: Set<String>): Boolean {
        sourcePlatformsPrefs().edit().putString(sourceId, platforms.joinToString(",")).apply()
        return true
    }

    /**
     * 根据平台 key 选择应使用的启用播放源：
     * 1. 优先：配置了该平台的源
     * 2. 其次：未配置平台（全平台生效）的启用源
     */
    fun getSourceForPlatform(platformKey: String): MusicSource? {
        return getSourcesForPlatform(platformKey).firstOrNull()
    }

    /**
     * 根据平台 key 返回按优先级排序的启用源列表。
     * 优先级 = 启用顺序（先启用的高优先级，见 getEnabledSources）；
     * **平台配置只做过滤，不改变优先级顺序**：
     * 仅返回「配置了该平台」或「未配置平台（全平台生效）」的启用源。
     * 播放失败时按此顺序逐个尝试下一个源（自动切换）。
     */
    fun getSourcesForPlatform(platformKey: String): List<MusicSource> {
        val enabled = getEnabledSources()
        if (enabled.isEmpty()) return emptyList()
        // 保持 sources 导入顺序，仅过滤出对该平台生效的源（配置了该平台 或 未配置=全平台）
        return enabled.filter {
            val platforms = getSourcePlatforms(it.id)
            platforms.isEmpty() || platforms.contains(platformKey)
        }
    }

    /**
     * 根据平台获取播放源
     */
    fun getSourcesByPlatform(platform: MusicPlatform): List<MusicSource> {
        return sources.filter { source ->
            val parseResult = parser.parse(source.scriptContent)
            if (parseResult is ParseResult.Success) {
                parseResult.platforms.containsKey(platform)
            } else {
                false
            }
        }
    }

    /**
     * 更新播放源
     */
    fun updateSource(id: String, updates: (MusicSource) -> MusicSource): Boolean {
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return false

        val updatedSource = updates(sources[index]).copy(updatedAt = System.currentTimeMillis())
        sources[index] = updatedSource
        kotlinx.coroutines.runBlocking {
            dataStoreManager?.updateSource(updatedSource)
        }
        _sourcesFlow.value = sources.toList()
        return true
    }

    /**
     * 导入播放源文件
     */
    fun importSourceFile(fileContent: String, fileName: String): ImportResult {
        return try {
            val source = createSourceFromScript(fileContent)
            if (source != null) {
                if (addSource(source)) {
                    ImportResult.Success("播放源 '${source.name}' 导入成功")
                } else {
                    ImportResult.Error("保存播放源失败")
                }
            } else {
                ImportResult.Error("解析播放源文件失败: 脚本格式不正确")
            }
        } catch (e: Exception) {
            ImportResult.Error("导入失败: ${e.message}")
        }
    }

    /**
     * 导出播放源为JS文件内容
     */
    fun exportSource(id: String): String? {
        val source = getSourceById(id) ?: return null
        return source.scriptContent
    }

    /**
     * 导入结果
     */
    sealed class ImportResult {
        data class Success(val message: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /**
     * 加载并执行播放源脚本
     */
    suspend fun loadAndExecuteSource(sourceId: String): SourceExecutionResult {
        val source = getSourceById(sourceId) ?: return SourceExecutionResult.Error("播放源不存在")

        return try {
            // 如果脚本内容相同就不需要重新加载
            if (currentScriptContent == source.scriptContent && sourceExecutor?.let { true } == true) {
                return SourceExecutionResult.Success("播放源已加载")
            }

            // 执行新的脚本
            val result = sourceExecutor?.initializeSource(source.scriptContent)
                ?: SourceExecutionResult.Error("源执行器未初始化")

            when (result) {
                is SourceExecutionResult.Success -> {
                    currentScriptContent = source.scriptContent
                    result
                }
                else -> result
            }
        } catch (e: Exception) {
            SourceExecutionResult.Error("加载播放源失败: ${e.message}")
        }
    }

    /**
     * 使用当前播放源搜索音乐
     */
    suspend fun searchMusicWithSource(
        keyword: String,
        page: Int = 1,
        limit: Int = 30
    ): SourceExecutionResult {
        val executor = sourceExecutor ?: return SourceExecutionResult.Error("源执行器未初始化")

        // 未加载源时自动加载（App 重启后 currentScriptContent 为空）
        val loadError = autoLoadEnabledSource()
        if (loadError != null) return loadError

        return executor.searchMusic(keyword, page, limit)
    }

    /**
     * 获取音乐播放URL
     * @param musicId 歌曲ID
     * @param quality 音质
     * @param source 歌曲平台 code（kw/kg/tx/wy/mg），按平台配置选择播放源
     * @param sourceId 指定要尝试的源 ID（多源自动切换用）；为 null 时按平台配置选源
     */
    suspend fun getMusicUrlWithSource(
        musicId: String,
        quality: AudioQuality = AudioQuality.QUALITY_128K,
        source: String = "kw",
        sourceId: String? = null
    ): SourceExecutionResult {
        val executor = sourceExecutor ?: return SourceExecutionResult.Error("源执行器未初始化")

        // 按平台选择应使用的播放源（支持多源按平台配置）；
        // 指定了 sourceId 时（多源切换）直接尝试该源，否则取平台最优源
        val targetSource = sourceId?.let { getSourceById(it) } ?: getSourceForPlatform(source)
        if (targetSource != null) {
            // 选中的源与当前已加载的源不同 → 切换加载
            if (currentScriptContent != targetSource.scriptContent) {
                val loadResult = loadAndExecuteSource(targetSource.id)
                if (loadResult is SourceExecutionResult.Error) {
                    Log.w(TAG, "按平台切换到播放源 ${targetSource.name} 失败: ${loadResult.message}，回退当前源")
                }
            }
        } else {
            // 没有匹配的源，回退到当前已加载的源
            val loadError = autoLoadEnabledSource()
            if (loadError != null) return loadError
        }

        return executor.getMusicUrl(musicId, quality, source)
    }

    /**
     * 获取歌词
     */
    suspend fun getLyricWithSource(musicId: String): SourceExecutionResult {
        val executor = sourceExecutor ?: return SourceExecutionResult.Error("源执行器未初始化")

        // 未加载源时自动加载
        val loadError = autoLoadEnabledSource()
        if (loadError != null) return loadError

        return executor.getLyric(musicId)
    }

    /**
     * 获取专辑封面
     */
    suspend fun getPicUrlWithSource(musicId: String): SourceExecutionResult {
        val executor = sourceExecutor ?: return SourceExecutionResult.Error("源执行器未初始化")

        // 未加载源时自动加载
        val loadError = autoLoadEnabledSource()
        if (loadError != null) return loadError

        return executor.getPicUrl(musicId)
    }

    /**
     * 如果当前没有加载播放源脚本，自动加载启用的播放源
     * @return 加载失败时返回错误结果；已加载或加载成功返回 null
     */
    private suspend fun autoLoadEnabledSource(): SourceExecutionResult? {
        if (currentScriptContent != null) return null
        val enabled = getEnabledSource() ?: return SourceExecutionResult.Error("没有加载的播放源")
        val result = loadAndExecuteSource(enabled.id)
        return if (result is SourceExecutionResult.Success) null else result
    }

    /**
     * 释放资源
     */
    fun release() {
        sourceExecutor?.release()
        javaScriptEngine?.release()
        sourceExecutor = null
        javaScriptEngine = null
        currentScriptContent = null
    }
}
