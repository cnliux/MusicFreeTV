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

        com.tvmusic.ui.theme.ThemeManager.init(this)
        com.tvmusic.ui.theme.LyricSettings.init(this)
        store = PluginStore(this)
        playback = PlaybackStore(this)
        runtime = PluginRuntime.create(this, store)
        repository = PluginRepository(runtime, store, appScope, this)

        PlayerManager.init(this)
        PlayerManager.attach(runtime)
        PlayerManager.attachPlaybackStore(playback)

        // 插件订阅源：不留任何默认地址。用户自行添加（插件设置 / web 控制台 / 设备上的 plugin_sources.* 文件）。
        // 这里只做“读取”：若设备上存在用户放置的 plugin_sources 文件，则幂等补全到订阅列表。
        val sources = readPluginSourcesFromDevice().orEmpty()
        val subscribed = store.listSubscriptions().map { it.url }.toSet()
        sources.filter { it !in subscribed }.forEach { store.addSubscription(it) }
        repository.refreshFromDb()

        // 不内置任何插件/订阅源：音源一律由用户添加（订阅同步 / 设备 plugin_sources 文件 / 手动安装）。

        repository.warmup()
        repository.syncAll()

        RemoteConfigService.ensureStarted(this)
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
