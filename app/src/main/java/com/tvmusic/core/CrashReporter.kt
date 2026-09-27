package com.tvmusic.core

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量崩溃监控（H1）：自建 UncaughtExceptionHandler，崩溃时落盘到
 * `filesDir/crash/`，下次启动时按需上报/查看。
 *
 * 设计原则：
 * - 不引第三方 SDK（符合项目本地定位，无追踪）。
 * - 崩溃落盘后**继续走系统默认处理**（让系统正常杀进程/弹崩溃对话框），
 *   不吞异常、不改变崩溃语义。
 * - native 崩溃（QuickJS SIGABRT 等）由系统 tombstone 记录，此处只覆盖 Java 层；
 *   但 Java handler 能捕获 JNI 层抛回的 Java 异常（如反射桥参数错误）。
 * - release 包也保留：崩溃是必采指标，且不写敏感信息（不含 token/账号）。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crash"
    private const val MAX_KEEP = 20 // 最多保留 20 份，避免占满存储

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 尽早调用（任何初始化之前）。 */
    fun install(app: Application) {
        if (installed) return
        installed = true
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(app, thread, throwable)
            } catch (e: Exception) {
                Log.w(TAG, "writeCrash failed", e)
            } finally {
                // 交给系统默认处理器：正常杀进程/弹崩溃对话框，不改变崩溃语义
                default?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "installed, crash dir = ${File(app.filesDir, CRASH_DIR).absolutePath}")
    }

    /** 崩溃文件目录。 */
    fun crashDir(app: Application): File = File(app.filesDir, CRASH_DIR)

    /** 列出已有崩溃报告（按时间倒序）。 */
    fun listCrashes(app: Application): List<File> {
        val dir = crashDir(app)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 是否有未读崩溃（供关于页/设置页提示）。 */
    fun hasCrash(app: Application): Boolean = listCrashes(app).isNotEmpty()

    /** 清空崩溃报告（用户查看后或手动清理）。 */
    fun clearAll(app: Application) {
        listCrashes(app).forEach { runCatching { it.delete() } }
    }

    private fun writeCrash(app: Application, thread: Thread, throwable: Throwable) {
        val dir = crashDir(app)
        if (!dir.exists()) dir.mkdirs()
        // 清理超出上限的旧报告
        val existing = listCrashes(app)
        if (existing.size >= MAX_KEEP) {
            existing.drop(MAX_KEEP - 1).forEach { runCatching { it.delete() } }
        }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$ts.txt")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val body = buildString {
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("thread: ${thread.name} (id=${thread.id})")
            appendLine("app: ${app.packageName}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (sdk=${Build.VERSION.SDK_INT})")
            appendLine("---")
            appendLine(sw.toString())
        }
        file.writeText(body, Charsets.UTF_8)
        Log.e(TAG, "crash saved: ${file.name}", throwable)
    }
}
