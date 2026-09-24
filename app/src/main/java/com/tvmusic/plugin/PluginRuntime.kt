package com.tvmusic.plugin

import android.content.Context
import android.os.SystemClock
import com.tvmusic.runtime.JsEngine
import com.tvmusic.runtime.PluginCallException
import com.tvmusic.runtime.QuickJsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 单平台调用健康度快照（内存态统计，不持久化，重启即清零）。
 * @param calls                  调用总数
 * @param fails                  失败数
 * @param lastError              最近一次错误信息（截断至约 200 字）
 * @param lastLatencyMs          最近一次耗时（含排队与执行）
 * @param lastSuccessAtElapsed   最近成功时间（SystemClock.elapsedRealtime；null 表示从未成功）
 */
data class PlatformHealth(
    val platform: String,
    val calls: Int,
    val fails: Int,
    val lastError: String?,
    val lastLatencyMs: Long,
    val lastSuccessAtElapsed: Long?
)

/**
 * 插件运行时外观。
 * 所有方法默认在 IO 线程执行，UI 层无需手动切线程。
 *
 * 并行搜索：单 QuickJS 引擎是单线程（插件内部 HTTP 为同步阻塞桥调用，且全局
 * invokeLock 串行），一个音源一个音源地搜非常慢。这里为搜索维护一个小型
 * 引擎池——每台引擎有独立 JS 线程与独立 QuickJS runtime；
 * [callParallel] 按“最空闲引擎”把调用分发到不同引擎上，实现真正的并行 I/O。
 *
 * 引擎池初始 [Companion.SEARCH_ENGINES] 台（warmup 时全量注册）；当所有引擎
 * 都有在途调用且未达上限时，按需懒扩容至最多 [Companion.MAX_LANES] 台。
 * 新扩容引擎不做全量注册，只在调用时按需注册目标平台的插件
 * （源码取自 [loadPlugin] 的缓存，无网络）。
 * 其余能力（播放/详情/导入导出）仍走主引擎，避免跨引擎共享状态问题。
 */
class PluginRuntime private constructor(
    private val primary: JsEngine,
    extras: List<JsEngine>,
    private val appContext: Context?,
    private val variablesProvider: (() -> Map<String, String>)?
) {

    /**
     * 引擎池（下标即 lane 号），支持运行期懒扩容。
     * 结构变更（追加引擎）都在 [laneLock] 内进行；读取走 COW 列表免锁。
     */
    private val lanes: CopyOnWriteArrayList<JsEngine> = CopyOnWriteArrayList<JsEngine>().apply {
        add(primary)
        addAll(extras)
    }

    /** 各引擎在途调用计数（取最小值做负载均衡；最小值 > 0 即“所有引擎都忙”）。 */
    private val laneBusy = AtomicIntegerArray(MAX_LANES)

    /**
     * 每台引擎“已注册平台集合”。初始引擎经 [loadPlugin] 全量注册时同步记录；
     * 新扩容引擎从空集合开始，调用时按需注册（幂等：已注册即跳过）。由 [laneLock] 保护。
     */
    private val laneRegistered = CopyOnWriteArrayList<MutableSet<String>>().apply {
        repeat(lanes.size) { add(mutableSetOf()) }
    }

    /** 每台引擎的注册互斥锁（防止同引擎并发调用时对同一平台重复注册）。 */
    private val laneRegisterLocks = Array(MAX_LANES) { Any() }

    private val laneLock = Any()

    /**
     * 平台 → 归属引擎（粘性路由）。插件的 JS 模块级状态（cookie/登录态/token/缓存）
     * 只在其首次运行的引擎上建立；跨引擎分发会导致状态分裂——聚合搜索里部分源
     * 无结果、而单独搜索（总是落到 primary）却有结果的根因。
     * 粘住 home 引擎后，不同平台仍分布在不同引擎上，保持真正的并行 I/O。
     */
    private val platformLane = ConcurrentHashMap<String, Int>()

    /** 平台 → 插件源码（[loadPlugin] 时缓存，供新扩容引擎按需注册，无网络）。 */
    private val platformSources = ConcurrentHashMap<String, String>()

    // ---------------- 平台健康度统计（内存态，不持久化） ----------------

    private class HealthStat {
        var calls = 0
        var fails = 0
        var lastError: String? = null
        var lastLatencyMs = 0L
        var lastSuccessAtElapsed: Long? = null
    }

    /** 平台 → 统计条目；条目字段由 synchronized(stat) 保护。 */
    private val healthStats = ConcurrentHashMap<String, HealthStat>()

    /** 记录一次平台调用结果（callAsync / callParallel 在拿到结果或异常处调用）。 */
    private fun recordHealth(platform: String, startedAt: Long, error: Throwable?) {
        val stat = healthStats.getOrPut(platform) { HealthStat() }
        val now = SystemClock.elapsedRealtime()
        synchronized(stat) {
            stat.calls++
            stat.lastLatencyMs = now - startedAt
            if (error != null) {
                stat.fails++
                stat.lastError = healthErrorText(error)
            } else {
                stat.lastSuccessAtElapsed = now
            }
        }
    }

    /**
     * 提炼可读的最近错误：去掉包装类名前缀与 JS 堆栈帧噪音。
     * 原始文本形如 "com.tvmusic.runtime.PluginCallException:  at getMediaSource (<input>:605)"，
     * 提炼为 "getMediaSource 失败（<input>:605）"；多行错误取首个非空行（通常含真实原因）。
     */
    private fun healthErrorText(error: Throwable): String {
        val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
        val raw = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        var s = raw.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: raw
        s = s.replace(Regex("^com\\.tvmusic\\.runtime\\.PluginCallException:\\s*"), "")
        val frame = Regex("^at\\s+(.+?)\\s*\\((\\S+)\\)$").find(s)
        if (frame != null) {
            s = "${frame.groupValues[1]} 失败（${frame.groupValues[2]}）"
        }
        return s.take(200)
    }

    /** 健康度快照（线程安全拷贝，按平台名排序）。 */
    fun healthSnapshot(): List<PlatformHealth> = healthStats.map { (p, s) ->
        synchronized(s) {
            PlatformHealth(p, s.calls, s.fails, s.lastError, s.lastLatencyMs, s.lastSuccessAtElapsed)
        }
    }.sortedBy { it.platform }

    /** 归还引擎槽位（在途计数减一）。 */
    private fun releaseLane(lane: Int) {
        laneBusy.decrementAndGet(lane)
    }

    /**
     * 取平台执行引擎并做在途计数（锁内完成）：
     * 已有 home 引擎则粘性返回；首次分配按“最空闲引擎”选取，
     * 若所有现有引擎都忙且未达上限，则懒创建新引擎接管该平台。
     */
    private fun acquireLane(platform: String): Int = synchronized(laneLock) {
        var lane = platformLane[platform]
        if (lane == null) {
            var best = 0
            for (i in 1 until lanes.size) if (laneBusy.get(i) < laneBusy.get(best)) best = i
            lane = best
            // 懒扩容：所有现有引擎都有在途调用且未达上限，才创建新引擎
            if (laneBusy.get(best) > 0 && lanes.size < MAX_LANES) {
                val created = createLaneLocked()
                if (created >= 0) lane = created
            }
            platformLane[platform] = lane
        }
        laneBusy.incrementAndGet(lane)
        lane
    }

    /**
     * 锁内：懒创建并追加一台新引擎。不做全量注册（省创建成本），
     * 目标平台的插件由调用时 [ensureRegistered] 在引擎上按需注册。
     * 创建方式遵循现有 warmup：QuickJsEngine 在调用线程上构造并 initialize()，
     * JS 执行集中在其自身 HandlerThread。
     * @return 新引擎 lane 号；创建失败返回 -1（回退复用现有引擎）
     */
    private fun createLaneLocked(): Int {
        val ctx = appContext ?: return -1
        val provider = variablesProvider ?: return -1
        val engine = runCatching {
            QuickJsEngine(ctx, provider).also { it.initialize() }
        }.getOrNull() ?: return -1
        lanes.add(engine)
        laneRegistered.add(mutableSetOf())
        return lanes.size - 1
    }

    /**
     * 确保目标平台已在指定引擎上注册（新扩容引擎的按需注册路径）。
     * 已注册则跳过（幂等）；源码取自 [loadPlugin] 缓存，无网络。
     * 注册成功后记入该引擎的已注册集合；失败不记录，允许下次调用重试。
     */
    private fun ensureRegistered(lane: Int, platform: String) {
        if (lane >= laneRegistered.size) return
        if (synchronized(laneLock) { platform in laneRegistered[lane] }) return
        val source = platformSources[platform] ?: return
        val engine = lanes.getOrNull(lane) ?: return
        synchronized(laneRegisterLocks[lane]) {
            // 双重检查：同引擎同平台的并发调用只注册一次
            if (synchronized(laneLock) { platform in laneRegistered[lane] }) return
            val ok = runCatching { engine.registerPlugin(platform, source) }.getOrDefault(false)
            if (ok) synchronized(laneLock) { laneRegistered[lane].add(platform) }
        }
    }

    companion object {
        /** 并行搜索的初始引擎总数。Amlogic p230 上 3 台足够，过大会推高 CPU/内存。 */
        private const val SEARCH_ENGINES = 3

        /** 引擎池上限：所有现有引擎都忙时按需懒扩容，最多 5 台。 */
        private const val MAX_LANES = 5

        @Volatile private var INSTANCE: PluginRuntime? = null

        fun create(context: Context, store: com.tvmusic.data.PluginStore): PluginRuntime {
            val existing = INSTANCE
            if (existing != null) return existing
            val provider = { store.allVariablesMerged() }
            val primary = QuickJsEngine(context.applicationContext, provider)
            primary.initialize()
            val extras = ArrayList<JsEngine>()
            while (extras.size < SEARCH_ENGINES - 1) {
                val e = runCatching {
                    QuickJsEngine(context.applicationContext, provider)
                        .also { it.initialize() }
                }.getOrNull()
                if (e == null) break
                extras.add(e)
            }
            val rt = PluginRuntime(primary, extras, context.applicationContext, provider)
            INSTANCE = rt
            return rt
        }

        fun get(): PluginRuntime = INSTANCE ?: error("PluginRuntime not initialized")
    }

    /**
     * 插件注册需要同步到所有引擎，否则并行搜索分发到的引擎上找不到该插件。
     * 同时缓存“平台 → 源码”，供新扩容引擎按需注册（无需回查 DB/网络）。
     */
    suspend fun loadPlugin(platform: String, source: String): Boolean = withContext(Dispatchers.IO) {
        platformSources[platform] = source
        val targets = lanes.toList()
        val ok = targets.all { it.registerPlugin(platform, source) }
        if (ok) {
            synchronized(laneLock) {
                // 只标记注册时的目标引擎；期间新懒创建的引擎保持未注册，走按需注册
                for (i in targets.indices) laneRegistered.getOrNull(i)?.add(platform)
            }
            // 插件源码可能更新了方法集，失效该平台的 hasMethod 缓存
            methodCache.keys.removeAll { it.startsWith("$platform::") }
        }
        ok
    }

    suspend fun hasPlugin(platform: String): Boolean = withContext(Dispatchers.IO) {
        primary.hasPlugin(platform)
    }

    /**
     * hasMethod 结果缓存：首页/推荐/排行 VM 在每次插件列表刷新时都会对每个启用插件
     * 同步调用多次，旧实现每次都在 primary 引擎串行锁上执行一次 JS 往返。
     * 方法集只在 loadPlugin 时变化，故可安全缓存（注册时按 platform 失效）。
     */
    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    suspend fun hasMethod(platform: String, method: String): Boolean {
        val key = "$platform::$method"
        methodCache[key]?.let { return it }
        return withContext(Dispatchers.IO) { primary.hasMethod(platform, method) }
            .also { methodCache[key] = it }
    }

    suspend fun readInfo(platform: String): JSONObject? = withContext(Dispatchers.IO) {
        val raw = primary.readPluginInfo(platform) ?: return@withContext null
        try { JSONObject(raw) } catch (_: Exception) { null }
    }

    suspend fun callAsync(
        platform: String,
        method: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = primary.timeoutMillis
    ): Any {
        val startedAt = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.IO) {
            try {
                val argsJson = JSONArray(args).toString()
                val result = primary.invoke(platform, method, argsJson, timeoutMs)
                val parsed = parseJsonResult(result)
                recordHealth(platform, startedAt, null)
                parsed
            } catch (t: Throwable) {
                recordHealth(platform, startedAt, t)
                throw t
            }
        }
    }

    suspend fun callAsync(platform: String, method: String, vararg args: Any): Any {
        val startedAt = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                args.forEach { a ->
                    when (a) {
                        is String -> arr.put(a)
                        is JSONObject -> arr.put(a)
                        is JSONArray -> arr.put(a)
                        is Number -> arr.put(a)
                        is Boolean -> arr.put(a)
                        is Nothing -> arr.put(JSONObject.NULL)
                        else -> arr.put(a.toString())
                    }
                }
                val result = primary.invoke(platform, method, arr.toString())
                val parsed = parseJsonResult(result)
                recordHealth(platform, startedAt, null)
                parsed
            } catch (t: Throwable) {
                recordHealth(platform, startedAt, t)
                throw t
            }
        }
    }

    /**
     * 并行搜索调用：分发到平台归属引擎（粘性）执行，多个音源可真正同时搜。
     * 首次出现的平台按“最空闲引擎”分配并记住归属；所有现有引擎都忙且未达
     * 上限时懒扩容新引擎。同一引擎上的多个平台由引擎自身的 invokeLock 串行
     * （排序正确，只是不并行）。
     * 新扩容引擎不预注册插件：执行前经 [ensureRegistered] 在引擎上按需注册目标平台。
     */
    suspend fun callParallel(
        platform: String,
        method: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = primary.timeoutMillis
    ): Any {
        // 计时从取 lane 开始：耗时含排队（lane 分配 + 引擎串行队列）与执行
        val startedAt = SystemClock.elapsedRealtime()
        val lane = acquireLane(platform)
        try {
            return withContext(Dispatchers.IO) {
                try {
                    ensureRegistered(lane, platform)
                    val argsJson = JSONArray(args).toString()
                    val result = lanes[lane].invoke(platform, method, argsJson, timeoutMs)
                    val parsed = parseJsonResult(result)
                    recordHealth(platform, startedAt, null)
                    parsed
                } catch (t: Throwable) {
                    recordHealth(platform, startedAt, t)
                    throw t
                }
            }
        } finally {
            releaseLane(lane)
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        lanes.forEach { runCatching { (it as? AutoCloseable)?.close() } }
    }

    private fun parseJsonResult(raw: String): Any {
        if (raw.isBlank() || raw == "null") return JSONObject()
        try {
            val obj = JSONObject(raw)
            if (obj.optBoolean("__notImplemented")) {
                return NotImplementedError()
            }
            return obj
        } catch (_: Exception) {
            // 不少插件方法直接返回数组（如 getTopLists / importMusicSheet / search 旧协议）
            try {
                return JSONArray(raw)
            } catch (_: Exception) {
                return raw
            }
        }
    }
}
