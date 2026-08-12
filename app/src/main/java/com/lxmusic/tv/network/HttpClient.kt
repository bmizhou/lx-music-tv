package com.lxmusic.tv.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import java.nio.charset.Charset

/**
 * HTTP客户端
 * 用于执行网络请求，支持GET和POST方法
 *
 * @param defaultConnectTimeoutMs 默认连接超时（毫秒）。默认全局 5s（快速失败防挂死）；
 *        响应较大的浏览数据接口（QQ 榜单等）由 BrowseDataService 构造时传 15s 覆盖，
 *        JS 源播放链路（lx.request）在调用处显式传 15s/30s。
 * @param defaultReadTimeoutMs 默认读取超时（毫秒），同上。
 * @param relaxHostnameVerification 是否放宽 HTTPS 主机名校验（默认 false）。
 *        部分平台 CDN 接口域名解析到的节点证书主机名不匹配（如 mobilecdn.kugou.com 解析到
 *        腾讯云 CDN、证书为 *.cdn.myqcloud.com，SAN 不含该域名），但证书链本身有效；
 *        仅浏览数据服务（BrowseDataService）开启此选项，其它请求保持严格校验。
 */
class HttpClient(
    private val defaultConnectTimeoutMs: Int = CONNECT_TIMEOUT,
    private val defaultReadTimeoutMs: Int = READ_TIMEOUT,
    private val relaxHostnameVerification: Boolean = false
) {
    
    companion object {
        private const val TAG = "HttpClient"
        // 全部平台接口统一 5s 超时（JS 源播放链路 lx.request 走独立长超时，见 JavaScriptEngine）
        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 5000
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        // 2.8 响应体大小上限（32MB）：本客户端只服务 JSON 接口（搜索/歌词/URL 解析，正常均 KB 级）；
        // JS 源请求可能被接口异常返回音频流（实测 metingapi br=999 返回 ~55MB FLAC 流），
        // 无条件 readBytes 转 String 会 OOM 崩溃。播放音频走 ExoPlayer 流式下载（CacheDataSource），
        // 不经本客户端，高音质播放不受此上限影响。
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    }
    
    /**
     * 发送GET请求
     * @param urlStr 请求URL
     * @param headers 请求头
     * @return 响应结果
     */
    suspend fun get(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = defaultConnectTimeoutMs,
        readTimeoutMs: Int = defaultReadTimeoutMs
    ): HttpResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        
        try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                // ⚠️ 恢复 HTTP keep-alive 连接复用（08-08 实测对照：08-06 版本复用连接 ~50ms 秒加载；
                // 08-07 误加 Connection: close 后每次请求重走 TCP+TLS 握手，全平台慢 2~5 倍）。
                // 若个别平台再出现陈旧连接挂死，改为仅对该平台禁用复用，勿全局关闭。
                
                // 设置自定义请求头
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            
            // 放宽主机名校验（仅构造时开启的服务使用）：在触发连接前设置才生效
            if (relaxHostnameVerification && connection is HttpsURLConnection) {
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            val responseBody = readResponse(connection)
            val responseHeaders = connection.headerFields
            
            HttpResponse(
                code = responseCode,
                message = responseMessage ?: "",
                body = responseBody,
                headers = responseHeaders.mapValues { it.value.joinToString(", ") }
            )
        } catch (e: Exception) {
            HttpResponse(
                code = -1,
                message = "请求失败: ${e.message}",
                body = "",
                headers = emptyMap(),
                error = e
            )
        } finally {
            connection?.disconnect()
        }
    }
    
    /**
     * 发送POST请求
     * @param urlStr 请求URL
     * @param body 请求体
     * @param contentType Content-Type
     * @param headers 请求头
     * @return 响应结果
     */
    suspend fun post(
        urlStr: String,
        body: String = "",
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = defaultConnectTimeoutMs,
        readTimeoutMs: Int = defaultReadTimeoutMs
    ): HttpResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        
        try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", contentType)
                // 与 GET 一致：恢复 keep-alive 连接复用（08-08 实测对照，见 get 注释）
                
                // 设置自定义请求头
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            
            // 放宽主机名校验（仅构造时开启的服务使用）：在触发连接前设置才生效
            if (relaxHostnameVerification && connection is HttpsURLConnection) {
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            
            // 写入请求体
            if (body.isNotEmpty()) {
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }
            
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            val responseBody = readResponse(connection)
            val responseHeaders = connection.headerFields
            
            HttpResponse(
                code = responseCode,
                message = responseMessage ?: "",
                body = responseBody,
                headers = responseHeaders.mapValues { it.value.joinToString(", ") }
            )
        } catch (e: Exception) {
            HttpResponse(
                code = -1,
                message = "请求失败: ${e.message}",
                body = "",
                headers = emptyMap(),
                error = e
            )
        } finally {
            connection?.disconnect()
        }
    }
    
    /**
     * 发送表单POST请求
     * @param urlStr 请求URL
     * @param params 表单参数
     * @param headers 请求头
     * @return 响应结果
     */
    suspend fun postForm(
        urlStr: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        // 将参数编码为表单格式
        val formBody = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        
        return post(
            urlStr = urlStr,
            body = formBody,
            contentType = "application/x-www-form-urlencoded",
            headers = headers
        )
    }
    
    /**
     * 读取响应内容（带编码自动检测）
     *
     * 大部分接口返回 UTF-8，但 QQ 音乐歌单接口（fcg_get_diss_by_tag.fcg）不带
     * utf8=1 参数时返回 GBK；而 client_music_search_songlist 接口主体是 UTF-8
     * 但个别字段混入 GBK 坏字节。策略：
     * 1. UTF-8 严格解码成功 → 直接返回
     * 2. 失败 → UTF-8 容错解码（坏字节替换为 U+FFFD），若坏字节很少
     *    （≤20 且 ≤1%）说明主体是 UTF-8（如 QQ 歌单搜索接口），用容错结果
     * 3. 坏字节大量存在 → 整体回退 GBK（纯 GBK 老接口）
     */
    private fun readResponse(connection: HttpURLConnection): String {
        val inputStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        // 2.8 预检：Content-Length 超限或音频/视频流 → 直接放弃读取（JS 源期望 JSON URL，流无意义且省内存）
        runCatching {
            val contentLength = connection.contentLength
            if (contentLength > MAX_RESPONSE_BYTES) {
                Log.w(TAG, "响应 Content-Length=${contentLength}B 超过 ${MAX_RESPONSE_BYTES / 1024 / 1024}MB 上限，放弃读取: ${connection.url}")
                return ""
            }
            val contentType = connection.contentType ?: ""
            if (contentType.startsWith("audio/") || contentType.startsWith("video/")) {
                Log.w(TAG, "响应为媒体流（$contentType），非 JSON 接口，放弃读取: ${connection.url}")
                return ""
            }
        }

        // 2.8 流式读取 + 上限截断（防接口异常返回超大 body 导致 OOM）；
        // 宁可返回截断内容（解析失败、源切换兜底），也不崩溃。
        val bytes = inputStream.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (total + n > MAX_RESPONSE_BYTES) {
                    Log.w(TAG, "响应体超过 ${MAX_RESPONSE_BYTES / 1024 / 1024}MB 上限已截断: ${connection.url}")
                    val remain = (MAX_RESPONSE_BYTES - total).coerceAtLeast(0)
                    if (remain > 0) buffer.write(chunk, 0, remain)
                    break
                }
                buffer.write(chunk, 0, n)
                total += n
            }
            buffer.toByteArray()
        }
        return decodeBody(bytes)
    }

    /**
     * 解码响应字节：UTF-8 严格解码 → 容错（少量坏字节）→ GBK 回退
     */
    private fun decodeBody(bytes: ByteArray): String {
        // 1. UTF-8 严格解码
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // 忽略，进入容错判定
        }

        // 2. UTF-8 容错解码（坏字节替换为 U+FFFD），统计坏字节数量
        val lenient = String(bytes, Charsets.UTF_8)
        val badCount = lenient.count { it == '\uFFFD' }

        // 坏字节很少（≤20 且 ≤1%）→ 主体是 UTF-8（QQ 歌单搜索接口混入少量 GBK 字节）
        if (badCount <= 20 && badCount * 100 <= bytes.size) {
            return lenient
        }

        // 3. 大量坏字节 → 整体回退 GBK（纯 GBK 老接口）
        return try {
            String(bytes, Charset.forName("GBK"))
        } catch (e2: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }
    
    /**
     * 编码URL参数
     */
    fun encodeUrl(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
}

/**
 * HTTP响应数据类
 */
data class HttpResponse(
    val code: Int,
    val message: String,
    val body: String,
    val headers: Map<String, String>,
    val error: Exception? = null
) {
    val isSuccess: Boolean
        get() = code in 200..299 && error == null
    
    val isRedirect: Boolean
        get() = code in 300..399
}