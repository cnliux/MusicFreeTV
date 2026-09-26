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
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        // 去掉查询参数再路由，允许 /?v=xxx 做缓存刷新
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")

        // 读头
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
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
                val map = HashMap<String, String>()
                val keys = varsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = varsObj.optString(k)
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
                com.tvmusic.player.PlayerManager.play(plugin, entry, listOf(entry), 0)
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
                com.tvmusic.player.PlayerManager.play(entries.first().plugin, entries.first(), entries, 0)
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
                    .toString())
            }
            method == "POST" && path == "/api/meta" -> {
                val body = readBody(input, headers)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val on = json?.optBoolean("enabled", com.tvmusic.config.MetaSettings.isEnabled)
                com.tvmusic.config.MetaSettings.setEnabled(on == true)
                respond(socket, 200, JSONObject()
                    .put("ok", true)
                    .put("enabled", com.tvmusic.config.MetaSettings.isEnabled)
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
                com.tvmusic.player.PlayerManager.play(first.plugin, first, entries, 0)
                respond(socket, 200, JSONObject().put("ok", true).put("count", entries.size).put("message", "已开始播放专辑「${fl.name}」").toString())
            }
            else -> respond(socket, 404, JSONObject().put("ok", false).put("error", "not found").toString())
        }
    }

    private fun app(): TvMusicApp = TvMusicApp.from(this)

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        // 上限 4MB：导入配置/批量收藏足够；超过直接拒绝，
        // 旧实现按 4MB 分配缓冲却用原始 len 作读取长度，超大请求必数组越界
        if (len > 4 * 1024 * 1024) throw IllegalArgumentException("payload too large")
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
            app().store.loadVariables(pk).forEach { (k, v) -> values.put(k, v) }
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
                        .map { it.info!!.platform }
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
                    coroutineScope {
                        enabled.forEachIndexed { idx, platform ->
                            launch(Dispatchers.IO) {
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
                                synchronized(collected) {
                                    collected[idx] = arr
                                    session.done = completed.incrementAndGet()
                                }
                                reaggregate()
                            }
                        }
                    }
                    reaggregate()
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
                "Access-Control-Allow-Origin: *\r\n" +
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
                addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                    addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                    addr.isMulticastAddress
            }
        } catch (_: Exception) {
            true // 解析失败不代理
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            503 -> "Service Unavailable"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
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
        val status = if (code == 200) "OK" else "Bad Request"
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Cache-Control: no-store\r\n\r\n"
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
            "Access-Control-Allow-Origin: *\r\n" +
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
                "Access-Control-Allow-Origin: *\r\n" +
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

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
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

private val PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
<meta http-equiv="Pragma" content="no-cache">
<meta http-equiv="Expires" content="0">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MusicFree TV 远程管理</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #0d0f14; --card: #171a21; --card2: #1e222b; --line: #262b36;
    --text: #eef1f6; --muted: #8b93a5; --accent: #5b8cff; --accent2: #7aa5ff;
    --danger: #e06c6c; --ok: #58c97b; --radius: 14px;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 0; font-family: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
    background: var(--bg); color: var(--text); min-height: 100vh;
    padding-bottom: calc(64px + env(safe-area-inset-bottom));
  }
  header {
    position: sticky; top: 0; z-index: 10;
    background: rgba(13,15,20,.88); backdrop-filter: blur(10px);
    padding: 14px 16px 10px; border-bottom: 1px solid var(--line);
  }
  header h1 { font-size: 17px; margin: 0; letter-spacing: .5px; }
  header .sub { color: var(--muted); font-size: 12px; margin-top: 3px; }
  main { max-width: 720px; margin: 0 auto; padding: 14px 14px 0; }
  .page { display: none; }
  .page.on { display: block; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px; margin-bottom: 14px; }
  .card h2 { font-size: 13px; margin: 0 0 10px; color: var(--muted); font-weight: 600; letter-spacing: 1px; }
  .row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--line); }
  .row:last-child { border-bottom: none; }
  .grow { flex: 1; min-width: 0; }
  .ellip { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .muted { color: var(--muted); font-size: 12px; }
  input[type=text] {
    flex: 1; min-width: 0; background: var(--card2); color: var(--text); border: 1px solid var(--line);
    border-radius: 10px; padding: 10px 12px; font-size: 15px; outline: none;
  }
  input[type=text]:focus { border-color: var(--accent); }
  button {
    background: var(--accent); color: #fff; border: none; border-radius: 10px; padding: 10px 16px;
    font-size: 14px; cursor: pointer; touch-action: manipulation;
  }
  button:active { filter: brightness(.85); }
  button.ghost { background: var(--card2); color: var(--text); border: 1px solid var(--line); }
  button.danger { background: transparent; color: var(--danger); border: 1px solid #4a2c2c; }
  button.small { padding: 6px 12px; font-size: 13px; border-radius: 8px; }
  .badge { display: inline-block; padding: 2px 9px; border-radius: 20px; font-size: 11px; }
  .badge.on { background: rgba(88,201,123,.15); color: var(--ok); }
  .badge.off { background: rgba(224,108,108,.15); color: var(--danger); }
  /* 底部导航 */
  nav {
    position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
    display: flex; background: rgba(19,22,29,.96); backdrop-filter: blur(12px);
    border-top: 1px solid var(--line); padding-bottom: env(safe-area-inset-bottom);
  }
  nav button {
    flex: 1; background: none; border: none; color: var(--muted); padding: 9px 0 7px;
    font-size: 11px; display: flex; flex-direction: column; align-items: center; gap: 3px; border-radius: 0;
  }
  nav button .ic { font-size: 20px; line-height: 1; }
  nav button.on { color: var(--accent); }
  /* 播放器 */
  .nowart { display: flex; justify-content: center; margin: 8px 0 16px; }
  .nowart img {
    width: 200px; height: 200px; border-radius: 18px; object-fit: cover;
    background: var(--card2); box-shadow: 0 12px 40px rgba(0,0,0,.55);
  }
  .nowtitle { text-align: center; font-size: 19px; font-weight: 700; }
  .nowartist { text-align: center; color: var(--muted); font-size: 14px; margin-top: 5px; }
  .seekwrap { padding: 14px 2px 0; }
  input[type=range] {
    -webkit-appearance: none; appearance: none; width: 100%; height: 26px; background: transparent; outline: none;
  }
  input[type=range]::-webkit-slider-runnable-track {
    height: 5px; border-radius: 3px;
    background: linear-gradient(to right, var(--accent) var(--fill,0%), #2c313d var(--fill,0%));
  }
  input[type=range]::-webkit-slider-thumb {
    -webkit-appearance: none; appearance: none; width: 18px; height: 18px; border-radius: 50%;
    background: #fff; margin-top: -6.5px; box-shadow: 0 1px 6px rgba(0,0,0,.5); border: none;
  }
  input[type=range]::-moz-range-track { height: 5px; border-radius: 3px; background: #2c313d; }
  input[type=range]::-moz-range-progress { height: 5px; border-radius: 3px; background: var(--accent); }
  input[type=range]::-moz-range-thumb { width: 18px; height: 18px; border-radius: 50%; background: #fff; border: none; }
  .times { display: flex; justify-content: space-between; color: var(--muted); font-size: 12px; margin-top: 2px; }
  .ctrls { display: flex; align-items: center; justify-content: center; gap: 18px; margin: 14px 0 4px; flex-wrap: wrap; }
  .ctrl {
    flex: none; width: 56px !important; height: 56px !important; border-radius: 50%;
    background: radial-gradient(circle at 35% 30%, #333a49, var(--card2) 70%);
    border: none; box-shadow: 0 4px 14px rgba(0,0,0,.45);
    color: var(--text); font-size: 20px; display: flex; align-items: center; justify-content: center; padding: 0;
    transition: transform .12s; cursor: pointer;
  }
  .ctrl:active { transform: scale(.92); }
  .ctrl.main {
    color: #fff;
    background: radial-gradient(circle at 35% 30%, var(--accent2), var(--accent) 75%);
    box-shadow: 0 6px 22px rgba(0,0,0,.5);
  }
  .ctrl.favon { color: var(--accent2); }
  .ctrls .side { flex: none; display: flex; flex-direction: column; align-items: center; gap: 2px; color: var(--muted); font-size: 10px; }
  .qitem { display: flex; align-items: center; gap: 10px; padding: 10px 4px; border-bottom: 1px solid var(--line); }
  .qitem:last-child { border-bottom: none; }
  .qitem.cur { color: var(--accent2); }
  .qitem .n { color: var(--muted); font-size: 12px; width: 26px; flex: none; }
  .chip {
    display: inline-flex; align-items: center; gap: 6px; background: var(--card2); color: var(--text);
    border: 1px solid var(--line); border-radius: 18px; padding: 7px 14px; font-size: 13px; cursor: pointer;
  }
  .chip.on { background: var(--accent); border-color: var(--accent); color: #fff; }
  .chip .x { color: inherit; opacity: .65; padding: 0 2px; }
  .chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
  /* 音源卡：一行里同时管「启用/顺序/变量」，模块名做成 chip 而不是大标题，省页面高度 */
  .srcitem { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--line); }
  .srcitem:last-child { border-bottom: none; }
  .srcno { width: 22px; flex: none; text-align: center; color: var(--muted); font-size: 12px; }
  .srcitem .name { flex: 1; min-width: 0; font-size: 15px; }
  .srcitem .act { display: flex; align-items: center; gap: 8px; flex: none; }
  .actb { background: none; border: none; color: var(--accent); font-size: 12px; padding: 4px 2px; cursor: pointer; }
  .actb.dim { color: var(--muted); }
  .actb.warn { color: var(--danger); }
  .varpanel { padding: 2px 0 10px 30px; border-bottom: 1px solid var(--line); }
  .varpanel .vrow { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
  .varpanel .vrow span { width: 148px; flex: none; color: var(--muted); font-size: 12px; }
  #toast {
    position: fixed; left: 50%; bottom: calc(80px + env(safe-area-inset-bottom)); transform: translateX(-50%);
    background: #262b36; color: var(--text); border: 1px solid var(--line);
    padding: 9px 18px; border-radius: 22px; font-size: 13px; opacity: 0; transition: opacity .25s;
    pointer-events: none; z-index: 30; max-width: 86vw;
  }
  #toast.show { opacity: 1; }
  #favModal, #collectModal {
    position: fixed; inset: 0; background: rgba(0,0,0,.6); z-index: 40;
    display: none; align-items: flex-end; justify-content: center;
  }
  #favModal.show, #collectModal.show { display: flex; }
  #favModal .sheet, #collectModal .sheet {
    width: 100%; max-width: 520px; background: var(--card); border-radius: 18px 18px 0 0;
    padding: 18px 18px calc(18px + env(safe-area-inset-bottom)); max-height: 70vh; overflow-y: auto;
  }
  #favModal h3, #collectModal h3 { margin: 0 0 4px; font-size: 16px; }
  #favModal .fitem, #collectModal .fitem {
    display: flex; align-items: center; gap: 10px; padding: 13px 6px; border-bottom: 1px solid var(--line);
    font-size: 15px; cursor: pointer;
  }
  #favModal .fitem .ck, #collectModal .fitem .ck { width: 24px; color: var(--accent2); font-size: 16px; flex: none; }
  #favModal .fitem .cnt, #collectModal .fitem .cnt { margin-left: auto; color: var(--muted); font-size: 12px; }
  #favModal .newrow, #collectModal .newrow { display: flex; gap: 8px; margin-top: 12px; }
  #favModal .newrow input, #collectModal .newrow input { flex: 1; }
  .pager { display: flex; align-items: center; gap: 8px; margin-top: 10px; font-size: 12px; color: var(--muted); }
  .pager button { padding: 6px 12px; font-size: 12px; }
  .empty { color: var(--muted); font-size: 13px; text-align: center; padding: 18px 0; }
  .volrow { display: flex; align-items: center; gap: 10px; padding: 4px 10px 0; }
  .volrow input { flex: 1; }
</style>
</head>
<body>
<header>
  <h1>MusicFree TV</h1>
  <div class="sub" id="statusLine">连接中…</div>
</header>

<main>
  <!-- 播放 -->
  <section class="page on" id="page-player">
    <div class="card">
      <div class="nowart"><img id="pArt" alt="" onerror="this.onerror=null;this.removeAttribute('src')"></div>
      <div class="nowtitle ellip" id="pTitle">未在播放</div>
      <div class="nowartist ellip" id="pArtist"></div>
      <div class="seekwrap">
        <input type="range" id="seekBar" min="0" max="1000" value="0" step="1">
        <div class="times"><span id="pPos">0:00</span><span id="pDur">0:00</span></div>
      </div>
      <div class="ctrls">
        <div class="side"><button class="ctrl" id="pMode" onclick="cycleMode()">⇄</button><span id="pModeName">顺序</span></div>
        <button class="ctrl" onclick="playerCmd('prev')">⏮︎</button>
        <button class="ctrl main" id="pToggle" onclick="playerCmd('playpause')">▶︎</button>
        <button class="ctrl" onclick="playerCmd('next')">⏭︎</button>
        <div class="side"><button class="ctrl" id="pFav" onclick="toggleCurFav()">♡</button><span>收藏</span></div>
        <div class="side"><button class="ctrl" onclick="showVol()">♪</button><span>音量</span></div>
      </div>
      <div class="volrow" id="volRow" style="display:none;">
        <button class="ghost small" onclick="volume(-0.1)">−</button>
        <input type="range" id="volBar" min="0" max="100" value="50">
        <button class="ghost small" onclick="volume(0.1)">＋</button>
        <span class="muted" id="pVol" style="width:38px;text-align:right;">50%</span>
      </div>
      <div class="muted" id="pErr" style="text-align:center;margin-top:6px;color:var(--danger);"></div>
    </div>
    <div class="card">
      <h2>播放列表 <span id="qCount" class="muted"></span></h2>
      <div id="queueBox"></div>
    </div>
  </section>

  <!-- 搜索 -->
  <section class="page" id="page-search">
    <div class="card">
      <div class="row" style="border:none;padding:0;">
        <input type="text" id="searchQ" placeholder="搜索歌曲，回车开始" onkeydown="if(event.key==='Enter')doSearch()">
        <button onclick="doSearch()">搜索</button>
      </div>
      <div class="row" style="border:none;padding:10px 0 6px;">
        <button class="ghost small" id="playAllBtn" onclick="playAllSearch()" style="display:none;">▶ 播放全部结果</button>
        <button class="ghost small" id="collectAllBtn" onclick="openCollectAll()" style="display:none;">♡ 全部收藏</button>
        <span class="muted" id="searchInfo"></span>
      </div>
      <div id="searchFilters">
        <div class="row" style="border:none;padding:4px 0;">
          <span class="muted" style="width:64px;">音源</span>
          <div class="chips" id="srcBar" style="flex:1;"></div>
        </div>
        <div class="row" style="border:none;padding:4px 0;">
          <span class="muted" style="width:64px;">时长</span>
          <input type="number" id="minD" placeholder="≥秒" style="width:72px;">
          <span class="muted">—</span>
          <input type="number" id="maxD" placeholder="≤秒" style="width:72px;">
          <label style="margin-left:16px;display:flex;align-items:center;"><input type="checkbox" id="needArt" style="margin-right:5px;"> 必须有封面</label>
        </div>
        <div class="row" style="border:none;padding:4px 0;">
          <span class="muted" style="width:64px;">排序</span>
          <select id="sortSel" style="width:120px;"></select>
          <select id="ascSel" style="width:90px;margin-left:8px;">
            <option value="1">升序</option>
            <option value="0">降序</option>
          </select>
          <button class="ghost small" style="margin-left:14px;" onclick="saveCfgInline()">存为新默认</button>
        </div>
      </div>
    </div>
    <div class="card" id="searchCard" style="display:none;">
      <h2>搜索结果</h2>
      <div class="chips" id="searchPluginBar" style="display:none;"></div>
      <div id="searchBox"></div>
      <div class="pager" id="searchPager"></div>
    </div>
  </section>

  <!-- 收藏 -->
  <section class="page" id="page-fav">
    <div class="card">
      <h2>收藏专辑</h2>
      <div class="chips" id="favListBar"></div>
      <div class="row" style="border:none;padding:0 0 10px;">
        <input type="text" id="favNewName" placeholder="新专辑名称">
        <button class="small" onclick="createFavList()">新建</button>
      </div>
      <div id="favActions"></div>
      <div id="favItems"></div>
      <div class="pager" id="favPager"></div>
    </div>
  </section>

  <!-- 播放历史 -->
  <section class="page" id="page-history">
    <div class="card">
      <h2>播放历史</h2>
      <div class="row" style="border:none;padding:0 0 10px;">
        <span class="muted" style="flex:1;">电视端最近播放（最多 100 条），点「播放」一键回放。</span>
        <button class="ghost small" onclick="clearHistory()">清空</button>
      </div>
      <div id="historyItems"></div>
    </div>
  </section>

  <!-- 管理 -->
  <section class="page" id="page-manage">
    <div class="card">
      <h2>界面主题</h2>
      <div class="chips" id="themeBar"></div>
      <div class="muted">选择后立即应用到电视端与本页。</div>
    </div>
    <div class="card">
      <h2>播放页歌词</h2>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">播放页逐行歌词的显示效果</span>
      </div>
      <div class="row" style="border:none;padding:6px 0;">
        <span class="muted" style="flex:1;">字体大小</span>
        <button class="ghost small" onclick="stepLyricSize(-2)">－</button>
        <span id="lyricSize" style="min-width:44px;text-align:center;"></span>
        <button class="ghost small" onclick="stepLyricSize(2)">＋</button>
      </div>
      <div class="row" style="border:none;padding:6px 0;">
        <span class="muted" style="flex:1;">颜色</span>
        <input type="color" id="lyricColor" style="width:44px;height:30px;border:none;background:none;padding:0;" onchange="setLyricColor(this.value)">
      </div>
      <div class="chips" id="lyricColorBar" style="margin-top:2px;"></div>
    </div>
    <div class="card">
      <h2>歌词/封面补全</h2>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">歌曲缺少歌词或封面时，按 曲名/歌手 从 lrc.cx 在线补齐</span>
        <button class="small" id="metaToggle" onclick="toggleMeta()">开</button>
      </div>
    </div>
    <div class="card">
      <h2>待机显示</h2>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">无操作达设定时长且正在播放时，电视自动进入播放器页</span>
        <button class="small" id="idleToggle" onclick="toggleIdle()">开</button>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">无操作时长（分钟，1-60）</span>
        <input type="number" id="idleMinutes" min="1" max="60" style="width:78px;flex:none;">
        <button class="small" onclick="saveIdleMinutes()">保存</button>
      </div>
    </div>
    <div class="card">
      <h2>音源与插件 <span id="pluginCount" class="muted"></span></h2>
      <div class="muted" style="padding:0 0 8px;">每行一个音源插件：启用/停用、配置登录变量、调整搜索优先级（越靠上越先搜索、结果越靠前），都在这一行里完成。</div>
      <div class="row" style="border:none;padding:0 0 8px;flex-wrap:wrap;gap:8px;">
        <span class="muted" style="flex:none;">默认排序</span>
        <select id="cfgSortBy" style="width:120px;"></select>
        <select id="cfgSortAsc" style="width:80px;">
          <option value="1">升序</option>
          <option value="0">降序</option>
        </select>
        <span class="muted" style="flex:none;margin-left:6px;">最多结果</span>
        <input type="number" id="cfgMaxTotal" min="20" max="200" step="10" style="width:78px;flex:none;">
      </div>
      <div id="srcList"></div>
      <div class="row" style="border:none;padding:8px 0 0;flex-wrap:wrap;gap:8px;">
        <button class="small" onclick="saveSearchCfg()">保存设置</button>
        <button class="ghost small" onclick="resetCfgOrder()">重置顺序</button>
        <button class="ghost small" onclick="syncAll()">⟳ 同步订阅</button>
        <button class="ghost small" id="uninstallAllBtn" onclick="uninstallAll(this)" style="margin-left:auto;">✕ 全部卸载</button>
      </div>
    </div>
    <div class="card">
      <h2>订阅源</h2>
      <div id="subList"></div>
      <div class="row" style="border:none;padding:10px 0 0;">
        <input type="text" id="subUrl" placeholder="plugins.json 或 .js 直链">
        <button class="small" onclick="addSub()">添加</button>
      </div>
    </div>
    <div class="card">
      <h2>配置</h2>
      <div class="muted">导出/导入主题、歌词设置与收藏专辑，用于备份或迁移到其他设备。</div>
      <div class="row" style="border:none;padding:10px 0 0;">
        <button class="small" onclick="exportConfig()">⬇ 导出配置</button>
        <button class="ghost small" onclick="el('importFile').click()">⬆ 导入配置</button>
        <input type="file" id="importFile" accept="application/json,.json" style="display:none;" onchange="importConfig(this)">
      </div>
    </div>
  </section>
</main>

<div id="toast"></div>

<div id="favModal" onclick="if(event.target===this)closeFavModal()">
  <div class="sheet">
    <h3 id="favModalTitle">收藏到…</h3>
    <div id="favAlbumList"></div>
    <div class="newrow">
      <input type="text" id="favAlbumNew" placeholder="新专辑名称">
      <button class="small" onclick="createFavAlbumFromModal()">新建</button>
    </div>
    <div class="newrow"><button class="ghost small" style="flex:1;" onclick="closeFavModal()">关闭</button></div>
  </div>
</div>

<div id="collectModal" onclick="if(event.target===this)closeCollectAll()">
  <div class="sheet">
    <h3 id="collectModalTitle">全部收藏到…</h3>
    <div class="muted" id="collectModalSub"></div>
    <div id="collectList"></div>
    <div class="newrow">
      <input type="text" id="collectNewName" placeholder="新收藏夹名称">
      <button class="small" onclick="createCollectAndAdd()">新建并收藏</button>
    </div>
    <div class="newrow"><button class="ghost small" style="flex:1;" onclick="closeCollectAll()">关闭</button></div>
  </div>
</div>

<nav>
  <button class="on" data-tab="player" onclick="switchTab('player')"><span class="ic">🎵</span>播放</button>
  <button data-tab="search" onclick="switchTab('search')"><span class="ic">🔍</span>搜索</button>
  <button data-tab="fav" onclick="switchTab('fav')"><span class="ic">❤️</span>收藏</button>
  <button data-tab="history" onclick="switchTab('history')"><span class="ic">🕘</span>历史</button>
  <button data-tab="manage" onclick="switchTab('manage')"><span class="ic">⚙️</span>管理</button>
</nav>

<script>
function api(path, opts) {
  return fetch(path, opts).then(function (r) { return r.json(); });
}
function el(id) { return document.getElementById(id); }
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
var toastTimer = null;
function toast(t) {
  var x = el('toast'); x.textContent = t; x.className = 'show';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { x.className = ''; }, 2200);
}
function fmtPos(ms) {
  if (!ms || ms < 0) ms = 0;
  var s = Math.floor(ms / 1000), m = Math.floor(s / 60); s = s % 60;
  return m + ':' + (s < 10 ? '0' : '') + s;
}
function post(path, body) {
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
}

function switchTab(name) {
  var pages = document.querySelectorAll('.page');
  for (var i = 0; i < pages.length; i++) pages[i].className = 'page';
  el('page-' + name).className = 'page on';
  var btns = document.querySelectorAll('nav button');
  for (var j = 0; j < btns.length; j++) btns[j].className = btns[j].getAttribute('data-tab') === name ? 'on' : '';
  if (name === 'player') loadPlayer();
  if (name === 'fav') loadFavLists();
  if (name === 'history') loadHistory();
  if (name === 'search') loadSearchCfg();
  if (name === 'manage') { loadSubs(); loadSearchCfg(); loadLyric(); loadMeta(); loadIdle(); }
}

/* ---------------- 播放器 ---------------- */
var MODES = ['ORDER', 'LOOP_ONE', 'SHUFFLE'];
var MODE_NAMES = { ORDER: '⇅ 顺序', LOOP_ONE: '🔂 单曲', SHUFFLE: '🔀 随机' };
var MODE_IC = { ORDER: '⇅', LOOP_ONE: '🔂︎', SHUFFLE: '🔀︎' };
var curMode = 'ORDER';
var lastStatus = null;
var seeking = false;

function cycleMode() {
  var i = MODES.indexOf(curMode);
  var next = MODES[(i + 1) % MODES.length];
  post('/api/player/mode', { mode: next }).then(function () {
    curMode = next; loadPlayer();
  }).catch(function () { toast('操作失败'); });
}

var playerFetching = false;
/* 播放器状态渲染：轮询与 SSE 推送共用同一条渲染路径，只换数据来源 */
function handlePlayerData(d) {
  lastStatus = d;
  if (!d.title) {
    el('pTitle').textContent = '未在播放';
    el('pArtist').textContent = '';
    el('pErr').textContent = '';
    el('pToggle').textContent = '▶︎';
    renderQueue(d, -1);
    return;
  }
  el('pTitle').textContent = d.title;
  el('pArtist').textContent = (d.artist || '') + (d.album ? ' · ' + d.album : '') + ' · ' + (d.index + 1) + '/' + d.queueSize + (d.buffering ? ' · 缓冲中' : '');
  var art = el('pArt');
  var want = d.artwork || '';
  var wantSrc = want ? '/api/img?url=' + encodeURIComponent(want) : '';
  // JS 里给 img.src 赋空字符串会被解析为当前页 URL 并再次触发 onerror，
  // 旧代码 onerror="this.src=''" 与之叠加形成无限请求循环（封面加载失败时
  // 浏览器每秒反复拉整页 HTML，CPU/网络双风暴导致页面卡死）。
  // 修复：无封面时移除 src 属性；onerror 首次触发后自毁并不再重试。
  if (art.getAttribute('src') !== wantSrc) {
    if (wantSrc) art.src = wantSrc; else art.removeAttribute('src');
  }
  art.style.visibility = want ? 'visible' : 'hidden';
  el('pToggle').textContent = d.playing ? '⏸︎' : '▶︎';
  var favBtn = el('pFav');
  if (favBtn) {
    favBtn.textContent = d.favorite ? '♥' : '♡';
    favBtn.className = 'ctrl' + (d.favorite ? ' favon' : '');
  }
  el('pErr').textContent = d.error ? String(d.error) : '';
  el('pVol').textContent = (d.volume || 0) + '%';
  el('volBar').value = d.volume || 0;
  if (d.playMode) {
    curMode = d.playMode;
    el('pMode').textContent = MODE_IC[d.playMode] || '⇅';
    el('pModeName').textContent = (MODE_NAMES[d.playMode] || '顺序').replace(/^[^ ]+ /, '');
  }
  el('pDur').textContent = fmtPos(d.duration);
  if (!seeking) {
    var f = d.duration > 0 ? Math.round(d.position / d.duration * 1000) : 0;
    var bar = el('seekBar');
    bar.value = f;
    bar.style.setProperty('--fill', (f / 10) + '%');
    el('pPos').textContent = fmtPos(d.position);
  }
  renderQueue(d, d.index);
}
function loadPlayer() {
  // 同一时刻只允许一条 /api/player 在途：慢网下响应未回时跳过本轮，避免请求堆积
  if (playerFetching) return;
  playerFetching = true;
  api('/api/player').then(function (d) {
    playerFetching = false;
    handlePlayerData(d);
  }).catch(function () { playerFetching = false; });
}

/* 进度条拖动：拖动中本地预览，松手提交 seek */
(function () {
  var bar = el('seekBar');
  function fill() {
    bar.style.setProperty('--fill', (bar.value / 10) + '%');
    if (lastStatus) el('pPos').textContent = fmtPos(bar.value / 1000 * lastStatus.duration);
  }
  bar.addEventListener('input', function () { seeking = true; fill(); });
  bar.addEventListener('change', function () {
    seeking = false;
    if (lastStatus && lastStatus.duration > 0) {
      post('/api/player/seek', { pos: Math.round(bar.value / 1000 * lastStatus.duration) })
        .then(function () { setTimeout(loadPlayer, 400); })
        .catch(function () { toast('跳转失败'); loadPlayer(); });
    }
  });
})();

function showVol() {
  var r = el('volRow');
  r.style.display = r.style.display === 'none' ? 'flex' : 'none';
}
function playerCmd(cmd) {
  api('/api/player/' + cmd, { method: 'POST' }).then(function () { loadPlayer(); });
}
/* 播放页收藏当前曲：弹出收藏夹选择 */
var favCtx = null; /* { mode:'player' } 或 { mode:'search', idx } */
function toggleCurFav() {
  favCtx = { mode: 'player' };
  el('favModalTitle').textContent = '收藏到…（' + (el('pTitle').textContent || '') + '）';
  loadFavAlbums();
  el('favModal').className = 'show';
}
function favResult(i) {
  favCtx = { mode: 'search', idx: i };
  var it = searchResults[i];
  el('favModalTitle').textContent = '收藏到…（' + (it ? it.title : '') + '）';
  loadFavAlbums();
  el('favModal').className = 'show';
}
function closeFavModal() { el('favModal').className = ''; }
function loadFavAlbums() {
  api('/api/player/fav-albums').then(function (d) {
    var box = el('favAlbumList');
    var arr = d.albums || [];
    var html = '';
    arr.forEach(function (a, i) {
      html += '<div class="fitem" onclick="pickFavAlbum(' + i + ')">' +
        '<span class="ck">' + (a.inList ? '♥' : '♡') + '</span>' +
        '<span class="ellip">' + esc(a.name) + '</span>' +
        '<span class="cnt">' + a.count + ' 首</span></div>';
    });
    box.innerHTML = html || '<div class="empty">还没有收藏专辑</div>';
    window._favAlbums = arr;
  }).catch(function () { toast('加载专辑失败'); });
}
function pickFavAlbum(i) {
  var a = (window._favAlbums || [])[i];
  if (!a || !favCtx) return;
  if (favCtx.mode === 'player') {
    post('/api/player/favorite', { listId: a.id }).then(function (d) {
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadPlayer(); loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  } else {
    var it = searchResults[favCtx.idx];
    if (!it) return;
    post('/api/fav/toggle', { listId: a.id, plugin: it.plugin, raw: it.raw }).then(function (d) {
      var b = el('sfav' + favCtx.idx);
      if (b) { b.textContent = d.favorited ? '♥' : '♡'; b.style.color = d.favorited ? 'var(--accent2)' : ''; }
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  }
}
function createFavAlbumFromModal() {
  var name = (el('favAlbumNew').value || '').trim();
  if (!name) { toast('请输入专辑名'); return; }
  post('/api/fav/lists/create', { name: name }).then(function () {
    el('favAlbumNew').value = '';
    loadFavAlbums();
  }).catch(function () { toast('新建失败'); });
}
function volume(delta) {
  post('/api/player/volume', { delta: delta }).then(function () { loadPlayer(); });
}

var lastQueueSig = '';
function renderQueue(d, currentIdx) {
  var q = d.queue || [];
  el('qCount').textContent = q.length ? '· ' + q.length + ' 首' : '';
  var box = el('queueBox');
  if (!q.length) { lastQueueSig = ''; box.innerHTML = '<div class="empty">队列为空，去搜索推歌吧</div>'; return; }
  // 队列内容播放期间不变，仅高亮行移动：签名相同就只切换 .cur，避免每 2 秒整表重建 DOM 卡顿
  var sig = q.map(function (it) { return it.title + '\u0000' + it.artist; }).join('\u0001');
  if (sig === lastQueueSig && box.children.length === q.length) {
    for (var k = 0; k < box.children.length; k++) {
      var c = box.children[k];
      var isCur = k === currentIdx;
      if (isCur !== c.classList.contains('cur')) {
        c.className = 'qitem' + (isCur ? ' cur' : '');
        c.children[0].textContent = isCur ? '▶' : (k + 1);
      }
    }
    return;
  }
  lastQueueSig = sig;
  var html = '';
  q.forEach(function (it) {
    html += '<div class="qitem' + (it.index === currentIdx ? ' cur' : '') + '" onclick="skipTo(' + it.index + ')">' +
      '<span class="n">' + (it.index === currentIdx ? '▶' : (it.index + 1)) + '</span>' +
      '<span class="grow ellip">' + esc(it.title) + '</span>' +
      '<span class="muted ellip" style="max-width:35%;">' + esc(it.artist) + '</span></div>';
  });
  box.innerHTML = html;
}
function skipTo(i) {
  post('/api/player/skip', { index: i }).then(function () { setTimeout(loadPlayer, 300); });
}

/* ---------------- 搜索配置与筛选 ---------------- */
var cfgOrder = [], srcNames = [], knownNames = [], srcSel = {}, orderUI = [];
var plugins = [], pluginMap = {};
var SORT_OPTS = [['default', '默认（音源顺序）'], ['duration', '时长'], ['title', '歌名'], ['artist', '歌手']];
function bindOpts(sel, opts, val) {
  if (!sel) return null;
  sel.innerHTML = '';
  opts.forEach(function (o) {
    var op = document.createElement('option');
    op.value = o[0];
    op.textContent = o[1];
    sel.appendChild(op);
  });
  sel.value = val;
  return sel.value;
}
function srcOrderFull() {
  var out = cfgOrder.slice();
  knownNames.forEach(function (n) { if (out.indexOf(n) < 0) out.push(n); });
  return out;
}
function loadSearchCfg() {
  var cfgP = api('/api/search/config');
  return Promise.all([api('/api/plugins'), api('/api/plugins/vars')]).then(function (arr) {
    var list = arr[0].plugins || [];
    var vlist = (arr[1] && arr[1].plugins) || [];
    pluginMap = {};
    vlist.forEach(function (vp) { pluginMap[vp.platform] = vp; });
    plugins = list;
    srcNames = list.filter(function (p) { return p.enabled && !p.loadError; })
      .map(function (p) { return p.platform || p.name; })
      .filter(function (n, i, a) { return n && a.indexOf(n) === i; });
    knownNames = list.map(function (p) { return p.platform || p.name; })
      .filter(function (n, i, a) { return n && a.indexOf(n) === i; });
    return cfgP;
  }).then(function (d) {
    cfgOrder = d.sourceOrder || [];
    bindOpts(el('cfgSortBy'), SORT_OPTS, d.sortBy);
    if (el('cfgSortAsc')) el('cfgSortAsc').value = d.asc ? '1' : '0';
    if (el('cfgMaxTotal')) el('cfgMaxTotal').value = d.maxTotal || 60;
    bindOpts(el('sortSel'), SORT_OPTS, d.sortBy);
    if (el('ascSel')) el('ascSel').value = d.asc ? '1' : '0';
    orderUI = srcOrderFull();
    renderSrcBar();
    renderSrcList();
  }).catch(function () { });
}
function renderSrcBar() {
  var bar = el('srcBar');
  if (!bar) return;
  bar.innerHTML = '';
  var mk = function (label, on, cb) {
    var chip = document.createElement('span');
    chip.className = 'chip' + (on ? ' on' : '');
    chip.textContent = label;
    chip.onclick = cb;
    bar.appendChild(chip);
  };
  var sorted = srcNames.slice().sort(function (a, b) {
    var ia = orderUI.indexOf(a), ib = orderUI.indexOf(b);
    return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib);
  });
  mk('全部', Object.keys(srcSel).length === 0, function () { srcSel = {}; renderSrcBar(); });
  sorted.forEach(function (n) {
    mk(n, !!srcSel[n], function () {
      if (srcSel[n]) delete srcSel[n]; else srcSel[n] = 1;
      renderSrcBar();
    });
  });
}
/* 音源与插件合并列表：每行按「优先级顺序」排，同时带停用/启用、变量配置、卸载。 */
function renderSrcList() {
  var box = el('srcList');
  if (!box) return;
  var cnt = el('pluginCount');
  if (cnt) cnt.textContent = plugins.length ? '· ' + plugins.length + ' 个' : '';
  if (!orderUI.length) { box.innerHTML = '<div class="empty">还没有音源插件，先在下方添加订阅源</div>'; return; }
  /* 兜底：插件库里存在但不在配置顺序中的音源（如 js 直链导入后未触发配置保存）
     追加到末尾显示——绝不隐藏已安装的插件，否则用户会误以为导入失败。 */
  var list = orderUI.slice();
  plugins.forEach(function (p) {
    var key = p.platform || p.name;
    if (key && list.indexOf(key) < 0) list.push(key);
  });
  var html = '';
  list.forEach(function (pk, i) {
    var p = null;
    for (var k = 0; k < plugins.length; k++) {
      if ((plugins[k].platform || plugins[k].name) === pk) { p = plugins[k]; break; }
    }
    var vp = pluginMap[pk];
    var defs = (vp && vp.userVariables) || [];
    var ghost = !p;
    var enabled = !!p && !!p.enabled && !p.loadError;
    var tag = '';
    if (ghost) tag = '<span class="badge off">已卸载</span>';
    else if (p.loadError) tag = '<span class="badge off">载入失败</span>';
    else if (!p.enabled) tag = '<span class="badge off">已停用</span>';
    else tag = '<span class="badge on">已启用</span>';
    var open = !!window._openVarPlatform && window._openVarPlatform === pk;
    html += '<div class="srcitem">' +
      '<span class="srcno">' + (i + 1) + '</span>' +
      '<div class="grow"><div class="ellip name">' + esc(pk) + ' ' + tag + '</div>' +
      '<div class="muted ellip">' + (ghost ? '订阅已移除' : ('v' + esc(p.version || '-'))) + (defs.length ? ' · ' + defs.length + ' 项登录变量' : '') + '</div></div>' +
      '<div class="act">' +
      (defs.length && !ghost ? '<button class="actb" onclick="toggleVars(' + i + ')">' + (open ? '收起变量' : '变量') + '</button>' : '') +
      (ghost ? '<button class="actb warn" onclick="removeOrderEntry(' + i + ')" title="从优先级中移除">✕</button>'
             : '<button class="actb' + (enabled ? ' dim' : '') + '" onclick="togglePlugin(' + i + ')">' + (enabled ? '停用' : '启用') + '</button>' +
               '<button class="actb warn" onclick="uninstallPlugin(' + i + ',this)">卸载</button>') +
      '<button class="actb dim" onclick="moveCfgOrder(' + i + ',-1)">↑</button>' +
      '<button class="actb dim" onclick="moveCfgOrder(' + i + ',1)">↓</button>' +
      '</div></div>';
    if (defs.length && !ghost) {
      html += '<div class="varpanel" id="varPanel' + i + '" style="display:' + (open ? 'block' : 'none') + ';">';
      defs.forEach(function (d) {
        var val = (vp.values && vp.values[d.key]) || '';
        var isPw = (d.type || '').toLowerCase() === 'password';
        html += '<div class="vrow"><span class="ellip">' + esc(d.name || d.key) + '</span>' +
          '<input ' + (isPw ? 'type="password"' : 'type="text"') + ' data-platform="' + esc(pk) + '" data-varkey="' + esc(d.key) + '" placeholder="' + esc(d.key) + '" value="' + esc(val) + '" style="flex:1;min-width:0;"></div>';
      });
      html += '<div class="vrow"><span></span><button class="small" onclick="savePluginVars(' + i + ')">保存变量</button></div></div>';
    }
  });
  box.innerHTML = html;
}
function moveCfgOrder(i, dir) {
  var j = i + dir;
  if (j < 0 || j >= orderUI.length) return;
  var t = orderUI[i]; orderUI[i] = orderUI[j]; orderUI[j] = t;
  renderSrcList();
}
function removeOrderEntry(i) {
  orderUI.splice(i, 1);
  renderSrcList();
}
function resetCfgOrder() {
  orderUI = knownNames.slice();
  renderSrcList();
  toast('已重置为插件顺序（尚未保存，请点「保存设置」）');
}
function toggleVars(i) {
  var pk = orderUI[i];
  if (!pk) return;
  var panel = el('varPanel' + i);
  if (!panel) return;
  var willOpen = panel.style.display === 'none';
  panel.style.display = willOpen ? 'block' : 'none';
  window._openVarPlatform = willOpen ? pk : '';
  renderSrcList();
}
function savePluginVars(i) {
  var pk = orderUI[i];
  if (!pk) return;
  var panel = el('varPanel' + i);
  if (!panel) return;
  var vars = {};
  var inputs = panel.querySelectorAll('[data-varkey]');
  for (var n = 0; n < inputs.length; n++) vars[inputs[n].getAttribute('data-varkey')] = inputs[n].value;
  post('/api/plugins/vars', { platform: pk, vars: vars }).then(function (d) {
    toast(d.message || '已保存');
    loadSearchCfg();
  }).catch(function () { toast('保存失败'); });
}
function togglePlugin(i) {
  var pk = orderUI[i];
  if (!pk) return;
  post('/api/plugins/toggle', { name: pk, enabled: !isPluginEnabled(pk) }).then(function (d) {
    if (d && d.ok === false) { toast(d.error || '操作失败'); return; }
    toast(isPluginEnabled(pk) ? '已停用' : '已启用');
    loadSearchCfg();
  }).catch(function () { toast('操作失败'); });
}
/* 两步确认：不用原生 confirm()——部分手机 WebView 会吞掉弹窗直接返回 false，
   表现为"点卸载没反应"。第一次点变红显示确认文案，再点执行，超时自动复原。 */
function armConfirm(btn, msg, fn) {
  if (btn._armed) {
    btn._armed = false;
    clearTimeout(btn._armT);
    btn.textContent = btn._t0;
    btn.style.color = '';
    fn();
    return;
  }
  btn._armed = true;
  btn._t0 = btn.textContent;
  btn.textContent = msg;
  btn.style.color = 'var(--danger)';
  btn._armT = setTimeout(function () {
    btn._armed = false;
    btn.textContent = btn._t0;
    btn.style.color = '';
  }, 2600);
}
function uninstallPlugin(i, btn) {
  var pk = orderUI[i];
  if (!pk) return;
  armConfirm(btn, '确认卸载?', function () {
    post('/api/plugins/uninstall', { name: pk }).then(function (d) {
      if (d && d.ok === false) { toast(d.error || '卸载失败'); return; }
      toast('已卸载 ' + pk);
      loadSearchCfg();
    }).catch(function () { toast('卸载失败'); });
  });
}
function uninstallAll(btn) {
  if (!plugins.length && !orderUI.length) { toast('当前没有插件'); return; }
  armConfirm(btn, '确认全部卸载?', function () {
    btn.disabled = true;
    post('/api/plugins/uninstallAll', {}).then(function (d) {
      if (d && d.ok === false) { toast(d.error || '卸载失败'); return; }
      toast(d.message || '已全部卸载');
      loadSearchCfg();
    }).catch(function () { toast('卸载失败'); });
    setTimeout(function () { btn.disabled = false; }, 800);
  });
}
function isPluginEnabled(pk) {
  for (var k = 0; k < plugins.length; k++) {
    if ((plugins[k].platform || plugins[k].name) === pk) return !!plugins[k].enabled;
  }
  return false;
}
function orderedCfg() {
  return orderUI.slice();
}
function saveSearchCfg() {
  var order = orderedCfg();
  cfgOrder = order; orderUI = order;
  post('/api/search/config', {
    sourceOrder: order,
    sortBy: el('cfgSortBy').value,
    asc: el('cfgSortAsc').value === '1',
    maxTotal: parseInt(el('cfgMaxTotal').value || '60', 10) || 60
  }).then(function (d) { toast(d.message || '已保存'); renderSrcList(); })
    .catch(function () { toast('保存失败'); });
}
function saveCfgInline() {
  post('/api/search/config', {
    sourceOrder: orderedCfg(),
    sortBy: el('sortSel').value,
    asc: el('ascSel').value === '1',
    maxTotal: parseInt((el('cfgMaxTotal') ? el('cfgMaxTotal').value : '60') || '60', 10)
  }).then(function (d) { toast(d.message || '已保存'); })
    .catch(function () { toast('保存失败'); });
}

/* ---------------- 搜索 ---------------- */
var searchResults = [], sPage = 1, sSize = 20, sPlugin = null;
var searchPerSrc = [];
var sTimer = null;
function doSearch() {
  var q = el('searchQ').value.trim();
  if (!q) return;
  if (sTimer) { clearInterval(sTimer); sTimer = null; }
  searchResults = [];
  searchPerSrc = [];
  sPlugin = null;
  sPage = 1;
  var url = '/api/search?q=' + encodeURIComponent(q);
  var sel = Object.keys(srcSel);
  if (sel.length) url += '&sources=' + encodeURIComponent(sel.join(','));
  var minD = parseInt(el('minD').value, 10);
  if (!isNaN(minD) && minD > 0) url += '&minD=' + minD;
  var maxD = parseInt(el('maxD').value, 10);
  if (!isNaN(maxD) && maxD > 0) url += '&maxD=' + maxD;
  if (el('needArt').checked) url += '&art=1';
  url += '&sort=' + encodeURIComponent(el('sortSel').value);
  url += '&asc=' + el('ascSel').value;
  toast('搜索中…');
  el('searchBox').innerHTML = '<div class="empty">正在搜索…</div>';
  el('searchInfo').textContent = '搜索中…';
  el('playAllBtn').style.display = 'none';
  el('collectAllBtn').style.display = 'none';
  el('searchCard').style.display = '';
  api(url).then(function (d) {
    if (!d || !d.ok) { toast(d && d.error || '搜索失败'); return; }
    if (!d.id) { searchResults = d.results || []; searchPerSrc = d.perSource || []; renderSearch(); return; }
    sTimer = setInterval(function () { pollSearch(d.id); }, 1200);
    pollSearch(d.id);
  }).catch(function () { toast('搜索失败'); });
}
var pollFetching = false;
function pollSearch(id) {
  /* 在途标记：慢网下响应未回时跳过本轮，避免 1.2s 轮询堆积并发拖死页面 */
  if (pollFetching) return;
  pollFetching = true;
  api('/api/search/poll?id=' + encodeURIComponent(id)).then(function (d) {
    pollFetching = false;
    if (!d || !d.ok) {
      clearInterval(sTimer); sTimer = null;
      el('searchInfo').textContent = '共 ' + (searchResults.length) + ' 条';
      return;
    }
    searchResults = d.results || [];
    searchPerSrc = d.perSource || [];
    if (!searchResults.length) sPage = 1;
    var prog = ' · 已搜索 ' + (d.done || 0) + '/' + (d.totalEnabled || 0) + ' 个音源…';
    el('searchInfo').textContent = '共 ' + (d.total || 0) + ' 条' + (d.finished ? '' : prog);
    el('playAllBtn').style.display = searchResults.length ? '' : 'none';
    el('collectAllBtn').style.display = searchResults.length ? '' : 'none';
    renderSearch();
    if (d.finished) { clearInterval(sTimer); sTimer = null; el('searchInfo').textContent = '共 ' + (d.total || 0) + ' 条'; }
  }).catch(function () { pollFetching = false; });
}
function playAllSearch() {
  if (!searchResults.length) return;
  // 跨源全部入队：当前站点过滤下的所有结果，每条 raw 补全自身 platform，
  // 后端按条目来源插件分别解析（旧实现只播放结果最多的单一音源，其余源被丢弃）
  var items = collectTargets();
  if (!items.length) { toast('没有可播放的结果'); return; }
  post('/api/play/queue', { plugin: items[0].platform, items: items }).then(function (d) {
    toast(d.message || '已播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  }).catch(function () { toast('播放失败'); });
}

/* ---------------- 全部收藏（搜索结果批量加入收藏夹） ---------------- */
function collectTargets() {
  // 当前站点过滤下可见的全部歌曲：raw 补全 platform 后返回（收藏/回放需要来源插件）
  var out = [];
  searchResults.forEach(function (it) {
    if (sPlugin && it.plugin !== sPlugin) return;
    var raw = it.raw || {};
    if (!raw.platform) {
      raw = JSON.parse(JSON.stringify(raw));
      raw.platform = it.plugin;
    }
    out.push(raw);
  });
  return out;
}
function openCollectAll() {
  var items = collectTargets();
  if (!items.length) { toast('当前没有可收藏的结果'); return; }
  el('collectModalTitle').textContent = '全部收藏到…';
  el('collectModalSub').textContent = '将当前列表的 ' + items.length + ' 首加入所选收藏夹（已收藏的自动跳过）';
  el('collectNewName').value = '';
  renderCollectList();
  el('collectModal').className = 'show';
}
function closeCollectAll() { el('collectModal').className = ''; }
function renderCollectList() {
  api('/api/fav/lists').then(function (d) {
    var box = el('collectList');
    var list = d.lists || [];
    window._collectLists = list;
    var items = collectTargets();
    var html = '';
    list.forEach(function (l, i) {
      html += '<div class="fitem" onclick="pickCollectList(' + i + ')">' +
        '<span class="ck">♡</span>' +
        '<span class="ellip">' + esc(l.name) + '</span>' +
        '<span class="cnt">' + l.count + ' 首</span></div>';
    });
    box.innerHTML = html || '<div class="empty">还没有收藏夹，可在下方新建</div>';
  }).catch(function () { toast('加载收藏夹失败'); });
}
function doCollectAll(listId, listName) {
  var items = collectTargets();
  if (!items.length) { toast('当前没有可收藏的结果'); return; }
  post('/api/fav/addAll', { listId: listId, items: items }).then(function (d) {
    if (d.ok) {
      toast(d.added > 0 ? '已加入「' + listName + '」' + d.added + ' 首' : '「' + listName + '」内已全部收藏');
      closeCollectAll();
    } else toast(d.error || '收藏失败');
  }).catch(function () { toast('收藏失败'); });
}
function pickCollectList(i) {
  var l = (window._collectLists || [])[i];
  if (l) doCollectAll(l.id, l.name);
}
function createCollectAndAdd() {
  var name = (el('collectNewName').value || '').trim();
  if (!name) { toast('请输入收藏夹名称'); return; }
  post('/api/fav/lists/create', { name: name }).then(function (d) {
    el('collectNewName').value = '';
    if (d.ok && d.id) doCollectAll(d.id, name);
    else toast('新建失败');
  }).catch(function () { toast('新建失败'); });
}
function renderSearch() {
  var box = el('searchBox');
  renderPluginBar();
  el('playAllBtn').style.display = searchResults.length ? '' : 'none';
  el('collectAllBtn').style.display = searchResults.length ? '' : 'none';
  var view = [];
  searchResults.forEach(function (it, i) { if (!sPlugin || it.plugin === sPlugin) view.push(i); });
  if (!searchResults.length) { box.innerHTML = '<div class="empty">没有结果</div>'; el('searchPager').innerHTML = ''; return; }
  if (!view.length) { box.innerHTML = '<div class="empty">该站点没有结果</div>'; el('searchPager').innerHTML = ''; return; }
  var pages = Math.max(1, Math.ceil(view.length / sSize));
  if (sPage > pages) sPage = pages;
  var html = '';
  view.slice((sPage - 1) * sSize, sPage * sSize).forEach(function (i) {
    var it = searchResults[i];
    html += '<div class="row">' +
      '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
      '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.plugin) + '</div></div>' +
      '<button class="small ghost" id="sfav' + i + '" onclick="favResult(' + i + ')">♡</button>' +
      '<button class="small" onclick="playResult(' + i + ')">播放</button></div>';
  });
  box.innerHTML = html;
  var pg = el('searchPager');
  pg.innerHTML = pages > 1 ?
    '<button class="ghost" ' + (sPage <= 1 ? 'disabled' : '') + ' onclick="sGo(' + (sPage - 1) + ')">‹</button>' +
    '<span>' + sPage + ' / ' + pages + '</span>' +
    '<button class="ghost" ' + (sPage >= pages ? 'disabled' : '') + ' onclick="sGo(' + (sPage + 1) + ')">›</button>' : '';
}
function sGo(p) { sPage = p; renderSearch(); }
function renderPluginBar() {
  var bar = el('searchPluginBar');
  if (!bar) return;
  var names = [], seen = {};
  var srcs = searchPerSrc.length ? searchPerSrc : [];
  srcs.forEach(function (s) { if (s.plugin && !seen[s.plugin]) { seen[s.plugin] = 1; names.push(s.plugin); } });
  if (names.length > 1) {
    bar.style.display = '';
    bar.innerHTML = '';
    var mk = function (label, val) {
      var chip = document.createElement('span');
      chip.className = 'chip' + (sPlugin === val ? ' on' : '');
      chip.textContent = label;
      chip.onclick = function () { sPlugin = val; sPage = 1; renderSearch(); };
      bar.appendChild(chip);
    };
    mk('全部', null);
    names.forEach(function (n) { mk(n, n); });
  } else {
    bar.style.display = 'none';
    bar.innerHTML = '';
  }
}
function playResult(i) {
  var it = searchResults[i];
  post('/api/play', { plugin: it.plugin, raw: it.raw }).then(function (d) {
    toast(d.message || '已播放');
  }).catch(function () { toast('播放失败'); });
}
/* ---------------- 收藏 ---------------- */
var favLists = [], curFavId = null, favPage = 1, favSize = 20;
function loadFavLists() {
  api('/api/fav/lists').then(function (d) {
    favLists = d.lists || [];
    if (!curFavId && favLists.length) curFavId = favLists[0].id;
    var bar = el('favListBar');
    bar.innerHTML = '';
    favLists.forEach(function (l) {
      var chip = document.createElement('span');
      chip.className = 'chip' + (l.id === curFavId ? ' on' : '');
      chip.textContent = l.name + ' (' + l.count + ')';
      chip.onclick = function () { curFavId = l.id; favPage = 1; loadFavLists(); };
      if (l.id !== 'fav_default') {
        var rn = document.createElement('span'); rn.className = 'x'; rn.textContent = '✎';
        rn.onclick = function (e) { e.stopPropagation(); renameFavList(l); };
        chip.appendChild(rn);
        var rm = document.createElement('span'); rm.className = 'x'; rm.textContent = '✕';
        rm.onclick = function (e) { e.stopPropagation(); removeFavList(l); };
        chip.appendChild(rm);
      }
      bar.appendChild(chip);
    });
    loadFavItems();
  }).catch(function () {});
}
function createFavList() {
  var name = el('favNewName').value.trim();
  if (!name) return;
  post('/api/fav/lists/create', { name: name }).then(function () {
    toast('已创建：' + name); el('favNewName').value = ''; loadFavLists();
  }).catch(function () { toast('创建失败'); });
}
function renameFavList(l) {
  var name = prompt('重命名专辑', l.name);
  if (!name || !name.trim()) return;
  post('/api/fav/lists/rename', { id: l.id, name: name.trim() }).then(loadFavLists);
}
function removeFavList(l) {
  if (!confirm('删除专辑「' + l.name + '」及其收藏？')) return;
  post('/api/fav/lists/remove', { id: l.id }).then(function () {
    if (curFavId === l.id) curFavId = null;
    loadFavLists();
  });
}
function loadFavItems() {
  var box = el('favItems'), act = el('favActions');
  act.innerHTML = '';
  if (!curFavId) { box.innerHTML = '<div class="empty">还没有专辑，点上方「新建」。</div>'; el('favPager').innerHTML = ''; return; }
  api('/api/fav/items?id=' + encodeURIComponent(curFavId) + '&page=' + favPage + '&size=' + favSize).then(function (d) {
    if (!d.ok) { box.innerHTML = '<div class="empty">' + esc(d.error) + '</div>'; return; }
    var items = d.items || [];
    if (d.total > 0) {
      act.innerHTML = '<button class="ghost small" onclick="playFavAlbum()">▶ 播放整个专辑（' + d.total + ' 首）</button>';
    }
    if (!items.length) { box.innerHTML = '<div class="empty">这个专辑还没有歌。</div>'; el('favPager').innerHTML = ''; return; }
    var html = '';
    window._favItems = d.items;
    items.forEach(function (it, k) {
      html += '<div class="row">' +
        '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
        '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.platform || '') + '</div></div>' +
        '<button class="small" onclick="playFavItem(' + k + ')">播放</button>' +
        '<button class="danger small" onclick="removeFavItem(' + k + ')">移出</button></div>';
    });
    box.innerHTML = html;
    var pages = Math.max(1, Math.ceil(d.total / d.size));
    el('favPager').innerHTML = pages > 1 ?
      '<button class="ghost" ' + (d.page <= 1 ? 'disabled' : '') + ' onclick="favGo(' + (d.page - 1) + ')">‹</button>' +
      '<span>' + d.page + ' / ' + pages + ' · 共 ' + d.total + '</span>' +
      '<button class="ghost" ' + (d.page >= pages ? 'disabled' : '') + ' onclick="favGo(' + (d.page + 1) + ')">›</button>' : '';
  }).catch(function () {});
}
function favGo(p) { favPage = p; loadFavItems(); }
function playFavItem(i) {
  var it = (window._favItems || [])[i];
  if (!it) return;
  post('/api/play', { plugin: it.platform, raw: it.raw }).then(function (d) { toast(d.message || '已播放'); });
}
function removeFavItem(i) {
  var it = (window._favItems || [])[i];
  if (!it) return;
  post('/api/fav/toggle', { listId: curFavId, raw: it.raw }).then(loadFavLists);
}
function playFavAlbum() {
  post('/api/fav/play', { id: curFavId }).then(function (d) {
    toast(d.message || '已开始播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  });
}

/* ---------------- 播放历史 ---------------- */
function loadHistory() {
  api('/api/history').then(function (d) {
    var box = el('historyItems');
    var items = d.items || [];
    if (!items.length) { box.innerHTML = '<div class="empty">还没有播放记录。</div>'; return; }
    window._histItems = items;
    var html = '';
    items.forEach(function (it, k) {
      html += '<div class="row">' +
        '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
        '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.platform || '') + '</div></div>' +
        '<button class="small" onclick="playHistoryItem(' + k + ')">播放</button></div>';
    });
    box.innerHTML = html;
  }).catch(function () {});
}
function playHistoryItem(i) {
  var it = (window._histItems || [])[i];
  if (!it) return;
  post('/api/play', { plugin: it.platform, raw: it.raw }).then(function (d) {
    toast(d.message || '已播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  }).catch(function () { toast('播放失败'); });
}
function clearHistory() {
  if (!confirm('清空全部播放历史？')) return;
  post('/api/history/clear', {}).then(function (d) {
    toast(d.message || '已清空');
    loadHistory();
  });
}

/* ---------------- 管理 ---------------- */
function loadSubs() {
  api('/api/subscriptions').then(function (d) {
    var box = el('subList');
    var list = d.subscriptions || [];
    if (!list.length) { box.innerHTML = '<div class="empty">还没有订阅源</div>'; return; }
    var html = '';
    list.forEach(function (s) {
      html += '<div class="row"><span class="grow muted ellip" title="' + esc(s.url) + '">' + esc(s.url) + '</span>' +
        '<button class="danger small" onclick="removeSub(this)" data-url="' + esc(s.url) + '">删除</button></div>';
    });
    box.innerHTML = html;
  }).catch(function () {});
}
function addSub() {
  var url = el('subUrl').value.trim();
  if (!url) return;
  post('/api/subscriptions', { url: url }).then(function (d) {
    toast(d.message || '已添加'); el('subUrl').value = '';
    loadSubs(); loadSearchCfg();
  }).catch(function () { toast('添加失败'); });
}
function removeSub(btn) {
  post('/api/subscriptions/remove', { url: btn.getAttribute('data-url') }).then(function () {
    toast('已删除订阅'); loadSubs();
  });
}
function syncAll() {
  api('/api/sync', { method: 'POST' }).then(function (d) {
    toast(d.message || '开始同步');
    setTimeout(loadSearchCfg, 4000);
  }).catch(function () { toast('同步失败'); });
}

/* ---------------- 主题 ---------------- */
var themes = [], curTheme = null;
function applyTheme(id) {
  var t = themes.filter(function (x) { return x.id === id; })[0];
  if (!t) return;
  var r = document.documentElement.style;
  r.setProperty('--accent', '#' + t.accent);
  r.setProperty('--accent2', '#' + (t.accent2 || t.accent));
  r.setProperty('--bg', '#' + t.bg);
  if (t.card) {
    r.setProperty('--card', '#' + t.card);
    r.setProperty('--card2', '#' + t.card);
  }
  // 文字/次要文字/描边跟随主题，避免切主题后出现与色板不搭的硬编码灰蓝
  if (t.text) r.setProperty('--text', '#' + t.text);
  if (t.muted) r.setProperty('--muted', '#' + t.muted);
  if (t.line) r.setProperty('--line', '#' + t.line);
  if (t.radius) r.setProperty('--radius', t.radius + 'px');
  curTheme = id;
  renderThemeBar();
}
function renderThemeBar() {
  var bar = el('themeBar');
  bar.innerHTML = '';
  themes.forEach(function (t) {
    var chip = document.createElement('span');
    chip.className = 'chip' + (t.id === curTheme ? ' on' : '');
    chip.style.borderColor = '#' + t.accent;
    chip.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border-radius:50%;background:#' + t.accent + ';"></span>' + esc(t.name);
    chip.onclick = function () { setTheme(t.id); };
    bar.appendChild(chip);
  });
}
function setTheme(id) {
  post('/api/theme', { id: id }).then(function () {
    applyTheme(id);
    toast('已切换主题');
  }).catch(function () { toast('切换失败'); });
}
function loadThemes() {
  api('/api/themes').then(function (d) {
    themes = d.themes || [];
    applyTheme(d.current || (themes[0] && themes[0].id));
  }).catch(function () {});
}

/* ---------------- 播放页歌词设置 ---------------- */
var lyricCfg = { fontSizeSp: 16, colorHex: 'FFFFFF' };
var LRC_COLORS = [
  { hex: 'FFFFFF', name: '白' },
  { hex: 'FF6B9D', name: '粉' },
  { hex: '4A7DFF', name: '蓝' },
  { hex: 'FFB74D', name: '橙' },
  { hex: '34D399', name: '绿' }
];
function renderLyric() {
  el('lyricSize').textContent = lyricCfg.fontSizeSp + ' sp';
  el('lyricColor').value = '#' + lyricCfg.colorHex;
  var cb = el('lyricColorBar');
  cb.innerHTML = '';
  LRC_COLORS.forEach(function (c) {
    var chip = document.createElement('span');
    chip.className = 'chip' + (c.hex === lyricCfg.colorHex ? ' on' : '');
    chip.style.borderColor = '#' + c.hex;
    chip.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border-radius:50%;background:#' + c.hex + ';"></span>' + c.name;
    chip.onclick = function () { saveLyric({ colorHex: c.hex }); };
    cb.appendChild(chip);
  });
}
function saveLyric(patch) {
  var body = {
    fontSizeSp: patch.fontSizeSp != null ? patch.fontSizeSp : lyricCfg.fontSizeSp,
    colorHex: patch.colorHex != null ? patch.colorHex : lyricCfg.colorHex
  };
  post('/api/lyric', body).then(function (d) {
    if (d.ok) { lyricCfg = body; renderLyric(); }
  }).catch(function () { toast('保存失败'); });
}
function stepLyricSize(delta) {
  saveLyric({ fontSizeSp: Math.min(40, Math.max(10, lyricCfg.fontSizeSp + delta)) });
}
function setLyricColor(v) { saveLyric({ colorHex: String(v).replace('#', '').toUpperCase() }); }
function loadLyric() {
  api('/api/lyric').then(function (d) {
    if (d.ok) {
      lyricCfg = { fontSizeSp: d.fontSizeSp, colorHex: d.colorHex };
      renderLyric();
    }
  }).catch(function () {});
}

/* ---------------- 歌词/封面补全（lrc.cx） ---------------- */
var metaCfg = { enabled: true };
function renderMeta() {
  el('metaToggle').textContent = metaCfg.enabled ? '开' : '关';
  el('metaToggle').className = 'small' + (metaCfg.enabled ? '' : ' ghost');
}
function toggleMeta() {
  var body = { enabled: !metaCfg.enabled };
  post('/api/meta', body).then(function (d) {
    if (d.ok) { metaCfg = { enabled: d.enabled }; renderMeta(); }
  }).catch(function () { toast('保存失败'); });
}
function loadMeta() {
  api('/api/meta').then(function (d) {
    if (d.ok) {
      metaCfg = { enabled: d.enabled != null ? d.enabled : true };
      renderMeta();
    }
  }).catch(function () {});
}

/* ---------------- 待机显示（无操作自动进入播放器页） ---------------- */
var idleCfg = { enabled: true, minutes: 1 };
function renderIdle() {
  el('idleToggle').textContent = idleCfg.enabled ? '开' : '关';
  el('idleToggle').className = 'small' + (idleCfg.enabled ? '' : ' ghost');
  if (document.activeElement !== el('idleMinutes')) el('idleMinutes').value = idleCfg.minutes || 1;
}
function toggleIdle() {
  post('/api/idle', { enabled: !idleCfg.enabled }).then(function (d) {
    if (d.ok) { idleCfg = { enabled: d.enabled, minutes: d.minutes }; renderIdle(); toast(d.enabled ? '已开启待机显示' : '已关闭待机显示'); }
  }).catch(function () { toast('保存失败'); });
}
function saveIdleMinutes() {
  var m = parseInt(el('idleMinutes').value || '1', 10) || 1;
  if (m < 1) m = 1; if (m > 60) m = 60;
  post('/api/idle', { minutes: m }).then(function (d) {
    if (d.ok) { idleCfg = { enabled: d.enabled, minutes: d.minutes }; renderIdle(); toast('已保存：无操作 ' + d.minutes + ' 分钟进入播放器'); }
  }).catch(function () { toast('保存失败'); });
}
function loadIdle() {
  api('/api/idle').then(function (d) {
    if (d.ok) { idleCfg = { enabled: d.enabled != null ? d.enabled : true, minutes: d.minutes || 1 }; renderIdle(); }
  }).catch(function () {});
}

/* ---------------- 配置导出/导入 ---------------- */
function exportConfig() {
  api('/api/export').then(function (d) {
    if (!d.ok) { toast('导出失败'); return; }
    var blob = new Blob([JSON.stringify(d, null, 2)], { type: 'application/json' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'musicfree-tv-config.json';
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { document.body.removeChild(a); URL.revokeObjectURL(a.href); }, 200);
    toast('已导出配置');
  }).catch(function () { toast('导出失败'); });
}
function importConfig(input) {
  var f = input.files && input.files[0];
  if (!f) return;
  var reader = new FileReader();
  reader.onload = function () {
    var data;
    try { data = JSON.parse(reader.result); } catch (e) { toast('文件不是合法 JSON'); input.value = ''; return; }
    post('/api/import', data).then(function (d) {
      if (d.ok) {
        var im = d.imported || {};
        toast('导入成功：' + (im.theme ? '主题✓' : '') + (im.lyric ? ' 歌词✓' : '') + ' 专辑 ' + (im.lists || 0) + ' 个');
        loadThemes();
        loadLyric();
        curFavId = null;
        loadFavLists();
      } else {
        toast('导入失败');
      }
    }).catch(function () { toast('导入失败'); });
    input.value = '';
  };
  reader.readAsText(f, 'utf-8');
}

/* ---------------- 状态与轮询 ---------------- */
var statusFetching = false;
function loadStatus() {
  if (statusFetching) return;
  statusFetching = true;
  api('/api/status').then(function (d) {
    statusFetching = false;
    if (d.status === 'ok') el('statusLine').textContent = '电视 ' + d.host + ':' + d.port + ' · v' + d.version + ' · 在线';
  }).catch(function () { statusFetching = false; el('statusLine').textContent = '无法连接电视端'; });
}

loadStatus();
loadThemes();
loadLyric();
loadPlayer();
loadFavLists();
loadSubs();
loadSearchCfg();

/* 页面切到后台时暂停连接：手机浏览器后台节流定时器不可靠，
   继续收发会在网络差时堆积请求把页面拖死；回到前台立即刷新一次并恢复。 */

/* 状态栏轮询（/api/status）保持原样 */
var statusTimer = null;
function startStatusPoll() {
  if (statusTimer) return;
  statusTimer = setInterval(loadStatus, 15000);
}
function stopStatusPoll() {
  if (!statusTimer) return;
  clearInterval(statusTimer);
  statusTimer = null;
}

/* 播放器状态：优先 SSE（/api/events）推送，失败自动降级回 2s 轮询 */
var evtSource = null;      /* 当前 EventSource，null 表示未在 SSE 模式 */
var playerPollTimer = null;/* 降级轮询定时器 */
var sseRetryTimer = null;  /* 30 秒后重试 SSE 的定时器 */
function startPlayerPoll() {
  if (playerPollTimer) return;
  playerPollTimer = setInterval(loadPlayer, 2000);
}
function stopPlayerPoll() {
  if (!playerPollTimer) return;
  clearInterval(playerPollTimer);
  playerPollTimer = null;
}
function openPlayerEvents() {
  if (evtSource || sseRetryTimer) return;
  if (!window.EventSource) { startPlayerPoll(); return; } /* 老内核浏览器直接走轮询 */
  var es;
  try {
    es = new EventSource('/api/events');
  } catch (e) { startPlayerPoll(); return; }
  evtSource = es;
  es.onmessage = function (ev) {
    /* 收到推送即视为 SSE 正常：停掉降级轮询，走与轮询相同的渲染路径 */
    stopPlayerPoll();
    var d;
    try { d = JSON.parse(ev.data); } catch (e) { return; }
    handlePlayerData(d);
  };
  es.onerror = function () {
    /* SSE 断开：降级回 2s 轮询，30 秒后重试 SSE，成功（收到推送）后自动停轮询 */
    es.close();
    if (evtSource === es) evtSource = null;
    startPlayerPoll();
    if (!sseRetryTimer) {
      sseRetryTimer = setTimeout(function () {
        sseRetryTimer = null;
        openPlayerEvents();
      }, 30000);
    }
  };
}
function closePlayerEvents() {
  if (sseRetryTimer) { clearTimeout(sseRetryTimer); sseRetryTimer = null; }
  if (evtSource) { evtSource.close(); evtSource = null; }
  stopPlayerPoll();
}

startStatusPoll();
openPlayerEvents();
document.addEventListener('visibilitychange', function () {
  if (document.hidden) {
    closePlayerEvents();
    stopStatusPoll();
  } else {
    loadStatus();
    loadPlayer();
    startStatusPoll();
    openPlayerEvents();
  }
});
</script>
</body>
</html>
"""
