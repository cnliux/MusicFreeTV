package com.tvmusic.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tvmusic.BuildConfig
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.FavList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网远程管理服务。
 * 在 TV 上开一个轻量 HTTP 端口，手机/PC 浏览器打开根路径即可：
 * 播放器控制 + 播放列表置顶，其次搜索推歌，底部管理订阅源与插件。
 */
class RemoteConfigService : Service() {

    companion object {
        private const val TAG = "RemoteConfigService"
        const val PORT = 9527
        private const val NOTIFICATION_ID = 101

        /** SSE 长连接并发上限：超出直接 503，浏览器自动降级 2s 轮询。 */
        private const val MAX_SSE_CLIENTS = 6

        /** 图片代理单张上限 10MB，防止异常大图整张入内存 OOM。 */
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        /** 图片代理重定向上限（手动逐跳跟随，每跳都做内网地址校验）。 */
        private const val MAX_IMAGE_HOPS = 5
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_HEADER_COUNT = 100
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        /** 图片代理专用共享 OkHttpClient：自身不自动跟随重定向，由代码逐跳校验后跟随。 */
        private val imageHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
        private val SORT_KEYS = setOf(
            com.tvmusic.config.SearchSettings.SORT_DEFAULT,
            com.tvmusic.config.SearchSettings.SORT_DURATION,
            com.tvmusic.config.SearchSettings.SORT_TITLE,
            com.tvmusic.config.SearchSettings.SORT_ARTIST
        )
        @Volatile
        var instance: RemoteConfigService? = null
            private set

        @Volatile
        var port: Int = PORT
            private set

        @Volatile
        var hostDisplay: String = "0.0.0.0"
            private set

        fun ensureStarted(context: Context) {
            val c = context.applicationContext
            if (instance != null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(
                    Intent(c, RemoteConfigService::class.java)
                )
            } else {
                c.startService(Intent(c, RemoteConfigService::class.java))
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * HTTP 线程池（含 SSE 长连接）。
     * 用 SynchronousQueue + 较大 max：并发一上来立即扩线程，而不是等队列塞满才扩——
     * 旧设计 LinkedBlockingQueue(64) 在队列不满时永远不会超过 core=2 条线程，
     * 1~2 个标签页的 SSE 长连接就能占死全部核心线程，让 /api/status、/api/plugins
     * 等短请求无限排队，远程页面表现为"无法动态加载数据"。
     */
    private val httpPool: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 16, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue()
        ).apply { allowCoreThreadTimeOut(true) }
    }

    /** SSE 并发限流：长连接最多占 MAX_SSE_CLIENTS 条线程，剩余线程永远留给短请求。 */
    private val sseSlots = java.util.concurrent.Semaphore(MAX_SSE_CLIENTS)

    /**
     * SSE 写 watchdog：Java socket 写操作没有超时可用（soTimeout 只管读），
     * 客户端锁屏/休眠/半开连接会让 out.write 永久阻塞并占死线程。
     * 每帧写之前挂一个"8 秒未完成就强制关 socket"的定时任务，写完取消，兜底释放线程。
     */
    private val sseWatchdog: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "remote-sse-watchdog").apply { isDaemon = true }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        acceptThread?.takeIf { it.isAlive }?.let { return }
        acceptThread = Thread({ runServer() }, "remote-config-http").apply {
            isDaemon = true
            start()
        }
    }

    private fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            serverSocket = ss
            port = ss.localPort
            hostDisplay = resolveLocalIp()
            Log.i(TAG, "config server on $hostDisplay:$port")

            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                // 每个连接交给线程池并发处理：浏览器会并发多条轮询/封面代理连接，
                // 单线程串行会让一条慢请求（如 /api/img 代理最坏阻塞十几秒）卡死整个服务。
                try {
                    httpPool.execute {
                        try {
                            sock.soTimeout = 15_000
                            handle(sock)
                        } catch (e: Exception) {
                            Log.w(TAG, "handle: ${e.message}")
                        } finally {
                            try { sock.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    // 线程池已满：直接拒绝，关闭连接，避免 accept 循环被拖住
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "server died: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        // 去掉查询参数再路由，允许 /?v=xxx 做缓存刷新
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")

        // 读头，并限制数量与总大小，避免慢连接或超长头消耗服务内存。
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        var headerCount = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES) ?: break
            if (line.isBlank()) break
            headerCount++
            if (headerCount > MAX_HEADER_COUNT) throw IllegalArgumentException("too many headers")
            headerBytes += line.toByteArray(Charsets.UTF_8).size
            if (headerBytes > MAX_HEADER_BYTES) throw IllegalArgumentException("headers too large")
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }

        val origin = headers["origin"]
        val host = headers["host"].orEmpty()
        if (!origin.isNullOrBlank() && runCatching { java.net.URI(origin).authority }.getOrNull() != host) {
            respond(socket, 403, JSONObject().put("ok", false).put("error", "cross-origin request denied").toString())
            return
        }

        when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                respondHtml(socket, 200, PAGE_HTML)
            }
            method == "GET" && (path == "/info" || path == "/api/status") -> {
                respond(socket, 200, infoJson().toString())
            }
            method == "GET" && path.startsWith("/api/img") -> {
                val target = java.net.URLDecoder.decode(
                    query.substringAfter("url=", ""), Charsets.UTF_8.name()
                )
                proxyImage(socket, target)
            }
            method == "GET" && path == "/api/subscriptions" -> {
                respond(socket, 200, subscriptionsJson().toString())
            }
            method == "POST" && path == "/api/subscriptions" -> {
                val body = readBody(input, headers)
                val url = runCatching { JSONObject(body).optString("url", "") }.getOrDefault("")
                if (url.isBlank()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing url").toString())
                    return
                }
                val repo = app().repository
                repo.addSubscription(url)
                var msg = "已添加订阅并同步"
                if (url.substringBefore('?').endsWith(".js", ignoreCase = true)) {
                    // js 直链：等安装完成再响应，把真实结果告诉用户。
                    // 旧实现只加订阅后异步同步，安装失败（下载失败/引擎注册超时）时
                    // 用户只看到列表里没有插件 + 配置残留的"已卸载"幽灵行，无从排查。
                    val err = runCatching {
                        kotlinx.coroutines.runBlocking { repo.importFromUrl(url) }
                    }.getOrNull()
                    msg = if (err == null) "插件安装成功" else "插件安装失败：$err"
                }
                repo.syncAll(force = true)
                respond(socket, 200, JSONObject().put("ok", true).put("message", msg).toString())
            }
            method == "POST" && path == "/api/subscriptions/remove" -> {
                val body = readBody(input, headers)
                val url = runCatching { JSONObject(body).optString("url", "") }.getOrDefault("")
                app().repository.removeSubscription(url)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "GET" && path == "/api/plugins" -> {
                respond(socket, 200, pluginsJson().toString())
            }
            method == "POST" && path == "/api/plugins/toggle" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val name = json?.optString("name", "") ?: ""
                if (name.isBlank()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing name").toString())
                    return
                }
                val ok = app().repository.toggleEnabled(name, json?.optBoolean("enabled", true) ?: true)
                if (ok) respond(socket, 200, JSONObject().put("ok", true).toString())
                else respond(socket, 404, JSONObject().put("ok", false).put("error", "plugin not found: $name").toString())
            }
            method == "POST" && path == "/api/plugins/uninstall" -> {
                val body = readBody(input, headers)
                val name = runCatching { JSONObject(body).optString("name", "") }.getOrDefault("")
                if (name.isBlank()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing name").toString())
                    return
                }
                val ok = app().repository.uninstall(name)
                if (ok) respond(socket, 200, JSONObject().put("ok", true).toString())
                else respond(socket, 404, JSONObject().put("ok", false).put("error", "plugin not found: $name").toString())
            }
            // 一键全部卸载：清空全部插件与变量；卸载名单阻止订阅同步复装（重新添加订阅可恢复）
            method == "POST" && path == "/api/plugins/uninstallAll" -> {
                val n = app().repository.uninstallAll()
                respond(
                    socket, 200,
                    JSONObject().put("ok", true).put("count", n)
                        .put("message", if (n > 0) "已卸载 $n 个插件" else "当前没有插件").toString()
                )
            }
            // 备份/恢复：导出 js 地址+订阅+音源顺序（不含源码），导入按地址重新拉取安装
            method == "GET" && path == "/api/plugins/export" -> {
                val date = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                respondDownload(socket, app().repository.exportBackupJson(), "musicfreetv-backup-$date.json")
            }
            method == "POST" && path == "/api/plugins/import" -> {
                val body = readBody(input, headers)
                val report = runCatching {
                    kotlinx.coroutines.runBlocking { app().repository.importBackupJson(body) }
                }.fold(
                    onSuccess = { it },
                    onFailure = {
                        respond(socket, 400, JSONObject().put("ok", false)
                            .put("error", "备份文件解析失败: ${it.message}").toString())
                        return
                    }
                )
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("installed", org.json.JSONArray(report.installed))
                    .put("skipped", report.skipped)
                    .put("failed", org.json.JSONArray(report.failed))
                    .put("message", buildString {
                        append("恢复完成：成功 ${report.installed.size}")
                        if (report.skipped > 0) append("，跳过 ${report.skipped}")
                        if (report.failed.isNotEmpty()) append("，失败 ${report.failed.size}")
                    }.toString())
                    .toString())
            }
            // 用户变量（Cookie/SESSDATA 等）：一套通用接口服务所有插件，
            // 读写均由插件头部的 userVariables 声明驱动，新增插件无需改这里。
            method == "GET" && path == "/api/plugins/vars" -> {
                respond(socket, 200, pluginVarsJson().toString())
            }
            method == "POST" && path == "/api/plugins/vars" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val platform = json?.optString("platform", "") ?: ""
                if (platform.isBlank()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing platform").toString())
                    return
                }
                val varsObj = json?.optJSONObject("vars") ?: JSONObject()
                val existing = app().store.loadVariables(platform)
                val secretKeys = app().store.loadPluginMetas()
                    .firstOrNull { (it.info?.platform ?: it.name) == platform }
                    ?.info?.userVariables.orEmpty()
                    .filter { it.type.equals("password", ignoreCase = true) }
                    .map { it.key }.toSet()
                val map = HashMap<String, String>()
                val keys = varsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val submitted = varsObj.optString(k)
                    map[k] = if (k in secretKeys && submitted.isEmpty()) existing[k].orEmpty() else submitted
                }
                app().store.replaceVariables(platform, map)
                respond(socket, 200, JSONObject().put("ok", true).put("message", "已保存 $platform 的变量").toString())
            }
            method == "POST" && path == "/api/sync" -> {
                app().repository.syncAll(force = true)
                respond(socket, 200, JSONObject().put("ok", true).put("message", "开始同步订阅").toString())
            }
            method == "GET" && path == "/api/search/config" -> {
                val s = com.tvmusic.config.SearchSettings.load(app())
                respond(
                    socket, 200,
                    JSONObject()
                        .put("ok", true)
                        .put("sourceOrder", JSONArray().apply { s.sourceOrder.forEach { put(it) } })
                        .put("sortBy", s.sortBy)
                        .put("asc", s.asc)
                        .put("maxTotal", s.maxTotal).toString()
                )
            }
            method == "POST" && path == "/api/search/config" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val order = runCatching {
                    (json?.optJSONArray("sourceOrder") ?: JSONArray()).let { a ->
                        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                    }
                }.getOrDefault(emptyList())
                val sortBy = json?.optString("sortBy", "") ?: ""
                val asc = json?.optBoolean("asc", true) ?: true
                val maxTotal = (json?.optInt("maxTotal", 60) ?: 60).coerceIn(20, 200)
                val settings = com.tvmusic.config.SearchSettings(
                    sourceOrder = order,
                    sortBy = if (sortBy in SORT_KEYS) sortBy else com.tvmusic.config.SearchSettings.SORT_DEFAULT,
                    asc = asc,
                    maxTotal = maxTotal
                )
                com.tvmusic.config.SearchSettings.save(app(), settings)
                respond(socket, 200, JSONObject().put("ok", true).put("message", "已保存搜索设置").toString())
            }
            method == "GET" && path == "/api/search/poll" -> {
                val id = runCatching {
                    java.net.URLDecoder.decode(query.substringAfter("id=", "").substringBefore("&"), Charsets.UTF_8.name())
                }.getOrDefault("")
                val session = searchSessions[id]
                if (session == null) {
                    respond(socket, 404, JSONObject().put("ok", false).put("error", "会话不存在或已过期").toString())
                } else {
                    respondSearchPoll(socket, session)
                }
            }
            method == "GET" && path.startsWith("/api/search") -> {
                val decode: (String) -> String = { s ->
                    runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
                }
                val param: (String, String) -> String = { name, def ->
                    query.split("&").firstOrNull { it.startsWith("$name=") }?.substringAfter("=") ?: def
                }
                val qRaw = param("q", "")
                if (qRaw.isEmpty()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing q").toString())
                    return
                }
                val q = decode(qRaw)
                val page = param("page", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val sources = if (param("sources", "").isBlank()) emptySet()
                    else decode(param("sources", "")).split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val minD = decode(param("minD", "")).toIntOrNull()
                val maxD = decode(param("maxD", "")).toIntOrNull()
                val needArt = param("art", "") == "1"
                val sortBy = decode(param("sort", ""))
                val asc = when (param("asc", "")) {
                    "0" -> false
                    "1" -> true
                    else -> null
                }
                searchAndStart(socket, q, page, SearchReq(sources, minD, maxD, needArt, sortBy, asc))
            }
            method == "POST" && path == "/api/play" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val plugin = json?.optString("plugin", "") ?: ""
                // 优先用搜索结果带回的完整 raw 条目（含 id/mediaId 等插件解析必需字段）
                val item = json?.optJSONObject("raw") ?: json?.optJSONObject("item")
                if (plugin.isBlank() || item == null) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing plugin/item").toString())
                    return
                }
                if (item.optString("platform").isBlank()) item.put("platform", plugin)
                val entry = com.tvmusic.player.QueueEntry(plugin, item)
                com.tvmusic.player.PlayerManager.play(plugin, entry, listOf(entry), 0, source = "$plugin · 远程点播")
                respond(socket, 200, JSONObject().put("ok", true).put("message", "已在电视端开始播放").toString())
            }
            method == "POST" && path == "/api/play/queue" -> {
                // 批量入队播放（如"播放全部"搜索结果）：{ plugin, items: [raw...] }
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val plugin = json?.optString("plugin", "") ?: ""
                val arr = json?.optJSONArray("items")
                if (plugin.isBlank() || arr == null || arr.length() == 0) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing plugin/items").toString())
                    return
                }
                val entries = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { raw ->
                        if (raw.optString("platform").isBlank()) raw.put("platform", plugin)
                        // 每条目可自带 platform（跨源"播放全部"）：缺省才用请求级 plugin
                        val pf = raw.optString("platform").ifBlank { plugin }
                        com.tvmusic.player.QueueEntry(pf, raw)
                    }
                }
                if (entries.isEmpty()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "no valid items").toString())
                    return
                }
                // 首条用其自身来源插件播放，队列内条目解析时各自走粘性引擎
                com.tvmusic.player.PlayerManager.play(entries.first().plugin, entries.first(), entries, 0, source = "${entries.first().plugin} · 远程点播")
                respond(socket, 200, JSONObject().put("ok", true).put("count", entries.size).put("message", "已加入播放列表并开始播放").toString())
            }
            method == "GET" && path == "/api/player" -> {
                respond(socket, 200, playerStatusJson().toString())
            }
            method == "GET" && path == "/api/history" -> {
                // 播放历史（最多 100 条，新→旧）：供远程页"历史"页签一键回放
                val hist = app().playback.history.value.take(100)
                val arr = JSONArray()
                hist.forEach { raw ->
                    arr.put(
                        JSONObject()
                            .put("title", raw.optString("title", ""))
                            .put("artist", raw.optString("artist", ""))
                            .put("platform", raw.optString("platform", ""))
                            .put("raw", raw)
                    )
                }
                respond(socket, 200, JSONObject().put("ok", true).put("total", arr.length()).put("items", arr).toString())
            }
            method == "POST" && path == "/api/history/clear" -> {
                app().playback.clearHistory()
                respond(socket, 200, JSONObject().put("ok", true).put("message", "播放历史已清空").toString())
            }
            method == "GET" && path == "/api/events" -> {
                // SSE 长连接：推送播放器状态，取代浏览器端 2s 轮询。
                // 长连接会长期占用工作线程，必须限流：占满时立刻 503，
                // 浏览器 EventSource 报错后自动降级 2s 轮询，30s 后再重试 SSE。
                if (!sseSlots.tryAcquire()) {
                    respond(socket, 503, JSONObject().put("ok", false).put("error", "sse busy").toString())
                    return
                }
                try {
                    respondEvents(socket)
                } finally {
                    sseSlots.release()
                }
            }
            method == "GET" && path == "/api/themes" -> {
                respond(socket, 200, themesJson().toString())
            }
            method == "POST" && path == "/api/theme" -> {
                val body = readBody(input, headers)
                val id = runCatching { JSONObject(body).optString("id", "") }.getOrDefault("")
                val ok = com.tvmusic.ui.theme.ThemeManager.set(id)
                if (!ok) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "unknown theme id").toString())
                    return
                }
                respond(socket, 200, JSONObject().put("ok", true).put("current", id).toString())
            }
            method == "POST" && path == "/api/player/playpause" -> {
                com.tvmusic.player.PlayerManager.playPause()
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/player/next" -> {
                com.tvmusic.player.PlayerManager.next()
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/player/prev" -> {
                com.tvmusic.player.PlayerManager.prev()
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/player/seek" -> {
                val body = readBody(input, headers)
                val pos = runCatching { JSONObject(body).optLong("pos", -1L) }.getOrDefault(-1L)
                if (pos < 0) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing pos").toString())
                    return
                }
                com.tvmusic.player.PlayerManager.seek(pos)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/player/volume" -> {
                val body = readBody(input, headers)
                val delta = runCatching { JSONObject(body).optDouble("delta", 0.1) }.getOrDefault(0.1)
                com.tvmusic.player.PlayerManager.adjustVolume(delta.toFloat())
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/player/skip" -> {
                val body = readBody(input, headers)
                val index = runCatching { JSONObject(body).optInt("index", -1) }.getOrDefault(-1)
                if (index < 0) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing index").toString())
                    return
                }
                com.tvmusic.player.PlayerManager.skipTo(index)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/player/mode" -> {
                val body = readBody(input, headers)
                val mode = runCatching { JSONObject(body).optString("mode", "") }.getOrDefault("")
                val pm = when (mode) {
                    "ORDER" -> com.tvmusic.player.PlayMode.ORDER
                    "LOOP_ONE" -> com.tvmusic.player.PlayMode.LOOP_ONE
                    "SHUFFLE" -> com.tvmusic.player.PlayMode.SHUFFLE
                    else -> null
                }
                if (pm == null) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "invalid mode").toString())
                    return
                }
                com.tvmusic.player.PlayerManager.setPlayMode(pm)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }

            // ---------------- 收藏专辑管理 ----------------
            method == "GET" && path == "/api/fav/lists" -> {
                respond(socket, 200, favListsJson().toString())
            }
            method == "POST" && path == "/api/fav/lists/create" -> {
                val body = readBody(input, headers)
                val name = runCatching { JSONObject(body).optString("name", "") }.getOrDefault("")
                if (name.isBlank()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing name").toString())
                    return
                }
                val id = app().playback.addList(name)
                respond(socket, 200, JSONObject().put("ok", true).put("id", id ?: "").toString())
            }
            method == "POST" && path == "/api/fav/lists/rename" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                app().playback.renameList(json?.optString("id", "") ?: "", json?.optString("name", "") ?: "")
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/fav/lists/remove" -> {
                val body = readBody(input, headers)
                val id = runCatching { JSONObject(body).optString("id", "") }.getOrDefault("")
                app().playback.removeList(id)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "GET" && path.startsWith("/api/fav/items") -> {
                val id = query.substringAfter("id=", "").substringBefore("&")
                val page = query.substringAfter("page=", "1").substringBefore("&").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = query.substringAfter("size=", "20").substringBefore("&").toIntOrNull()?.coerceIn(1, 200) ?: 20
                respond(socket, 200, favItemsJson(id, page, size).toString())
            }
            method == "POST" && path == "/api/fav/toggle" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val listId = json?.optString("listId", "") ?: ""
                val item = json?.optJSONObject("raw")
                if (listId.isBlank() || item == null) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing listId/raw").toString())
                    return
                }
                // 搜索结果 raw 不带 platform，收藏前补全，否则"我的列表"播放取不到插件
                if (item.optString("platform").isBlank()) {
                    val p = json?.optString("plugin", "") ?: ""
                    if (p.isNotBlank()) item.put("platform", p)
                }
                val fav = app().playback.toggleFavorite(item, listId)
                respond(socket, 200, JSONObject().put("ok", true).put("favorited", fav).toString())
            }
            method == "POST" && path == "/api/fav/addAll" -> {
                // 全部收藏：把搜索结果当前列表批量加入指定收藏夹（按主键自动去重）
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val listId = json?.optString("listId", "") ?: ""
                val arr = json?.optJSONArray("items")
                if (listId.isBlank() || arr == null || arr.length() == 0) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing listId/items").toString())
                    return
                }
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) ?: return@mapNotNull null }
                    .onEach { item ->
                        // 搜索结果 raw 不带 platform，收藏前补全，否则"我的列表"回放取不到插件
                        if (item.optString("platform").isBlank()) {
                            val p = json?.optString("plugin", "") ?: ""
                            if (p.isNotBlank()) item.put("platform", p)
                        }
                    }
                val added = app().playback.addAllToList(listId, items)
                respond(socket, 200, JSONObject().put("ok", true).put("added", added).toString())
            }
            method == "POST" && path == "/api/player/favorite" -> {
                val body = readBody(input, headers)
                val listId = runCatching { JSONObject(body).optString("listId", "") }.getOrDefault("")
                val fav = if (listId.isBlank())
                    com.tvmusic.player.PlayerManager.toggleFavorite()
                else
                    com.tvmusic.player.PlayerManager.toggleFavorite(listId)
                respond(socket, 200, JSONObject().put("ok", true).put("favorited", fav).toString())
            }
            method == "GET" && path == "/api/player/fav-albums" -> {
                // 全部收藏专辑 + 当前播放曲目在各专辑中的收藏状态
                val cur = com.tvmusic.player.PlayerManager.uiState.value.current
                val inLists = if (cur != null) app().playback.favoriteListsOf(cur.raw) else emptySet()
                val arr = JSONArray()
                app().playback.lists.value.forEach { l ->
                    arr.put(
                        JSONObject()
                            .put("id", l.id)
                            .put("name", l.name)
                            .put("count", l.items.size)
                            .put("inList", l.id in inLists)
                    )
                }
                respond(socket, 200, JSONObject().put("ok", true).put("albums", arr).toString())
            }
            method == "GET" && path == "/api/lyric" -> {
                val c = com.tvmusic.ui.theme.LyricSettings.config.value
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("fontSizeSp", c.fontSizeSp)
                    .put("colorHex", c.colorHex)
                    .toString())
            }
            method == "POST" && path == "/api/lyric" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val cur = com.tvmusic.ui.theme.LyricSettings.config.value
                val next = com.tvmusic.ui.theme.LyricConfig(
                    fontSizeSp = json?.optInt("fontSizeSp", cur.fontSizeSp)?.coerceIn(10, 40) ?: cur.fontSizeSp,
                    colorHex = json?.optString("colorHex", cur.colorHex)?.trim()?.trimStart('#')?.take(6)
                        ?.ifBlank { cur.colorHex } ?: cur.colorHex
                )
                com.tvmusic.ui.theme.LyricSettings.update(next)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "GET" && path == "/api/meta" -> {
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("enabled", com.tvmusic.config.MetaSettings.isEnabled)
                    .put("fallbackOtherSource", com.tvmusic.config.MetaSettings.fallbackOtherSource)
                    .put("minPlaySeconds", com.tvmusic.config.MetaSettings.minPlaySeconds)
                    .put("preferPlugin", com.tvmusic.config.MetaSettings.fallbackPreferPlugin)
                    .put("fallbackStrategy", com.tvmusic.config.MetaSettings.fallbackStrategy)
                    .put("coverShape", com.tvmusic.config.MetaSettings.playerCoverShape)
                    .put("coverSpinMs", com.tvmusic.config.MetaSettings.playerCoverSpinMs)
                    .toString())
            }
            method == "POST" && path == "/api/meta" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                // 仅在请求显式携带字段时才写入：body 非法/字段缺省一律保持原值。
                // 旧实现 json?.optBoolean(...) 在 json==null 时把 enabled 误置 false。
                if (json != null) {
                    if (json.has("enabled")) {
                        com.tvmusic.config.MetaSettings.setEnabled(json.optBoolean("enabled"))
                    }
                    if (json.has("fallbackOtherSource")) {
                        com.tvmusic.config.MetaSettings.setFallbackOtherSource(
                            json.optBoolean("fallbackOtherSource")
                        )
                    }
                    if (json.has("minPlaySeconds")) {
                        com.tvmusic.config.MetaSettings.setMinPlaySeconds(json.optInt("minPlaySeconds"))
                    }
                    if (json.has("preferPlugin")) {
                        com.tvmusic.config.MetaSettings.setFallbackPreferPlugin(
                            json.optString("preferPlugin")
                        )
                    }
                    if (json.has("fallbackStrategy")) {
                        com.tvmusic.config.MetaSettings.setFallbackStrategy(
                            json.optString("fallbackStrategy")
                        )
                    }
                    if (json.has("coverShape")) {
                        com.tvmusic.config.MetaSettings.setCoverShape(json.optString("coverShape"))
                    }
                    if (json.has("coverSpinMs")) {
                        com.tvmusic.config.MetaSettings.setCoverSpinMs(json.optInt("coverSpinMs"))
                    }
                }
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("enabled", com.tvmusic.config.MetaSettings.isEnabled)
                    .put("fallbackOtherSource", com.tvmusic.config.MetaSettings.fallbackOtherSource)
                    .put("minPlaySeconds", com.tvmusic.config.MetaSettings.minPlaySeconds)
                    .put("preferPlugin", com.tvmusic.config.MetaSettings.fallbackPreferPlugin)
                    .put("fallbackStrategy", com.tvmusic.config.MetaSettings.fallbackStrategy)
                    .put("coverShape", com.tvmusic.config.MetaSettings.playerCoverShape)
                    .put("coverSpinMs", com.tvmusic.config.MetaSettings.playerCoverSpinMs)
                    .toString())
            }
            // 无操作自动进入播放器页（电视待机显示）：开关 + 时长（分钟）
            method == "GET" && path == "/api/idle" -> {
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("enabled", com.tvmusic.config.IdleSettings.isEnabled)
                    .put("minutes", com.tvmusic.config.IdleSettings.currentMinutes)
                    .toString())
            }
            method == "POST" && path == "/api/idle" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json?.has("enabled") == true) {
                    com.tvmusic.config.IdleSettings.setEnabled(json.optBoolean("enabled", true))
                }
                if (json?.has("minutes") == true) {
                    com.tvmusic.config.IdleSettings.setMinutes(json.optInt("minutes", 1))
                }
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("enabled", com.tvmusic.config.IdleSettings.isEnabled)
                    .put("minutes", com.tvmusic.config.IdleSettings.currentMinutes)
                    .toString())
            }
            method == "GET" && path == "/api/export" -> {
                // 配置导出：主题 + 歌词设置 + 全部收藏专辑（含原始条目）
                val c = com.tvmusic.ui.theme.LyricSettings.config.value
                val listsArr = JSONArray()
                app().playback.lists.value.forEach { l ->
                    val items = JSONArray()
                    l.items.forEach { items.put(it) }
                    listsArr.put(
                        JSONObject().put("id", l.id).put("name", l.name).put("items", items)
                    )
                }
                val exportBody = JSONObject()
                    .put("ok", true)
                    .put("version", 1)
                    .put("theme", com.tvmusic.ui.theme.ThemeManager.currentId())
                    .put(
                        "lyric",
                        JSONObject()
                            .put("fontSizeSp", c.fontSizeSp)
                            .put("colorHex", c.colorHex)
                    )
                    .put("favLists", listsArr)
                    .toString()
                val rawQuery = parts[1].substringAfter('?', "")
                if (rawQuery.contains("dl=1")) {
                    respondDownload(socket, exportBody, "musicfree-tv-config.json")
                } else {
                    respond(socket, 200, exportBody)
                }
            }
            method == "POST" && path == "/api/import" -> {
                // 配置导入：缺字段容错；favLists 存在时整体替换收藏专辑
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json == null) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "bad json").toString())
                    return
                }
                var themeApplied = false
                if (json.has("theme")) {
                    val tid = json.optString("theme", "")
                    if (tid.isNotBlank()) themeApplied = com.tvmusic.ui.theme.ThemeManager.set(tid)
                }
                var lyricApplied = false
                val lo = json.optJSONObject("lyric")
                if (lo != null) {
                    val cur = com.tvmusic.ui.theme.LyricSettings.config.value
                    val next = com.tvmusic.ui.theme.LyricConfig(
                        fontSizeSp = lo.optInt("fontSizeSp", cur.fontSizeSp).coerceIn(10, 40),
                        colorHex = lo.optString("colorHex", cur.colorHex).trim().trimStart('#').take(6)
                            .ifBlank { cur.colorHex }
                    )
                    com.tvmusic.ui.theme.LyricSettings.update(next)
                    lyricApplied = true
                }
                var listCount = 0
                val la = json.optJSONArray("favLists")
                if (la != null) {
                    val imported = (0 until la.length()).mapNotNull { i ->
                        val o = la.optJSONObject(i) ?: return@mapNotNull null
                        val itemsArr = o.optJSONArray("items") ?: JSONArray()
                        FavList(
                            id = o.optString("id", "").ifBlank { "import_$i" },
                            name = o.optString("name", "").ifBlank { "Imported " + (i + 1) },
                            items = (0 until itemsArr.length()).mapNotNull { j -> itemsArr.optJSONObject(j) }
                        )
                    }
                    val merged = imported.toMutableList()
                    if (merged.none { it.id == com.tvmusic.data.PlaybackStore.DEFAULT_FAV_ID }) {
                        merged.add(0, FavList(com.tvmusic.data.PlaybackStore.DEFAULT_FAV_ID, "我的收藏", emptyList()))
                    }
                    app().playback.replaceAllLists(merged)
                    listCount = merged.size
                }
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put(
                        "imported",
                        JSONObject()
                            .put("theme", themeApplied)
                            .put("lyric", lyricApplied)
                            .put("lists", listCount)
                    )
                    .toString())
            }
            method == "POST" && path == "/api/fav/play" -> {
                // 播放整个收藏专辑：{ id }
                val body = readBody(input, headers)
                val id = runCatching { JSONObject(body).optString("id", "") }.getOrDefault("")
                val fl = app().playback.lists.value.firstOrNull { it.id == id }
                if (fl == null || fl.items.isEmpty()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "list empty or not found").toString())
                    return
                }
                val entries = fl.items.map { raw ->
                    val p = raw.optString("platform", "")
                    com.tvmusic.player.QueueEntry(p, raw)
                }
                val first = entries.first()
                com.tvmusic.player.PlayerManager.play(first.plugin, first, entries, 0, source = "${first.plugin} · ${fl.name}")
                respond(socket, 200, JSONObject().put("ok", true).put("count", entries.size).put("message", "已开始播放专辑「${fl.name}」").toString())
            }
            else -> respond(socket, 404, JSONObject().put("ok", false).put("error", "not found").toString())
        }
    }

    private fun app(): TvMusicApp = TvMusicApp.from(this)

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        // 上限 4MB：导入配置/批量收藏足够；超过直接拒绝。
        if (len > MAX_BODY_BYTES) throw IllegalArgumentException("payload too large")
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun subscriptionsJson(): JSONObject {
        val arr = JSONArray()
        val app = app()
        app.store.listSubscriptions().forEach { s ->
            arr.put(JSONObject().put("url", s.url).put("addedAt", s.addedAt))
        }
        return JSONObject().put("ok", true).put("subscriptions", arr)
    }

    /** 主题列表与当前值，供远程页面渲染主题选择器。 */
    private fun themesJson(): JSONObject {
        val arr = JSONArray()
        com.tvmusic.ui.theme.ThemeManager.themes.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name)
                    .put("accent", t.accent).put("accent2", t.accent2)
                    .put("bg", t.bg).put("card", t.card)
                    .put("text", t.text).put("muted", t.muted).put("line", t.line)
                    .put("radius", t.radiusPx)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("current", com.tvmusic.ui.theme.ThemeManager.currentId())
            .put("themes", arr)
    }

    private fun playerStatusJson(): JSONObject {
        val st = com.tvmusic.player.PlayerManager.uiState.value
        val cur = st.current
        val queue = JSONArray()
        st.queue.forEachIndexed { i, e ->
            queue.put(
                JSONObject()
                    .put("index", i)
                    .put("title", e.title)
                    .put("artist", e.artist)
                    .put("album", e.album)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("playing", st.isPlaying)
            .put("buffering", st.buffering)
            .put("title", cur?.title ?: "")
            .put("artist", cur?.artist ?: "")
            .put("album", cur?.album ?: "")
            .put("artwork", cur?.artwork ?: "")
            .put("duration", st.durationMs)
            .put("position", st.positionMs)
            .put("index", st.queueIndex)
            .put("queueSize", st.queue.size)
            .put("queue", queue)
            .put("volume", st.volume)
            .put("playMode", st.playMode.name)
            .put("favorite", st.isFavorite)
            .put("error", st.error ?: JSONObject.NULL)
    }

    /** 全部收藏专辑概览（id/name/count）。 */
    private fun favListsJson(): JSONObject {
        val arr = JSONArray()
        app().playback.lists.value.forEach { l ->
            arr.put(JSONObject().put("id", l.id).put("name", l.name).put("count", l.items.size))
        }
        return JSONObject().put("ok", true).put("lists", arr)
    }

    /** 指定专辑内的歌曲（分页）。 */
    private fun favItemsJson(id: String, page: Int, size: Int): JSONObject {
        val fl = app().playback.lists.value.firstOrNull { it.id == id }
            ?: return JSONObject().put("ok", false).put("error", "list not found")
        val total = fl.items.size
        val from = ((page - 1) * size).coerceIn(0, total)
        val to = (from + size).coerceAtMost(total)
        val items = JSONArray()
        fl.items.subList(from, to).forEach { it ->
            items.put(
                JSONObject()
                    .put("title", it.optString("title"))
                    .put("artist", it.optString("artist"))
                    .put("album", it.optString("album"))
                    .put("platform", it.optString("platform"))
                    .put("artwork", it.optString("artwork", "").ifEmpty { it.optString("coverImg") })
                    .put("raw", it)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("name", fl.name)
            .put("page", page)
            .put("size", size)
            .put("total", total)
            .put("items", items)
    }

    private fun pluginsJson(): JSONObject {
        val arr = JSONArray()
        // 元数据查询：远程管理只需要 name/platform/version/enabled/loadError，
        // 不读源码大字段（旧实现每次打开插件页都物化全部源码，电视端内存抖动卡死）
        app().store.loadPluginMetas().map { it.takeIf { p -> p.info != null } }
            .filterNotNull()
            .forEach { p ->
                arr.put(
                    JSONObject()
                        .put("name", p.name)
                        .put("platform", p.info?.platform ?: p.name)
                        .put("version", p.version ?: "")
                        .put("enabled", p.enabled)
                        .put("loadError", p.loadError ?: JSONObject.NULL)
                )
            }
        return JSONObject().put("ok", true).put("plugins", arr)
    }

    /**
     * 通用用户变量视图：返回所有声明了 userVariables 的插件的「声明 + 当前值」。
     * 前端按声明动态渲染表单——插件加多少变量、以后新插件带什么变量都自动支持，
     * 不需要为咪咕 Cookie / Bilibili SESSDATA 之类各写一套代码。
     */
    private fun pluginVarsJson(): JSONObject {
        val arr = JSONArray()
        // 同 pluginsJson：元数据查询，避免每次变量视图都物化全部插件源码
        app().store.loadPluginMetas().forEach { p ->
            val defs = p.info?.userVariables.orEmpty()
            if (defs.isEmpty()) return@forEach
            val pk = p.info?.platform?.takeIf { it.isNotBlank() } ?: p.name
            val defArr = JSONArray()
            defs.forEach { d ->
                defArr.put(
                    JSONObject()
                        .put("key", d.key)
                        .put("name", d.name)
                        .put("type", d.type ?: JSONObject.NULL)
                )
            }
            val values = JSONObject()
            val secretKeys = defs.filter { it.type.equals("password", ignoreCase = true) }.map { it.key }.toSet()
            app().store.loadVariables(pk).forEach { (k, v) ->
                values.put(k, if (k in secretKeys && v.isNotEmpty()) "" else v)
            }
            arr.put(
                JSONObject()
                    .put("platform", pk)
                    .put("name", p.name)
                    .put("userVariables", defArr)
                    .put("values", values)
            )
        }
        return JSONObject().put("ok", true).put("plugins", arr)
    }

    /**
     * 单次搜索的请求参数。sources 为空表示搜全部启用插件；
     * sortBy/asc 为空时回落到后台配置。
     */
    private data class SearchReq(
        val sources: Set<String> = emptySet(),
        val minDurSec: Int? = null,
        val maxDurSec: Int? = null,
        val needArt: Boolean = false,
        val sortBy: String = "",
        val asc: Boolean? = null
    )

    /** 渐进式搜索会话：后台线程逐个插件搜索，前台轮询拿到已积累的结果。 */
    private class SearchSession(
        val id: String,
        val keyword: String,
        val page: Int,
        val req: SearchReq
    ) {
        val results = Collections.synchronizedList(ArrayList<JSONObject>())
        val perSource = Collections.synchronizedList(ArrayList<JSONObject>())
        @Volatile var total = 0
        @Volatile var done = 0
        @Volatile var totalEnabled = 0
        @Volatile var finished = false
        val startedAt = System.currentTimeMillis()
    }

    private val searchSessions = ConcurrentHashMap<String, SearchSession>()
    private val searchIdGen = java.util.concurrent.atomic.AtomicInteger(0)

    private fun pruneSearchSessions() {
        val now = System.currentTimeMillis()
        searchSessions.values.filter { it.finished && now - it.startedAt > 5 * 60 * 1000 }
            .forEach { searchSessions.remove(it.id) }
        if (searchSessions.size > 20) {
            searchSessions.values.filter { it.finished }.sortedBy { it.startedAt }
                .take(searchSessions.size - 20).forEach { searchSessions.remove(it.id) }
        }
    }

    /**
     * 启动渐进式搜索：立即返回会话 id，搜索在后台线程并行跑多个插件，
     * 结果随完成进度积累，前端轮询 /api/search/poll 边到边展示。
     * 单源外层预算 60s / invoke 45s（放宽以吸收同引擎排队），失败降级为空。
     */
    private fun searchAndStart(socket: Socket, q: String, page: Int, req: SearchReq) {
        // q 已在 HTTP 层 URLDecoder.decode 过一次，直接使用，避免二次解码破坏含 % / + 的关键词
        val keyword = q
        val session = SearchSession(searchIdGen.incrementAndGet().toString(), keyword, page, req)
        searchSessions[session.id] = session
        pruneSearchSessions()
        Thread({
            try {
                runBlocking {
                    val app = app()
                    val settings = com.tvmusic.config.SearchSettings.load(app)
                    val sortBy = req.sortBy.ifBlank { settings.sortBy }
                    val asc = req.asc ?: settings.asc
                    val maxTotal = settings.maxTotal
                    // 并行搜索：多引擎池（callParallel）按最闲引擎分发，多个音源真正同时搜。
                    val enabled = app.repository.listEnabled()
                        .filter { it.info != null && it.loadError == null }
                        .map { it.info?.platform ?: it.name }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .filter { req.sources.isEmpty() || it in req.sources }
                        .let { com.tvmusic.config.SearchSettings.ordered(it, settings.sourceOrder) }
                    session.totalEnabled = enabled.size
                    val collected = arrayOfNulls<JSONArray>(enabled.size)
                    val completed = java.util.concurrent.atomic.AtomicInteger(0)
                    fun reaggregate() {
                        synchronized(collected) {
                            synchronized(session.results) {
                                session.results.clear()
                                session.perSource.clear()
                                session.total = 0
                                for ((idx, platform) in enabled.withIndex()) {
                                    // 前缀缺口用 continue：已完成的靠后音源先展示，前面的慢源完成后自动并入
                                    val arr = collected[idx] ?: continue
                                    var ok = 0
                                    for (i in 0 until arr.length()) {
                                        // 每音源独立配额：不再用跨源全局截断吞掉靠后的音源
                                        if (ok >= maxTotal) break
                                        val item = arr.optJSONObject(i) ?: continue
                                        val type = item.optString("type")
                                        if (type.isNotBlank() && type != "music") continue
                                        val title = item.optString("title").trim()
                                        if (title.isBlank()) continue
                                        val rawDurSec = item.optLong("duration", 0L) / 1000
                                        if (req.minDurSec != null && rawDurSec > 0 && rawDurSec < req.minDurSec) continue
                                        if (req.maxDurSec != null && rawDurSec > 0 && rawDurSec > req.maxDurSec) continue
                                        val art = item.optString("artwork", "").ifEmpty { item.optString("coverImg", "") }
                                        if (req.needArt && art.isBlank()) continue
                                        ok++
                                        session.results.add(
                                            JSONObject()
                                                .put("plugin", platform)
                                                .put("title", title)
                                                .put("artist", item.optString("artist"))
                                                .put("album", item.optString("album"))
                                                .put("artwork", art)
                                                .put("duration", item.optLong("duration", 0L))
                                                .put("raw", item)
                                        )
                                        session.total++
                                    }
                                    session.perSource.add(JSONObject().put("plugin", platform).put("count", ok))
                                }
                            }
                        }
                    }
                    val searchStarted = android.os.SystemClock.elapsedRealtime()
                    coroutineScope {
                        enabled.forEachIndexed { idx, platform ->
                            launch(Dispatchers.IO) {
                                if (idx >= 3) kotlinx.coroutines.delay(350L)
                                val sourceStarted = android.os.SystemClock.elapsedRealtime()
                                val arr = try {
                                    // 粘性引擎路由下，同引擎多平台会排队；外层预算放宽到 60s 给足排队余量，
                                    // 单个 invoke 45s（略低于 TV 端默认 60s），宁慢勿丢源（超时即整源无结果）
                                    withTimeoutOrNull(60_000) {
                                        app.runtime.callParallel(
                                            platform, "search", listOf(keyword, page.toString(), "music"),
                                            timeoutMs = 45_000
                                        )
                                    }?.let { res ->
                                        (res as? JSONObject)?.optJSONArray("data") ?: (res as? JSONArray)
                                    } ?: JSONArray()
                                } catch (e: Exception) {
                                    JSONArray()
                                }
                                val elapsed = android.os.SystemClock.elapsedRealtime() - sourceStarted
                                Log.i("PerfSearch", "remote source=$platform elapsed=${elapsed}ms results=${arr.length()} strategy=staggered3")
                                synchronized(collected) {
                                    collected[idx] = arr
                                    session.done = completed.incrementAndGet()
                                }
                                reaggregate()
                            }
                        }
                    }
                    reaggregate()
                    Log.i("PerfSearch", "remote done=${android.os.SystemClock.elapsedRealtime() - searchStarted}ms sources=${enabled.size} strategy=staggered3")
                    if (sortBy != com.tvmusic.config.SearchSettings.SORT_DEFAULT) {
                        synchronized(session.results) {
                            session.results.sortWith(
                                com.tvmusic.config.SearchSettings.comparator<JSONObject>(
                                    sortBy, asc,
                                    { it.optString("title", "") },
                                    { it.optString("artist", "") },
                                    { it.optLong("duration", 0L) }
                                )
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "search session ${session.id} aborted: ${t.message}")
            } finally {
                session.finished = true
            }
        }, "search-session").apply { isDaemon = true }.start()
        respond(socket, 200, JSONObject().put("ok", true).put("id", session.id).put("message", "搜索中").toString())
    }

    /** 轮询会话：返回已积累的结果快照与完成进度。 */
    private fun respondSearchPoll(socket: Socket, session: SearchSession) {
        // 直接引用条目对象：聚合逻辑只会 clear() 列表、不会改动单个 JSONObject，
        // 无需每次轮询都 toString 再重新解析做深拷贝（大结果集下开销显著）。
        val results = JSONArray()
        synchronized(session.results) {
            session.results.forEach { results.put(it) }
        }
        val perSource = JSONArray()
        synchronized(session.perSource) {
            session.perSource.forEach { perSource.put(it) }
        }
        respond(
            socket, 200,
            JSONObject()
                .put("ok", true)
                .put("id", session.id)
                .put("finished", session.finished)
                .put("done", session.done)
                .put("totalEnabled", session.totalEnabled)
                .put("total", session.total)
                .put("results", results)
                .put("perSource", perSource).toString()
        )
    }

    private fun infoJson(): JSONObject {
        return JSONObject()
            .put("app", getString(com.tvmusic.R.string.app_name))
            .put("version", BuildConfig.VERSION_NAME)
            .put("host", hostDisplay)
            .put("port", port)
            .put("status", "ok")
    }

    /**
     * 图片代理：手机浏览器直连 B 站等图床会被防盗链拦截，
     * 改由 TV 端带 Referer 拉取后转发给浏览器。
     */
    private fun proxyImage(socket: Socket, target: String) {
        if (!target.startsWith("http")) {
            respond(socket, 400, "{\"ok\":false}")
            return
        }
        try {
            // 手动逐跳跟随重定向：每一跳都校验目标主机不是内网地址（SSRF 防护），
            // 同时保留图床 302 跳 CDN 的正常能力（OkHttp 客户端自身关闭自动跟随）
            var url = target
            var response: okhttp3.Response? = null
            var hops = 0
            while (true) {
                val u = java.net.URL(url)
                if (isBlockedHost(u.host)) {
                    response?.close()
                    respond(socket, 403, "{\"ok\":false}")
                    return
                }
                val host = u.host.lowercase()
                val ref = when {
                    host.contains("hdslb") || host.contains("bilibili") -> "https://www.bilibili.com/"
                    else -> u.protocol + "://" + u.host + "/"
                }
                val req = okhttp3.Request.Builder().url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/109.0 Mobile Safari/537.36"
                    )
                    .header("Referer", ref)
                    .build()
                response?.close()
                response = imageHttpClient.newCall(req).execute()
                val code = response.code
                if (code in 300..399) {
                    val loc = response.header("Location")
                    if (loc.isNullOrBlank() || ++hops > MAX_IMAGE_HOPS) {
                        response.close()
                        respond(socket, 404, "{\"ok\":false}")
                        return
                    }
                    url = java.net.URL(java.net.URL(url), loc).toString()
                    continue
                }
                if (code != 200) {
                    response.close()
                    respond(socket, 404, "{\"ok\":false}")
                    return
                }
                break
            }
            val body = response!!.body!!
            // 超大图直接拒绝，防止整张入内存导致 OOM
            val declared = body.contentLength()
            if (declared > MAX_IMAGE_BYTES) {
                response.close()
                respond(socket, 413, "{\"ok\":false}")
                return
            }
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(declared.coerceAtLeast(64 * 1024L).toInt())
                val tmp = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMAGE_BYTES) { response.close(); return@use null }
                    out.write(tmp, 0, n)
                }
                out.toByteArray()
            }
            val ctype = body.contentType()?.toString() ?: "image/jpeg"
            response.close()
            if (bytes == null) {
                respond(socket, 413, "{\"ok\":false}")
                return
            }
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $ctype\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: public, max-age=86400\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "proxyImage: ${e.message}")
            runCatching { respond(socket, 502, "{\"ok\":false}") }
        }
    }

    /** 判断目标主机是否解析到回环/内网/链路本地地址（SSRF 防护）。 */
    private fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").substringBefore(']').substringBefore(':')
        if (h == "localhost" || h.isBlank()) return true
        return try {
            java.net.InetAddress.getAllByName(h).any { addr ->
                val bytes = addr.address
                val ipv4Shared = bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 &&
                    (bytes[1].toInt() and 0xc0) == 64 // 100.64.0.0/10 CGNAT
                val ipv6UniqueLocal = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
                addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                    addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                    addr.isMulticastAddress || ipv4Shared || ipv6UniqueLocal
            }
        } catch (_: Exception) {
            true // 解析失败不代理
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        try {
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "respond: ${e.message}")
        }
    }

    private fun respondHtml(socket: Socket, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            else -> "Bad Request"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'self' data: blob:; img-src 'self' data: blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\n\r\n"
        try {
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "respondHtml: ${e.message}")
        }
    }

    private fun respondDownload(socket: Socket, body: String, filename: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Disposition: attachment; filename=\"$filename\"\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        try {
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "respondDownload: ${e.message}")
        }
    }

    /**
     * SSE 推送（GET /api/events）：每 1000ms 发一帧 `data: <播放器状态JSON>\n\n`。
     * 状态组装复用 playerStatusJson()，与 /api/player 完全同源，不另写状态逻辑。
     * 并发约束：由 sseSlots 限流（最多 MAX_SSE_CLIENTS 条），超出 503 后浏览器自动降级轮询；
     * 每帧写有 8 秒 watchdog 兜底，客户端停滞时强制断开，线程不会被永久占死。
     */
    private fun respondEvents(socket: Socket) {
        val out = socket.getOutputStream()
        try {
            // 15s 读超时只作用于请求头读取阶段；SSE 建立后只写不读，理论上不会触发，
            // 这里显式关闭该连接的读超时，确保长连接不被干扰。
            runCatching { socket.soTimeout = 0 }
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: keep-alive\r\n\r\n"
            writeFrame(socket, out, head.toByteArray(Charsets.UTF_8))
            while (true) {
                val frame = "data: ${playerStatusJson().toString()}\n\n"
                writeFrame(socket, out, frame.toByteArray(Charsets.UTF_8))
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break // 服务销毁（线程池 shutdownNow）时被打断，干净退出
                }
            }
        } catch (e: java.io.IOException) {
            // SocketException 是 IOException 子类：客户端断开/写失败/watchdog 强制断开，干净退出循环
            Log.i(TAG, "events: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** 带 watchdog 的一帧写入：8 秒写不完（客户端停滞/半开连接）就强制关 socket 让 write 抛异常解锁。 */
    private fun writeFrame(socket: Socket, out: java.io.OutputStream, bytes: ByteArray) {
        val wd = sseWatchdog.schedule({ runCatching { socket.close() } }, 8, java.util.concurrent.TimeUnit.SECONDS)
        try {
            out.write(bytes)
            out.flush()
        } finally {
            wd.cancel(false)
        }
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val sb = StringBuilder()
        var prev = -1
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (++count > maxBytes) throw IllegalArgumentException("HTTP line too long")
            if (prev == '\r'.code && b == '\n'.code) {
                return sb.dropLast(1).toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun resolveLocalIp(): String {
        return try {
            val enums = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (n in enums) {
                val addrs = Collections.list(n.inetAddresses)
                for (a in addrs) {
                    if (!a.isLoopbackAddress) {
                        val text = a.hostAddress ?: continue
                        if (text.contains(':')) continue // ipv6
                        return text
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "remote_config"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "远程配置服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            // 通知渠道前时代（API 24/25）：只能用过期的 priority 控制通知级别，
            // O 及以上已由 NotificationChannel 的 IMPORTANCE_LOW 承担
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setContentTitle(getString(com.tvmusic.R.string.app_name))
            .setContentText("远程配置服务运行中 · 端口 $port")
            .setSmallIcon(com.tvmusic.R.drawable.ic_stat_remote)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        instance = null
        try { serverSocket?.close() } catch (_: Exception) {}
        try { httpPool.shutdownNow() } catch (_: Exception) {}
        try { sseWatchdog.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }
}
