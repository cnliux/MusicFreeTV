package com.tvmusic.core

/**
 * 最小性能埋点（M20）：计数器 + 慢事件环形缓冲。
 *
 * 设计原则：
 * - 零依赖、纯内存、线程安全（原子计数 + 同步 List）。
 * - 不上报到任何外部服务（项目无追踪定位）；通过远程管理控制台 /metrics 查看。
 * - 采集核心指标：冷启动耗时、插件调用成功/失败/超时、播放起播耗时。
 */
object Metrics {

    /** 进程启动时刻（elapsedRealtime），用于计算冷启动耗时。
     *  注意：object 在首次被访问时才类加载并初始化本字段；若某条路径在 onCreate
     *  早期就触碰 Metrics，processStartMs 会过早捕获导致 coldStartMs≈0。
     *  因此冷启动起点改由 TvMusicApp.onCreate 显式打点（markProcessStart）。 */
    @Volatile
    private var processStartMs = android.os.SystemClock.elapsedRealtime()

    /** 在 Application.onCreate 最早处显式打点冷启动起点（避免类加载时机导致 0 值）。 */
    fun markProcessStart() {
        processStartMs = android.os.SystemClock.elapsedRealtime()
    }

    @Volatile
    var coldStartMs: Long = -1L
        private set

    /** 记录 Application.onCreate → 引擎就绪的耗时。 */
    fun markColdStartDone() {
        if (coldStartMs < 0) coldStartMs = android.os.SystemClock.elapsedRealtime() - processStartMs
    }

    // ---------------- 计数器 ----------------
    private val pluginCalls = java.util.concurrent.atomic.AtomicLong()
    private val pluginFails = java.util.concurrent.atomic.AtomicLong()
    private val pluginTimeouts = java.util.concurrent.atomic.AtomicLong()
    private val playStarts = java.util.concurrent.atomic.AtomicLong()

    fun recordPluginCall(success: Boolean, timeout: Boolean) {
        pluginCalls.incrementAndGet()
        if (!success) {
            if (timeout) pluginTimeouts.incrementAndGet() else pluginFails.incrementAndGet()
        }
    }

    fun recordPlayStart() = playStarts.incrementAndGet()

    // ---------------- 慢事件环形缓冲 ----------------
    data class SlowEvent(val atMs: Long, val kind: String, val detail: String, val costMs: Long)

    private const val SLOW_MAX = 50
    private const val SLOW_THRESHOLD_MS = 2000L

    // 直接持有 LinkedList（Deque）引用：不能用 Collections.synchronizedList 包装，
    // 包装后类型退化为 List 接口，addLast/removeFirst 会被解析成 java.util.List 的
    // 不存在方法 → NoSuchMethodError（minSdk 24，无 java.util.List.addLast）。
    // 线程安全由访问处的 synchronized(lock) 保证。
    private val slowEvents = java.util.LinkedList<SlowEvent>()

    /** 记录一次慢调用（>=2s）；超出容量丢弃最旧。 */
    fun recordSlow(kind: String, detail: String, costMs: Long) {
        if (costMs < SLOW_THRESHOLD_MS) return
        synchronized(slowEvents) {
            if (slowEvents.size >= SLOW_MAX) slowEvents.removeFirst()
            slowEvents.addLast(SlowEvent(android.os.SystemClock.elapsedRealtime(), kind, detail, costMs))
        }
    }

    /** 播放起播耗时（play() 调用 → READY 首帧）。 */
    fun recordPlayLatency(costMs: Long) = recordSlow("play_latency", "", costMs).also {
        // 起播耗时全量记录（不只慢的），用独立环形缓冲
        synchronized(latencies) {
            if (latencies.size >= 30) latencies.removeFirst()
            latencies.addLast(costMs)
        }
    }

    private val latencies = java.util.LinkedList<Long>()

    // ---------------- 输出 ----------------

    /** 生成 JSON 摘要（远程管理 /metrics 端点用）。 */
    fun snapshotJson(): org.json.JSONObject {
        val avgLat = synchronized(latencies) {
            if (latencies.isEmpty()) 0L else latencies.sum() / latencies.size
        }
        val slowJson = org.json.JSONArray()
        synchronized(slowEvents) {
            slowEvents.reversed().take(20).forEach {
                slowJson.put(
                    org.json.JSONObject()
                        .put("kind", it.kind)
                        .put("detail", it.detail)
                        .put("costMs", it.costMs)
                )
            }
        }
        return org.json.JSONObject()
            .put("coldStartMs", coldStartMs)
            .put("pluginCalls", pluginCalls.get())
            .put("pluginFails", pluginFails.get())
            .put("pluginTimeouts", pluginTimeouts.get())
            .put("playStarts", playStarts.get())
            .put("avgPlayLatencyMs", avgLat)
            .put("uptimeMs", android.os.SystemClock.elapsedRealtime() - processStartMs)
            .put("slowEvents", slowJson)
    }
}
