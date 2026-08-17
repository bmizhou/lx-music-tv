package com.lxmusic.tv.script

import android.util.Base64
import android.util.Log
import com.lxmusic.tv.network.HttpClient
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JavaScript 引擎（QuickJS 实现）
 *
 * 参考 lx-music-mobile（洛雪音乐安卓版）的引擎方案：
 * 桌面端用 Electron+V8、移动端用 QuickJS，均原生支持 ES2020 语法，
 * 因此音源脚本无需 Rhino 时代的语法转换（?. / ?? / for-of / 展开 / async 等原生可用），
 * 解密循环速度比 Rhino 解释器快 1~2 个数量级（混淆源启动从"分钟级卡死"降到秒级）。
 *
 * 架构：
 * - 全部 JS 操作在专用单线程（engineExecutor）执行，QuickJSContext 线程安全由其保证
 * - preload（QuickJSPreload）提供 globalThis.lx 运行时（request 异步队列/on/send/setTimeout）
 * - JS -> Java：__lx_native_call__(action, dataJson)（HTTP 发起/inited/timer/日志）
 * - Java -> JS：__lx_dispatch__（派发 request 事件，返回 holder 轮询）、
 *              __lx_on_response__（注入 HTTP/timer 结果并驱动 Promise job）
 * - dispatchEvent 在引擎线程外轮询：注入异步结果 -> evaluate 驱动 pending jobs -> 检查 holder
 */
class JavaScriptEngine {

    companion object {
        private const val TAG = "LX-JSEngine"
        /** 事件派发超时（毫秒）：15s。聚合源内部逐个子源尝试，断网时子源连接快速失败（秒级），
         *  15s 足够遍历多个子源；在线时正常源通常 10s 内出结果。超时即判该源失败切下一个源。
         *  单源 30s × 3 个源 = 90s 无反馈等待不可接受，故取 15s */
        private const val DISPATCH_TIMEOUT_MS = 15_000L
        /** 等待循环步进（毫秒） */
        private const val WAIT_STEP_MS = 5L
        /** executeScript 后等待 inited 的最长时长（顶层版本检查可能异步发 inited） */
        private const val INIT_DRAIN_MS = 3_000L
        /** 2.8 引擎单任务看门狗超时（毫秒）：脚本死循环/阻塞卡死引擎线程时超时并重建引擎；
         *  与 DISPATCH_TIMEOUT_MS 一致取 15s（看门狗 ≥ 派发超时会无限等待，< 派发超时则误杀慢请求） */
        private const val ENGINE_TASK_TIMEOUT_MS = 15_000L
        /** JS 源请求（lx.request）连接/读取超时：JS 源加载歌曲播放 URL 可能较慢，
         *  不纳入「平台接口统一 5s」范围，保持长超时 */
        private const val JS_CONNECT_TIMEOUT_MS = 15_000
        private const val JS_READ_TIMEOUT_MS = 30_000
    }

    private val httpClient = HttpClient()

    /** IO 作用域：HTTP 请求在 IO 线程跑，完成后结果放入 pendingHttp，不碰 QuickJS */
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 引擎专用单线程（QuickJSContext 必须单线程访问，跨线程会抛异常）；var：卡死时重建 */
    @Volatile
    private var engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lx-quickjs-engine").apply { isDaemon = true }
    }

    // ===== 仅引擎线程访问 =====
    private var context: QuickJSContext? = null
    private var global: JSObject? = null

    // ===== 跨线程共享状态 =====
    /** HTTP 完成结果：key -> 响应 JSON（等待引擎线程注入 JS） */
    private val pendingHttp = ConcurrentHashMap<String, JSONObject>()
    /** 定时器：timerId -> 到期时间戳 */
    private val pendingTimers = ConcurrentHashMap<Long, Long>()
    /** 已取消的定时器 id */
    private val clearedTimers = ConcurrentHashMap.newKeySet<Long>()
    /** 2.8 在途请求数（JS 侧 lx.request 已发出、尚未注入响应的数量）。
     *  仅 drain/初始化阶段使用：防止「请求刚发出、Java 尚未登记 pendingHttp」时
     *  runDrainLoop 误判空闲提前退出，导致异步注册型音源（如六音）的响应无人注入、
     *  request 处理器永不注册。请求发出 +1，响应注入完成 -1。 */
    private val activeRequestCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** 脚本 send(EVENT_NAMES.inited, data) 的数据（JSON 字符串） */
    @Volatile
    private var initEventDataJson: String? = null

    @Volatile
    private var isReady = false

    init {
        // 加载 QuickJS 原生库
        try {
            QuickJSLoader.init()
        } catch (e: Throwable) {
            Log.e(TAG, "QuickJSLoader.init 失败: ${e.message}", e)
        }
    }

    // ===== 引擎线程调度 =====

    /**
     * 在引擎线程执行任务并挂起等待结果。
     * 2.8 看门狗：任务超时（脚本死循环/阻塞卡死引擎线程）→ 重建引擎并抛错，
     * 避免 suspendCancellableCoroutine 永久不恢复（getMusicUrl 等整个流程冻结）。
     */
    private suspend fun <T> onEngineThread(block: () -> T): T = suspendCancellableCoroutine { cont ->
        val future = engineExecutor.submit<T> {
            try {
                block()
            } catch (t: Throwable) {
                throw t
            }
        }
        cont.invokeOnCancellation { future.cancel(true) }
        try {
            cont.resume(future.get(ENGINE_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            Log.e(TAG, "引擎线程任务超时（疑似脚本死循环/阻塞），重建引擎")
            rebuildEngine()
            cont.resumeWithException(JavaScriptEngineException("引擎执行超时（${ENGINE_TASK_TIMEOUT_MS / 1000}s）"))
        } catch (e: InterruptedException) {
            cont.resumeWithException(e)
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        }
    }

    /**
     * 2.8 引擎线程卡死（任务超时）后重建：丢弃卡死的旧线程与旧 context，换新单线程执行器。
     * 旧 context 由卡死的引擎线程持有、无法安全销毁 → 丢弃引用（每次卡死泄漏一次，可接受）；
     * 下一次 executeScript 在新线程上 createContext 重新初始化，引擎自愈。
     */
    private fun rebuildEngine() {
        try {
            context = null
            global = null
            engineExecutor.shutdownNow()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "关闭旧引擎线程失败: ${e.message}")
        }
        engineExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "lx-quickjs-engine").apply { isDaemon = true }
        }
        Log.w(TAG, "引擎已重建（新线程）")
    }

    /** 在引擎线程执行任务并阻塞等待（用于非 suspend 的 getEventHandler 等） */
    private fun <T> onEngineThreadBlocking(block: () -> T): T {
        val future = engineExecutor.submit<T> { block() }
        return try {
            future.get(15, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.e(TAG, "引擎线程阻塞任务超时，重建引擎")
            rebuildEngine()
            throw e
        }
    }

    // ===== 生命周期 =====

    /**
     * 初始化引擎（幂等）
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            QuickJSLoader.init()
            isReady = true
            Log.d(TAG, "QuickJS 引擎初始化完成")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            isReady = false
            Log.e(TAG, "QuickJS 引擎初始化失败: ${e.message}", e)
        }
    }

    /**
     * 执行播放源脚本
     *
     * 每次执行都重建 QuickJSContext（同 scope 重复顶层 const 会抛 redeclaration），
     * 并注入 preload 运行时，随后执行源脚本。
     */
    suspend fun executeScript(scriptContent: String): JavaScriptResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "===== QuickJS 执行播放源脚本 =====")
            Log.i(TAG, "脚本长度: ${scriptContent.length}")

            // 重置共享状态
            resetState()

            onEngineThread {
                // 重建 context（防止 const/let 重复声明）
                destroyContext()
                createContext()

                // 1. 注入 preload 运行时
                context?.evaluate(QuickJSPreload.SCRIPT, "lx-preload.js")
                Log.d(TAG, "preload 运行时注入完成")

                // 2. 填充 currentScriptInfo（部分音源如"玉宁熙"会校验版本/名称，缺失报初始化失败）
                val meta = parseScriptMetadata(scriptContent)
                meta.put("rawScript", scriptContent)
                val setupFn = global?.getJSFunction("__lx_setup__")
                setupFn?.call(meta.toString())
                Log.d(TAG, "currentScriptInfo: name=${meta.optString("name")}, version=${meta.optString("version")}")

                // 3. 执行源脚本（QuickJS 原生支持 ES2020，无需语法转换）
                try {
                    context?.evaluate(scriptContent, "lx-music-source")
                } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                    // 脚本顶层抛错但已注册 request handler / 已发 inited 时视为初始化成功。
                    // 参考 lx-music-mobile QuickJS.java: catch 后 if (inited) return ""。
                    // 部分混淆源（如玉宁熙）在注册与 send(inited) 之后还有自校验，校验失败会 throw，
                    // 此时事件处理器已就绪，应容忍抛错继续使用。
                    val registered = global?.getJSFunction("__lx_has_handler__")
                        ?.call("request") as? Boolean ?: false
                    if (initEventDataJson != null || registered) {
                        Log.w(TAG, "源脚本执行抛错（已注册 request 处理器/已发 inited，按洛雪规则视为初始化成功）: ${e.message}")
                    } else {
                        throw e
                    }
                }
                Log.d(TAG, "源脚本执行完成")
            }

            // 4. drain：等顶层异步（版本检查回调里 send inited）完成，或收到 inited / 超时
            runDrainLoop(INIT_DRAIN_MS)

            Log.i(TAG, "脚本执行成功")
            JavaScriptResult.Success(result = null, resultString = "脚本执行成功")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "执行脚本失败: ${e.message}", e)
            JavaScriptResult.Error(message = "执行脚本失败: ${e.message}", exception = e)
        }
    }

    /**
     * 获取脚本 send(EVENT_NAMES.inited, ...) 的数据
     */
    fun getInitEventData(): Any? {
        val json = initEventDataJson ?: return null
        return try {
            JSONObject(json)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "解析 inited 数据失败: ${e.message}")
            null
        }
    }

    /**
     * 查询事件处理器是否已注册（非空即已注册）
     */
    fun getEventHandler(eventName: String): Any? {
        return try {
            onEngineThreadBlocking {
                val g = global ?: return@onEngineThreadBlocking null
                val checkFn = g.getJSFunction("__lx_has_handler__")
                if (checkFn == null) {
                    null
                } else {
                    val registered = checkFn.call(eventName)
                    if (registered is Boolean && registered) Unit else null
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "查询事件处理器失败: ${e.message}")
            null
        }
    }

    /**
     * 调用脚本全局函数（传统函数模式源）
     */
    suspend fun callFunction(
        functionName: String,
        args: Array<out Any> = emptyArray()
    ): JavaScriptResult = withContext(Dispatchers.IO) {
        try {
            val result = onEngineThread {
                val g = global ?: throw JavaScriptEngineException("JavaScript引擎未初始化")
                val fn = g.getJSFunction(functionName)
                if (fn == null) {
                    Log.w(TAG, "函数 '$functionName' 不存在或不是函数")
                    null
                } else {
                    fn.call(*args)
                }
            }
            if (result == null) {
                JavaScriptResult.Error("函数 '$functionName' 不存在或不是函数")
            } else {
                val resultString = result.toString()
                Log.d(TAG, "函数 '$functionName' 返回: ${resultString.take(200)}")
                JavaScriptResult.Success(result = result, resultString = resultString)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "调用函数 '$functionName' 失败: ${e.message}", e)
            JavaScriptResult.Error("调用函数 '$functionName' 失败: ${e.message}", e)
        }
    }

    /**
     * 派发事件（事件驱动模式核心）
     *
     * 流程：
     * 1. 引擎线程调用 __lx_dispatch__ 触发 events.request(data)，返回 holder{done,value,error}
     * 2. 在引擎线程外轮询：
     *    a. 注入已完成的 HTTP 响应 / 到期的定时器（__lx_on_response__，引擎线程执行）
     *    b. evaluate("0") 驱动 QuickJS 的 pending Promise jobs
     *    c. 检查 holder.done
     * 3. handler 内部所有异步 await（lx.request/setTimeout）都由上述注入-驱动闭环推进
     */
    suspend fun dispatchEvent(
        eventName: String,
        eventData: Any
    ): JavaScriptResult = withContext(Dispatchers.IO) {
        if (eventName != "request") {
            return@withContext JavaScriptResult.Error("不支持的事件: $eventName")
        }
        try {
            val dataJson = eventData.toString()

            // 1. 触发 handler，拿到 holder
            val holder = onEngineThread {
                val g = global ?: throw JavaScriptEngineException("JavaScript引擎未初始化")
                val dispatchFn = g.getJSFunction("__lx_dispatch__")
                    ?: throw JavaScriptEngineException("preload 运行时未加载")
                dispatchFn.call("request", dataJson) as? JSObject
            } ?: throw JavaScriptEngineException("事件派发失败：handler 返回为空")

            // 2. 轮询等待
            val deadline = System.currentTimeMillis() + DISPATCH_TIMEOUT_MS
            var result: JavaScriptResult? = null
            while (System.currentTimeMillis() < deadline) {
                // a. 注入异步结果（HTTP / timer），内部会驱动 pending jobs
                injectResponses()
                injectTimers()

                // b. 检查 holder
                val done = onEngineThread {
                    holder.getBooleanProperty("done") ?: false
                }
                if (done) {
                    val value = onEngineThread { holder.getProperty("value") }
                    val error = onEngineThread { holder.getProperty("error") }
                    val errorStr = error?.toString()
                    if (errorStr != null && errorStr.isNotBlank() && errorStr != "null") {
                        Log.e(TAG, "[dispatchEvent] handler 返回错误: $errorStr")
                        result = JavaScriptResult.Error(errorStr)
                    } else {
                        val v = value?.toString() ?: ""
                        Log.d(TAG, "[dispatchEvent] handler 返回: ${v.take(200)}")
                        result = JavaScriptResult.Success(result = value, resultString = v)
                    }
                    break
                }

                // c. 驱动 pending jobs + 让出线程
                onEngineThread { context?.evaluate("0", "drain") }
                Thread.sleep(WAIT_STEP_MS)
            }

            result ?: JavaScriptResult.Error("播放源响应超时（${DISPATCH_TIMEOUT_MS / 1000}s）")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "[dispatchEvent] 异常: ${e.message}", e)
            JavaScriptResult.Error("事件派发失败: ${e.message}")
        }
    }

    /**
     * 获取全局变量（传统函数模式判断 lx 是否存在用）
     */
    suspend fun getVariable(variableName: String): JavaScriptResult = withContext(Dispatchers.IO) {
        try {
            val result = onEngineThread {
                val g = global ?: throw JavaScriptEngineException("JavaScript引擎未初始化")
                g.getProperty(variableName)
            }
            if (result == null) {
                JavaScriptResult.Error("变量 '$variableName' 不存在")
            } else {
                JavaScriptResult.Success(result = result, resultString = result.toString())
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            JavaScriptResult.Error("获取变量 '$variableName' 失败: ${e.message}", e)
        }
    }

    /**
     * 设置全局变量
     */
    suspend fun setVariable(variableName: String, value: Any) = withContext(Dispatchers.IO) {
        try {
            onEngineThread {
                val g = global ?: throw JavaScriptEngineException("JavaScript引擎未初始化")
                val ctx = context ?: throw JavaScriptEngineException("JavaScript引擎未初始化")
                ctx.setProperty(g, variableName, value)
            }
            Unit
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "设置变量 '$variableName' 失败: ${e.message}", e)
        }
    }

    /**
     * 释放引擎
     */
    fun release() {
        try {
            onEngineThreadBlocking {
                destroyContext()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "释放引擎异常: ${e.message}")
        }
        resetState()
        isReady = false
    }

    fun isReady(): Boolean = isReady

    // ===== 内部实现 =====

    /** 重置跨线程共享状态 */
    private fun resetState() {
        initEventDataJson = null
        pendingHttp.clear()
        pendingTimers.clear()
        clearedTimers.clear()
        activeRequestCount.set(0)
    }

    /** 销毁当前 context（仅引擎线程调用） */
    private fun destroyContext() {
        try {
            context?.destroy()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "销毁 context 异常: ${e.message}")
        }
        context = null
        global = null
    }

    /** 创建新 context 并注入 Java 桥（仅引擎线程调用） */
    private fun createContext() {
        val ctx = QuickJSContext.create()
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(info: String) {
                Log.i("LX-JSConsole", info ?: "")
            }

            override fun info(info: String) {
                Log.i("LX-JSConsole", info ?: "")
            }

            override fun warn(info: String) {
                Log.w("LX-JSConsole", info ?: "")
            }

            override fun error(info: String) {
                Log.e("LX-JSConsole", info ?: "")
            }
        })
        // 内存沙箱：上限 128MB，防止音源脚本内存失控
        try {
            ctx.setMemoryLimit(128 * 1024 * 1024)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "设置内存限制失败: ${e.message}")
        }

        val g = ctx.getGlobalObject()

        // JS -> Java 桥（HTTP 发起 / inited / 定时器 / 日志 / AES / MD5）
        // 返回 handleNativeCall 的结果：同步型 action（aes_encrypt/md5）返回结果字符串给 JS，
        // 异步型（request/inited/timer）返回 null（与旧行为一致）
        g.setProperty("__lx_native_call__", JSCallFunction { args ->
            handleNativeCall(args)
        })

        context = ctx
        global = g
        Log.d(TAG, "QuickJS context 创建完成（内存上限 128MB）")
    }

    /**
     * 处理 JS -> Java 调用（在引擎线程的调用栈内，必须立即返回，不能阻塞）
     */
    private fun handleNativeCall(args: Array<out Any>): String? {
        return try {
            val action = args.getOrNull(0)?.toString() ?: ""
            val dataJson = args.getOrNull(1)?.toString() ?: "{}"
            when (action) {
                // 发起 HTTP 请求：登记并让 IO 协程执行（不阻塞引擎线程）
                "request" -> {
                    // 2.8 在途请求计数：drain 循环据此等待异步注册型音源的响应
                    activeRequestCount.incrementAndGet()
                    val data = JSONObject(dataJson)
                    launchHttpRequest(data)
                    null
                }
                // 脚本初始化完成：记录 inited 数据
                "inited" -> {
                    initEventDataJson = dataJson
                    Log.i(TAG, "★ 收到 inited 事件: ${dataJson.take(200)}")
                    null
                }
                // 注册定时器
                "schedule_timer" -> {
                    val data = JSONObject(dataJson)
                    val id = data.optLong("id")
                    val delay = data.optLong("delay", 0)
                    pendingTimers[id] = System.currentTimeMillis() + delay
                    null
                }
                // 取消定时器
                "clear_timer" -> {
                    val data = JSONObject(dataJson)
                    clearedTimers.add(data.optLong("id"))
                    null
                }
                // 源更新提示
                "update_alert" -> {
                    Log.i(TAG, "源更新提示: ${dataJson.take(200)}")
                    null
                }
                // 日志
                "log" -> {
                    try {
                        val data = JSONObject(dataJson)
                        Log.i("LX-JSConsole", "${data.optString("level")}: ${data.optString("message")}")
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                        Log.i("LX-JSConsole", dataJson)
                    }
                    null
                }
                // 2.8 utils.crypto.aesEncrypt：AES-128-CBC(PKCS7)/ECB(PKCS5) 加密，
                // 入参 data/key/iv 均为 base64，返回 base64 密文（洛雪 utils 兼容）
                "aes_encrypt" -> {
                    val data = JSONObject(dataJson)
                    aesEncryptBase64(
                        dataB64 = data.optString("data"),
                        keyB64 = data.optString("key"),
                        ivB64 = data.optString("iv"),
                        mode = data.optString("mode", "cbc")
                    )
                }
                // 2.8 utils.crypto.md5：MD5 十六进制（洛雪移动版先 encodeURIComponent 在 JS 侧完成）
                "md5" -> {
                    val data = JSONObject(dataJson)
                    md5Hex(data.optString("str"))
                }
                else -> {
                    Log.w(TAG, "未知 native 调用: $action")
                    null
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "handleNativeCall 异常: ${e.message}", e)
            null
        }
    }

    /**
     * 2.8 AES-128 加密（洛雪 utils.crypto.aesEncrypt 的 Java 实现）。
     * 入参均为 base64（JS 侧 dataToB64 转换），返回 base64 密文。
     * mode=cbc → AES/CBC/PKCS7Padding（需 16 字节 IV）；mode=ecb → AES/ECB/PKCS5Padding。
     */
    private fun aesEncryptBase64(dataB64: String, keyB64: String, ivB64: String, mode: String): String? {
        return try {
            val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
            if (keyBytes.size != 16) throw IllegalArgumentException("AES-128 密钥须为 16 字节，实际 ${keyBytes.size}")
            val dataBytes = Base64.decode(dataB64, Base64.DEFAULT)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val cipher: Cipher = if (mode == "ecb") {
                Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, keySpec) }
            } else {
                val ivBytes = Base64.decode(ivB64, Base64.DEFAULT)
                if (ivBytes.size != 16) throw IllegalArgumentException("AES-CBC IV 须为 16 字节，实际 ${ivBytes.size}")
                Cipher.getInstance("AES/CBC/PKCS7Padding").apply {
                    init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
                }
            }
            Base64.encodeToString(cipher.doFinal(dataBytes), Base64.NO_WRAP)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "aes_encrypt 失败: ${e.message}")
            null
        }
    }

    /** 2.8 MD5 十六进制（洛雪 utils.crypto.md5，入参已由 JS 侧 encodeURIComponent） */
    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }

    /**
     * 发起 HTTP 请求（IO 协程执行，结果放入 pendingHttp 等待引擎线程注入）
     */
    private fun launchHttpRequest(data: JSONObject) {        val key = data.optString("key")
        val url = data.optString("url")
        val method = data.optString("method", "get").uppercase()
        val body = data.optString("body", "")
        val headers = mutableMapOf<String, String>()
        val headersObj = data.optJSONObject("headers")
        headersObj?.keys()?.forEach { keyName -> headers[keyName] = headersObj.optString(keyName) }

        if (url.isBlank()) {
            val resp = JSONObject().apply { put("key", key); put("error", "URL不能为空") }
            pendingHttp[key] = resp
            return
        }

        ioScope.launch {
            try {
                Log.d(TAG, "[lx.request] $method $url")
                val result = when (method) {
                    // JS 源请求走长超时（播放 URL 获取可能较慢），不纳入平台接口统一 5s
                    "POST" -> httpClient.post(url, body, headers = headers, connectTimeoutMs = JS_CONNECT_TIMEOUT_MS, readTimeoutMs = JS_READ_TIMEOUT_MS)
                    else -> httpClient.get(url, headers, connectTimeoutMs = JS_CONNECT_TIMEOUT_MS, readTimeoutMs = JS_READ_TIMEOUT_MS)
                }
                val resp = JSONObject().apply {
                    put("key", key)
                    put("statusCode", result.code)
                    put("statusMessage", result.message ?: "")
                    put("body", result.body)
                    val headersJson = JSONObject()
                    // 注意：HttpURLConnection.headerFields 会把状态行挂在 null 键下，
                    // 传给 JSONObject.put(null, ...) 会抛 "Names must be non-null"，必须跳过
                    result.headers.forEach { (k, v) -> if (k != null) headersJson.put(k, v) }
                    put("headers", headersJson)
                }
                pendingHttp[key] = resp
                Log.d(TAG, "[lx.request] 完成: ${result.code}")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                Log.e(TAG, "[lx.request] 请求异常: ${e.message}")
                val resp = JSONObject().apply {
                    put("key", key)
                    put("error", e.message ?: "request failed")
                }
                pendingHttp[key] = resp
            }
        }
    }

    /** 将已完成的 HTTP 响应注入 JS（引擎线程执行，驱动 handler 的 Promise 链） */
    private suspend fun injectResponses() {
        if (pendingHttp.isEmpty()) return
        try {
            // 2.8 用可取消的挂起版 onEngineThread（原 onEngineThreadBlocking 的 future.get 阻塞
            // 无法被协程取消中断——取消链路在此断裂，withTimeoutOrNull 等超时失效）
            onEngineThread {
                val g = global ?: return@onEngineThread
                val fn = g.getJSFunction("__lx_on_response__") ?: return@onEngineThread
                val entries = pendingHttp.entries.toList()
                for (entry in entries) {
                    pendingHttp.remove(entry.key)
                    try {
                        fn.call("http", entry.value.toString())
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                        Log.e(TAG, "注入 HTTP 响应失败: ${e.message}")
                    }
                    // 2.8 响应已注入（无论成功失败都算处理完），在途计数减一
                    activeRequestCount.decrementAndGet()
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "injectResponses 异常: ${e.message}")
        }
    }

    /** 将到期的定时器注入 JS（引擎线程执行） */
    private suspend fun injectTimers() {
        if (pendingTimers.isEmpty()) return
        try {
            onEngineThread {
                val g = global ?: return@onEngineThread
                val fn = g.getJSFunction("__lx_on_response__") ?: return@onEngineThread
                val now = System.currentTimeMillis()
                val dueIds = pendingTimers.entries.filter { it.value <= now }.map { it.key }
                for (id in dueIds) {
                    pendingTimers.remove(id)
                    if (clearedTimers.contains(id)) {
                        clearedTimers.remove(id)
                        continue
                    }
                    try {
                        fn.call("timer", JSONObject().put("id", id).toString())
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                        Log.e(TAG, "注入 timer 失败: ${e.message}")
                    }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "injectTimers 异常: ${e.message}")
        }
    }

    /**
     * 短暂 drain：驱动顶层异步任务（版本检查/异步注册型音源在回调里 send(inited) / on(request)），
     * 直到收到 inited、request 处理器已注册，或确认无待处理异步任务后退出。
     *
     * 2.8 竞态修复：退出条件从「pendingHttp 与 pendingTimers 均空」改为
     * 「在途请求数 activeRequestCount 为 0 且均空且已驱动至少一轮」——
     * 六音等异步注册型音源自举后先发请求（lx.request），若 Java 侧尚未把响应登记进
     * pendingHttp，旧条件会误判空闲提前退出，导致响应无人注入、request 处理器永不注册。
     */
    private suspend fun runDrainLoop(maxMs: Long) {
        val deadline = System.currentTimeMillis() + maxMs
        var rounds = 0
        while (System.currentTimeMillis() < deadline) {
            rounds++
            injectResponses()
            injectTimers()
            try {
                onEngineThread { context?.evaluate("0", "drain") }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                break
            }
            // 收到 inited → 初始化完成
            if (initEventDataJson != null) break
            // request 处理器已注册（异步注册型音源：请求响应后注册）→ 初始化完成
            val handlerRegistered = try {
                onEngineThread {
                    val g = global ?: return@onEngineThread false
                    val check = g.getJSFunction("__lx_has_handler__")
                    (check?.call("request") as? Boolean) ?: false
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
                false
            }
            if (handlerRegistered) break
            // 无在途请求、无待注入响应、无待触发定时器，且已驱动至少一轮：
            // 首轮不退出（请求可能刚发出、Java 侧尚未登记 pendingHttp，见 activeRequestCount）
            if (rounds > 1 && activeRequestCount.get() == 0 && pendingHttp.isEmpty() && pendingTimers.isEmpty()) {
                break
            }
            Thread.sleep(WAIT_STEP_MS)
        }
    }

    /**
     * 解析脚本头部元数据注释（@name/@version/@description/@author/@homepage）
     */
    private fun parseScriptMetadata(script: String): JSONObject {
        val meta = JSONObject()
        try {
            val head = script.take(2000)
            val regex = Regex("@(name|version|description|author|homepage)[ \\t]+([^\\r\\n*]+)")
            for (m in regex.findAll(head)) {
                val key = m.groupValues[1]
                val value = m.groupValues[2].trim().trimEnd('*', '/', ' ').trim()
                if (value.isNotEmpty()) meta.put(key, value)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "解析脚本元数据失败: ${e.message}")
        }
        return meta
    }
}

/**
 * JavaScript 执行结果
 */
sealed class JavaScriptResult {
    data class Success(
        val result: Any?,
        val resultString: String
    ) : JavaScriptResult()

    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : JavaScriptResult()
}

/**
 * JavaScript引擎异常
 */
class JavaScriptEngineException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
