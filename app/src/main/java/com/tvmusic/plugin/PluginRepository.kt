package com.tvmusic.plugin

import android.content.Context
import android.util.Log
import com.tvmusic.model.PluginInfo
import com.tvmusic.model.PluginRecord
import com.tvmusic.data.PluginStore
import com.tvmusic.model.UserVarDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    // M1：从共享 relaxed 客户端派生（订阅下载/更新可能较慢），复用连接池。
    private val okHttp = com.tvmusic.net.HttpClients.derive(com.tvmusic.net.HttpClients.relaxed).build()

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

    /** 同步进度：当前已处理数/总数，null=未同步。 */
    private val _syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val syncProgress: StateFlow<Pair<Int, Int>?> = _syncProgress.asStateFlow()

    /** DB 中已有插件已全部注册进引擎（warmup 完成）。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        refreshFromDb()
    }

    fun refreshFromDb() {
        // 元数据轻量查询：StateFlow 常驻内存，不放源码（warmup 注册用全量查询，互不影响）
        _plugins.value = store.loadPluginMetas()
        _subscribed.value = store.listSubscriptions().map { it.url }
        // 同步进行中不裁剪（插件正逐个安装，尚未落库的音源会被误删并打乱用户排序）
        if (!_syncing.value) pruneSourceOrder()
        // 插件集合的任何变化（安装/卸载/启停）都会让引擎侧可用性探测结果过期，
        // 必须立刻失效缓存；否则新装/重新启用的插件最长 30s 不出现在搜索与换源候选里，
        // 表现为"插件明明装上了却搜不到/换不到它"。
        runtime.invalidateAvailability()
    }

    /**
     * 自愈清理音源优先级（sourceOrder）中的「幽灵项」：只保留仍存在于插件库的
     * platform/name。
     *
     * 历史 bug：旧 [detectPlatform] 对混淆后的插件 JS 会把方法名（supportedQualities /
     * getMusicSheetInfo）或随机标识符（OTpDp / gNEyM）误认成 platform，
     * [appendToSourceOrder] 把它写进配置。这些项没有对应的插件，远程管理「音源」页与
     * TV 端都会渲染成「已卸载 / 订阅已移除」灰行，用户误以为订阅文件里有脏数据。
     */
    private fun pruneSourceOrder(): Int {
        val valid = _plugins.value
            .mapNotNull { (it.info?.platform ?: it.name).takeIf { s -> s.isNotBlank() } }
            .toSet()
        // 插件库为空（全新安装、尚未同步完成）时不动配置，避免把待安装的顺序清空
        if (valid.isEmpty()) return 0
        val prefs = appContext.getSharedPreferences("search_config", Context.MODE_PRIVATE)
        val cur = readSourceOrder(prefs)
        if (cur.isEmpty()) return 0
        val kept = cur.filter { it in valid }
        if (kept.size == cur.size) return 0
        prefs.edit()
            .putString("sourceOrder", org.json.JSONArray().apply { kept.forEach { put(it) } }.toString())
            .apply()
        Log.i("PluginRepository", "pruneSourceOrder: removed ${cur.size - kept.size} ghost entries")
        return cur.size - kept.size
    }

    /**
     * 启动时把 DB 里已保存的插件源码直接注册进 JS 引擎（无需联网重新下载），
     * 注册完成后才对外发射插件列表，保证首页/搜索首屏不会拿到“插件未加载”。
     */
    fun warmup() {
        scope.launch(Dispatchers.IO) {
            // 崩溃看门狗**只做诊断，不再封禁插件**（用户明确要求「不应该封禁」）：
            // 标记文件残留说明上次启动在加载某个插件时 native crash；
            // 这里只累计次数并打日志，方便定位坏插件，下次启动仍然重试它——
            // 旧实现累计到 3 次就永久跳过 + 写 load_error，一次瞬时问题（内存紧张、
            // 引擎忙）会让插件永久"消失"，用户只能卸载重装配件才能恢复。
            val marker = File(appContext.filesDir, "plugin_load_marker")
            if (marker.exists()) {
                val crashedName = runCatching { marker.readText().trim() }.getOrNull()
                marker.delete()
                if (!crashedName.isNullOrBlank()) {
                    val counts = readBlocklistCounts()
                    val bumped = (counts[crashedName] ?: 0) + 1
                    counts[crashedName] = bumped
                    writeBlocklistCounts(counts)
                    Log.w(
                        "PluginRepository",
                        "plugin '$crashedName' crashed during load x$bumped (diagnostic only, retrying)"
                    )
                }
            }
            // 不按崩溃次数/历史 load_error 过滤：每次启动都重新尝试所有带源码的插件。
            val records = store.loadPlugins().filter { it.source != null }
            var okCount = 0
            for (p in records) {
                val platform = p.info?.platform ?: detectPlatformSafe(p.source ?: "")
                if (platform.isNullOrBlank()) continue
                val marker = File(appContext.filesDir, "plugin_load_marker")
                marker.writeText(p.name)
                // 只注册主引擎（warmup 提速 3 倍）：并行引擎经 ensureRegistered 按需补注册
                val loaded = runCatching { runtime.registerPrimary(platform, p.source!!) }.getOrDefault(false)
                marker.delete()
                if (loaded) {
                    removeBlocklistEntry(p.name)
                    // 上次失败留下的 load_error 只作诊断，成功一次即清掉
                    if (p.loadError != null) store.clearLoadErrorByName(p.name)
                    okCount++
                    // 每注册成功一个就刷新列表：首页/搜索侧插件渐进可见，不必等全部完成
                    refreshFromDb()
                } else {
                    // 失败只记录诊断，不再把它当"永久封禁"——下次启动继续重试
                    store.markLoadErrorByName(p.name, "register failed")
                    Log.w("PluginRepository", "register failed: ${p.name}")
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
     * 同时把该插件从远程管理配置的音源优先级（sourceOrder）中移除——否则
     * /api/plugins 里已没有它，配置里却还在，前端会一直显示"已卸载"幽灵行。
     */
    fun uninstall(nameOrPlatform: String): Boolean {
        val hit = store.deletePlugin(nameOrPlatform) ?: return false
        store.markUninstalled(hit.first)
        hit.second?.let { store.markUninstalled(it) }
        removeFromSourceOrder(listOfNotNull(hit.first, hit.second))
        refreshFromDb()
        return true
    }

    /** 一键全部卸载：清空全部插件与变量并记入卸载名单（订阅保留，但不再自动复装）。 */
    fun uninstallAll(): Int {
        val metas = store.loadPluginMetas()
        val keys = metas.flatMap { listOfNotNull(it.name, it.info?.platform) }
        val n = store.deleteAllPlugins()
        if (n > 0) {
            removeFromSourceOrder(keys)
            refreshFromDb()
        }
        return n
    }

    /** 把指定插件从后台配置的音源优先级顺序（SearchSettings.sourceOrder）中移除。 */
    private fun removeFromSourceOrder(platforms: Collection<String>) {
        val prefs = appContext.getSharedPreferences("search_config", Context.MODE_PRIVATE)
        val cur = readSourceOrder(prefs)
        val targets = platforms.filter { it.isNotBlank() }.toSet()
        val kept = cur.filter { it !in targets }
        if (kept.size == cur.size) return
        prefs.edit()
            .putString("sourceOrder", org.json.JSONArray().apply { kept.forEach { put(it) } }.toString())
            .apply()
    }

    /** 安装成功后把新音源追加进远程管理的音源优先级配置——否则前端列表（按 sourceOrder 渲染）
     *  完全不显示该插件，用户会误以为 js 导入失败（"装上了但列表看不到"的根因）。 */
    private fun appendToSourceOrder(platform: String) {
        if (platform.isBlank()) return
        val prefs = appContext.getSharedPreferences("search_config", Context.MODE_PRIVATE)
        val cur = readSourceOrder(prefs)
        if (platform in cur) return
        prefs.edit()
            .putString("sourceOrder", org.json.JSONArray().apply { cur.forEach { put(it) }; put(platform) }.toString())
            .apply()
    }

    private fun readSourceOrder(prefs: android.content.SharedPreferences): List<String> =
        runCatching {
            val a = org.json.JSONArray(prefs.getString("sourceOrder", "[]") ?: "[]")
            (0 until a.length()).map { a.optString(it) }
        }.getOrDefault(emptyList())

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

    /**
     * 下载 url 内容，失败返回 null。HTML 响应（如 404 错误页）也返回 null。
     *
     * 重试：GitHub Pages / 图床类 CDN 在短时间内被连续请求数十个文件时会返回 429/5xx
     * 或直接断连。旧实现一次失败即放弃，实测订阅里「汽水音乐」（484KB）、「聚合」（2.9MB）
     * 两个插件在每次同步都装不上（报告 CDN限速/404），重试后即可成功。
     * 只对网络异常与 429/5xx 重试；404/403 是确定性错误，重试无意义。
     */
    private fun fetchOk(url: String, retries: Int = 2): String? {
        var attempt = 0
        while (true) {
            try {
                okHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return null
                        // CDN/源站返回 HTML 错误页时 body 不含 JS 特征，直接拒绝避免误安装
                        if (body.contains("<!DOCTYPE") || body.contains("<html") || body.contains("<head>")) return null
                        return body
                    }
                    val code = resp.code
                    if (code != 429 && code !in 500..599) {
                        Log.w("PluginRepository", "fetch $url -> HTTP $code")
                        return null
                    }
                    Log.w("PluginRepository", "fetch $url -> HTTP $code, will retry (#${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w("PluginRepository", "fetch $url failed: ${e.message}, will retry (#${attempt + 1})")
            }
            if (attempt >= retries) return null
            attempt++
            try {
                Thread.sleep(800L * attempt)
            } catch (_: InterruptedException) {
                return null
            }
        }
    }

    /** 内容识别安装：plugins.json 列表 → 逐个安装；否则按单个 .js 插件安装。 */
    private suspend fun installContent(url: String, body: String, fromSync: Boolean): String? {
        val trimmed = body.trim()
        // Try {"plugins": [...]}
        val arr1 = runCatching { JSONObject(trimmed).optJSONArray("plugins") }.getOrNull()
        if (arr1 != null && arr1.length() > 0) {
            importListJsonObj(trimmed, fromSync)
            return null
        }
        // Try direct array [...]
        val arr2 = runCatching { org.json.JSONArray(trimmed) }.getOrNull()
        if (arr2 != null && arr2.length() > 0) {
            // Check if it's array of plugin objects
            val first = arr2.optJSONObject(0)
            if (first != null && (first.has("url") || first.has("srcUrl") || first.has("scriptUrl"))) {
                importListJsonArray(arr2, fromSync)
                return null
            }
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

    /** 批量导入结果：installed=装好/恢复的项，skipped=已存在跳过数，failed=失败明细。 */
    data class ImportReport(
        val installed: List<String>,
        val skipped: Int,
        val failed: List<String>
    )

    /**
     * 导出插件恢复数据（轻量备份）：每个插件的 js 直链地址 + 音源顺序，不含源码、不含订阅合集。
     * 订阅安装的插件其 record.url 同为条目级 js 地址（importListJson 传 install 的是条目 url，
     * 不是订阅地址），所以直接取全部插件地址即可逐个恢复。
     */
    fun exportBackupJson(): String {
        val plugins = org.json.JSONArray()
        store.loadPlugins()
            .filter { !it.url.isNullOrBlank() }
            .forEach { p ->
                plugins.put(
                    org.json.JSONObject()
                        .put("name", p.name)
                        .put("url", p.url)
                        .put("enabled", p.enabled)
                )
            }
        val order = org.json.JSONArray()
        com.tvmusic.config.SearchSettings.load(appContext).sourceOrder.forEach { order.put(it) }
        return org.json.JSONObject()
            .put("type", "musicfreetv-plugins")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("plugins", plugins)
            .put("sourceOrder", order)
            .toString(2)
    }

    /**
     * 从备份 JSON 恢复（exportBackupJson 的格式）：
     * 逐个插件 js 地址拉取安装（清除卸载名单，等同手动导入），最后恢复 sourceOrder。
     * 不处理订阅源——备份里没有合集地址，恢复的插件独立于订阅存在。
     */
    suspend fun importBackupJson(json: String): ImportReport = withContext(Dispatchers.IO) {
        val root = org.json.JSONObject(json)
        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val arr = root.optJSONArray("plugins") ?: org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url", "").trim()
            if (u.isBlank()) continue
            val label = o.optString("name", "").ifBlank { u.takeLast(40) }
            val err = importFromUrl(u)
            if (err == null) installed.add(label) else failed.add("$label: $err")
        }

        val order = root.optJSONArray("sourceOrder") ?: org.json.JSONArray()
        val orderList = (0 until order.length()).map { order.optString(it) }.filter { it.isNotBlank() }
        if (orderList.isNotEmpty()) {
            val cur = com.tvmusic.config.SearchSettings.load(appContext)
            com.tvmusic.config.SearchSettings.save(appContext, cur.copy(sourceOrder = orderList))
        }

        ImportReport(installed, 0, failed)
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
        // 旧实现在这里查崩溃黑名单直接拒绝安装（「连续 3 次载入崩溃 → 永久禁止」）。
        // 用户明确要求不封禁插件：任何插件都应允许重新安装/更新，崩溃只记日志与诊断数据。
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
            // 去重与更新判定只需要 hash/enabled/installedAt，元数据查询即可（不物化全部旧源码）
            val existing = store.loadPluginMetas()
            existing.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }?.let { dup ->
                Log.i("PluginRepository", "skip duplicate source: ${dup.name} ($name)")
                return@withContext null
            }
            // 同名插件：指纹不同说明订阅源发布了更新，走更新路径（重新注册 + upsert，
            // 保留原启用状态与安装时间）。旧实现此处静默 return，订阅插件更新永远无法下发
            val oldRecord = existing.firstOrNull { it.name == name }
            val rawPlatform = detectPlatform(js)
            // detectPlatform 对 Parcel/webpack 混淆产物可能返回 null（platform 值被加密为函数调用），
            // 此时回退使用订阅条目中的 name 作为 platform（内部标识一致即可，readInfo 会覆盖为实际值）。
            var platform = rawPlatform?.takeIf { it.isNotEmpty() }
                ?: detectPlatformFromUrl(url, js)?.takeIf { it.isNotEmpty() }
                ?: name.ifEmpty { null }
            if (platform == null) return@withContext "无法解析插件 platform"
            val jsSource = js
            if (fromSync && platform in blockedNames) {
                Log.i("PluginRepository", "skip uninstalled plugin: $platform")
                return@withContext null
            }
            // 崩溃看门狗：写标记，若注册时 native crash，下次启动跳过该插件
            val marker = File(appContext.filesDir, "plugin_load_marker")
            marker.writeText(name.ifBlank { platform })
            val loaded = runCatching { runtime.registerPrimary(platform, jsSource) }.getOrDefault(false)
            marker.delete()
            if (!loaded) {
                // 带上源码长度：用于区分「下载被截断」（长度与订阅源不符）
                // 与「引擎/插件语法不兼容」（长度正常但 QuickJS 编译失败）。
                Log.w("PluginRepository", "register failed: $name platform=$platform sourceLen=${jsSource.length}")
                return@withContext "插件注册失败: $platform（源码 ${jsSource.length} 字符）"
            }
            val infoRaw = runtime.readInfo(platform) ?: return@withContext "读取插件信息失败: $platform"
            val info = parseInfo(infoRaw)
            // 引擎读回的真实 platform 才是权威值：detectPlatform 只有字面量写法才可靠，
            // 混淆产物会被误取成方法名（supportedQualities/getMusicSheetInfo）或随机标识符
            // （OTpDp/gNEyM）。若继续拿这个猜测值当注册 key，同进程内所有用真实 platform
            // 发起的调用都会查不到插件（表现为"装上了却不可用"），且会污染音源优先级。
            val realPlatform = info.platform.trim()
            if (realPlatform.isNotEmpty() && realPlatform != platform) {
                Log.i("PluginRepository", "platform corrected: $platform -> $realPlatform ($name)")
                runCatching { runtime.registerPrimary(realPlatform, jsSource) }.getOrDefault(false)
                platform = realPlatform
            }
            if (fromSync && platform in blockedNames) {
                Log.i("PluginRepository", "skip uninstalled plugin (real platform): $platform")
                return@withContext null
            }
            if (!fromSync) {
                store.clearUninstalled(listOf(name, platform))
            }
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
                    source = jsSource,
                    info = info,
                    loadError = null,
                    hash = hash
                )
            )
            // 必须用引擎读出的真实 platform：detectPlatform 对混淆产物可能返回
            // 随机标识符/方法名（如 'getMediaSource':function 形态被误取），
            // 直接入列会污染音源优先级，导致搜索/换源候选大面积丢失。
            appendToSourceOrder(info.platform.ifBlank { platform })
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

    /** 源码指纹：sha256 前 16 位十六进制，作为插件唯一 id。
     *  非安全用途（仅内容变更检测）；算法从 SHA-1 升级为 SHA-256，变更后已有插件
     *  会被判定为"源码已变"触发一次重新注册，属可接受的一次性开销。 */
    private fun sourceHash(source: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(source.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
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
    private suspend fun importListJsonObj(body: String, fromSync: Boolean) {
        val arr = runCatching { JSONObject(body).optJSONArray("plugins") }.getOrNull() ?: return
        importListJsonArray(arr, fromSync)
    }

    private suspend fun importListJsonArray(arr: org.json.JSONArray, fromSync: Boolean) {
        syncSeenCount.addAndGet(arr.length())
        // 串行下载+注册：每个插件下载完再下载下一个，避免触发 CDN 限速
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val pluginUrl = optStr(item, "url") ?: optStr(item, "srcUrl") ?: optStr(item, "scriptUrl")
                ?: continue
            val version = optStr(item, "version")
            val idx = i + 1
            _syncProgress.value = idx to arr.length()

            val js = fetchOk(pluginUrl)
            if (js == null) {
                Log.w("PluginRepository", "sync $name: 下载失败（CDN限速/404）")
                if (fromSync) installEvents.add(Triple(name, "failed", "CDN限速/404"))
                continue
            }
            // 不再使用白名单限制，否则订阅里绝大多数音源都会被静默丢弃，
            // 表现为"首页只装上一个插件"。崩溃防护由看门狗 + 永久黑名单负责。
            val err = install(name, pluginUrl, version = version, source = js, fromSync = fromSync)
            if (err != null) {
                Log.w("PluginRepository", "install $name: $err")
                if (fromSync) installEvents.add(Triple(name, "failed", err))
            }
        }
        // 同步完成，清除进度
        _syncProgress.value = null
    }

    private suspend fun importListJson(body: String, fromSync: Boolean) {
        val arr = runCatching { JSONObject(body).optJSONArray("plugins") }.getOrNull() ?: return
        importListJsonArray(arr, fromSync)
    }

    /**
     * 从源码中探测真实 platform：取源码中"最后一次出现"的 `platform: "xxx"` 或 `platform: 'xxx'`。
     * 同时兼容 `platform = "..."` 赋值写法，以及 `var PLATFORM = "xxx"; platform: PLATFORM` 这类变量引用写法。
     * Parcel 打包产物与 TS 编译产物都把插件对象里的 platform 放在文件末尾附近，
     * 内层调用里的 platform（如 "yqq.json"、"WebFilter"）都出现在前面。
     */
    private fun detectPlatform(source: String): String? {
        // 1) 字面量形式（优先）。键可能带引号：混淆产物常写成 'platform':'qq' / "platform":"x"
        val literal = Regex("""["']?platform["']?\s*[:=]\s*["']([^"']*)["']""").findAll(source).toList()
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !isNoisePlatform(it) }
        if (literal.isNotEmpty()) return literal.last()

        // 2) 变量引用形式：先收集 var/let/const X = 'xxx' 映射，
        //    再找源码中 platform: X / platform = X 引用的标识符，取最后一次的对应字面量。
        val vars = Regex("""(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*["']([^"']*)["']""")
            .findAll(source).toList()
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        if (vars.isEmpty()) return null
        val refs = Regex("""["']?platform["']?\s*[:=]\s*([A-Za-z_$][\w$]*)""").findAll(source).toList()
        for (m in refs.reversed()) {
            val v = vars[m.groupValues[1]] ?: continue
            if (v.isNotEmpty() && !isNoisePlatform(v)) return v
        }

        // 3) 混淆产物：平台名以对象字面量值出现，如 {'netease':0x1} / {'kuwo':searchKuwo}
        //    匹配 'string':<非字符串值> 模式。混淆代码中会混杂大量随机标识符（如 'aUiUA':_0x1(...)），
        //    平台名通常出现在 SOURCE_WEIGHT 等特定变量附近（偏后）。因此取所有合法匹配，
        //    优先选最后一个字母数字混合型候选（真实平台名如 netease/qq 更可能是字母数字混合，
        //    而非纯大写字母随机串）。
        val obfuscated = Regex(
            """'([^'\s]{2,20})'\s*:\s*(?!["'])(?!['"])(?:0x[0-9a-fA-F]+|[a-zA-Z_$][\w$]*\s*\(|async\s|function\b|true|false|null|undefined)"""
        ).findAll(source).toList()
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !isNoisePlatform(it) }
        if (obfuscated.isNotEmpty()) {
            // 优先选字母数字混合候选（真实平台名），退而求其次取最后一个
            val mixed = obfuscated.filter { it.any { c -> c in '0'..'9' } || it.any { c -> c in 'a'..'z' } && it.any { c -> c in 'A'..'Z' } }
            return if (mixed.isNotEmpty()) mixed.last() else obfuscated.last()
        }

        return null
    }

    /** 过滤掉噪音词：协议标识、非平台值等。 */
    private fun isNoisePlatform(s: String): Boolean =
        s in setOf("pc", "web", "WebFilter", "H5", "h5") ||
            s.endsWith(".json") ||
            s.length > 30  // 混淆字符串通常很短

    /**
     * 从插件 URL 文件名推断平台名：如 netease-source.plugin.v1.9.27.js → "netease"。
     * 作为 detectPlatform 失败时的最后兜底——文件名是订阅源提供的明确标识，
     * 且在源码中验证存在该字符串即可确认有效性，避免误匹配。
     */
    private fun detectPlatformFromUrl(url: String, source: String): String? {
        val fileName = url.substringAfterLast("/")
            .substringBefore(".js")
            .substringBefore("-source")
            .substringBefore("-plugin")
            .trim()
        if (fileName.isEmpty() || fileName.length > 30) return null
        // 验证：源码中确实包含该字符串（避免文件名与平台名完全无关的极端情况）
        if (!source.contains("\"$fileName\"") && !source.contains("'$fileName'")) return null
        return fileName
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