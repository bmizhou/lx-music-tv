package com.lxmusic.tv.script

import android.util.Log
import com.lxmusic.tv.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

/**
 * 播放源执行器
 *
 * 参考 lx-music-desktop 的 UserApi 模块实现:
 * 1. 脚本执行后，通过 send(EVENT_NAMES.inited, { sources }) 宣告支持的源
 * 2. 应用通过 on(EVENT_NAMES.request, handler) 注册的 handler 处理请求
 * 3. 请求格式: { source: 'kw', action: 'musicUrl', info: { type: '320k', musicInfo: {...} } }
 * 4. handler 必须返回 Promise，resolve 值为 URL 字符串
 *
 * 支持两种模式:
 * 1. 传统函数调用模式: 脚本定义 search/getMusicUrl/getLyric 等全局函数
 * 2. 事件驱动模式（洛雪音乐标准）: 脚本通过 on(EVENT_NAMES.request, handler) 注册处理器
 *    通过 send(EVENT_NAMES.inited, data) 宣告初始化完成
 */
class SourceExecutor(
    private val javaScriptEngine: JavaScriptEngine
) {

    companion object {
        private const val TAG = "LX-SourceExecutor"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 是否使用事件驱动模式
    private var useEventDrivenModel = false

    // 从 inited 事件中提取的源信息
    private var sourceInfoFromInit: MutableMap<String, Any> = mutableMapOf()

    /**
     * 初始化播放源脚本
     *
     * 流程（参考 lx-music-desktop）:
     * 1. 执行脚本 → 脚本调用 on(EVENT_NAMES.request, handler) 注册处理器
     * 2. 脚本调用 send(EVENT_NAMES.inited, { sources }) 宣告初始化完成
     * 3. 检查 initEventData 和 eventHandlers 判断模式
     */
    suspend fun initializeSource(scriptContent: String): SourceExecutionResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "===== 初始化播放源 =====")
            Log.i(TAG, "脚本长度: ${scriptContent.length}")

            // 执行脚本
            Log.i(TAG, "执行脚本...")
            val result = javaScriptEngine.executeScript(scriptContent)

            when (result) {
                is JavaScriptResult.Success -> {
                    Log.i(TAG, "脚本执行成功")

                    // 等待异步操作完成
                    // 注意：由于我们使用 runBlocking 同步执行 HTTP 请求，
                    // send() 和 on() 应该在脚本执行期间就已经被调用
                    // 但为了安全，还是等待一下
                    kotlinx.coroutines.delay(100)

                    Log.i(TAG, "检查初始化状态...")
                    Log.i(TAG, "  initEventData: ${if (javaScriptEngine.getInitEventData() != null) "已收到" else "未收到"}")
                    Log.i(TAG, "  已注册事件处理器: ${getRegisteredEventNames()}")

                    // 检查是否是事件驱动模式
                    val initEventData = javaScriptEngine.getInitEventData()
                    if (initEventData is org.json.JSONObject) {
                        // 事件驱动模式：脚本已通过 send(EVENT_NAMES.inited, data) 初始化
                        useEventDrivenModel = true
                        parseInitEventData(initEventData)
                        Log.i(TAG, "★ 播放源使用事件驱动模式初始化成功")
                        Log.i(TAG, "  支持的源: ${sourceInfoFromInit.keys}")
                        return@withContext SourceExecutionResult.Success("播放源初始化成功（事件驱动模式）")
                    }

                    // 检查是否有 EVENT_NAMES.request 的处理器注册
                    val requestHandler = javaScriptEngine.getEventHandler("request")
                    if (requestHandler != null) {
                        useEventDrivenModel = true
                        Log.i(TAG, "★ 检测到 request 事件处理器，使用事件驱动模式")
                        return@withContext SourceExecutionResult.Success("播放源初始化成功（事件驱动模式）")
                    }

                    // 传统函数模式：检查 lx 变量
                    Log.d(TAG, "未检测到事件驱动模式，尝试传统函数模式")
                    val lxCheck = javaScriptEngine.getVariable("lx")
                    when (lxCheck) {
                        is JavaScriptResult.Success -> {
                            useEventDrivenModel = false
                            Log.i(TAG, "播放源使用函数调用模式初始化成功")
                            SourceExecutionResult.Success("播放源初始化成功（函数调用模式）")
                        }
                        is JavaScriptResult.Error -> {
                            Log.e(TAG, "播放源初始化失败: ${lxCheck.message}")
                            SourceExecutionResult.Error("播放源初始化失败: ${lxCheck.message}")
                        }
                    }
                }
                is JavaScriptResult.Error -> {
                    Log.e(TAG, "执行播放源脚本失败: ${result.message}")
                    SourceExecutionResult.Error("执行播放源脚本失败: ${result.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化播放源异常", e)
            SourceExecutionResult.Error("初始化播放源时出错: ${e.message}")
        }
    }

    /**
     * 获取已注册的事件名称列表（用于日志）
     */
    private fun getRegisteredEventNames(): String {
        return try {
            val names = mutableListOf<String>()
            if (javaScriptEngine.getEventHandler("request") != null) names.add("request")
            names.joinToString(", ").ifEmpty { "无" }
        } catch (e: Exception) {
            "获取失败"
        }
    }

    /**
     * 解析 inited 事件数据，提取源信息
     *
     * 参考 lx-music-desktop preload.js handleInit:
     * info.sources[source] = {
     *   type: 'music',
     *   actions: ['musicUrl'],
     *   qualitys: ['128k', '320k', 'flac', 'flac24bit'],
     * }
     */
    private fun parseInitEventData(data: org.json.JSONObject) {
        try {
            // 提取 sources 字段
            val sources = data.optJSONObject("sources")
            if (sources != null) {
                val keys = sources.keys()
                for (key in keys) {
                    val sourceData = sources.opt(key)
                    sourceInfoFromInit[key] = sourceData
                    Log.d(TAG, "  源 '$key': $sourceData")
                }
                Log.i(TAG, "解析到 ${sourceInfoFromInit.size} 个源")
            } else {
                Log.w(TAG, "inited 数据中没有 sources 字段")
                Log.w(TAG, "  inited 数据: ${data.toString().take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 inited 数据失败: ${e.message}", e)
        }
    }

    /**
     * 搜索音乐
     */
    suspend fun searchMusic(
        keyword: String,
        page: Int = 1,
        limit: Int = 30
    ): SourceExecutionResult = withContext(Dispatchers.IO) {
        try {
            if (useEventDrivenModel) {
                return@withContext SourceExecutionResult.Error(
                    "当前播放源不支持搜索功能（仅支持获取播放地址），请使用内置搜索"
                )
            }

            val searchResult = javaScriptEngine.callFunction(
                "search",
                arrayOf(keyword, page, limit)
            )

            when (searchResult) {
                is JavaScriptResult.Success -> {
                    val musicList = parseSearchResult(searchResult.resultString)
                    SourceExecutionResult.SearchSuccess(musicList)
                }
                is JavaScriptResult.Error -> {
                    SourceExecutionResult.Error("搜索失败: ${searchResult.message}")
                }
            }
        } catch (e: Exception) {
            SourceExecutionResult.Error("搜索音乐时出错: ${e.message}")
        }
    }

    /**
     * 获取音乐播放URL
     *
     * 参考 lx-music-desktop preload.js handleRequest:
     * - action: 'musicUrl'
     * - source: 'kw'/'kg'/'tx'/'wy'/'mg'/'local'
     * - info: { type: '320k', musicInfo: { songmid: 'xxx', hash: 'xxx' } }
     * - handler 返回 Promise<string> (URL)
     */
    suspend fun getMusicUrl(
        musicId: String,
        quality: AudioQuality = AudioQuality.QUALITY_128K,
        source: String = "kw"
    ): SourceExecutionResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "获取播放URL: musicId=$musicId, quality=${quality.key}")

            if (useEventDrivenModel) {
                return@withContext dispatchMusicUrlRequest(musicId, quality, source)
            }

            // 传统函数模式
            val urlResult = javaScriptEngine.callFunction(
                "getMusicUrl",
                arrayOf(musicId, quality.key)
            )

            when (urlResult) {
                is JavaScriptResult.Success -> {
                    val url = urlResult.resultString
                    if (url.isNotBlank() && url != "undefined" && url != "null") {
                        Log.i(TAG, "获取到播放URL: $url")
                        SourceExecutionResult.UrlSuccess(url)
                    } else {
                        SourceExecutionResult.Error("获取播放URL失败: 返回空URL")
                    }
                }
                is JavaScriptResult.Error -> {
                    SourceExecutionResult.Error("获取播放URL失败: ${urlResult.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取播放URL异常", e)
            SourceExecutionResult.Error("获取播放URL时出错: ${e.message}")
        }
    }

    /**
     * 通过事件驱动模式获取播放URL
     *
     * 参考 lx-music-desktop preload.js handleRequest:
     * events.request.call(context, { source, action, info })
     *
     * @param musicId 格式通常为 "songmid" 或 "hash"（来自搜索结果的 id 字段）
     * @param quality 音质
     * @param source 音乐平台代码（kw/kg/tx/wy/mg），必须与歌曲实际平台匹配，
     *               否则 JS 源会用错误的平台 ID 请求播放地址
     */
    private suspend fun dispatchMusicUrlRequest(
        musicId: String,
        quality: AudioQuality,
        source: String = "kw"
    ): SourceExecutionResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "[dispatchMusicUrlRequest] 开始派发 musicUrl 请求")
            Log.i(TAG, "  musicId: $musicId")
            Log.i(TAG, "  quality: ${quality.key}")
            Log.i(TAG, "  source: $source")

            // 构造 musicInfo 对象
            // 参考 lx-music-desktop: info.musicInfo 包含 songmid, hash 等字段
            // 注意: musicId 可能是拼接格式（QQ: songMid_mediaMid, 酷狗: hash_albumId），
            // 而 JS 源只需要纯 ID（songmid/hash），必须拆分，否则请求拼接串会失败
            val pureId = musicId.substringBefore("_")
            val eventData = JSONObject().apply {
                put("source", source)
                put("action", "musicUrl")
                put("info", JSONObject().apply {
                    put("type", quality.key)
                    put("musicInfo", JSONObject().apply {
                        put("songmid", pureId)
                        put("hash", pureId)
                    })
                })
            }

            Log.d(TAG, "[dispatchMusicUrlRequest] 事件数据: $eventData")

            // 触发 request 事件
            val result = javaScriptEngine.dispatchEvent("request", eventData)

            when (result) {
                is JavaScriptResult.Success -> {
                    val url = result.resultString
                    Log.i(TAG, "[dispatchMusicUrlRequest] ★ 获取到播放URL: $url")
                    if (url.isNotBlank() && url != "undefined" && url != "null") {
                        SourceExecutionResult.UrlSuccess(url)
                    } else {
                        SourceExecutionResult.Error("获取播放URL失败: 返回空URL")
                    }
                }
                is JavaScriptResult.Error -> {
                    Log.e(TAG, "[dispatchMusicUrlRequest] 获取播放URL失败: ${result.message}")
                    SourceExecutionResult.Error("获取播放URL失败: ${result.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[dispatchMusicUrlRequest] 异常", e)
            SourceExecutionResult.Error("获取播放URL时出错: ${e.message}")
        }
    }

    /**
     * 获取歌词
     *
     * 参考 lx-music-desktop:
     * - 事件驱动模式下向源派发 { action: 'lyric', source, info: { musicInfo } } 请求，
     *   支持歌词的源返回 LRC 文本；不支持的源返回错误，由调用方 fallback 内置 API
     */
    suspend fun getLyric(musicId: String): SourceExecutionResult = withContext(Dispatchers.IO) {
        try {
            if (useEventDrivenModel) {
                // 构造 lyric 请求事件（参考 lx-music handleRequest）
                val eventData = JSONObject().apply {
                    put("source", "kw")
                    put("action", "lyric")
                    put("info", JSONObject().apply {
                        put("musicInfo", JSONObject().apply {
                            put("songmid", musicId)
                            put("hash", musicId)
                        })
                    })
                }
                val result = javaScriptEngine.dispatchEvent("request", eventData)
                return@withContext when (result) {
                    is JavaScriptResult.Success -> {
                        val lyric = result.resultString
                        if (lyric.isNotBlank() && lyric != "undefined" && lyric != "null") {
                            SourceExecutionResult.LyricSuccess(lyric)
                        } else {
                            SourceExecutionResult.Error("源返回空歌词")
                        }
                    }
                    is JavaScriptResult.Error -> {
                        SourceExecutionResult.Error("播放源不支持歌词: ${result.message}")
                    }
                }
            }

            val lyricResult = javaScriptEngine.callFunction(
                "getLyric",
                arrayOf(musicId)
            )

            when (lyricResult) {
                is JavaScriptResult.Success -> {
                    SourceExecutionResult.LyricSuccess(lyricResult.resultString)
                }
                is JavaScriptResult.Error -> {
                    SourceExecutionResult.Error("获取歌词失败: ${lyricResult.message}")
                }
            }
        } catch (e: Exception) {
            SourceExecutionResult.Error("获取歌词时出错: ${e.message}")
        }
    }

    /**
     * 获取专辑封面
     */
    suspend fun getPicUrl(musicId: String): SourceExecutionResult = withContext(Dispatchers.IO) {
        try {
            if (useEventDrivenModel) {
                return@withContext SourceExecutionResult.Error("当前播放源不支持获取封面")
            }

            val picResult = javaScriptEngine.callFunction(
                "getPicUrl",
                arrayOf(musicId)
            )

            when (picResult) {
                is JavaScriptResult.Success -> {
                    SourceExecutionResult.PicSuccess(picResult.resultString)
                }
                is JavaScriptResult.Error -> {
                    SourceExecutionResult.Error("获取专辑封面失败: ${picResult.message}")
                }
            }
        } catch (e: Exception) {
            SourceExecutionResult.Error("获取专辑封面时出错: ${e.message}")
        }
    }

    /**
     * 解析搜索结果JSON
     */
    private fun parseSearchResult(jsonStr: String): List<MusicItem> {
        return try {
            val jsonObject = json.parseToJsonElement(jsonStr) as? JsonObject
            val musicListJson = jsonObject?.get("musicList")?.toString()
            if (musicListJson != null) {
                json.decodeFromString<List<MusicItem>>(musicListJson)
            } else {
                json.decodeFromString<List<MusicItem>>(jsonStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析搜索结果失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        useEventDrivenModel = false
        sourceInfoFromInit.clear()
        javaScriptEngine.release()
    }
}

/**
 * 源执行结果
 */
sealed class SourceExecutionResult {
    data class Success(val message: String) : SourceExecutionResult()
    data class Error(val message: String) : SourceExecutionResult()
    data class SearchSuccess(val musicList: List<MusicItem>) : SourceExecutionResult()
    data class UrlSuccess(val url: String) : SourceExecutionResult()
    data class LyricSuccess(val lyric: String) : SourceExecutionResult()
    data class PicSuccess(val picUrl: String) : SourceExecutionResult()
}
