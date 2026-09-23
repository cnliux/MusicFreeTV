package com.tvmusic.core

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.runBlocking

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

        store = PluginStore(this)
        playback = PlaybackStore(this)
        runtime = PluginRuntime.create(this, store)
        repository = PluginRepository(runtime, store, appScope, this)

        PlayerManager.init(this)
        PlayerManager.attach(runtime)
        PlayerManager.attachPlaybackStore(playback)

        val subs = store.listSubscriptions()
        if (subs.isEmpty()) {
            store.addSubscription(DEFAULT_SUBSCRIPTION)
            repository.refreshFromDb()
        } else {
            // 迁移旧版默认订阅源到最新仓库
            val oldUrls = setOf(
                "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@latest/plugins.json",
                "https://cdn.jsdelivr.net/gh/buaiwanyouxi/musicfreemusicfree-all@main/musicfree-tianpeng.json"
            )
            if (subs.any { it.url in oldUrls }) {
                oldUrls.forEach { store.removeSubscription(it) }
                if (store.listSubscriptions().none { it.url == DEFAULT_SUBSCRIPTION }) {
                    store.addSubscription(DEFAULT_SUBSCRIPTION)
                }
                repository.refreshFromDb()
            }
        }

        // 首启兜底：DB 为空时装入 assets/plugins 下打包的已知可用插件，
        // 保证即使订阅源同步失败，首页/搜索仍有数据可加载。
        if (store.loadPlugins().isEmpty()) {
            installBundledPluginsBlocking()
        }

        repository.warmup()
        repository.syncAll()

        RemoteConfigService.ensureStarted(this)
    }

    private fun installBundledPluginsBlocking() {
        val names = try {
            assets.list("plugins")
        } catch (e: Exception) {
            Log.w("TvMusicApp", "list bundled plugins: ${e.message}")
            null
        } ?: return
        runBlocking(Dispatchers.IO) {
            for (name in names) {
                if (!name.endsWith(".js", ignoreCase = true)) continue
                val src = readAssetOrNull("plugins/$name") ?: continue
                // 不指定 displayName，让 install 用插件内的 platform 作为名称，
                // 这样订阅同步时同名插件会被幂等跳过，避免重复注册导致崩溃。
                val err = repository.install("", url = "asset://plugins/$name", source = src)
                if (err != null) {
                    Log.w("TvMusicApp", "install bundled $name failed: $err")
                } else {
                    Log.i("TvMusicApp", "bundled plugin installed: $name")
                }
            }
        }
    }

    private fun readAssetOrNull(path: String): String? {
        return try {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w("TvMusicApp", "read asset $path: ${e.message}")
            null
        }
    }

    companion object {
        const val DEFAULT_SUBSCRIPTION =
            "https://cdn.jsdelivr.net/gh/buaiwanyouxi/musicfreemusicfree-all@main/musicfree-tianpeng.json"

        fun from(context: Context): TvMusicApp =
            context.applicationContext as TvMusicApp
    }
}
