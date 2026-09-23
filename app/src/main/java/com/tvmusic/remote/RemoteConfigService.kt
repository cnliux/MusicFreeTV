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
import kotlinx.coroutines.Dispatchers
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
        private const val MAX_TOTAL = 60
        private const val MAX_PLUGINS_PER_SEARCH = 5

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
                try {
                    handle(sock)
                } catch (e: Exception) {
                    Log.w(TAG, "handle: ${e.message}")
                } finally {
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
        val path = parts[1]

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
                repo.syncAll()
                respond(socket, 200, JSONObject().put("ok", true).put("message", "已添加订阅并同步").toString())
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
                app().repository.toggleEnabled(name, json?.optBoolean("enabled", true) ?: true)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/plugins/uninstall" -> {
                val body = readBody(input, headers)
                val name = runCatching { JSONObject(body).optString("name", "") }.getOrDefault("")
                app().repository.uninstall(name)
                respond(socket, 200, JSONObject().put("ok", true).toString())
            }
            method == "POST" && path == "/api/sync" -> {
                app().repository.syncAll()
                respond(socket, 200, JSONObject().put("ok", true).put("message", "开始同步订阅").toString())
            }
            method == "GET" && path.startsWith("/api/search") -> {
                val q = path.substringAfter("q=", "").substringBefore("&").trim()
                val page = path.substringAfter("page=", "1").substringBefore("&").toIntOrNull()?.coerceAtLeast(1) ?: 1
                if (q.isEmpty()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "missing q").toString())
                    return
                }
                searchAndRespond(socket, q, page)
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
                        com.tvmusic.player.QueueEntry(plugin, raw)
                    }
                }
                if (entries.isEmpty()) {
                    respond(socket, 400, JSONObject().put("ok", false).put("error", "no valid items").toString())
                    return
                }
                com.tvmusic.player.PlayerManager.play(plugin, entries.first(), entries, 0)
                respond(socket, 200, JSONObject().put("ok", true).put("count", entries.size).put("message", "已加入播放列表并开始播放").toString())
            }
            method == "GET" && path == "/api/player" -> {
                respond(socket, 200, playerStatusJson().toString())
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
                val id = path.substringAfter("id=", "").substringBefore("&")
                val page = path.substringAfter("page=", "1").substringBefore("&").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = path.substringAfter("size=", "20").substringBefore("&").toIntOrNull()?.coerceIn(1, 200) ?: 20
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
        val buf = ByteArray(len.coerceAtMost(4 * 1024 * 1024))
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
            arr.put(JSONObject().put("id", t.id).put("name", t.name).put("accent", t.accent).put("bg", t.bg))
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
        app().store.loadPlugins().map { it.takeIf { p -> p.info != null } }
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
     * 远程搜索：对所有启用插件并发搜索（仿 lx-music 的做法：单个失败降级为空，合并结果去重），
     * 发起后在后台执行并返回前 MAX_TOTAL 条。
     */
    private fun searchAndRespond(socket: Socket, q: String, page: Int) {
        val keyword = try {
            java.net.URLDecoder.decode(q, Charsets.UTF_8.name())
        } catch (e: Exception) {
            respond(socket, 400, JSONObject().put("ok", false).put("error", "无效的关键词").toString())
            return
        }
        runBlocking {
            val app = app()
            // JS 引擎单线程执行插件方法（内部网络为同步调用），并发无法并行；
            // 串行搜索前 3 个可用插件，每个硬超时 10s。
            val enabled = app.repository.listEnabled()
                .filter { it.info != null && it.loadError == null }
                .take(MAX_PLUGINS_PER_SEARCH)
            val results = JSONArray()
            var seen = HashSet<String>()
            var total = 0
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                for (plugin in enabled) {
                    if (total >= MAX_TOTAL) break
                    val platform = plugin.info!!.platform
                    val fut = executor.submit(java.util.concurrent.Callable<JSONArray> {
                        val res = try {
                            runBlocking {
                                app.runtime.callAsync(platform, "search", listOf(keyword, page.toString(), "music"))
                            }
                        } catch (e: Exception) {
                            null
                        }
                        (res as? JSONObject)?.optJSONArray("data")
                            ?: (res as? JSONArray)
                            ?: JSONArray()
                    })
                    val arr = try {
                        fut.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        fut.cancel(true)
                        continue
                    }
                    for (i in 0 until arr.length()) {
                        if (total >= MAX_TOTAL) break
                        val item = arr.optJSONObject(i) ?: continue
                        val type = item.optString("type")
                        if (type.isNotBlank() && type != "music") continue
                        val title = item.optString("title").trim()
                        if (title.isBlank()) continue
                        val key = title + "\u0000" + item.optString("artist")
                        if (!seen.add(key)) continue
                        results.put(
                            JSONObject()
                                .put("plugin", platform)
                                .put("title", title)
                                .put("artist", item.optString("artist"))
                                .put("album", item.optString("album"))
                                .put("artwork", item.optString("artwork", "").ifEmpty { item.optString("coverImg") })
                                .put("duration", item.optLong("duration", 0L))
                                .put("raw", item)
                        )
                        total++
                    }
                }
            } finally {
                executor.shutdownNow()
            }
            respond(socket, 200, JSONObject().put("ok", true).put("results", results).put("total", total).toString())
        }
    }

    private fun infoJson(): JSONObject {
        return JSONObject()
            .put("app", getString(com.tvmusic.R.string.app_name))
            .put("version", BuildConfig.VERSION_NAME)
            .put("host", hostDisplay)
            .put("port", port)
            .put("status", "ok")
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
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
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(com.tvmusic.R.string.app_name))
            .setContentText("远程配置服务运行中 · 端口 $port")
            .setSmallIcon(com.tvmusic.R.drawable.ic_stat_remote)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        instance = null
        try { serverSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

private val PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MusicFree TV 远程管理</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #0d0f14; --card: #171a21; --card2: #1e222b; --line: #262b36;
    --text: #eef1f6; --muted: #8b93a5; --accent: #5b8cff; --accent2: #7aa5ff;
    --danger: #e06c6c; --ok: #58c97b;
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
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 14px; margin-bottom: 14px; }
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
  .ctrls { display: flex; align-items: center; justify-content: center; gap: 26px; margin: 14px 0 4px; }
  .ctrl {
    width: 52px; height: 52px; border-radius: 50%; background: var(--card2); border: 1px solid var(--line);
    color: var(--text); font-size: 20px; display: flex; align-items: center; justify-content: center; padding: 0;
  }
  .ctrl.main { width: 68px; height: 68px; background: var(--accent); border: none; font-size: 26px; color: #fff; }
  .ctrls .side { display: flex; flex-direction: column; align-items: center; gap: 2px; color: var(--muted); font-size: 10px; }
  .ctrls .side button { width: 44px; height: 44px; font-size: 16px; }
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
  #toast {
    position: fixed; left: 50%; bottom: calc(80px + env(safe-area-inset-bottom)); transform: translateX(-50%);
    background: #262b36; color: var(--text); border: 1px solid var(--line);
    padding: 9px 18px; border-radius: 22px; font-size: 13px; opacity: 0; transition: opacity .25s;
    pointer-events: none; z-index: 30; max-width: 86vw;
  }
  #toast.show { opacity: 1; }
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
      <div class="nowart"><img id="pArt" src="" alt="" onerror="this.src=''"></div>
      <div class="nowtitle ellip" id="pTitle">未在播放</div>
      <div class="nowartist ellip" id="pArtist"></div>
      <div class="seekwrap">
        <input type="range" id="seekBar" min="0" max="1000" value="0" step="1">
        <div class="times"><span id="pPos">0:00</span><span id="pDur">0:00</span></div>
      </div>
      <div class="ctrls">
        <div class="side"><button class="ctrl" id="pMode" onclick="cycleMode()">↔</button><span id="pModeName">顺序</span></div>
        <button class="ctrl" onclick="playerCmd('prev')">⏮</button>
        <button class="ctrl main" id="pToggle" onclick="playerCmd('playpause')">▶</button>
        <button class="ctrl" onclick="playerCmd('next')">⏭</button>
        <div class="side"><button class="ctrl" onclick="showVol()">🔊</button><span>音量</span></div>
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
      <div class="row" style="border:none;padding:10px 0 0;">
        <button class="ghost small" id="playAllBtn" onclick="playAllSearch()" style="display:none;">▶ 播放全部结果</button>
        <span class="muted" id="searchInfo"></span>
      </div>
    </div>
    <div class="card" id="searchCard" style="display:none;">
      <h2>搜索结果</h2>
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

  <!-- 管理 -->
  <section class="page" id="page-manage">
    <div class="card">
      <h2>界面主题</h2>
      <div class="chips" id="themeBar"></div>
      <div class="muted">选择后立即应用到电视端与本页。</div>
    </div>
    <div class="card">
      <h2>订阅源</h2>
      <div id="subList"></div>
      <div class="row" style="border:none;padding:10px 0 0;">
        <input type="text" id="subUrl" placeholder="plugins.json 或 .js 直链">
        <button class="small" onclick="addSub()">添加</button>
      </div>
      <div class="row" style="border:none;padding:8px 0 0;">
        <button class="ghost small" onclick="syncAll()">⟳ 立即同步全部订阅</button>
      </div>
    </div>
    <div class="card">
      <h2>插件 <span id="pluginCount" class="muted"></span></h2>
      <div id="pluginList"></div>
    </div>
  </section>
</main>

<div id="toast"></div>

<nav>
  <button class="on" data-tab="player" onclick="switchTab('player')"><span class="ic">🎵</span>播放</button>
  <button data-tab="search" onclick="switchTab('search')"><span class="ic">🔍</span>搜索</button>
  <button data-tab="fav" onclick="switchTab('fav')"><span class="ic">❤️</span>收藏</button>
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
  if (name === 'manage') { loadSubs(); loadPlugins(); }
}

/* ---------------- 播放器 ---------------- */
var MODES = ['ORDER', 'LOOP_ONE', 'SHUFFLE'];
var MODE_NAMES = { ORDER: '⇅ 顺序', LOOP_ONE: '🔂 单曲', SHUFFLE: '🔀 随机' };
var MODE_IC = { ORDER: '⇅', LOOP_ONE: '🔂', SHUFFLE: '🔀' };
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

function loadPlayer() {
  api('/api/player').then(function (d) {
    lastStatus = d;
    if (!d.title) {
      el('pTitle').textContent = '未在播放';
      el('pArtist').textContent = '';
      el('pErr').textContent = '';
      el('pToggle').textContent = '▶';
      renderQueue(d, -1);
      return;
    }
    el('pTitle').textContent = d.title;
    el('pArtist').textContent = (d.artist || '') + (d.album ? ' · ' + d.album : '') + ' · ' + (d.index + 1) + '/' + d.queueSize + (d.buffering ? ' · 缓冲中' : '');
    var art = el('pArt');
    var want = d.artwork || '';
    if (art.getAttribute('src') !== want) art.src = want;
    art.style.visibility = want ? 'visible' : 'hidden';
    el('pToggle').textContent = d.playing ? '⏸' : '▶';
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
  }).catch(function () {});
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
function volume(delta) {
  post('/api/player/volume', { delta: delta }).then(function () { loadPlayer(); });
}

function renderQueue(d, currentIdx) {
  var q = d.queue || [];
  el('qCount').textContent = q.length ? '· ' + q.length + ' 首' : '';
  var box = el('queueBox');
  if (!q.length) { box.innerHTML = '<div class="empty">队列为空，去搜索推歌吧</div>'; return; }
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

/* ---------------- 搜索 ---------------- */
var searchResults = [], sPage = 1, sSize = 20;
function doSearch() {
  var q = el('searchQ').value.trim();
  if (!q) return;
  toast('搜索中…');
  api('/api/search?q=' + encodeURIComponent(q)).then(function (d) {
    searchResults = d.results || [];
    sPage = 1;
    el('searchInfo').textContent = '共 ' + (d.total || 0) + ' 条';
    el('playAllBtn').style.display = searchResults.length ? '' : 'none';
    el('searchCard').style.display = '';
    renderSearch();
  }).catch(function () { toast('搜索失败'); });
}
function playAllSearch() {
  if (!searchResults.length) return;
  var byPlugin = {};
  searchResults.forEach(function (it) { (byPlugin[it.plugin] = byPlugin[it.plugin] || []).push(it.raw); });
  var best = null, bestN = 0;
  Object.keys(byPlugin).forEach(function (p) { if (byPlugin[p].length > bestN) { best = p; bestN = byPlugin[p].length; } });
  post('/api/play/queue', { plugin: best, items: byPlugin[best] }).then(function (d) {
    toast(d.message || '已播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  }).catch(function () { toast('播放失败'); });
}
function renderSearch() {
  var box = el('searchBox');
  if (!searchResults.length) { box.innerHTML = '<div class="empty">没有结果</div>'; el('searchPager').innerHTML = ''; return; }
  var pages = Math.max(1, Math.ceil(searchResults.length / sSize));
  if (sPage > pages) sPage = pages;
  var html = '';
  searchResults.slice((sPage - 1) * sSize, sPage * sSize).forEach(function (it, k) {
    var i = (sPage - 1) * sSize + k;
    html += '<div class="row">' +
      '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
      '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.plugin) + '</div></div>' +
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
    items.forEach(function (it, k) {
      var i = (favPage - 1) * favSize + k;
      window._favItems = d.items;
      html += '<div class="row">' +
        '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
        '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.platform || '') + '</div></div>' +
        '<button class="small" onclick="playFavItem(' + i + ')">播放</button>' +
        '<button class="danger small" onclick="removeFavItem(' + i + ')">移出</button></div>';
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
    loadSubs(); loadPlugins();
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
    setTimeout(function () { loadPlugins(); }, 4000);
  }).catch(function () { toast('同步失败'); });
}
function loadPlugins() {
  api('/api/plugins').then(function (d) {
    var list = d.plugins || [];
    el('pluginCount').textContent = '· ' + list.length + ' 个';
    var box = el('pluginList');
    if (!list.length) { box.innerHTML = '<div class="empty">还没有插件，先添加订阅源</div>'; return; }
    var html = '';
    list.forEach(function (p, i) {
      html += '<div class="row">' +
        '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(p.platform) + '</div>' +
        '<div class="muted">' + (p.loadError ? '<span class="badge off">载入失败</span>' : 'v' + esc(p.version) + ' <span class="badge ' + (p.enabled ? 'on' : 'off') + '">' + (p.enabled ? '已启用' : '已停用') + '</span>') + '</div></div>' +
        '<button class="ghost small" onclick="togglePlugin(' + i + ')">' + (p.enabled ? '停用' : '启用') + '</button> ' +
        '<button class="danger small" onclick="uninstallPlugin(' + i + ')">卸载</button></div>';
    });
    box.innerHTML = html;
    window._plugins = list;
  }).catch(function () {});
}
function togglePlugin(i) {
  var p = (window._plugins || [])[i];
  if (!p) return;
  post('/api/plugins/toggle', { name: p.name, enabled: !p.enabled }).then(loadPlugins);
}
function uninstallPlugin(i) {
  var p = (window._plugins || [])[i];
  if (!p) return;
  if (!confirm('卸载插件「' + p.platform + '」？')) return;
  post('/api/plugins/uninstall', { name: p.name }).then(loadPlugins);
}

/* ---------------- 主题 ---------------- */
var themes = [], curTheme = null;
function applyTheme(id) {
  var t = themes.filter(function (x) { return x.id === id; })[0];
  if (!t) return;
  var r = document.documentElement.style;
  r.setProperty('--accent', '#' + t.accent);
  r.setProperty('--bg', '#' + t.bg);
  // 强调色的半透明派生（用于 range 轨道等）
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

/* ---------------- 状态与轮询 ---------------- */
function loadStatus() {
  api('/api/status').then(function (d) {
    if (d.status === 'ok') el('statusLine').textContent = '电视 ' + d.host + ':' + d.port + ' · v' + d.version + ' · 在线';
  }).catch(function () { el('statusLine').textContent = '无法连接电视端'; });
}

loadStatus();
loadThemes();
loadPlayer();
loadFavLists();
loadSubs();
loadPlugins();
setInterval(loadStatus, 15000);
setInterval(loadPlayer, 2000);
</script>
</body>
</html>
"""
