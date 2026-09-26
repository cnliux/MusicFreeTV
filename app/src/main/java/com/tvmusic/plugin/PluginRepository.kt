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

    /** 自动同步节流用的偏好存储。 */
    private val syncPrefs by lazy {
        appContext.getSharedPreferences("plugin_sync_prefs", Context.MODE_PRIVATE)
    }

    private companion object {
        const val KEY_LAST_AUTO_SYNC = "last_auto_sync_at"
        const val AUTO_SYNC_INTERVAL_MS = 4 * 60 * 60 * 1000L

        /** 崩溃封禁阈值：连续这么多次"加载时 native crash"才永久禁止该插件。 */
        const val BLOCK_THRESHOLD = 3
    }

    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    private val _subscribed = MutableStateFlow<List<String>>(emptyList())
    val subscribed: StateFlow<List<String>> = _subscribed.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * 一次手动"检查更新"的结果汇总。
     * @param updated 本次源码指纹变化、实际重装的插件名
     * @param installed 本次新装的插件名
     * @param failed 失败项（插件名: 原因）
     * @param totalSeen 订阅源里声明的插件总数
     */
    data class SyncReport(
        val timeMs: Long,
        val updated: List<String>,
        val installed: List<String>,
        val failed: List<String>,
        val totalSeen: Int
    )

    private val _syncReport = MutableStateFlow<SyncReport?>(null)
    /** 最近一次手动同步（检查更新）的结果；自动同步不产生报告。 */
    val syncReport: StateFlow<SyncReport?> = _syncReport.asStateFlow()

    /** install() 过程中记录的事件（name, kind, err?）：手动同步开始时清空，结束后汇总成 SyncReport。 */
    private val installEvents = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, String?>>()

    /** 手动同步期间订阅源声明的插件条目总数（含未变化跳过的），结束时清零。 */
    private val syncSeenCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 同步防重入原子闸门（_syncing StateFlow 的 check-then-set 非原子，不能做并发闸门）。 */
    private val syncGate = java.util.concurrent.atomic.AtomicBoolean(false)

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
            // 标记文件会残留；连续 3 次才永久封禁，期间每次启动重试，允许偶发崩溃恢复。
            val marker = File(appContext.filesDir, "plugin_load_marker")
            if (marker.exists()) {
                val crashedName = runCatching { marker.readText().trim() }.getOrNull()
                marker.delete()
                if (!crashedName.isNullOrBlank()) {
                    val counts = readBlocklistCounts()
                    val bumped = (counts[crashedName] ?: 0) + 1
                    counts[crashedName] = bumped
                    writeBlocklistCounts(counts)
                    if (bumped >= BLOCK_THRESHOLD) {
                        store.markLoadErrorByName(crashedName, "native crash during load (blocked)")
                        Log.w("PluginRepository", "plugin '$crashedName' crashed $bumped times, blocked")
                    } else {
                        Log.w("PluginRepository", "plugin '$crashedName' crashed x$bumped/$BLOCK_THRESHOLD, will retry next launch")
                    }
                }
            }
            val blocked = blockedPlugins()
            val records = store.loadPlugins().filter { it.source != null && it.loadError == null && it.name !in blocked }
            var okCount = 0
            for (p in records) {
                val platform = p.info?.platform ?: detectPlatformSafe(p.source ?: "")
                if (platform.isNullOrBlank()) continue
                val marker = File(appContext.filesDir, "plugin_load_marker")
                marker.writeText(p.name)
                val loaded = runCatching { runtime.loadPlugin(platform, p.source!!) }.getOrDefault(false)
                marker.delete()
                if (loaded) {
                    removeBlocklistEntry(p.name)
                    okCount++
                    // 每注册成功一个就刷新列表：首页/搜索侧插件渐进可见，不必等全部完成
                    refreshFromDb()
                } else {
                    store.markLoadErrorByName(p.name, "register failed")
                    Log.w("PluginRepository", "register failed: ${p.id}")
                }
            }
            Log.i("PluginRepository", "warmup done: $okCount/${records.size} plugins loaded")
            _ready.value = true
            refreshFromDb()
            // 自动同步放在 warmup 之后串行执行：不与插件注册抢 JS 引擎锁；
            // 且带 4 小时节流，正常启动直接跳过重复下载。
            syncAll(force = false)
        }
    }

    /** 读取崩溃计数表 {name: count}；旧格式裸 name 行按已封禁(BLOCK_THRESHOLD)处理。 */
    private fun readBlocklistCounts(): MutableMap<String, Int> {
        val f = File(appContext.filesDir, "plugin_blocklist")
        if (!f.exists()) return HashMap()
        val counts = HashMap<String, Int>()
        f.readLines().forEach { line ->
            val s = line.trim()
            if (s.isEmpty()) return@forEach
            val idx = s.lastIndexOf(':')
            val n = if (idx > 0) s.substring(idx + 1).toIntOrNull() else null
            if (n != null) counts[s.substring(0, idx)] = n else counts[s] = BLOCK_THRESHOLD
        }
        return counts
    }

    private fun writeBlocklistCounts(counts: Map<String, Int>) {
        val f = File(appContext.filesDir, "plugin_blocklist")
        f.writeText(counts.entries.joinToString("\n") { "${it.key}:${it.value}" })
    }

    /** 插件成功加载后解除其崩溃封禁计数（一次成功即洗掉历史崩溃记录）。 */
    private fun removeBlocklistEntry(name: String) {
        val counts = readBlocklistCounts()
        if (counts.remove(name) != null) writeBlocklistCounts(counts)
    }

    /** 读取已封禁插件（崩溃计数达到阈值的）。 */
    private fun blockedPlugins(): Set<String> =
        readBlocklistCounts().entries.filter { it.value >= BLOCK_THRESHOLD }.map { it.key }.toSet()

    /** 从 DB 未解析源码时临时探测 platform 的兜底。 */
    private fun detectPlatformSafe(source: String): String? = detectPlatform(source)

    fun addSubscription(url: String) {
        if (url.isBlank()) return
        // 主动添加订阅 = 明确想让订阅内容生效：清空卸载名单，否则名单会拦截新订阅里的插件
        store.clearUninstalled(store.listUninstalled())
        store.addSubscription(url.trim())
        refreshFromDb()
    }

    fun removeSubscription(url: String) {
        store.removeSubscription(url)
        refreshFromDb()
    }

    fun listEnabled(): List<PluginRecord> = _plugins.value.filter { it.enabled }

    /** @return 是否命中插件（未命中由 HTTP 层回 404，不再静默成功）。 */
    fun toggleEnabled(nameOrPlatform: String, enabled: Boolean): Boolean {
        val ok = store.setPluginEnabled(nameOrPlatform, enabled)
        if (ok) refreshFromDb()
        return ok
    }

    /**
     * 卸载单个插件：删除插件行与变量，并把 name/platform 记入卸载名单。
     * 名单会阻止订阅同步把同一插件再次装回（"卸载了又自动出现"的根因）。
     */
    fun uninstall(nameOrPlatform: String): Boolean {
        val hit = store.deletePlugin(nameOrPlatform) ?: return false
        store.markUninstalled(hit.first)
        hit.second?.let { store.markUninstalled(it) }
        refreshFromDb()
        return true
    }

    /** 一键全部卸载：清空全部插件与变量并记入卸载名单（订阅保留，但不再自动复装）。 */
    fun uninstallAll(): Int {
        val n = store.deleteAllPlugins()
        if (n > 0) refreshFromDb()
        return n
    }

    /**
     * 遍历所有订阅源并安装/更新其中的插件。
     * @param force 手动触发（设置页 / web 控制台 / 新增订阅）时为 true，跳过节流立即同步。
     *              自动触发（启动 warmup 完成后）走 4 小时节流：已装插件时跳过重复
     *              下载与注册——此前每次启动都与 warmup 并发抢 JS 引擎锁，
     *              是"启动后插件迟迟不可用"的主要根因。
     */
    fun syncAll(force: Boolean = false) {
        // check-then-set 必须原子：UI 线程与远程 HTTP 线程可并发触发，
        // 旧实现两段式判断会双跑（重复下载/注册，marker 注释要求串行）
        if (!syncGate.compareAndSet(false, true)) return
        if (!force && !autoSyncDue()) {
            syncGate.set(false)
            return
        }
        _syncing.value = true
        if (force) {
            installEvents.clear()
            syncSeenCount.set(0)
        }
        scope.launch {
            try {
                for (sub in _subscribed.value) {
                    try {
                        syncOne(sub)
                    } catch (e: Exception) {
                        Log.w("PluginRepository", "sync $sub failed: ${e.message}")
                        installEvents.add(Triple(sub, "failed", e.message))
                    }
                }
            } finally {
                _syncing.value = false
                syncGate.set(false)
                syncPrefs.edit().putLong(KEY_LAST_AUTO_SYNC, System.currentTimeMillis()).apply()
                if (force) {
                    val updated = mutableListOf<String>()
                    val installed = mutableListOf<String>()
                    val failed = mutableListOf<String>()
                    installEvents.forEach { (name, kind, err) ->
                        when (kind) {
                            "updated" -> updated.add(name)
                            "installed" -> installed.add(name)
                            else -> failed.add("$name（${err ?: "未知原因"}）")
                        }
                    }
                    _syncReport.value = SyncReport(
                        timeMs = System.currentTimeMillis(),
                        updated = updated,
                        installed = installed,
                        failed = failed,
                        totalSeen = syncSeenCount.get()
                    )
                }
            }
            refreshFromDb()
        }
    }

    /** 自动同步节流：4 小时内且已有可用插件时跳过（全新安装不跳）。 */
    private fun autoSyncDue(): Boolean {
        val last = syncPrefs.getLong(KEY_LAST_AUTO_SYNC, 0L)
        if (System.currentTimeMillis() - last <= AUTO_SYNC_INTERVAL_MS && _plugins.value.any { it.enabled }) {
            return false
        }
        return true
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
    private suspend fun installContent(url: String, body: String, fromSync: Boolean): String? {
        val arr = runCatching { JSONObject(body).optJSONArray("plugins") }.getOrNull()
        if (arr != null && arr.length() > 0) {
            importListJson(body, fromSync)
            return null
        }
        syncSeenCount.incrementAndGet()
        return installFromBody(url, body, fromSync)
    }

    /** 单个插件源码安装入口（探测 platform 后交给 install）。 */
    private suspend fun installFromBody(url: String, body: String, fromSync: Boolean): String? {
        val platform = detectPlatform(body) ?: return "无法解析插件 platform"
        return install(platform, url, source = body, fromSync = fromSync)
    }

    /**
     * 从 url 导入：内容为 plugins.json 列表则批量安装，否则视为单个插件 .js。
     * 返回错误信息，成功返回 null。
     * 手动导入（非订阅同步）：清除对应卸载名单，视为用户想要恢复该插件。
     */
    suspend fun importFromUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = fetchOk(url) ?: return@withContext "下载失败: HTTP 无法访问"
            installContent(url, body, fromSync = false)
        } catch (e: Exception) {
            "拉取失败: ${e.message}"
        }
    }

    /** 安装/更新：注册到引擎 -> 读元信息 -> 入库。
     *  @param fromSync true=订阅自动同步（命中卸载名单则静默跳过）；false=手动导入（清除名单允许恢复） */
    suspend fun install(
        name: String,
        url: String,
        version: String? = null,
        source: String? = null,
        keepEnabled: Boolean = true,
        fromSync: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        // 崩溃黑名单：连续 3 次加载时 native crash 后禁止自动/订阅同步再尝试，
        // 防止每次同步都崩一次进程。解除途径：卸载该插件后重新添加订阅即可全新重装。
        if (name in blockedPlugins()) {
            return@withContext "插件在黑名单中（曾连续 3 次载入时崩溃，请卸载后重新订阅）"
        }
        // 卸载名单：订阅同步不把用户手动卸载过的插件装回（手动导入视为恢复，清除名单）
        val blockedNames = if (fromSync) store.listUninstalled() else emptySet()
        if (fromSync && name in blockedNames) {
            Log.i("PluginRepository", "skip uninstalled plugin: $name")
            return@withContext null
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
            // 同名插件：指纹不同说明订阅源发布了更新，走更新路径（重新注册 + upsert，
            // 保留原启用状态与安装时间）。旧实现此处静默 return，订阅插件更新永远无法下发
            val oldRecord = existing.firstOrNull { it.name == name }
            val platform = detectPlatform(js) ?: return@withContext "无法解析插件 platform"
            if (fromSync && platform in blockedNames) {
                Log.i("PluginRepository", "skip uninstalled plugin: $platform")
                return@withContext null
            }
            if (!fromSync) {
                store.clearUninstalled(listOf(name, platform))
            }
            // 崩溃看门狗：写标记，若注册时 native crash，下次启动跳过该插件
            val marker = File(appContext.filesDir, "plugin_load_marker")
            marker.writeText(name.ifBlank { platform })
            val loaded = runCatching { runtime.loadPlugin(platform, js) }.getOrDefault(false)
            marker.delete()
            if (!loaded) return@withContext "插件注册失败: $platform"
            val infoRaw = runtime.readInfo(platform) ?: return@withContext "读取插件信息失败: $platform"
            val info = parseInfo(infoRaw)
            if (oldRecord != null) {
                Log.i("PluginRepository", "update plugin $name ${oldRecord.hash.take(6)} -> ${hash.take(6)}")
            }
            store.upsertPlugin(
                PluginRecord(
                    name = name.ifBlank { info.platform },
                    url = url,
                    version = version ?: info.version,
                    enabled = oldRecord?.enabled ?: keepEnabled,
                    installedAt = oldRecord?.installedAt ?: System.currentTimeMillis(),
                    source = js,
                    info = info,
                    loadError = null,
                    hash = hash
                )
            )
            installEvents.add(
                Triple(name.ifBlank { info.platform }, if (oldRecord != null) "updated" else "installed", null)
            )
            refreshFromDb()
            null
        } catch (e: Throwable) {
            installEvents.add(Triple(name, "failed", e.message))
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
            installContent(subUrl, body, fromSync = true)?.let { Log.w("PluginRepository", "syncOne $subUrl: $it") }
        } catch (e: Exception) {
            Log.w("PluginRepository", "syncOne $subUrl: ${e.message}")
        }
    }

    /**
     * 逐个安装订阅里的插件。
     * 必须串行：并发安装会让"源码指纹去重"读到过期快照产生竞态，
     * 也会让崩溃看门狗 marker 文件互相覆盖，无法定位真正崩溃的插件。
     */
    private suspend fun importListJson(body: String, fromSync: Boolean) {
        val arr = runCatching { JSONObject(body).optJSONArray("plugins") }.getOrNull() ?: return
        syncSeenCount.addAndGet(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val pluginUrl = item.optString("url")
            val version = optStr(item, "version")
            if (pluginUrl.isEmpty()) continue
            // 不再使用白名单限制，否则订阅里绝大多数音源都会被静默丢弃，
            // 表现为"首页只装上一个插件"。崩溃防护由看门狗 + 永久黑名单负责。
            val err = install(name, pluginUrl, version = version, fromSync = fromSync)
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

    /** 读取可选字符串字段：缺失/NULL/空串统一返回 null（避免 optString(key, null) 的类型不匹配警告）。 */
    private fun optStr(o: JSONObject, key: String): String? = o.optString(key).takeIf { it.isNotEmpty() }

    private fun parseInfo(json: JSONObject): PluginInfo {
        fun JSONArray.toStrings(): List<String> =
            (0 until length()).map { i -> optString(i) }

        val vars = json.optJSONArray("userVariables")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: JSONObject()
                UserVarDef(
                    key = o.optString("key"),
                    name = o.optString("name", o.optString("key")),
                    type = optStr(o, "type")
                )
            }
        } ?: emptyList()

        return PluginInfo(
            platform = json.optString("platform", ""),
            version = optStr(json, "version"),
            author = optStr(json, "author"),
            srcUrl = optStr(json, "srcUrl"),
            appVersion = optStr(json, "appVersion"),
            description = optStr(json, "description"),
            cacheControl = optStr(json, "cacheControl"),
            primaryKey = json.optJSONArray("primaryKey")?.toStrings() ?: emptyList(),
            supportedSearchType = json.optJSONArray("supportedSearchType")?.toStrings() ?: emptyList(),
            userVariables = vars,
            hints = json.optJSONObject("hints")
        )
    }
}