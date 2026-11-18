package com.lxmusic.tv.data.source

import android.content.Context
import android.util.Log
import com.lxmusic.tv.data.model.*
import com.lxmusic.tv.data.storage.DataStoreManager
import com.lxmusic.tv.network.HttpClient
import com.lxmusic.tv.script.JavaScriptEngine
import com.lxmusic.tv.script.SourceExecutor
import com.lxmusic.tv.script.JavaScriptResult
import com.lxmusic.tv.script.SourceExecutionResult
import com.lxmusic.tv.service.http.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val remoteHttpClient = HttpClient()
    // 远程自动更新轮询任务的独立作用域，release() 时取消，避免泄漏
    private val remoteUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteUpdateJob: Job? = null

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

        startRemoteAutoUpdateLoop()
    }

    /**
     * 远程源自动更新：仅在 App 每次启动（init）时触发一次，强制刷新所有配置了
     * remoteUrl 的源（不做周期轮询，不判断是否到期）。拉取失败只记录错误、
     * 保留旧脚本内容（见 refreshRemoteSource）。
     */
    private fun startRemoteAutoUpdateLoop() {
        remoteUpdateJob?.cancel()
        remoteUpdateJob = remoteUpdateScope.launch {
            try {
                refreshAllRemoteSources()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "App启动时远程播放源强制刷新异常: ${e.message}", e)
            }
        }
    }

    /**
     * 强制刷新所有配置了 remoteUrl 的源（忽略是否到期），用于 App 启动时。
     */
    private suspend fun refreshAllRemoteSources() {
        val targets = sources.filter { !it.remoteUrl.isNullOrBlank() }
        for (source in targets) {
            refreshRemoteSource(source.id)
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
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
     * @param remoteUrl 非空时标记该源为远程自动更新源（见 refreshRemoteSource）
     */
    fun createSourceFromScript(
        scriptContent: String,
        remoteUrl: String? = null,
        remoteUpdateIntervalMinutes: Int = 360
    ): MusicSource? {
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
                    scriptContent = scriptContent,
                    remoteUrl = remoteUrl,
                    remoteUpdateIntervalMinutes = remoteUpdateIntervalMinutes,
                    lastRemoteUpdateAt = if (remoteUrl != null) System.currentTimeMillis() else null
                )
            }
            is ParseResult.Error -> {
                // 记录失败原因（设置-异常日志/Web /log 可查 logcat，便于排查导入失败）
                Log.w(TAG, "播放源解析失败: ${parseResult.message}")
                null
            }
        }
    }

    /**
     * 将网络层的失败响应转换为可读的错误文案：
     * code == -1 时是连接/解析异常（DNS 失败、超时等），message 里带了具体原因；
     * 其余情况是服务端返回的 HTTP 状态码。
     */
    private fun describeDownloadFailure(resp: com.lxmusic.tv.network.HttpResponse): String {
        return if (resp.code == -1) {
            "下载失败: ${resp.message.ifBlank { "无法连接到服务器" }}"
        } else {
            "下载失败: HTTP ${resp.code}"
        }
    }

    /**
     * 新增一个远程自动更新播放源：立即从 url 拉取一次脚本并保存，之后由后台轮询周期刷新。
     */
    suspend fun addRemoteSource(url: String, intervalMinutes: Int = 360): RemoteUpdateResult {
        val content = try {
            val resp = remoteHttpClient.get(url)
            if (!resp.isSuccess || resp.body.isBlank()) {
                return RemoteUpdateResult.Error(describeDownloadFailure(resp))
            }
            resp.body
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return RemoteUpdateResult.Error("下载失败: ${e.message}")
        }

        val source = createSourceFromScript(content, remoteUrl = url, remoteUpdateIntervalMinutes = intervalMinutes)
            ?: return RemoteUpdateResult.Error("解析失败: 脚本格式不正确")

        return if (addSource(source)) {
            RemoteUpdateResult.Success("远程播放源 '${source.name}' 添加成功")
        } else {
            RemoteUpdateResult.Error("保存播放源失败")
        }
    }

    /**
     * 将已存在的播放源关联到一个远程地址（后台会周期拉取更新）；url 传 null 解除关联。
     */
    fun setSourceRemote(id: String, url: String?, intervalMinutes: Int = 360): Boolean {
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return false
        val now = System.currentTimeMillis()
        val updated = sources[index].copy(
            remoteUrl = url?.takeIf { it.isNotBlank() },
            remoteUpdateIntervalMinutes = intervalMinutes,
            // 清空关联时一起清空上次更新时间；重新关联时不立即当作"已更新"，让下一轮轮询真正拉取
            lastRemoteUpdateAt = if (url.isNullOrBlank()) null else sources[index].lastRemoteUpdateAt,
            lastRemoteUpdateError = null,
            updatedAt = now
        )
        sources[index] = updated
        kotlinx.coroutines.runBlocking {
            dataStoreManager?.updateSource(updated)
        }
        _sourcesFlow.value = sources.toList()
        return true
    }

    /**
     * 立即刷新一个远程源：成功则覆盖 scriptContent 并清空错误；
     * 失败（网络错误或解析失败）时保留旧脚本内容不变，只记录 lastRemoteUpdateError。
     */
    suspend fun refreshRemoteSource(id: String): RemoteUpdateResult {
        val source = getSourceById(id) ?: return RemoteUpdateResult.Error("播放源不存在")
        val url = source.remoteUrl
        if (url.isNullOrBlank()) return RemoteUpdateResult.Error("该播放源未配置远程地址")

        val now = System.currentTimeMillis()
        val result = try {
            val resp = remoteHttpClient.get(url)
            if (!resp.isSuccess || resp.body.isBlank()) {
                RemoteUpdateResult.Error(describeDownloadFailure(resp))
            } else {
                when (val parsed = parser.parse(resp.body)) {
                    is ParseResult.Success -> {
                        updateSource(id) {
                            it.copy(
                                name = parsed.metadata.name,
                                description = parsed.metadata.description,
                                version = parsed.metadata.version,
                                author = parsed.metadata.author,
                                homepage = parsed.metadata.homepage,
                                scriptContent = resp.body,
                                lastRemoteUpdateAt = now,
                                lastRemoteUpdateError = null
                            )
                        }
                        RemoteUpdateResult.Success("播放源 '${parsed.metadata.name}' 已更新")
                    }
                    is ParseResult.Error -> RemoteUpdateResult.Error("解析失败: ${parsed.message}")
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            RemoteUpdateResult.Error("下载失败: ${e.message}")
        }

        if (result is RemoteUpdateResult.Error) {
            // 失败：只打时间戳+错误信息，不动 scriptContent（继续用旧脚本，即"更新失败使用旧的"）
            Log.w(TAG, "远程播放源刷新失败 id=$id url=$url: ${result.message}")
            updateSource(id) { it.copy(lastRemoteUpdateAt = now, lastRemoteUpdateError = result.message) }
        }
        return result
    }

    /**
     * 远程更新结果
     */
    sealed class RemoteUpdateResult {
        data class Success(val message: String) : RemoteUpdateResult()
        data class Error(val message: String) : RemoteUpdateResult()
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
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
        sourceId: String? = null,
        // 2.8 歌曲信息透传给 JS 源（按歌名搜索的源需要，缺失报「没歌名搜不了」）
        songName: String = "",
        singer: String = ""
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
                    // 2.8 目标源脚本加载失败：直接判为失败返回（上层回调失败信息并切下一个源）。
                    // 原「回退当前源」会拿旧脚本假扮该源执行 → 返回同一 URL、无失败提示、
                    // 播放反复失败（多源切换时后几个源全部无效）
                    Log.w(TAG, "播放源 ${targetSource.name} 加载失败，判为失败: ${loadResult.message}")
                    return loadResult
                }
            }
        } else {
            // 没有匹配的源，回退到当前已加载的源
            val loadError = autoLoadEnabledSource()
            if (loadError != null) return loadError
        }

        return executor.getMusicUrl(musicId, quality, source, songName, singer)
    }

    /**
     * 获取歌词
     */
    suspend fun getLyricWithSource(
        musicId: String,
        // 2.8 补平台/歌名/歌手（JS 源歌词需要）
        source: String = "kw",
        songName: String = "",
        singer: String = ""
    ): SourceExecutionResult {
        val executor = sourceExecutor ?: return SourceExecutionResult.Error("源执行器未初始化")

        // 未加载源时自动加载
        val loadError = autoLoadEnabledSource()
        if (loadError != null) return loadError

        return executor.getLyric(musicId, source, songName, singer)
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
        remoteUpdateJob?.cancel()
        sourceExecutor?.release()
        javaScriptEngine?.release()
        sourceExecutor = null
        javaScriptEngine = null
        currentScriptContent = null
    }

    // ========== 供 HttpServer（非挂起上下文）调用的阻塞包装 ==========
    // HttpServer 的请求处理方法是同步的 Response 返回值，风格上与本类里其它用
    // kotlinx.coroutines.runBlocking 包装 DB 写入的方法一致（如 addSource/deleteSource）。

    /**
     * 获取播放源的远程地址（供 Web 管理页展示）
     */
    override fun getSourceRemoteUrl(id: String): String? = getSourceById(id)?.remoteUrl

    /**
     * 设置/解除播放源的远程自动更新地址
     */
    override fun setSourceRemoteUrl(id: String, url: String?, intervalMinutes: Int): Boolean =
        setSourceRemote(id, url, intervalMinutes)

    /**
     * 立即刷新一个远程源（阻塞直到完成），返回 (是否成功, 提示信息)
     */
    override fun refreshRemoteSourceBlocking(id: String): Pair<Boolean, String> = runBlocking {
        when (val result = refreshRemoteSource(id)) {
            is RemoteUpdateResult.Success -> true to result.message
            is RemoteUpdateResult.Error -> false to result.message
        }
    }

    /**
     * 新增远程自动更新播放源（阻塞直到首次拉取完成），返回 (是否成功, 提示信息)
     */
    override fun addRemoteSourceBlocking(url: String, intervalMinutes: Int): Pair<Boolean, String> = runBlocking {
        when (val result = addRemoteSource(url, intervalMinutes)) {
            is RemoteUpdateResult.Success -> true to result.message
            is RemoteUpdateResult.Error -> false to result.message
        }
    }
}
