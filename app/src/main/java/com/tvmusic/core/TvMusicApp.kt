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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            // M20：冷启动关键路径（引擎+DB+warmup 前奏）完成打点
            com.tvmusic.core.Metrics.markColdStartDone()

            // 不内置任何插件/订阅源：音源一律由用户添加（订阅同步 / 设备 plugin_sources 文件 / 手动安装）。
            repo.warmup()
            // 自动同步由 warmup 完成后串行触发（带 4h 节流），避免启动时与插件注册抢 JS 引擎锁

            RemoteConfigService.ensureStarted(appCtx)
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
            coil.ImageLoader(this@TvMusicApp).memoryCache?.clear()
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

    companion object {
        fun from(context: Context): TvMusicApp =
            context.applicationContext as TvMusicApp
    }
}
