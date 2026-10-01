package com.tvmusic.core

import android.app.Application
import android.os.Build
import android.util.Log
import com.tvmusic.BuildConfig
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

    /** 提示确认标记（"用户已知晓这批崩溃"），有新崩溃才再提示。 */
    private const val ACK_FILE = "crash_ack"

    /** 单份崩溃在诊断包里的最大字符数（防极端堆栈撑爆 HTTP 响应）。 */
    private const val MAX_TEXT_PER_FILE = 64 * 1024

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

    /**
     * P0-8：是否需要在开屏提示一次"上次异常退出"。
     *
     * 原来 listCrashes/hasCrash/clearAll 全仓零调用——崩溃文件躺在 filesDir/crash 里，
     * 用户没 root/没 adb 就永远拿不到，开发者只能靠口头描述定位。
     * 加确认标记文件，保证"有新崩溃才提示一次"，不会每次开屏都弹。
     */
    fun pendingPrompt(app: Application): Boolean {
        if (listCrashes(app).isEmpty()) return false
        return !File(app.filesDir, ACK_FILE).exists()
    }

    /** 用户已处理（选择清除或保留待导出）→ 写标记，之后不再提示。 */
    fun markPromptHandled(app: Application) {
        runCatching {
            File(app.filesDir, ACK_FILE).writeText(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                Charsets.UTF_8
            )
        }.onFailure { Log.w(TAG, "write ack failed: ${it.message}") }
    }

    /**
     * P0-8：诊断导出包（崩溃全文 + 设备/应用信息 + 运行指标）。
     * 供远程管理 `GET /api/diag/export` 下载；单份崩溃截断上限 [MAX_TEXT_PER_FILE]
     * 防止极端堆栈把响应撑爆。
     */
    fun buildExportBundle(app: Application, metricsJson: String? = null): String = buildString {
        appendLine("# MusicFreeTV 诊断包")
        appendLine("exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("app: ${app.packageName} version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        val crashes = listCrashes(app)
        appendLine("crash_count: ${crashes.size}")
        if (crashes.isEmpty()) {
            appendLine("(无崩溃记录)")
        }
        crashes.forEach { f ->
            appendLine()
            appendLine("===== ${f.name} (${f.length()} bytes) =====")
            appendLine(runCatching { f.readText(Charsets.UTF_8) }.getOrElse { "(读取失败: ${it.message})" }
                .take(MAX_TEXT_PER_FILE))
        }
        if (!metricsJson.isNullOrBlank()) {
            appendLine()
            appendLine("===== metrics =====")
            appendLine(metricsJson)
        }
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
