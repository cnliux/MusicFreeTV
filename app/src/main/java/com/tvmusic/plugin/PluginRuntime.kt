package com.tvmusic.plugin

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tvmusic.config.MetaSettings
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
 * 并行路径（搜索/排行/推荐）不占用 lane 0；播放/详情/导入导出仍走主引擎。
 */
class PluginRuntime private constructor(
    primary: JsEngine,
    extras: List<JsEngine>,
    private val appContext: Context?,
    private val variablesProvider: ((platform: String) -> Map<String, String>)?,
    /** 引擎池上限：内存充裕的设备放宽（见 [Companion.create]），小内存 TV 盒保守。 */
    private val maxLanes: Int = MAX_LANES
) {

    /**
     * 主引擎（就是 [lanes] 的 0 号）。P0-7：引擎可能被判定 poisoned（插件死循环占死
     * JS 线程），此时用新引擎整台替换，引用必须可变、对所有线程可见。
     */
    @Volatile
    private var primary: JsEngine = primary

    /**
     * 引擎池（下标即 lane 号），支持运行期懒扩容与 poisoned 引擎重建。
     * 结构变更（追加引擎、整台替换）都在 [laneLock] 内进行；读取走 COW 列表免锁。
     */
    private val lanes: CopyOnWriteArrayList<JsEngine> = CopyOnWriteArrayList<JsEngine>().apply {
        add(primary)
        addAll(extras)
    }

    /** 各引擎在途调用计数（取最小值做负载均衡；最小值 > 0 即“所有引擎都忙”）。 */
    private val laneBusy = AtomicIntegerArray(maxLanes)

    /**
     * 每台引擎“已注册平台集合”。初始引擎经 [loadPlugin] 全量注册时同步记录；
     * 新扩容引擎从空集合开始，调用时按需注册（幂等：已注册即跳过）。由 [laneLock] 保护。
     */
    private val laneRegistered = CopyOnWriteArrayList<MutableSet<String>>().apply {
        repeat(lanes.size) { add(mutableSetOf()) }
    }

    /** 每台引擎的注册互斥锁（防止同引擎并发调用时对同一平台重复注册）。 */
    private val laneRegisterLocks = Array(maxLanes) { Any() }

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
        var timeouts = 0
        var consecutiveFails = 0
        var lastFailAt: Long = 0L
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
        val cost = now - startedAt
        val isTimeout = error is com.tvmusic.runtime.PluginCallException &&
            (error.message?.contains("timeout", ignoreCase = true) == true ||
                error.message?.contains("busy", ignoreCase = true) == true)
        synchronized(stat) {
            stat.calls++
            stat.lastLatencyMs = cost
            if (error != null) {
                stat.fails++
                if (isTimeout) stat.timeouts++
                stat.consecutiveFails++
                stat.lastFailAt = now
                stat.lastError = healthErrorText(error)
            } else {
                stat.lastSuccessAtElapsed = now
                stat.consecutiveFails = 0
                stat.lastFailAt = 0L
            }
        }
        // M20：全局埋点——插件调用成功率/超时率 + 慢调用（>=2s）环形缓冲
        com.tvmusic.core.Metrics.recordPluginCall(
            success = error == null,
            timeout = isTimeout
        )
        com.tvmusic.core.Metrics.recordSlow("plugin_call", platform, cost)
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

    /**
     * 换源竞速用的健康分（0~100，越大越先试）：失败率高、最近延迟大、从未成功 → 降分；
     * 无调用记录给中性 60（新平台值得一试）。
     */
    fun fallbackScore(platform: String, weightPct: Int = MetaSettings.DEFAULT_HEALTH_WEIGHT): Int {
        val s = healthStats[platform] ?: return 60
        synchronized(s) {
            if (s.calls == 0) return 60
            val failRatio = s.fails.toFloat() / s.calls
            val timeoutRatio = if (s.calls > 0) s.timeouts.toFloat() / s.calls else 0f
            val weight = weightPct.coerceIn(0, 300)
            val latPenalty = (s.lastLatencyMs / 1000).toInt() * weight / 100
            var score = 60 - (failRatio * 70).toInt() * weight / 100 - (timeoutRatio * 40).toInt() * weight / 100 - latPenalty
            if (s.fails > 0 && s.lastSuccessAtElapsed == null) score -= 30
            if (s.lastLatencyMs > 8000) score -= 20
            if (s.lastLatencyMs > 15000) score -= 25
            if (s.consecutiveFails >= 3) score -= 15
            if (s.consecutiveFails >= 5) score -= 20
            if (s.timeouts >= 3) score -= 10
            return score.coerceIn(0, 100)
        }
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
     * 已有 home 引擎则粘性返回；首次分配优先空闲引擎；全忙且未达上限则新建引擎接管，
     * 尽量减少"后来的音源排队等先前慢源"导致的整源饿死。
     *
     * P0-7：跳过 poisoned 引擎（JS 线程被插件死循环占死），粘性 lane 中毒时先尝试重建；
     * 全池都中毒（设备极差/中断能力缺失）才退而复用 0 号，把错误如实报给调用方。
     */
    private fun acquireLane(platform: String): Int = synchronized(laneLock) {
        var lane = platformLane[platform]
        // 并行路径不占用主引擎：搜索中毒不能拖死播放/详情。仅一台引擎时才退回 0。
        if (lane != null && lane == 0 && lanes.size > 1) {
            platformLane.remove(platform)
            lane = null
        }
        if (lane != null && lanes.getOrNull(lane)?.poisoned == true) {
            if (rebuildLaneLocked(lane)) {
                Log.w(TAG, "lane $lane rebuilt after poisoned (platform=$platform)")
            } else {
                platformLane.remove(platform)
                lane = null
            }
        }
        if (lane == null) {
            val start = if (lanes.size > 1) 1 else 0
            var idle = -1
            var best = -1
            var bestBusy = Int.MAX_VALUE
            for (i in start until lanes.size) {
                if (lanes[i].poisoned) continue
                val busy = laneBusy.get(i)
                if (busy == 0) { idle = i; break }
                if (busy < bestBusy) { bestBusy = busy; best = i }
            }
            lane = when {
                idle >= 0 -> idle
                else -> {
                    var picked = -1
                    if (lanes.size < maxLanes) {
                        picked = createLaneLocked()
                    }
                    if (picked < 0) picked = best
                    if (picked < 0 && lanes.size > 1) {
                        rebuildLaneLocked(1)
                        picked = 1
                    }
                    if (picked < 0) 0 else picked
                }
            }
            platformLane[platform] = lane
        }
        laneBusy.incrementAndGet(lane)
        lane
    }

    /** 锁内：新建一台引擎（不注册插件），失败返回 null。 */
    private fun newEngineLocked(): JsEngine? {
        val ctx = appContext ?: return null
        val provider = variablesProvider ?: return null
        return runCatching {
            QuickJsEngine(ctx, provider).also { it.initialize() }
        }.getOrNull()
    }

    /**
     * 锁内：用新引擎整台替换指定 lane，并清空其已注册记录（下次调用经 [ensureRegistered]
     * 从 [platformSources] 缓存补注册）。
     *
     * 故意不 close 被替换下来的引擎：它的 JS 线程正卡在死循环里，
     * 跨线程 close 会在 native 层释放正在使用的 runtime 对象，极易 SIGSEGV。
     * 丢弃引用让它随 GC 回收（线程泄漏换进程稳定，这是划算的取舍）。
     * @return 替换是否成功
     */
    private fun rebuildLaneLocked(lane: Int): Boolean {
        val fresh = newEngineLocked() ?: return false
        replaceLaneLocked(lane, fresh)
        return true
    }

    /** 锁内：整台替换 lane 上的引擎（主引擎同步更新引用）。 */
    private fun replaceLaneLocked(lane: Int, fresh: JsEngine) {
        if (lane >= lanes.size) return
        lanes.set(lane, fresh)
        if (lane < laneRegistered.size) laneRegistered[lane] = mutableSetOf()
        if (lane == 0) primary = fresh
        // 重建后该 lane 是空引擎：旧 hasMethod 缓存（尤其是「空引擎 → false」）会让
        // 推荐/排行页直接显示「已启用的插件均不支持」。hasMethod 现已改为多 lane
        // 探测且 false 不缓存，这里只需整表失效即可（保留缓存会漏报新能力）。
        methodCache.clear()
    }

    /**
     * 取出可用的 lane 引擎；中毒则先尝试重建（锁内只做一次替换），
     * 重建失败时返回 null 让调用方按"引擎不可用"处理，而不是把请求打进死线程。
     */
    private fun healthyLaneOrNull(lane: Int): JsEngine? {
        val engine = lanes.getOrNull(lane) ?: return null
        if (!engine.poisoned) return engine
        synchronized(laneLock) {
            val again = lanes.getOrNull(lane) ?: return null
            if (!again.poisoned) return again
            if (!rebuildLaneLocked(lane)) return null
            Log.w(TAG, "lane $lane rebuilt on demand: ${again.poisonReason}")
            return lanes.getOrNull(lane)
        }
    }

    /** 主引擎版 [healthyLaneOrNull]（播放/详情/信息类调用走主引擎）。 */
    private fun healthyPrimary(): JsEngine? {
        if (!primary.poisoned) return primary
        synchronized(laneLock) {
            val again = primary
            if (!again.poisoned) return again
            if (!rebuildLaneLocked(0)) return null
            Log.w(TAG, "primary engine rebuilt on demand: ${again.poisonReason}")
            return primary
        }
    }

    /**
     * 锁内：懒创建并追加一台新引擎。不做全量注册（省创建成本），
     * 目标平台的插件由调用时 [ensureRegistered] 在引擎上按需注册。
     * 创建方式遵循现有 warmup：QuickJsEngine 在调用线程上构造并 initialize()，
     * JS 执行集中在其自身 HandlerThread。
     * @return 新引擎 lane 号；创建失败返回 -1（回退复用现有引擎）
     */
    private fun createLaneLocked(): Int {
        val engine = newEngineLocked() ?: return -1
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
        val source = synchronized(laneLock) { platformSources[platform] } ?: return
        synchronized(laneRegisterLocks[lane]) {
            // 双重检查：同引擎同平台的并发调用只注册一次
            if (synchronized(laneLock) { platform in laneRegistered[lane] }) return
            // 锁内再取当前引擎：等待期间 lane 可能已被 poisoned 重建，
            // 绝不能把 register 打进已丢弃的旧 runtime。
            val engine = healthyLaneOrNull(lane) ?: return
            val ok = runCatching { engine.registerPlugin(platform, source) }.getOrDefault(false)
            if (ok) synchronized(laneLock) { laneRegistered[lane].add(platform) }
        }
    }

    /** 引擎不可用（重建失败或全池中毒）时的统一报错，避免请求打进死线程白等预算。 */
    private fun engineUnavailable(lane: Int): Nothing =
        throw PluginCallException("js engine unavailable (lane $lane)")

    companion object {
        private const val TAG = "PluginRuntime"

        /** 并行搜索的初始引擎总数。Amlogic p230 上 3 台足够，过大会推高 CPU/内存。 */
        private const val SEARCH_ENGINES = 3

        /** 引擎池默认上限：所有现有引擎都忙时按需懒扩容，最多 6 台（小内存 TV 盒）。 */
        private const val MAX_LANES = 6

        /**
         * 大内存设备（总 RAM ≥ 2.5GB）的引擎池上限。
         *
         * 聚合搜索的总耗时大致是「54 个源 ÷ lane 数 × 单源耗时」：6 条 lane 时每条约
         * 9 个源串行，尾部源排队超过 queue wait 就被丢弃（实测设备 54 源只有 21 源
         * 出结果）。lane 内大部分时间阻塞在 OkHttp 读，瓶颈是并发 I/O 而不是 CPU，
         * 所以内存宽松时多开 2 条 lane 能直接换吞吐。
         */
        private const val MAX_LANES_BIG_MEM = 8

        /** 插件可用性缓存 TTL（ms），过期后重新 hasPlugin+hasMethod 探测。 */
        private const val AVAIL_CACHE_TTL_MS = 30_000L

        @Volatile private var INSTANCE: PluginRuntime? = null

        fun create(context: Context, store: com.tvmusic.data.PluginStore): PluginRuntime {
            val existing = INSTANCE
            if (existing != null) return existing
            val provider: (String) -> Map<String, String> = { platform ->
                if (platform.isBlank()) emptyMap()
                else store.loadVariables(platform)
            }
            // P0-6：主引擎构造/initialize 原来在 runCatching 之外，ABI 不符（缺 x86 32 位等）
            // 抛 UnsatisfiedLinkError 直接杀进程（开屏首页即崩）。这里显式收口并重抛成
            // 带说明的异常，由 TvMusicApp 初始化协程统一记账成"初始化失败/可重试"。
            // 故意 catch Throwable：UnsatisfiedLinkError 是 Error 不是 Exception。
            val primary = try {
                QuickJsEngine(context.applicationContext, provider).also { it.initialize() }
            } catch (t: Throwable) {
                Log.e("PluginRuntime", "primary JS engine init failed", t)
                throw IllegalStateException("插件引擎初始化失败（当前设备可能不支持 QuickJS ABI）", t)
            }
            val extras = ArrayList<JsEngine>()
            while (extras.size < SEARCH_ENGINES - 1) {
                val e = runCatching {
                    QuickJsEngine(context.applicationContext, provider)
                        .also { it.initialize() }
                }.getOrNull()
                if (e == null) break
                extras.add(e)
            }
            // 引擎池上限按总内存分档：每台 lane 独立 QuickJS runtime + 已注册插件源码，
            // 小内存 TV 盒（1.8GB 实测）保持 6，避免挤掉音频解码/GC 空间；
            // 大内存设备放宽到 8 提高聚合搜索吞吐。
            val totalMemMb = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                mi.totalMem / (1024L * 1024L)
            }.getOrDefault(0L)
            val laneCap = if (totalMemMb >= 2560L) MAX_LANES_BIG_MEM else MAX_LANES
            val rt = PluginRuntime(primary, extras, context.applicationContext, provider, laneCap)
            INSTANCE = rt
            return rt
        }

        fun get(): PluginRuntime = INSTANCE ?: error("PluginRuntime not initialized")
    }

    /**
     * 插件注册需要同步到所有引擎，否则并行搜索分发到的引擎上找不到该插件。
     * 同时缓存“平台 → 源码”，供新扩容引擎按需注册（无需回查 DB/网络）。
     *
     * 不做短路：旧实现用 `all { }`，首台引擎失败/超时即短路，后面的引擎**根本没注册**，
     * 结果是「部分引擎有插件、部分没有」——并行调用落到没注册的引擎上会报
     * `plugin not loaded`，而整体又判为失败不入库，用户表现就是"导入插件后没加载上"。
     * 现在逐台独立注册并记账，未注册成功的 lane 交由 [ensureRegistered] 按需补注册。
     */
    suspend fun loadPlugin(platform: String, source: String): Boolean = withContext(Dispatchers.IO) {
        val targets = lanes.indices.toList()
        val results = targets.map { i ->
            val engine = healthyLaneOrNull(i)
            val ok = if (engine == null) false
            else runCatching { engine.registerPlugin(platform, source) }.getOrDefault(false)
            if (ok) synchronized(laneLock) { laneRegistered.getOrNull(i)?.add(platform) }
            ok
        }
        val okCount = results.count { it }
        if (okCount < results.size) {
            val failed = results.indices.filter { !results[it] }
            Log.w("PluginRuntime", "register $platform: $okCount/${results.size} lanes ok, failed lanes=$failed")
        }
        if (okCount == 0) {
            // 全军覆没：清掉源码缓存，避免 ensureRegistered 反复拿一份永远注册不上的源码重试
            synchronized(laneLock) { platformSources.remove(platform) }
            return@withContext false
        }
        synchronized(laneLock) { platformSources[platform] = source }
        // 插件源码可能更新了方法集，失效该平台的 hasMethod 缓存
        methodCache.keys.removeAll { it.startsWith("$platform::") }
        true
    }

    /**
     * 只在主引擎注册插件（warmup 专用）：并行引擎（lane≥1）与懒扩容引擎一样，
     * 首次调用时经 [ensureRegistered] 从 [platformSources] 缓存补注册。
     * 54 个插件全量 × 3 台引擎串行注册要 7 分钟以上（低配 TV 上每个 2~9s），
     * 是"冷启动后很久首页/排行榜都读不出数据"的直接原因；改为主引擎单台注册
     * 后 warmup 时间降为 1/3，其余引擎用到时才补（并行搜索首个请求稍慢一次）。
     */
    suspend fun registerPrimary(platform: String, source: String): Boolean = withContext(Dispatchers.IO) {
        val engine = healthyLaneOrNull(0) ?: return@withContext false
        val ok = runCatching { engine.registerPlugin(platform, source) }.getOrDefault(false)
        if (!ok) return@withContext false
        synchronized(laneLock) {
            laneRegistered.getOrNull(0)?.add(platform)
            platformSources[platform] = source
        }
        methodCache.keys.removeAll { it.startsWith("$platform::") }
        true
    }

    suspend fun hasPlugin(platform: String): Boolean = withContext(Dispatchers.IO) {
        // 逐 lane 探测（0 号即主引擎）：某台引擎 poisoned 时先重建再试，
        // 避免"主引擎坏过一次就永远报插件没装上"。
        for (i in lanes.indices) {
            val engine = healthyLaneOrNull(i) ?: continue
            if (runCatching { engine.hasPlugin(platform) }.getOrDefault(false)) {
                return@withContext true
            }
        }
        false
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
        return withContext(Dispatchers.IO) {
            // 主引擎优先：warmup 保证所有可用插件都注册在主引擎上，方法集由它说了算。
            // 只有主引擎上没有该插件（注册失败/引擎被重建）才逐台按需补注册再探——
            // 全量探测（caps/首页/排行榜/推荐页签）若对每个"不支持"的插件都在
            // 所有并行引擎上懒注册一遍（每个 3~9s），会把探测队列拖成几分钟，
            // 表现为「启动早期插件明明在、推荐/排行榜全显示未提供」。
            val primary = healthyLaneOrNull(0)
            if (primary != null && runCatching { primary.hasPlugin(platform) }.getOrDefault(false)) {
                return@withContext runCatching { primary.hasMethod(platform, method) }.getOrDefault(false)
            }
            for (i in lanes.indices) {
                ensureRegistered(i, platform)
                val yes = runCatching {
                    healthyLaneOrNull(i)?.hasMethod(platform, method) == true
                }.getOrDefault(false)
                if (yes) return@withContext true
            }
            false
        }
            .also { if (it) methodCache[key] = true }
    }

    /**
     * 插件可用性缓存：避免每次搜索/换源都对全部插件逐一 hasPlugin+hasMethod 探测 JS 引擎。
     * 缓存 30 秒，过期后重新探测；插件安装/卸载/切换启用时由调用方 invalidate 失效。
     */
    private val availCache = ConcurrentHashMap<String, AvailabilityEntry>()

    private data class AvailabilityEntry(
        val available: Boolean,
        val checkedAt: Long
    )

    /**
     * 判定某插件对某方法是否真正可用：已注册到引擎且该方法存在。
     * 结果缓存 30 秒，过期后重探测。
     */
    suspend fun isAvailable(platform: String, method: String): Boolean = withContext(Dispatchers.IO) {
        val key = "$platform::$method"
        val now = System.currentTimeMillis()
        val entry = availCache[key]
        if (entry != null && now - entry.checkedAt < AVAIL_CACHE_TTL_MS) {
            return@withContext entry.available
        }
        val ok = if (!runCatching { hasPlugin(platform) }.getOrDefault(false)) false
        else runCatching { hasMethod(platform, method) }.getOrDefault(false)
        availCache[key] = AvailabilityEntry(ok, now)
        ok
    }

    /**
     * 过滤出对 [method] 真正可用的平台列表（保持原列表顺序）。
     * 适用于搜索/换源等需要“只选能用的插件”的场景，替代只读元数据的 info/loadError 过滤。
     */
    suspend fun availablePlatforms(platforms: List<String>, method: String): List<String> = withContext(Dispatchers.IO) {
        platforms.filter { isAvailable(it, method) }
    }

    /** 清空可用性缓存（插件安装/卸载/启用状态变更时调用）。 */
    fun invalidateAvailability() {
        availCache.clear()
    }

    /**
     * 读取插件静态信息。primary 是首选，但 primary 注册失败/超时时（首次装大插件时
     * 偶发）退到其它 lane 读，避免"插件其实已装上却报读取插件信息失败"而不入库。
     */
    suspend fun readInfo(platform: String): JSONObject? = withContext(Dispatchers.IO) {
        val raw = readInfoFrom(0, platform) ?: run {
            var found: String? = null
            for (i in 1 until lanes.size) {
                found = readInfoFrom(i, platform)
                if (found != null) break
            }
            found
        } ?: return@withContext null
        try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun readInfoFrom(lane: Int, platform: String): String? =
        runCatching {
            ensureRegistered(lane, platform)
            healthyLaneOrNull(lane)?.readPluginInfo(platform)
        }.getOrNull()

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
                ensureRegistered(0, platform)
                val engine = healthyPrimary() ?: throw PluginCallException("js engine unavailable (primary)")
                val result = engine.invoke(platform, method, argsJson, timeoutMs)
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
                ensureRegistered(0, platform)
                val engine = healthyPrimary() ?: throw PluginCallException("js engine unavailable (primary)")
                val result = engine.invoke(platform, method, arr.toString())
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
    ): Any = callParallelJson(platform, method, JSONArray(args).toString(), timeoutMs)

    private suspend fun callParallelJson(
        platform: String,
        method: String,
        argsJson: String,
        timeoutMs: Long
    ): Any {
        val startedAt = SystemClock.elapsedRealtime()
        val lane = acquireLane(platform)
        try {
            return withContext(Dispatchers.IO) {
                try {
                    ensureRegistered(lane, platform)
                    val engine = healthyLaneOrNull(lane) ?: engineUnavailable(lane)
                    val result = engine.invoke(platform, method, argsJson, timeoutMs)
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
        // poisoned 引擎的 JS 线程还卡在死循环里，跨线程 close 会在 native 层释放
        // 正在使用的 runtime 对象（极易 SIGSEGV），故只跳过不关。
        lanes.forEach { engine ->
            if (!engine.poisoned) runCatching { engine.close() }
        }
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
