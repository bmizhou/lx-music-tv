package com.lxmusic.tv.data.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.8 异常日志导出：
 * - 应用内未捕获异常（崩溃）由 UncaughtExceptionHandler 捕获，追加写入 lx_logs/crash.log（跨会话保留）；
 * - exportToFile 时实时 dump 本进程 logcat（LX-* 等应用自身日志）合并为 lx_logs/export.log；
 * - 供 Web 端 /log 路由读取导出（设置页提示手机/电脑浏览器访问）。
 */
object LogExporter {
    private const val TAG = "LX-LogExporter"
    private const val LOG_DIR = "lx_logs"
    private const val CRASH_FILE = "crash.log"
    private const val EXPORT_FILE = "export.log"

    @Volatile
    private var initialized = false

    /** Application.onCreate 调用：接管未捕获异常，崩溃堆栈写入 crash.log */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendCrash(appContext, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "记录崩溃日志失败: ${e.message}")
            }
            // 交还原默认处理器（系统弹窗/自杀流程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "异常日志捕获已启用")
    }

    /** 崩溃堆栈追加到 crash.log（线程安全：synchronized） */
    @Synchronized
    private fun appendCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val dir = logDir(context)
            val file = File(dir, CRASH_FILE)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val entry = buildString {
                append("===== 崩溃时间: ").append(time).append(" =====\n")
                append("线程: ").append(thread.name).append('\n')
                append(sw.toString())
                append("\n\n")
            }
            // 追加写入（保留最近 200 条，超限裁剪前半部分）
            val existing = if (file.exists()) file.readText() else ""
            val combined = existing + entry
            file.writeText(if (combined.length > 512 * 1024) combined.takeLast(512 * 1024) else combined)
        } catch (e: Exception) {
            Log.e(TAG, "写崩溃日志失败: ${e.message}")
        }
    }

    /**
     * 导出合并日志：crash.log（历史崩溃） + 实时 dump 的本进程 logcat（应用自身 LX-* 日志）。
     * 写 lx_logs/export.log 并返回文件；logcat 读取失败时降级为仅 crash.log。
     */
    fun exportToFile(context: Context): File {
        val dir = logDir(context)
        val crashFile = File(dir, CRASH_FILE)
        val exportFile = File(dir, EXPORT_FILE)

        val crashContent = if (crashFile.exists()) {
            "======== 应用崩溃日志（crash.log） ========\n" + crashFile.readText() + "\n\n"
        } else {
            "======== 应用崩溃日志（crash.log） ========\n（暂无崩溃记录）\n\n"
        }

        // 实时 dump 本进程 logcat（LX-* 等应用自身日志；Android 允许应用读取自己 UID 的日志）
        val logcatContent = try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "-t", "2000")
            )
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            "======== 运行日志（logcat，最近 2000 行） ========\n$text"
        } catch (e: Exception) {
            Log.w(TAG, "读取 logcat 失败（可能无权限），仅导出崩溃日志: ${e.message}")
            "======== 运行日志（logcat） ========\n（读取失败：${e.message}）"
        }

        val merged = crashContent + logcatContent
        exportFile.writeText(merged)
        return exportFile
    }

    /** 异常日志目录 */
    private fun logDir(context: Context): File =
        File(context.filesDir, LOG_DIR).apply { mkdirs() }
}
