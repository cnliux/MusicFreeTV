package com.tvmusic.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.tvmusic.data.PlaybackStore
import com.tvmusic.data.PluginStore
import com.tvmusic.player.PlayerManager
import com.tvmusic.plugin.PluginRepository
import com.tvmusic.plugin.PluginRuntime
import com.tvmusic.remote.RemoteConfigService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class TvMusicApp : Application() {

    lateinit var store: PluginStore
        private set
    lateinit var playback: PlaybackStore
        private set
    lateinit var runtime: PluginRuntime
        private set
    lateinit var repository: PluginRepository
        private set

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            // P0-6：初始化协程未捕获异常会直接杀进程（冷启动必崩）。这里兜底记账，
            // 同时释放 awaitEngineReady 的等待者，避免它们各自再阻塞 30s。
            CoroutineExceptionHandler { _, t -> recordStartupFailure("scope", t) }
    )

    override fun onCreate() {
        super.onCreate()

        // 冷启动起点打点（M20）：必须在任何 Metrics 触碰之前，否则类加载时机导致 coldStartMs≈0
        Metrics.markProcessStart()
        // 崩溃监控必须最先安装（任何初始化之前），覆盖后续所有初始化路径的崩溃
        CrashReporter.install(this)

        // H8：注册内存回调，TV 内存吃紧时主动回收图片缓存/JS 多余引擎/预加载缓存，
        // 提升后台存活率与二次启动速度。
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("deprecated in framework")
            override fun onLowMemory() {
                trimMemory()
            }
            override fun onTrimMemory(level: Int) {
                if (level >= TRIM_MEMORY_UI_HIDDEN) trimMemory()
            }
        })

        com.tvmusic.ui.theme.ThemeManager.init(this)
        com.tvmusic.ui.theme.LyricSettings.init(this)
        com.tvmusic.config.MetaSettings.init(this)
        com.tvmusic.config.IdleSettings.init(this)
        store = PluginStore(this)
        playback = PlaybackStore(this)

        // H5/H9：JS 引擎创建（3 台 QuickJS runtime + 读 10+ assets JS）与插件 DB 查询/预热
        // 全部下沉到 IO 线程，不再压冷启动主线程。依赖它们的 PlayerManager 与 UI 通过
        // 懒初始化/异步通知拿到就绪后的实例（PluginRuntime/Repository 完成后才 attach）。
        val appCtx = applicationContext
        appScope.launch(Dispatchers.IO) {
            // P0-6：整段初始化包 try/catch。任一环节失败（QuickJS ABI 不符、assets 缺失、
            // SQLite 打不开、外置存储异常）都必须"可降级地失败"：记账 + 释放等待者，
            // 由各 ViewModel 显示"初始化失败/重试"，而不是开屏首页直接崩。
            try {
                val rt = PluginRuntime.create(appCtx, store)
                val repo = PluginRepository(rt, store, appScope, appCtx)
                runtime = rt
                repository = repo
                // H7：插件目录扫描（外置存储多路径 readText）在 IO 线程执行
                val sources = readPluginSourcesFromDevice().orEmpty()
                val subscribed = store.listSubscriptions().map { it.url }.toSet()
                sources.filter { it !in subscribed }.forEach { store.addSubscription(it) }
                repo.refreshFromDb()

                // 引擎就绪后再挂到 PlayerManager（主线程只做轻量 attach）
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    PlayerManager.init(appCtx)
                    PlayerManager.attach(rt)
                    PlayerManager.attachRepository(repo)
                    PlayerManager.attachPlaybackStore(playback)
                    PlayerManager.loadResumeAsync()
                }
                // 标记引擎就绪：解除 awaitEngineReady 的等待（ViewModel/远程服务可安全访问）
                markEngineReady()
                // M20：冷启动关键路径（引擎+DB+warmup 前奏）完成打点
                com.tvmusic.core.Metrics.markColdStartDone()

                // 不内置任何插件/订阅源：音源一律由用户添加（订阅同步 / 设备 plugin_sources 文件 / 手动安装）。
                repo.warmup()
                // 自动同步由 warmup 完成后串行触发（带 4h 节流），避免启动时与插件注册抢 JS 引擎锁

                RemoteConfigService.ensureStarted(appCtx)
            } catch (t: Throwable) {
                // 故意 catch Throwable：UnsatisfiedLinkError（ABI 不符）等是 Error 不是 Exception，
                // 漏掉它们等于开屏必崩。错误已记账，不静默吞。
                recordStartupFailure("init", t)
                // 失败也要让等待者立刻返回 false，而不是各自阻塞满 30s
                markEngineReady()
            }
        }

        // 应用退出钩子：Application 没有可靠的 onDestroy，改用"最后一个 Activity 停止"
        // 作为退出/切后台时机，立即落盘播放恢复快照（1s 防抖等不及）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedCount = 0
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedCount++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount <= 0) PlayerManager.flushResumeNow()
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** 内存紧张时回收：图片内存缓存清空，播放器预加载缓存由 PlayerManager 自行收缩。 */
    private fun trimMemory() {
        runCatching {
            com.tvmusic.net.AppImageLoader.trimMemory(this@TvMusicApp)
        }
        runCatching { PlayerManager.onTrimMemory() }
    }

    /**
     * 读取设备上的插件订阅源文件（用户可自行放置/编辑，无需重打包）。
     * 查找顺序：外部文件目录（/sdcard/Android/data/<pkg>/files/plugin_sources*） → 内部 files 目录。
     * 支持纯文本（一行一个 URL，# 注释）与 JSON（{"sources":[...]} 或裸数组）。
     */
    private fun readPluginSourcesFromDevice(): List<String>? {
        fun from(f: File): List<String>? {
            if (!f.exists()) return null
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
            return parsePluginSources(text)?.let { if (it.isEmpty()) null else it }
        }
        getExternalFilesDir(null)?.let {
            for (name in listOf("plugin_sources", "plugin_sources.json", "plugin_sources.txt")) {
                from(File(it, name))?.let { return it }
            }
        }
        for (name in listOf("plugin_sources", "plugin_sources.json", "plugin_sources.txt")) {
            from(File(filesDir, name))?.let { return it }
        }
        return null
    }

    /** 解析订阅源：兼容纯文本行、{"sources":[...]}、{"plugins":[{url}]} 与裸 JSON 数组。 */
    private fun parsePluginSources(text: String): List<String>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val arr = runCatching { org.json.JSONArray(t) }.getOrNull() ?: return null
            return (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim() }
                .filter { it.startsWith("http") }
        }
        if (t.startsWith("{")) {
            val o = runCatching { org.json.JSONObject(t) }.getOrNull() ?: return null
            val arr = o.optJSONArray("sources") ?: o.optJSONArray("plugins") ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("url")?.trim() ?: arr.optString(i).trim()
            }.filter { it.startsWith("http") }
        }
        return text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("http") }
            .toList()
    }

    // ---------------- 引擎就绪等待（H5/H9 异步初始化的访问安全） ----------------

    /** 引擎初始化完成的闭锁：IO 协程完成后 countDown。 */
    private val engineReadyLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * 同步等待引擎/仓库就绪（最多 30s）。
     * ViewModel 构造、RemoteConfigService 处理请求时在访问 runtime/repository 前调用，
     * 避免 lateinit 未赋值导致的 UninitializedPropertyAccessException（真机崩溃根因）。
     * 引擎初始化在 IO 协程（H5/H9），通常 <1s 完成；超时返回 false 让调用方降级。
     */
    fun awaitEngineReady(timeoutMs: Long = 30_000): Boolean {
        return try {
            engineReadyLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 引擎就绪后调用（由初始化协程在完成时触发）。 */
    private fun markEngineReady() {
        engineReadyLatch.countDown()
    }

    /**
     * 初始化失败原因（P0-6）：非 null 表示引擎/仓库不可用，UI 应显示"初始化失败/重试"。
     * 保留原始异常类名与首行消息便于定位（ABI 不符通常是 UnsatisfiedLinkError）。
     */
    @Volatile
    var startupFailure: String? = null
        private set

    /** 引擎/仓库是否可安全访问。 */
    val engineReady: Boolean
        get() = startupFailure == null && ::runtime.isInitialized && ::repository.isInitialized

    /** 失败记账：写日志 + Metrics（供 /api/metrics、/api/health 观察）。 */
    private fun recordStartupFailure(stage: String, t: Throwable) {
        val msg = "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
        startupFailure = msg
        Log.e("TvMusicApp", "startup failed at $stage: $msg", t)
        runCatching { Metrics.recordStartupFailure(stage, msg) }
    }

    /**
     * 安全取运行时（P0-6）：未就绪返回 null，而不是抛 UninitializedPropertyAccessException。
     * 5 个 ViewModel 与远程服务都改走这里，初始化失败时降级为"显示错误"而非崩溃。
     */
    fun runtimeOrNull(): PluginRuntime? = if (::runtime.isInitialized) runtime else null

    fun repositoryOrNull(): PluginRepository? = if (::repository.isInitialized) repository else null

    companion object {
        fun from(context: Context): TvMusicApp =
            context.applicationContext as TvMusicApp
    }
}
