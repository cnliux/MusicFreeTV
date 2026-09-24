package com.tvmusic.plugin

import android.content.Context
import android.util.Log
import com.tvmusic.data.PluginInfo
import com.tvmusic.data.PluginRecord
import com.tvmusic.data.PluginStore
import com.tvmusic.data.UserVarDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件仓库：负责插件列表、订阅源（plugins.json）拉取、安装 / 更新 / 卸载。
 *
 * 插件源码普遍是两种形态：
 *  1. TS 编译产物：`module.exports = { platform: "网易云", ... }`（最后出现的 platform 即真实平台名）
 *  2. Parcel 打包：`$parcel$export(module.exports, "default", () => plugin)`，插件对象在 exports.default
 */
class PluginRepository(
    private val runtime: PluginRuntime,
    private val store: PluginStore,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val appContext: Context
) {

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    private val _subscribed = MutableStateFlow<List<String>>(emptyList())
    val subscribed: StateFlow<List<String>> = _subscribed.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** DB 中已有插件已全部注册进引擎（warmup 完成）。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        refreshFromDb()
    }

    fun refreshFromDb() {
        _plugins.value = store.loadPlugins()
        _subscribed.value = store.listSubscriptions().map { it.url }
    }

    /**
     * 启动时把 DB 里已保存的插件源码直接注册进 JS 引擎（无需联网重新下载），
     * 注册完成后才对外发射插件列表，保证首页/搜索首屏不会拿到“插件未加载”。
     */
    fun warmup() {
        scope.launch(Dispatchers.IO) {
            // 崩溃看门狗：若上次启动在加载某个插件时 native crash，
            // 标记文件会残留；将该插件加入永久黑名单，避免无限崩溃循环。
            val marker = File(appContext.filesDir, "plugin_load_marker")
            val blocklist = File(appContext.filesDir, "plugin_blocklist")
            if (marker.exists()) {
                val crashedName = runCatching { marker.readText().trim() }.getOrNull()
                marker.delete()
                if (!crashedName.isNullOrBlank()) {
                    // 加入永久黑名单（去重追加）
                    val existing = runCatching { blocklist.readText().lines().toSet() }.getOrDefault(emptySet())
                    if (crashedName !in existing) {
                        blocklist.appendText("$crashedName\n")
                    }
                    store.markLoadErrorByName(crashedName, "native crash during load (blocked)")
                    Log.w("PluginRepository", "plugin '$crashedName' crashed, added to blocklist")
                }
            }
            val blocked = blockedPlugins()
            val records = store.loadPlugins().filter { it.source != null && it.loadError == null && it.name !in blocked }
            var okCount = 0
            for (p in records) {
                val platform = p.info?.platform ?: detectPlatformSafe(p.source ?: "")
                if (platform.isNullOrBlank()) continue
                marker.writeText(p.name)
                val loaded = runCatching { runtime.loadPlugin(platform, p.source!!) }.getOrDefault(false)
                marker.delete()
                if (loaded) {
                    okCount++
                } else {
                    store.markLoadErrorByName(p.name, "register failed")
                    Log.w("PluginRepository", "register failed: ${p.id}")
                }
            }
            marker.delete()
            Log.i("PluginRepository", "warmup done: $okCount/${records.size} plugins loaded")
            _ready.value = true
            refreshFromDb()
        }
    }

    /** 读取永久黑名单（native crash 过的插件名）。 */
    private fun blockedPlugins(): Set<String> {
        val f = File(appContext.filesDir, "plugin_blocklist")
        if (!f.exists()) return emptySet()
        return runCatching { f.readText().lines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    }

    /** 从 DB 未解析源码时临时探测 platform 的兜底。 */
    private fun detectPlatformSafe(source: String): String? = detectPlatform(source)

    fun addSubscription(url: String) {
        if (url.isBlank()) return
        store.addSubscription(url.trim())
        refreshFromDb()
    }

    fun removeSubscription(url: String) {
        store.removeSubscription(url)
        refreshFromDb()
    }

    fun listEnabled(): List<PluginRecord> = _plugins.value.filter { it.enabled }

    fun toggleEnabled(name: String, enabled: Boolean) {
        store.setPluginEnabled(name, enabled)
        refreshFromDb()
    }

    fun uninstall(name: String) {
        store.deletePlugin(name)
        refreshFromDb()
    }

    /** 遍历所有订阅源并安装/更新其中的插件。 */
    fun syncAll() {
        if (_syncing.value) return
        _syncing.value = true
        scope.launch {
            try {
                for (sub in _subscribed.value) {
                    try {
                        syncOne(sub)
                    } catch (e: Exception) {
                        Log.w("PluginRepository", "sync $sub failed: ${e.message}")
                    }
                }
            } finally {
                _syncing.value = false
            }
            refreshFromDb()
        }
    }

    /** 下载 url 内容，失败返回 null。 */
    private fun fetchOk(url: String): String? = try {
        okHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /** 内容识别安装：plugins.json 列表 → 逐个安装；否则按单个 .js 插件安装。 */
    private suspend fun installContent(url: String, body: String): String? {
        val arr = runCatching { JSONObject(body).optJSONArray("plugins") }.getOrNull()
        if (arr != null && arr.length() > 0) {
            importListJson(body)
            return null
        }
        return installFromBody(url, body)
    }

    /** 单个插件源码安装入口（探测 platform 后交给 install）。 */
    private suspend fun installFromBody(url: String, body: String): String? {
        val platform = detectPlatform(body) ?: return "无法解析插件 platform"
        return install(platform, url, source = body)
    }

    /**
     * 从 url 导入：内容为 plugins.json 列表则批量安装，否则视为单个插件 .js。
     * 返回错误信息，成功返回 null。
     */
    suspend fun importFromUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = fetchOk(url) ?: return@withContext "下载失败: HTTP 无法访问"
            installContent(url, body)
        } catch (e: Exception) {
            "拉取失败: ${e.message}"
        }
    }

    /** 安装/更新：注册到引擎 -> 读元信息 -> 入库。 */
    suspend fun install(
        name: String,
        url: String,
        version: String? = null,
        source: String? = null,
        keepEnabled: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        // 黑名单：上次 native crash 过的插件不再尝试安装。
        if (name in blockedPlugins()) {
            return@withContext "插件在黑名单中（曾导致崩溃）"
        }
        try {
            val js = source ?: run {
                val req = Request.Builder().url(url).build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "下载失败: HTTP ${resp.code}"
                    resp.body?.string() ?: return@withContext "空响应"
                }
            }
            // 唯一 id：源码指纹。同一份源码（哪怕订阅里名字不同）只安装一次，
            // 防止重复注册进 JS 引擎互相覆盖 platform。
            val hash = sourceHash(js)
            val existing = store.loadPlugins()
            existing.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }?.let { dup ->
                Log.i("PluginRepository", "skip duplicate source: ${dup.name} ($name)")
                return@withContext null
            }
            // 幂等：同名插件已安装则跳过（更新需先卸载）。
            if (existing.firstOrNull { it.name == name } != null) {
                return@withContext null
            }
            val platform = detectPlatform(js) ?: return@withContext "无法解析插件 platform"
            // 崩溃看门狗：写标记，若注册时 native crash，下次启动跳过该插件
            val marker = File(appContext.filesDir, "plugin_load_marker")
            marker.writeText(name.ifBlank { platform })
            val loaded = runtime.loadPlugin(platform, js)
            marker.delete()
            if (!loaded) return@withContext "插件注册失败: $platform"
            val infoRaw = runtime.readInfo(platform) ?: return@withContext "读取插件信息失败: $platform"
            val info = parseInfo(infoRaw)
            store.upsertPlugin(
                PluginRecord(
                    name = name.ifBlank { info.platform },
                    url = url,
                    version = version ?: info.version,
                    enabled = keepEnabled,
                    installedAt = System.currentTimeMillis(),
                    source = js,
                    info = info,
                    loadError = null,
                    hash = hash
                )
            )
            refreshFromDb()
            null
        } catch (e: Throwable) {
            refreshFromDb()
            "安装失败: ${e.message}"
        }
    }

    /** 源码指纹：sha1 前 12 位十六进制，作为插件唯一 id。 */
    private fun sourceHash(source: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(source.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(12)
        } catch (e: Exception) {
            source.hashCode().toString(16)
        }
    }

    private suspend fun syncOne(subUrl: String) = withContext(Dispatchers.IO) {
        try {
            val body = fetchOk(subUrl) ?: return@withContext
            installContent(subUrl, body)?.let { Log.w("PluginRepository", "syncOne $subUrl: $it") }
        } catch (e: Exception) {
            Log.w("PluginRepository", "syncOne $subUrl: ${e.message}")
        }
    }

    /**
     * 逐个安装订阅里的插件。
     * 必须串行：并发安装会让"源码指纹去重"读到过期快照产生竞态，
     * 也会让崩溃看门狗 marker 文件互相覆盖，无法定位真正崩溃的插件。
     */
    private suspend fun importListJson(body: String) {
        val arr = runCatching { JSONObject(body).optJSONArray("plugins") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val pluginUrl = item.optString("url")
            val version = item.optString("version", null)
            if (pluginUrl.isEmpty()) continue
            // 不再使用白名单限制，否则订阅里绝大多数音源都会被静默丢弃，
            // 表现为"首页只装上一个插件"。崩溃防护由看门狗 + 永久黑名单负责。
            val err = install(name, pluginUrl, version)
            if (err != null) Log.w("PluginRepository", "install $name: $err")
        }
    }

    /**
     * 从源码中探测真实 platform：取源码中"最后一次出现"的 `platform: "xxx"` 或 `platform: 'xxx'`。
     * 同时兼容 `platform = "..."` 赋值写法，以及 `var PLATFORM = "xxx"; platform: PLATFORM` 这类变量引用写法。
     * Parcel 打包产物与 TS 编译产物都把插件对象里的 platform 放在文件末尾附近，
     * 内层调用里的 platform（如 "yqq.json"、"WebFilter"）都出现在前面。
     */
    private fun detectPlatform(source: String): String? {
        // 1) 字面量形式（优先）
        val literal = Regex("""platform\s*[:=]\s*["']([^"']*)["']""").findAll(source).toList()
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && it != "pc" && it != "web" && it != "WebFilter" && it != "H5" && it != "h5" && !it.endsWith(".json") }
        if (literal.isNotEmpty()) return literal.last()

        // 2) 变量引用形式：先收集 var/let/const X = 'xxx' 映射，
        //    再找源码中 platform: X / platform = X 引用的标识符，取最后一次的对应字面量。
        val vars = Regex("""(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*["']([^"']*)["']""")
            .findAll(source).toList()
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        if (vars.isEmpty()) return null
        val refs = Regex("""platform\s*[:=]\s*([A-Za-z_$][\w$]*)""").findAll(source).toList()
        for (m in refs.reversed()) {
            val v = vars[m.groupValues[1]] ?: continue
            if (v.isNotEmpty() && v != "pc" && v != "web" && v != "WebFilter" && v != "H5" && v != "h5" && !v.endsWith(".json")) return v
        }
        return null
    }

    private fun parseInfo(json: JSONObject): PluginInfo {
        fun JSONArray.toStrings(): List<String> =
            (0 until length()).map { i -> optString(i) }

        val vars = json.optJSONArray("userVariables")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: JSONObject()
                UserVarDef(
                    key = o.optString("key"),
                    name = o.optString("name", o.optString("key")),
                    type = o.optString("type", null)
                )
            }
        } ?: emptyList()

        return PluginInfo(
            platform = json.optString("platform", ""),
            version = json.optString("version", null),
            author = json.optString("author", null),
            srcUrl = json.optString("srcUrl", null),
            appVersion = json.optString("appVersion", null),
            description = json.optString("description", null),
            cacheControl = json.optString("cacheControl", null),
            primaryKey = json.optJSONArray("primaryKey")?.toStrings() ?: emptyList(),
            supportedSearchType = json.optJSONArray("supportedSearchType")?.toStrings() ?: emptyList(),
            userVariables = vars,
            hints = json.optJSONObject("hints")
        )
    }
}