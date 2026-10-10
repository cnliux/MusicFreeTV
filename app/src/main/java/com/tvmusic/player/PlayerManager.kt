package com.tvmusic.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.tvmusic.plugin.PluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import com.tvmusic.constants.PluginMethod

/** 播放模式：顺序 / 单曲循环 / 随机。 */
enum class PlayMode { ORDER, LOOP_ONE, SHUFFLE }

data class QueueEntry(
    val plugin: String,
    val raw: JSONObject,
    val title: String = raw.optString("title", ""),
    val artist: String = raw.optString("artist", ""),
    val album: String = raw.optString("album", ""),
    val artwork: String = raw.optString("artwork", "").ifBlank { raw.optString("coverImg", "") }
) {
    init {
        // 插件返回的 raw 通常不带 platform（该字段由协议层注入）。
        // 这里统一补全，保证写入播放历史/收藏的条目永远带来源插件名。
        if (plugin.isNotBlank() && raw.optString("platform").isBlank()) {
            raw.put("platform", plugin)
        }
    }
}

data class LrcLine(val timeMs: Long, val text: String, val translation: String? = null)

data class PlayerUiState(
    val current: QueueEntry? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** 已缓冲进度（ExoPlayer bufferedPosition），用于进度条缓冲段。 */
    val bufferedPositionMs: Long = 0,
    val buffering: Boolean = false,
    val error: String? = null,
    val queue: List<QueueEntry> = emptyList(),
    val queueIndex: Int = -1,
    val lrcLines: List<LrcLine> = emptyList(),
    val lrcIndex: Int = -1,
    val isFavorite: Boolean = false,
    val volume: Int = 100,
    val playMode: PlayMode = PlayMode.ORDER,
    /** 当前条目含视频轨（由 ExoPlayer 轨道检测得出，仅真实视频流为 true）。 */
    val isVideo: Boolean = false,
    /** 播放倍速（0.75 ~ 2.0）。 */
    val speed: Float = 1f,
    /** 定时关闭剩余毫秒；0 表示未启用。 */
    val sleepRemainingMs: Long = 0L,
    /** 均衡器总开关（含低音增强）。 */
    val eqEnabled: Boolean = false,
    /** 低音增强强度 0~1000（系统 BassBoost 取值范围）。 */
    val bassStrength: Int = 0,
    /** 均衡器预设序号：0 = 原声（平直），1..N = 系统预设。 */
    val eqPreset: Int = 0,
    /** 歌词/封面兜底提示（lrc.cx），播放页控制条左侧显示 20 秒后自动消失。 */
    val lyricNotice: String? = null,
    /** 音源切换提示（尝试/已换源/受限跳过），与歌词提示分行显示互不覆盖，同样 20 秒消失。 */
    val sourceNotice: String? = null,
    /** 来源标签（「插件名 · 歌单名」，如「wx · 华语热歌」），迷你播放器与播放页展示。 */
    val sourceLabel: String? = null,
    /** 实际取流插件与队列条目来源插件不同（已换源）时显示，如「bilibili」；未换源为 null。 */
    val playingVia: String? = null
)

/**
 * 统一播放控制器：负责调用插件 getMediaSource 取播放地址、管理媒体队列、透传请求头。
 * 全部状态通过 [uiState] 暴露给 Compose UI。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@SuppressLint("StaticFieldLeak")
object PlayerManager {

    private const val KEY_QUALITY = "quality"
    private const val KEY_PLAY_MODE = "playMode"
    private const val KEY_SPEED = "playbackSpeed"
    private const val KEY_EQ_ENABLED = "eqEnabled"
    private const val KEY_BASS = "bassStrength"
    private const val KEY_EQ_PRESET = "eqPreset"

    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ticker = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * 结构性 UI 状态：拷贝自 uiState 但屏蔽每秒刷新的进度/缓冲/歌词字段，
     * 供播放页外壳订阅——ticker 刷新时该流不发射，避免整屏随之秒级重组。
     */
    val screenState: StateFlow<PlayerUiState> = _uiState
        .map {
            it.copy(
                positionMs = 0, durationMs = 0, bufferedPositionMs = 0,
                lrcIndex = -1, lrcLines = emptyList(), sleepRemainingMs = 0
            )
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, PlayerUiState())

    var player: ExoPlayer? = null
        private set

    /** 供服务/UI 获取播放器实例（不存在则创建）。 */
    fun ensurePlayer(): ExoPlayer = requirePlayer()

    /**
     * 进程内控制器与连接 future：作为 PlaybackService 会话的客户端。
     * Media3 的 MediaSessionService 只有在**存在已连接 controller** 时才会显示媒体通知并
     * 以前台服务运行（1.5.1 的 shouldRunInForeground/shouldShowNotification 都以连接
     * controller 为前提）；单纯 startService 会话是孤儿，startForegroundService 则会因
     * 5s 内无人 startForeground 被系统杀掉。所以由进程内 MediaController 充当客户端：
     * buildAsync 即绑定并拉起服务，起播后 media3 自动前台 + 通知 + 系统媒体键路由。
     */
    private var sessionController: androidx.media3.session.MediaController? = null
    private var sessionControllerFuture:
        com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    /** 连接 PlaybackService 会话（幂等）：首次 play 提交时调用，绑定失败只记日志不影响播放。 */
    private fun ensureSessionConnection(ctx: Context) {
        if (sessionController != null || sessionControllerFuture != null) return
        runCatching {
            val token = androidx.media3.session.SessionToken(
                ctx,
                android.content.ComponentName(ctx, PlaybackService::class.java)
            )
            val future = androidx.media3.session.MediaController.Builder(ctx, token).buildAsync()
            sessionControllerFuture = future
            future.addListener({
                sessionController = runCatching { future.get() }
                    .onFailure {
                        sessionControllerFuture = null
                        android.util.Log.w("PlayerManager", "media session controller connect failed", it)
                    }
                    .getOrNull()
            }, java.util.concurrent.Executor { ticker.post(it) })
        }.onFailure {
            sessionControllerFuture = null
            android.util.Log.w("PlayerManager", "media session connect failed", it)
        }
    }

    /** 解绑会话控制器（退出应用时调用）：否则 bound service 会保住进程不退出。 */
    fun disconnectSession() {
        sessionController = null
        sessionControllerFuture?.let {
            runCatching { androidx.media3.session.MediaController.releaseFuture(it) }
        }
        sessionControllerFuture = null
    }

    /** 通知栏封面下载器：封面 URL 通常与音源同源，需带上 getMediaSource 返回的请求头才能加载。
     *  M1：从共享 strict 客户端派生，复用连接池/线程池。 */
    private val artworkLoader = com.tvmusic.net.HttpClients.derive(com.tvmusic.net.HttpClients.strict).build()

    /** lrc.cx 兜底下载器：独立超时（放宽 + 总时限），便于网络抖动时重试。
     *  M1：从共享 relaxed 客户端派生。 */
    private val metaHttp = com.tvmusic.net.HttpClients.derive(com.tvmusic.net.HttpClients.relaxed)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** M2：歌词兜底专用短超时客户端（8s 总时限），歌词是可缺失的辅助信息，不配长重试。 */
    private val lyricsHttp = com.tvmusic.net.HttpClients.derive()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** M2：歌词兜底负缓存（曲目 key -> 失败时刻 elapsedRealtime），TTL 内不再重试。 */
    private val lrcNegativeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var runtime: PluginRuntime? = null
    private var playbackStore: com.tvmusic.data.PlaybackStore? = null
    private var mediaSourceFactory: androidx.media3.exoplayer.source.DefaultMediaSourceFactory? = null

    /** 连续播放失败计数：达到阈值则停止自动跳下一曲，避免整队列快速空转。 */
    private var errorStreak = 0

    /** 已尝试过「换插件救场」重试的条目 key（防死循环）。 */
    @Volatile
    private var fallbackTriedFor: String? = null

    /** 该条目已用过的取流失败换源次数：允许 2 次，一次网络抖动不该烧光整首的救场机会。 */
    @Volatile
    private var fallbackTriedCount = 0

    /**
     * 版权短播连环换源状态：同一首（key）连续短播时逐个插件重试（上限 [SHORT_PLAY_MAX_RETRY]），
     * 已实际取流过且仍受限的插件进 [shortPlayExclude] 不再复用；用户主动播放时整体重置。
     */
    @Volatile
    private var shortPlayFor: String? = null
    private var shortPlayCount = 0

    /** 已受限插件排除集：主线程写、换源协程读，用并发集合。 */
    private val shortPlayExclude: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 当前实际取流的插件（可能与队列条目 plugin 不同——换源救场后为备选插件）。 */
    @Volatile
    private var playingVia: String? = null

    /**
     * 换源救场的搜索结果缓存（platform+曲名 → 命中的候选条目列表）：连环换源重试时省去重复搜索。
     * 空列表=确认无匹配（负缓存）；调用失败不缓存，下次可重试。
     */
    private val fallbackSearchCache = java.util.concurrent.ConcurrentHashMap<String, List<JSONObject>>()

    /**
     * 播放会话代数：每次 play() 自增。getMediaSource 是异步解析，快速连点两首歌时
     * 旧歌的解析结果可能后返回并 setMediaItem 覆盖新歌（表现为"点了新歌放的还是旧歌"）。
     * 过期的解析结果直接丢弃。
     */
    @Volatile
    private var playSession = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 最近一次 play() 提交给 ExoPlayer 的 mediaId（plugin:id）。
     * onMediaItemTransition 用媒体本身的 mediaId 定位队列条目，而不是按当前 queueIndex 反查：
     * 快速连点「下一首」时 queueIndex 已被第二次 play 改掉，反查会拿到第三首的条目，
     * 写错 current/历史/歌词。命中不到就放弃更新，宁可不刷新也不要写错。
     */
    @Volatile
    private var pendingMediaId: String? = null

    // ---------------- 下一首预加载 ----------------

    /** 预解析结果缓存有效期：10 分钟（音源签名 URL 通常存活更久）。 */
    private const val PRELOAD_TTL_MS = 10 * 60_000L

    /** 版权短播连环换源的最大重试次数（每曲）：逐个插件试完即放弃，防死循环。 */
    private const val SHORT_PLAY_MAX_RETRY = 4

    /** M2：歌词兜底最多尝试的候选条数（歌词是可缺失的辅助信息，不配全候选长重试）。 */
    private const val LRC_FALLBACK_CANDIDATES = 2

    /** M2：歌词兜底负缓存 TTL——同一曲目失败后 10 分钟内不再重试。 */
    private const val LRC_NEG_TTL_MS = 10 * 60_000L

    /** 换源扫描中单个候选插件单次调用（search/getMediaSource）的超时：慢平台快速跳过。 */
    private const val FALLBACK_CALL_TIMEOUT_MS = 8_000L

    /**
     * 聚合搜索结果缓存（进程内静态，重启清零）：归一化「搜索词」→ 平台 → 命中条目 raw。
     * 换源探测优先复用这里的命中，跳过每平台 2~5 秒的 search 调用——用户从搜索结果
     * 点歌、主源不可播换源时，各平台候选条目其实已经搜好了。
     * 上限 [AGG_CACHE_MAX] 条，超限清一半（与 fallbackSearchCache 同策略）。
     */
    private val aggregateCache =
        java.util.concurrent.ConcurrentHashMap<String, Map<String, List<JSONObject>>>()
    private const val AGG_CACHE_MAX = 64

    /** 搜索页/远程搜索每源完成时写入命中条目（raw 为插件返回的裸 music 条目）。 */
    fun registerAggregateResults(query: String, platform: String, items: List<JSONObject>) {
        val key = normalizeName(query)
        if (key.isEmpty() || platform.isBlank() || items.isEmpty()) return
        if (aggregateCache.size >= AGG_CACHE_MAX) {
            val it0 = aggregateCache.keys.iterator()
            repeat(aggregateCache.size / 2) { if (it0.hasNext()) { it0.next(); it0.remove() } }
        }
        aggregateCache.merge(key, mapOf(platform to items)) { old, new -> old + new }
    }

    /**
     * 从聚合缓存找 [platform] 上匹配「歌名」的候选条目；无命中返回 null（回退真实搜索）。
     * 搜索词与歌名互为包含即视为同一轮搜索（搜「周杰伦」点《晴天》、搜「晴天」点《晴天》都能命中）。
     */
    private fun aggMatchesFor(title: String, platform: String): List<JSONObject>? {
        if (aggregateCache.isEmpty()) return null
        val want = normalizeName(title)
        if (want.isEmpty()) return null
        var best: Map<String, List<JSONObject>>? = null
        for ((k, v) in aggregateCache) {
            if (k == want || want.contains(k) || k.contains(want)) { best = v; break }
        }
        val entries = best?.get(platform) ?: return null
        val out = ArrayList<JSONObject>(FALLBACK_MAX_MATCHES)
        for (o in entries) {
            val t = normalizeName(o.optString("title", ""))
            if (t.isNotEmpty() && (t == want || t.contains(want) || want.contains(t))) {
                if (o.optString("platform").isBlank()) o.put("platform", platform)
                out.add(o)
                if (out.size >= FALLBACK_MAX_MATCHES) break
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** 换源竞速整链硬上限：到点仍无人产出可播 URL 即放弃本轮。 */
    private const val FALLBACK_RACE_MS = 18_000L

    /** 换源扫描每平台最多尝试的搜索命中条数：第一条可能是翻唱/伴奏，解析不可播时继续试后面的。 */
    private const val FALLBACK_MAX_MATCHES = 3

    /** 分批竞速：首批覆盖三条 QuickJS lane，其余候选错峰启动，避免低配 TV 瞬时争抢。 */
    private const val FALLBACK_FAST_BATCH = 3
    private const val FALLBACK_STAGGER_MS = 350L
    private const val FALLBACK_FAST_BATCH_MIN = 1
    private const val FALLBACK_FAST_BATCH_MAX = 6

    /**
     * 预加载命中的取流结果：url/headers 已解析好；via=null 表示来自原插件，
     * 非空表示预加载阶段换源成功的实际插件（播放时直接沿用，无感切换）。
     */
    private data class PreloadedMedia(
        val url: String,
        val headers: Map<String, String>,
        val quality: String,
        val atMs: Long,
        val via: String? = null
    )

    /** 预加载缓存：entryKey -> 已解析的可播放取流结果（含换源）。容量 4，按访问序淘汰最旧。 */
    private val preloadCache = object : LinkedHashMap<String, PreloadedMedia>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreloadedMedia>?): Boolean =
            size > 4
    }

    /** 本次播放已触发过"下一首预加载"的当前曲 key（每首只触发一次，切歌时重置）。 */
    @Volatile
    private var preloadTriggeredFor: String? = null

    /** 条目稳定 key：与歌词去重同一规则（plugin + 主键 id）。 */
    private fun entryKeyOf(e: QueueEntry): String =
        e.plugin + ":" + e.raw.optString("id", e.raw.optString("songmid", e.raw.optString("lid", "")))

    /** 当前曲 key 缓存：同一条目在 ticker 里反复检查时不再重复拼字符串。 */
    private var preloadKeyOfCurrent: String? = null
    private var preloadKeyItem: QueueEntry? = null

    /**
     * 播放到后半段时，提前在后台解析下一首音源并缓存：
     * 切歌时 play() 命中缓存可跳过 QuickJS 解析，出声更快。
     * 随机模式目标不固定、单曲队列/单曲循环无需解析，均跳过。
     */
    private fun maybePreloadNext() {
        val st = _uiState.value
        val dur = st.durationMs
        if (dur <= 0L || st.positionMs < dur / 2) return
        if (st.playMode != PlayMode.ORDER || st.queue.size <= 1) return
        val cur = st.current ?: return
        // 引用判同避免每 tick 重建 key；切歌后 current 是新的对象才重算
        if (preloadKeyItem !== cur) {
            preloadKeyItem = cur
            preloadKeyOfCurrent = entryKeyOf(cur)
        }
        val curKey = preloadKeyOfCurrent ?: return
        if (preloadTriggeredFor == curKey) return
        preloadTriggeredFor = curKey
        val nextIdx = if (st.queueIndex + 1 in st.queue.indices) st.queueIndex + 1 else 0
        val next = st.queue.getOrNull(nextIdx) ?: return
        val nk = entryKeyOf(next)
        if (nk == curKey) return
        synchronized(preloadCache) {
            if (preloadCache.containsKey(nk)) return
        }
        val rt = runtime ?: return
        val my = playSession.get() // 预解析归属当前播放会话：切歌后结果作废
        scope.launch(Dispatchers.Default) {
            try {
                // 与正式播放同走粘性 home 引擎；此时处于歌曲后半段，
                // 引擎队列通常空闲，预解析不与用户操作抢资源。
                var url = ""
                var headers = emptyMap<String, String>()
                var via: String? = null
                val media = resolveMediaSource(rt, next.plugin, next.raw)
                val u = media?.optString("url").orEmpty()
                // FLV 直链 ExoPlayer 无解封装器，视为不可播放（N2：不再误缓存）
                val flv = u.isNotBlank() &&
                    u.substringBefore('?').substringBefore('#').endsWith(".flv", ignoreCase = true)
                if (u.isNotBlank() && !flv) {
                    url = u
                    headers = parseMediaHeaders(media!!)
                } else if (com.tvmusic.config.MetaSettings.fallbackOtherSource) {
                    // N1：原插件不可播放（解析抛错/空地址/FLV）时，预加载阶段就换源，
                    // 让切到该曲时无需再走慢路径，实现无感切换。
                    val fb = tryFallbackSource(rt, next, my, silent = true)
                    if (fb != null) {
                        url = fb.url
                        headers = fb.headers
                        via = fb.plugin
                    }
                }
                if (url.isBlank()) return@launch // 换源也没找到：留待正式播放时再处理
                if (my != playSession.get()) return@launch // 切歌后作废
                synchronized(preloadCache) {
                    preloadCache[nk] = PreloadedMedia(
                        url, headers, quality,
                        android.os.SystemClock.elapsedRealtime(), via
                    )
                }
            } catch (_: Exception) {
                // 预加载失败静默：正式播放时还会重新解析
            }
        }
    }

    /**
     * 统一处理播放失败：记录错误并自动跳到队列下一曲。
     * 单曲循环 / 队列只剩一首时不跳，避免原地打转。
     * @param autoSkip false 时只提示不自动跳曲（如 FLV 这类换一首也无法解决的硬限制）。
     */
    private fun reportPlayError(message: String, autoSkip: Boolean = true) {
        val st = _uiState.value
        _uiState.update { it.copy(error = message, buffering = false) }
        android.util.Log.w("PlayerManager", "play error: $message (streak=$errorStreak)")
        if (!autoSkip) return
        if (st.queue.size <= 1 || st.playMode == PlayMode.LOOP_ONE) return
        if (errorStreak >= st.queue.size) {
            android.util.Log.w("PlayerManager", "too many consecutive errors, stop auto-skip")
            return
        }
        errorStreak++
        // 延迟一点再跳，避免错误风暴；期间用户可手动操作
        val mySession = playSession.get() // 捕获当前播放会话代际
        ticker.postDelayed({
            // 代际校验：延迟期间用户切歌则放弃本次恢复动作，避免打到新歌（R4）
            if (mySession != playSession.get()) return@postDelayed
            if (_uiState.value.error != null) next()
        }, 1200)
    }

    /** 按错误码大类转译成用户可读提示（DRM / IO / 解码，其余保持原始码名）。 */
    private fun friendlyPlaybackError(error: androidx.media3.common.PlaybackException): String {
        val name = error.errorCodeName
        return when {
            name.startsWith("ERROR_CODE_DRM") -> "加密内容（DRM），设备不支持播放"
            name.startsWith("ERROR_CODE_IO") -> "网络错误或音源不可用"
            name.startsWith("ERROR_CODE_DECODING") -> "解码失败，音频/视频格式不支持"
            else -> "播放失败：$name"
        }
    }

    /** 音质档位：插件 getMediaSource 的第二个参数。standard/high/low/super 等。
     * 用 StateFlow 使设置页选中态即时重组（原普通 var 点击后不刷新，违反 R20）。 */
    private val _quality = MutableStateFlow("standard")
    val quality: String
        get() = _quality.value
    /** Compose 订阅用 StateFlow。 */
    val qualityFlow: StateFlow<String> = _quality

    private val qualityPrefs by lazy {
        context?.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
    }

    fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        _quality.value = qualityPrefs?.getString(KEY_QUALITY, "standard") ?: "standard"
        val savedMode = runCatching {
            PlayMode.valueOf(qualityPrefs?.getString(KEY_PLAY_MODE, PlayMode.ORDER.name) ?: PlayMode.ORDER.name)
        }.getOrDefault(PlayMode.ORDER)
        val savedSpeed = qualityPrefs?.getFloat(KEY_SPEED, 1f) ?: 1f
        _uiState.update {
            it.copy(
                playMode = savedMode,
                speed = savedSpeed,
                eqEnabled = qualityPrefs?.getBoolean(KEY_EQ_ENABLED, false) ?: false,
                bassStrength = qualityPrefs?.getInt(KEY_BASS, 0) ?: 0,
                eqPreset = qualityPrefs?.getInt(KEY_EQ_PRESET, 0) ?: 0
            )
        }
    }

    fun setQuality(q: String) {
        _quality.value = q
        qualityPrefs?.edit()?.putString(KEY_QUALITY, q)?.apply()
    }

    fun attach(runtime: PluginRuntime) {
        this.runtime = runtime
    }

    /** 插件仓库引用：供「换插件救场」枚举其他可用音源（见 [tryFallbackSource]）。 */
    fun attachRepository(repo: com.tvmusic.plugin.PluginRepository) {
        this.repository = repo
    }

    private var repository: com.tvmusic.plugin.PluginRepository? = null

    fun attachPlaybackStore(store: com.tvmusic.data.PlaybackStore) {
        this.playbackStore = store
    }

    private fun requirePlayer(): ExoPlayer {
        player?.let { return it }
        val ctx = context ?: error("PlayerManager not initialized")
        val dsFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val msf = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
            .setDataSourceFactory(dsFactory)
        mediaSourceFactory = msf
        // M3：显式缓冲策略。TV 弱网场景默认参数起播偏慢（5s 起播缓冲），
        // 调为 2.5s 起播 / 15s 起播上限 / 50s 最大缓冲，兼顾秒开与流畅。
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .build()
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(msf)
            .setLoadControl(loadControl)
            .build()
        p.setPlaybackSpeed(_uiState.value.speed)
        p.addListener(playerListener)
        attachAudioEffects(ctx, p)
        player = p
        return p
    }

    // ---------------- 均衡器 / 低音增强（系统音效） ----------------

    private var equalizer: android.media.audiofx.Equalizer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null

    private val _eqPresets = MutableStateFlow(listOf("原声"))
    /** 可用预设名列表：0 固定为"原声"（平直），1..N 为系统预设。播放器创建后才有完整列表。 */
    val eqPresets: StateFlow<List<String>> = _eqPresets.asStateFlow()

    /**
     * 系统 Equalizer 预设名通常是英文（"Rock"/"Pop"/"Jazz"/"Classic" 等），
     * 用户在电视端看到的是英文，体验不佳。这里做一次中文映射，未匹配到时回退原值。
     */
    private fun mapEqPresetToChinese(raw: String): String {
        val key = raw.trim().lowercase()
        return when {
            key == "flat" || key == "original" || key == "off" -> "原声"
            key == "rock" -> "摇滚"
            key == "pop" -> "流行"
            key == "jazz" -> "爵士"
            key == "classic" || key == "classical" -> "古典"
            key == "blues" -> "布鲁斯"
            key == "bass boost" || key == "bass_boost" -> "低音增强"
            key == "vocal" -> "人声"
            key == "dance" -> "舞曲"
            key == "country" -> "乡村"
            key == "folk" -> "民谣"
            key == "hiphop" || key == "hip-hop" || key == "rap" -> "嘻哈"
            key == "metal" || key == "heavy metal" -> "金属"
            key == "soul" -> "灵魂"
            key == "reggae" -> "雷鬼"
            key == "newage" || key == "new age" -> "新世纪"
            key == "opera" -> "歌剧"
            key == "chiptune" -> "芯片音"
            key == "ambient" -> "环境音"
            key == "cinema" || key == "movie" -> "电影"
            key == "party" -> "派对"
            key == "game" || key == "games" -> "游戏"
            key == "sports" -> "体育"
            key == "speech" || key == "voice" -> "语音"
            key == "phone" -> "电话"
            key == "podcast" -> "播客"
            key == "audiobook" -> "有声书"
            key == "night" -> "夜间"
            key == "sleep" -> "睡眠"
            key == "nature" || key == "relax" -> "自然"
            key == "stereo" -> "立体声"
            key == "surround" -> "环绕"
            else -> raw
        }
    }

    /**
     * 给播放器挂上系统音效（Equalizer + BassBoost）。
     * 必须显式生成并回设 audioSessionId：未显式设置时播放器起播才分配会话，
     * 设置页在首次播放前打开均衡器会拿不到会话 id。
     * 部分设备/模拟器音效框架缺失，构造抛异常时静默降级（功能不可用但不影响播放）。
     */
    private fun attachAudioEffects(ctx: Context, p: ExoPlayer) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val sessionId = am.generateAudioSessionId()
            p.setAudioSessionId(sessionId)
            val eq = android.media.audiofx.Equalizer(0, sessionId)
            val bb = android.media.audiofx.BassBoost(0, sessionId)
            equalizer = eq
            bassBoost = bb
            val names = mutableListOf("原声")
            for (i in 0 until eq.numberOfPresets.toInt()) {
                // 系统返回的预设名通常是英文（Rock/Pop/Jazz...），用户希望看到中文
                names.add(mapEqPresetToChinese(eq.getPresetName(i.toShort())))
            }
            _eqPresets.value = names
            applyAudioEffects()
        } catch (e: Exception) {
            android.util.Log.w("PlayerManager", "audio effects unavailable: ${e.message}")
        }
    }

    /** 把当前 uiState 的音效设置应用到 Equalizer / BassBoost。 */
    private fun applyAudioEffects() {
        val st = _uiState.value
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = st.eqEnabled
                if (st.eqEnabled) {
                    if (st.eqPreset in 1..eq.numberOfPresets.toInt()) {
                        eq.usePreset((st.eqPreset - 1).toShort())
                    } else {
                        // 原声：全部频段回中（频段电平范围的中点即 0 增益）
                        val range = eq.getBandLevelRange()
                        val center = ((range[0].toInt() + range[1].toInt()) / 2).toShort()
                        for (b in 0 until eq.numberOfBands.toInt()) eq.setBandLevel(b.toShort(), center)
                    }
                }
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                bb.enabled = st.eqEnabled && st.bassStrength > 0
                if (bb.strengthSupported) bb.setStrength(st.bassStrength.coerceIn(0, 1000).toShort())
            }
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        _uiState.update { it.copy(eqEnabled = enabled) }
        qualityPrefs?.edit()?.putBoolean(KEY_EQ_ENABLED, enabled)?.apply()
        applyAudioEffects()
    }

    /** 低音增强强度：0~1000。 */
    fun setBassStrength(v: Int) {
        val c = v.coerceIn(0, 1000)
        _uiState.update { it.copy(bassStrength = c) }
        qualityPrefs?.edit()?.putInt(KEY_BASS, c)?.apply()
        applyAudioEffects()
    }

    /** 选择均衡器预设：0 = 原声，1..N = 系统预设。 */
    fun setEqPreset(index: Int) {
        val c = index.coerceIn(0, _eqPresets.value.lastIndex.coerceAtLeast(0))
        _uiState.update { it.copy(eqPreset = c) }
        qualityPrefs?.edit()?.putInt(KEY_EQ_PRESET, c)?.apply()
        applyAudioEffects()
    }

    // ---------------- 倍速 / 定时关闭 ----------------

    /** 倍速档位：点击按钮循环切换。 */
    val speedSteps = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** 循环切换倍速：0.75 → 1 → 1.25 → 1.5 → 2 → 0.75。 */
    fun cycleSpeed(): Float {
        val cur = _uiState.value.speed
        val idx = speedSteps.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        val next = speedSteps[((if (idx < 0) 1 else idx) + 1) % speedSteps.size]
        setSpeed(next)
        return next
    }

    fun setSpeed(s: Float) {
        _uiState.update { it.copy(speed = s) }
        qualityPrefs?.edit()?.putFloat(KEY_SPEED, s)?.apply()
        ticker.post {
            player?.setPlaybackSpeed(s)
        }
    }

    /** 定时关闭到期时刻（elapsedRealtime 毫秒）；0 表示未启用。 */
    private var sleepDeadline = 0L

    private val sleepRunnable = object : Runnable {
        override fun run() {
            val dl = sleepDeadline
            if (dl == 0L) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= dl) {
                sleepDeadline = 0L
                _uiState.update { it.copy(sleepRemainingMs = 0L) }
                // 到点暂停播放（不销毁播放器，用户仍可手动继续）
                ticker.post { player?.pause() }
                return
            }
            _uiState.update { it.copy(sleepRemainingMs = dl - now) }
            ticker.postDelayed(this, 1000)
        }
    }

    /** 设置定时关闭：minutes<=0 取消。 */
    fun setSleepTimer(minutes: Int) {
        ticker.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepDeadline = 0L
            _uiState.update { it.copy(sleepRemainingMs = 0L) }
            return
        }
        sleepDeadline = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        _uiState.update { it.copy(sleepRemainingMs = minutes * 60_000L) }
        ticker.postDelayed(sleepRunnable, 1000)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) errorStreak = 0
            _uiState.update { it.copy(isPlaying = isPlaying) }
            // 事件驱动 ticker：仅播放中周期刷新；暂停/停止时停表并做一次最终刷新，
            // 把最后的进度/歌词行写进 uiState，避免 UI 停在旧帧。
            if (isPlaying) startTicker() else stopTickerWithFinalRefresh()
            // 暂停也是一次"该快照了"的时点（防抖落盘）
            if (!isPlaying) scheduleResumeSave()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update {
                it.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    error = null
                )
            }
            // 队列由应用层管理（每次只 setMediaItem 单曲），
            // 播完不会自动切歌，必须在此监听 ENDED 主动推进——这是歌单无法连续播放的根因。
            if (playbackState == Player.STATE_ENDED) {
                handleMediaEnded()
            }
            // READY 即时间线就绪：命中「试听截断」特征立即提前换源，不等掐断段播完
            if (playbackState == Player.STATE_READY) {
                // M20：起播耗时（play() → 首次 READY）。playStartMs>0 表示本会话尚未记录，
                // 记录后清零，seek/暂停恢复再次进 READY 不会重复打点。
                val t0 = playStartMs
                if (t0 > 0L) {
                    playStartMs = 0L
                    val cost = android.os.SystemClock.elapsedRealtime() - t0
                    com.tvmusic.core.Metrics.recordPlayLatency(cost)
                    com.tvmusic.core.Metrics.recordPlayStart()
                }
                maybeEarlyCutoffSwitch(player?.duration ?: C.TIME_UNSET)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // 解析成功但取流失败（链接过期/404 等）：给「换插件救场」机会。
            // 每条目最多 2 次（fallbackTriedCount 计数）：换源链里一次网络抖动/引擎排队失败
            // 不该直接烧光这首歌的救场机会；仍失败则照常报错跳曲。
            val st = _uiState.value
            val entry = st.current
            val key = entry?.let { entryKeyOf(it) }
            if (com.tvmusic.config.MetaSettings.fallbackOtherSource &&
                entry != null && key != null && st.queue.isNotEmpty() &&
                (key != fallbackTriedFor || fallbackTriedCount < 2)
            ) {
                fallbackTriedCount = if (key != fallbackTriedFor) 1 else fallbackTriedCount + 1
                fallbackTriedFor = key
                android.util.Log.i("PlayerManager", "player error [$key], retry via other source #${fallbackTriedCount}")
                play(entry.plugin, entry, st.queue, st.queueIndex, forceFallback = true)
                return
            }
            reportPlayError(friendlyPlaybackError(error))
        }

        /** seek 等位置跳变：立即刷一次状态，不必等下一个 ticker 周期。 */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            refreshTickState()
        }

        /**
         * 视频检测：轨道变化时判断当前是否有被选中的视频轨。
         * 比按 URL 后缀嗅探可靠——mp4 也可能是纯音频，而真实视频轨不会误判。
         * 音频条目恒为 false；视频条目自动切换 UI 到视频渲染模式。
         */
        override fun onTracksChanged(tracks: Tracks) {
            val hasVideo = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && group.isSelected
            }
            if (_uiState.value.isVideo != hasVideo) {
                _uiState.update { it.copy(isVideo = hasVideo) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            // 以媒体自身 mediaId（plugin:id，与 entryKeyOf 同规则）定位条目，
            // 避免连点切歌提交期 queueIndex 已指向别的条目而把 current/历史/歌词写错。
            val wantId = mediaItem.mediaId
            val entry = _uiState.value.queue.firstOrNull { entryKeyOf(it) == wantId } ?: return
            val fav = playbackStore?.isFavorite(entry.raw) ?: false
            _uiState.update { it.copy(current = entry, isFavorite = fav) }
            playbackStore?.addHistory(entry.raw)
            fetchLyric(entry)
        }
    }

    // ---------------- 进度刷新 ticker（事件驱动） ----------------

    /**
     * 事件驱动 ticker：只在"正在播放"时按 1s 周期刷新进度/歌词行；
     * 暂停由 onIsPlayingChanged(false) 停表并做最终刷新，seek 由
     * onPositionDiscontinuity 立即刷一次，避免常驻空转。
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshTickState()
            // 仅在播放中续期；暂停/空闲时不排下一拍
            if (player?.isPlaying == true) ticker.postDelayed(this, 1000)
        }
    }

    private fun startTicker() {
        ticker.removeCallbacks(tickRunnable)
        ticker.postDelayed(tickRunnable, 1000)
    }

    /** 停掉 ticker 并做一次最终状态刷新（暂停/停止时调用）。 */
    private fun stopTickerWithFinalRefresh() {
        ticker.removeCallbacks(tickRunnable)
        refreshTickState()
    }

    /** 把播放器当前进度/歌词行/音量刷进 uiState（ticker 周期与 seek/discontinuity 共用）。 */
    private fun refreshTickState() {
        val p = player ?: return
        // lrcIndex 在 update 闭包内基于最新 lrcLines 计算：避免与 fetchLyric 并发写歌词时
        // 用旧快照的 lrcLines 算出的索引覆盖刚写进去的新歌词列表。
        _uiState.update {
            it.copy(
                durationMs = p.duration,
                positionMs = p.currentPosition,
                bufferedPositionMs = p.bufferedPosition,
                lrcIndex = findLrcIndex(it.lrcLines, p.currentPosition),
                volume = (p.volume * 100).toInt()
            )
        }
        // 每秒进度刷新顺带检查：进入后半段则后台预解析下一首
        maybePreloadNext()
    }

    /** 找最后一个 timeMs <= posMs 的行索引；歌词已按 timeMs 升序，二分查找省掉每秒线性扫描。 */
    private fun findLrcIndex(lines: List<LrcLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return idx
    }

    // ---------------- 播放 ----------------

    /**
     * 播放一个条目（可带同源队列）。先经插件解析出真实音源 URL。
     * @param queue 若为空则只播放 single
     * @param startOffsetMs 起播偏移（进程被杀恢复时从上次进度续播），默认 0
     */
    fun play(
        plugin: String,
        item: QueueEntry,
        queue: List<QueueEntry>? = null,
        startIndex: Int = 0,
        startOffsetMs: Long = 0,
        /** 来源标签「插件名 · 歌单名」；null 保留原值（切歌/恢复场景），空串清除。 */
        source: String? = null,
        /** true = 跳过本插件解析，直接走「其他插件」换源（取流失败重试路径）。 */
        forceFallback: Boolean = false
    ) {
        // 插件解析（含阻塞式 JS 调用）放 Default 线程；
        // ExoPlayer 只能在主线程访问，拿到地址后必须切回主线程。
        val my = playSession.incrementAndGet()
        playStartMs = android.os.SystemClock.elapsedRealtime() // M20：起播耗时起点（含解析+缓冲）
        lastLyricKey = null // 新会话重置：重播/切歌后重试同一首歌时不再因旧 key 被跳过
        if (!forceFallback) {
            fallbackTriedFor = null // 用户主动播放：重新给换源机会
            fallbackTriedCount = 0
            earlyCutoffFired.remove(entryKeyOf(item)) // 重播同曲允许重新做截断判定
            shortPlayFor = null     // 短播连环状态一并重置
            shortPlayCount = 0
            shortPlayExclude.clear()
            playingVia = null       // 清掉上一首的实际取流插件，避免脏值误入新一首的排除集
            // 提示常驻到切歌：新会话开始先清掉上一首残留的歌词/换源提示
            _uiState.update { it.copy(lyricNotice = null, sourceNotice = null) }
        }
        scope.launch(Dispatchers.Default) {
            try {
                // 快速连点时本协程可能已过期（更新的切歌请求接管）：必须先校验再写状态。
                // 旧实现在守卫前写 current/queue，旧协程若晚调度会用旧歌覆盖新歌的 UI 状态。
                if (my != playSession.get()) return@launch
                preloadTriggeredFor = null // 新曲开始：允许对本曲触发一次"下一首预加载"
                // 无封面且开启了兜底补全时，按 曲名/歌手 换成带 lrc.cx 回退封面的条目：
                // QueueEntry.artwork 是不可变构造值，只能通过 copy 产生新条目，原地改 raw 无效。
                // 必须把回退封面写进 queue：onMediaItemTransition 会用 queue[idx] 覆盖 current，
                // 只改 current 会在起播后被空封面条目重新打回占位符（播放页依旧 ♪）
                val provided = queue ?: listOf(item)
                val effectiveQueue = provided.map { withFallbackArtwork(it) }
                val shown = effectiveQueue[startIndex.coerceIn(0, effectiveQueue.lastIndex)]
                if (shown !== item) showLyricNotice("封面已用 lrc.cx 补充")
                _uiState.update {
                    it.copy(
                        current = shown,
                        queue = effectiveQueue,
                        queueIndex = startIndex,
                        error = null,
                        // 来源标签随新会话更新：null=保留（切歌/续播），空串=清除
                        sourceLabel = if (source == null) it.sourceLabel else source.ifBlank { null },
                        // 新会话开始：换源标记复位，解析后再按实际取流插件回填
                        playingVia = null
                    )
                }
                // 队列内容/索引已变化：调度防抖写入恢复快照
                scheduleResumeSave()
                val rt = runtime ?: error("PlayerManager runtime not attached")
                // 预加载命中：本曲在上一首后半段已解析（含换源）过，直接复用可播放地址
                // （音质一致、未过期、非 FLV）。forceFallback 时丢弃缓存（坏地址不复用）。
                val key = entryKeyOf(item)
                val cached = if (forceFallback) null else
                    synchronized(preloadCache) { preloadCache.remove(key) }
                        ?.takeIf {
                            it.quality == quality &&
                                android.os.SystemClock.elapsedRealtime() - it.atMs < PRELOAD_TTL_MS
                        }
                val tooShortMeta = !meetsMinPlayDuration(item.raw)
                val aggregate = com.tvmusic.config.MetaSettings.preferAggregate
                // C 投机换源：主源解析的同时并行竞速探备选（未命中缓存且非换源重试时才需要）。
                // 主源成功 → 结果进 preloadCache 备用；主源失败 → 换源近乎零额外等待。
                if (!forceFallback && cached == null &&
                    com.tvmusic.config.MetaSettings.fallbackOtherSource
                ) {
                    specFallback(rt, item, my, includeSelf = aggregate || tooShortMeta)
                }
                var url: String
                var headers: Map<String, String>
                var viaPlugin: String
                if (cached != null) {
                    // 命中缓存：url 在预加载阶段已保证非空/非 FLV，via 为实际取流插件
                    url = cached.url
                    headers = cached.headers
                    viaPlugin = cached.via ?: plugin
                } else {
                    // 走粘性引擎路由（平台 home 引擎）：1) 不与 primary 上的首页/详情/
                    // 搜索分页等流量互相排队（引擎 invokeLock 全局串行，主引擎被慢源
                    // 占住时点击播放会延迟几十秒才出声）；2) 复用该平台搜索时在 home
                    // 引擎上建立的模块级状态（cookie/token），解析更快更稳。
                    // 只在明确知道主源有问题时才跳过解析：
                    //   - forceFallback：取流失败重试，主源已知失败
                    //   - tooShortMeta：元数据时长已低于最低播放设定，主源必然被掐断
                    // aggregate（聚合搜索）**不影响**主源解析——它只是换源时的排序偏好。
                    // 主源解析成功后若可播，就直接播主源，不再强行换源。
                    // （历史 bug：aggregate 曾让 skipPrimary=true 导致主源被跳过解析，
                    // 用户反馈 djcsj 等能正常播放的主源被强制换源而失去播放）
                    val skipPrimaryResolve = forceFallback || tooShortMeta
                    val media = if (skipPrimaryResolve) null else resolveMediaSource(rt, plugin, item.raw)
                    // media == null 表示 getMediaSource 抛错（版权/会员/插件过期）。
                    val primaryFailed = media == null && !forceFallback && !tooShortMeta
                    url = media?.optString("url").orEmpty()
                    headers = media?.let { parseMediaHeaders(it) } ?: emptyMap()
                    viaPlugin = plugin
                    // FLV 直链 ExoPlayer 无对应解封装器，必然失败（换下一曲也一样）。
                    val flv = url.isNotBlank() &&
                        url.substringBefore('?').substringBefore('#').endsWith(".flv", ignoreCase = true)
                    // 主源**结果**不可用（解析为空 / FLV / 强制换源 / 主源失败）时才换取流地址。
                    // 主源成功解析出可播 URL 就直接播主源，不再强行换源。
                    val primaryUsable = url.isNotBlank() && !flv && !forceFallback && !tooShortMeta
                    if (!primaryUsable) {
                        if (com.tvmusic.config.MetaSettings.fallbackOtherSource) {
                            // 短播连环链进行中：排除已实际取流过仍受限的插件
                            val keyNow = entryKeyOf(item)
                            val excl = if (shortPlayFor == keyNow) shortPlayExclude else emptySet()
                            if (tooShortMeta) showSourceNotice("时长不足设定，换源中…")
                            consumeSpecFallback(
                                rt, item, my, excl,
                                includeSelf = aggregate || tooShortMeta
                            )?.let { fb ->
                                url = fb.url
                                headers = fb.headers
                                viaPlugin = fb.plugin
                            }
                        }
                        if (url.isBlank()) {
                            // 兜底也没找到可播来源：按原始失败原因提示。FLV 属硬限制不自动跳曲。
                            reportPlayError(
                                when {
                                    flv -> "该音源为 FLV 直链，当前设备暂不支持播放"
                                    forceFallback -> "播放失败：其他音源也没有该歌曲的可播放地址"
                                    primaryFailed -> "音源解析失败：可能是该歌曲有版权/会员限制，或插件需要更新"
                                    else -> "该音源未返回可播放地址（可能需配置用户变量或 VIP）"
                                },
                                autoSkip = !flv
                            )
                            return@launch
                        }
                    }
                }
                if (my != playSession.get()) return@launch
                if (viaPlugin != plugin) showSourceNotice("已换源「$viaPlugin」播放")
                playingVia = viaPlugin // 记录实际取流插件：短播连环重试时加入排除集
                // 供标题栏常驻提示：仅在实际取流插件 ≠ 队列条目来源插件（发生换源）时展示
                _uiState.update {
                    it.copy(playingVia = if (viaPlugin != item.plugin) viaPlugin else null)
                }
                // 记住本次请求头：通知栏封面（BitmapLoader）沿用同一套认证头
                activeHeaders = headers

                val mediaItem = MediaItem.Builder()
                    .setUri(url) // 真实音源 URL（当前条目经插件解析）
                    .setMediaId(
                        "${item.plugin}:" +
                            item.raw.optString("id", item.raw.optString("songmid", item.raw.optString("lid", "")))
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .setAlbumTitle(item.album)
                            .setArtworkUri(
                                shown.artwork.takeIf { it.startsWith("http") }
                                    ?.let { android.net.Uri.parse(it) }
                            )
                            .build()
                    )
                    .build()

                withContext(Dispatchers.Main) {
                    // 会话守卫：解析期间用户又点了别的歌，本次过期结果直接丢弃，
                    // 否则旧地址 setMediaItem 会覆盖新歌（"点新歌放旧歌"）
                    if (my != playSession.get()) return@withContext
                    // 每个音源可能带不同请求头：更新 DataSourceFactory（同一 DefaultMediaSourceFactory 实例）
                    val p = requirePlayer()
                    // 首次提交前拉起 PlaybackService 并连接会话（P0-1）：
                    // 否则播放态下没有 MediaSession/通知/前台服务，回桌面即被 LMK 回收
                    context?.let { ensureSessionConnection(it) }
                    // 每首歌都重置默认请求头：上一首若带 Cookie/Authorization，
                    // 空 headers 时不重置会把鉴权头带到下一首公开直链上。
                    mediaSourceFactory?.setDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setAllowCrossProtocolRedirects(true)
                            .setDefaultRequestProperties(headers)
                    )
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    pendingMediaId = mediaItem.mediaId // 提交后记录本次媒体身份，transition 据此定位队列条目
                    // 恢复播放：prepare 后先 seek 到上次进度再起播（未 ready 的 seek 会在 prepared 后生效）
                    if (startOffsetMs > 0) p.seekTo(startOffsetMs)
                    p.play()
                }
                // 通知栏封面由 PlaybackService 的 BitmapLoader 在媒体项切换时按需加载（带请求头，不阻塞播放）
                fetchLyric(item)
            } catch (e: com.tvmusic.runtime.PluginCallException) {
                // 插件 getMediaSource 抛错：多为目标平台版权/会员限制或插件过期，
                // 原始信息只有 JS 行号（如 "at getMediaSource (<input>:424)"），对用户无意义
                reportPlayError("音源解析失败：可能是该歌曲有版权/会员限制，或插件需要更新")
            } catch (e: Exception) {
                reportPlayError(e.message ?: "播放失败")
            }
        }
    }

    /** 解析插件 getMediaSource 返回的 headers 为有序 Map。 */
    private fun parseMediaHeaders(media: JSONObject): Map<String, String> {
        val h = media.optJSONObject("headers") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        val names = h.names() ?: return map
        for (i in 0 until names.length()) runCatching {
            map[names.getString(i)] = h.getString(names.getString(i))
        }
        return map
    }

    /**
     * 调用 [plugin] 的 getMediaSource 解析真实音源。
     * 返回解析后的 media JSON（可能 url 为空，表示插件无该曲地址）；
     * 返回 null 表示调用抛错（版权/会员限制或插件过期）。
     */
    private suspend fun resolveMediaSource(
        rt: PluginRuntime, plugin: String, raw: JSONObject,
        timeoutMs: Long = 60_000L
    ): JSONObject? = try {
        when (val result = rt.callParallel(plugin, PluginMethod.MEDIA_SOURCE, listOf(raw.toString(), quality), timeoutMs = timeoutMs)) {
            is JSONObject -> result
            is NotImplementedError -> JSONObject()
            else -> (result as? JSONArray)?.optJSONObject(0) ?: JSONObject()
        }
    } catch (e: com.tvmusic.runtime.PluginCallException) {
        android.util.Log.w("PlayerManager", "getMediaSource failed on $plugin: ${e.message}")
        null
    } catch (e: Exception) {
        android.util.Log.w("PlayerManager", "getMediaSource error on $plugin: ${e.message}")
        null
    }

    /** 换插件救场命中的可播放来源。 */
    private data class FallbackSource(val plugin: String, val url: String, val headers: Map<String, String>)

    /**
     * 主音源不可播放时，在其他已启用插件中按「曲名+歌手」搜索同一首歌，
     * 逐个尝试解析出可播放地址（跳过 FLV），命中第一个即返回。
     * 全程受 [playSession] 守卫：用户已切歌则立即放弃，绝不覆盖新歌。
     * 不改动队列条目本身——只借用其他插件的取流地址播放同一首歌。
     */
    private suspend fun tryFallbackSource(
        rt: PluginRuntime, item: QueueEntry, my: Int,
        exclude: Set<String> = emptySet(),
        silent: Boolean = false,
        includeSelf: Boolean = false
    ): FallbackSource? {
        val repo = repository ?: return null
        if (item.title.isBlank()) return null
        val order = com.tvmusic.config.SearchSettings.load(context ?: return null).sourceOrder
        val candidates = com.tvmusic.config.SearchSettings.ordered(
            repo.listEnabled()
                .filter { it.info != null && it.loadError == null }
                .map { it.info?.platform ?: it.name }
                .filter { includeSelf || it != item.plugin }
                .filter { it !in exclude },
            order
        )
        // 发起顺序：远程配置的优先换源插件置顶抢跑，其余按平台健康分降序
        // （死源/慢源垫底，不占竞速发起位）；同分保持 sourceOrder 次序。
        val prefer = com.tvmusic.config.MetaSettings.fallbackPreferPlugin
        val ranked = if (candidates.isEmpty()) candidates else
            candidates
                .map { it to (if (!com.tvmusic.config.MetaSettings.preferAggregate && prefer.isNotBlank() && it == prefer) 1000 else rt.fallbackScore(it, com.tvmusic.config.MetaSettings.fallbackHealthWeight)) }
                .sortedByDescending { it.second }
                .map { it.first }
        if (ranked.isEmpty()) return null
        if (!silent) showSourceNotice("正在尝试其他音源…")
        val artist = item.artist
        val cacheKeyPrefix = normalizeName(item.title) + "|" + normalizeName(artist) + "|"
        // 按 fallbackStrategy 分发到 4 种竞速策略。所有策略共享整链硬上限 FALLBACK_RACE_MS，
        // 到点仍未有 winner 就返回 null（不再分钟级空等）。
        val raceStarted = android.os.SystemClock.elapsedRealtime()
        val fastBatch = com.tvmusic.config.MetaSettings.fallbackFastBatch
        val strategy = com.tvmusic.config.MetaSettings.fallbackStrategy
        return coroutineScope {
            val winner = CompletableDeferred<FallbackSource>()
            var racers: List<kotlinx.coroutines.Job> = emptyList()
            when (strategy) {
                com.tvmusic.config.MetaSettings.STRATEGY_ALL_PARALLEL -> {
                    // 全候选同时启动：候选数少时最快，多时可能瞬时占满 QuickJS lane
                    racers = ranked.map { platform ->
                        launch(Dispatchers.Default) {
                            val started = android.os.SystemClock.elapsedRealtime()
                            probeFallbackPlatform(rt, item, platform, cacheKeyPrefix, artist, my)?.let {
                                android.util.Log.i(
                                    "PerfFallback",
                                    "candidate=$platform elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms"
                                )
                                winner.complete(it)
                            }
                        }
                    }
                }
                com.tvmusic.config.MetaSettings.STRATEGY_SEQUENTIAL -> {
                    // 严格顺序：按健康度排序依次尝试，前一候选成功即返回，节省引擎串行队列
                    val sequential = async(Dispatchers.Default) {
                        for (platform in ranked) {
                            if (my != playSession.get()) return@async null
                            val started = android.os.SystemClock.elapsedRealtime()
                            probeFallbackPlatform(rt, item, platform, cacheKeyPrefix, artist, my)?.let {
                                android.util.Log.i(
                                    "PerfFallback",
                                    "candidate=$platform elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms"
                                )
                                return@async it
                            }
                        }
                        null
                    }
                    val first = withTimeoutOrNull(FALLBACK_RACE_MS) { sequential.await() }
                    android.util.Log.i(
                        "PerfFallback",
                        "strategy=sequential winner=${first?.plugin ?: "none"} total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms candidates=${ranked.size}"
                    )
                    sequential.cancel()
                    return@coroutineScope first
                }
                com.tvmusic.config.MetaSettings.STRATEGY_PREFER_FIRST -> {
                    // 只跑健康度第 1 名：极致低延迟，容忍换源失败率升高
                    if (ranked.isNotEmpty()) {
                        val only = async(Dispatchers.Default) {
                            val platform = ranked.first()
                            val started = android.os.SystemClock.elapsedRealtime()
                            probeFallbackPlatform(rt, item, platform, cacheKeyPrefix, artist, my)?.let {
                                android.util.Log.i(
                                    "PerfFallback",
                                    "candidate=$platform elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms"
                                )
                                it
                            }
                        }
                        val first = withTimeoutOrNull(FALLBACK_RACE_MS) { only.await() }
                        android.util.Log.i(
                            "PerfFallback",
                            "strategy=preferFirst winner=${first?.plugin ?: "none"} total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms candidates=${ranked.size}"
                        )
                        only.cancel()
                        return@coroutineScope first
                    }
                }
                else -> {
                    // staggered（默认）：健康度前 fastBatch 名立即启动，其余错峰 350ms
                    racers = ranked.mapIndexed { index, platform ->
                        launch(Dispatchers.Default) {
                            if (index >= fastBatch) kotlinx.coroutines.delay(FALLBACK_STAGGER_MS)
                            val started = android.os.SystemClock.elapsedRealtime()
                            probeFallbackPlatform(rt, item, platform, cacheKeyPrefix, artist, my)?.let {
                                android.util.Log.i(
                                    "PerfFallback",
                                    "candidate=$platform elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms"
                                )
                                winner.complete(it)
                            }
                        }
                    }
                }
            }
            // staggered / allParallel 走整链硬上限 + winner.await
            val first = withTimeoutOrNull(FALLBACK_RACE_MS) { winner.await() }
            android.util.Log.i(
                "PerfFallback",
                "strategy=$strategy winner=${first?.plugin ?: "none"} total=${android.os.SystemClock.elapsedRealtime() - raceStarted}ms candidates=${ranked.size}"
            )
            racers.forEach { it.cancel() }
            first
        }
    }

    /** 单平台换源探测：搜索命中（同轮缓存复用）→ 逐条解析，产出第一个可播 URL。 */
    private suspend fun probeFallbackPlatform(
        rt: PluginRuntime, item: QueueEntry, platform: String,
        cacheKeyPrefix: String, artist: String, my: Int
    ): FallbackSource? {
        if (my != playSession.get()) return null
        // 优先复用聚合搜索缓存的命中（跳过 search 的 2~5 秒）；未命中才真实搜索。
        // 连环换源重试时复用同一轮的搜索命中，省去 2-5 秒的重复搜索；
        // 候选调用统一 8s 超时（含引擎排队），慢/卡平台快速跳过，不拖整条链。
        // 空列表=确认无匹配（负缓存）；调用失败(null)不缓存，下一轮可重试。
        val matches = aggMatchesFor(item.title, platform)
            ?: fallbackSearchCache[cacheKeyPrefix + platform]
            ?: searchMusicOn(rt, platform, item.title, artist)?.also {
                // 超限时只清约一半旧条目，而非全清：保留近期热点，避免命中率骤降。
                // ConcurrentHashMap 迭代顺序不稳定，"一半"是近似值，此处可接受。
                if (fallbackSearchCache.size > 32) {
                    val it0 = fallbackSearchCache.keys.iterator()
                    repeat(fallbackSearchCache.size / 2) {
                        if (it0.hasNext()) { it0.next(); it0.remove() }
                    }
                }
                fallbackSearchCache[cacheKeyPrefix + platform] = it
            }
            ?: return null
        // 同平台按命中顺序逐条试：第一条可能是翻唱/伴奏版且不可播，不能整平台放弃
        for (matched in matches) {
            if (my != playSession.get()) return null
            if (!meetsMinPlayDuration(matched)) continue
            val media = resolveMediaSource(rt, platform, matched, timeoutMs = FALLBACK_CALL_TIMEOUT_MS)
                ?: continue
            val url = media.optString("url")
            if (url.isBlank()) continue
            if (url.substringBefore('?').substringBefore('#').endsWith(".flv", ignoreCase = true)) continue
            return FallbackSource(platform, url, parseMediaHeaders(media))
        }
        return null
    }

    /**
     * C 投机换源：play() 解析主源的同时并行竞速探备选。
     * 主源成功 → 竞速结果进 preloadCache（重播/重进秒出）；
     * 主源失败 → consumeSpecFallback 直接等竞速结果，换源近乎零额外等待。
     */
    private var specDeferred: Deferred<FallbackSource?>? = null

    @Volatile
    private var specKey: String? = null

    private fun specFallback(
        rt: PluginRuntime, item: QueueEntry, my: Int,
        includeSelf: Boolean = false
    ) {
        specDeferred?.cancel()
        val key = entryKeyOf(item)
        specKey = key
        specDeferred = scope.async(Dispatchers.Default) {
            val fb = tryFallbackSource(rt, item, my, silent = true, includeSelf = includeSelf)
            if (fb != null && my == playSession.get() && key == specKey) {
                synchronized(preloadCache) {
                    if (!preloadCache.containsKey(key)) {
                        preloadCache[key] = PreloadedMedia(
                            fb.url, fb.headers, quality,
                            android.os.SystemClock.elapsedRealtime(), fb.plugin
                        )
                    }
                }
            }
            fb
        }
    }

    /**
     * 消费投机竞速结果：与本次同曲同会话且无换源排除集时直接等它（多半已热好）；
     * 否则作废投机、现场扫描（排除集/换歌后投机结果可能来自已证明受限的插件，不能要）。
     */
    private suspend fun consumeSpecFallback(
        rt: PluginRuntime, item: QueueEntry, my: Int, exclude: Set<String>,
        includeSelf: Boolean = false
    ): FallbackSource? {
        val spec = specDeferred
        val key = entryKeyOf(item)
        if (spec != null && specKey == key && exclude.isEmpty()) {
            val early = runCatching { withTimeout(FALLBACK_RACE_MS) { spec.await() } }.getOrNull()
            if (early != null && my == playSession.get()) return early
        }
        specDeferred?.cancel()
        specDeferred = null
        return tryFallbackSource(rt, item, my, exclude = exclude, includeSelf = includeSelf)
    }

    /**
     * 在 [platform] 上搜「title artist」，返回标题（近似）匹配的前 [FALLBACK_MAX_MATCHES] 条 music 条目 raw。
     * 多词无结果时降级为纯歌名再搜一次（部分插件按 AND 匹配，「歌名 歌手」会搜空）。
     * 返回 null = 调用失败（网络/超时，可重试，不做负缓存）；空列表 = 确认无匹配。
     */
    private suspend fun searchMusicOn(
        rt: PluginRuntime, platform: String, title: String, artist: String
    ): List<JSONObject>? {
        val want = normalizeName(title)
        val primary = searchOnce(rt, platform, if (artist.isBlank()) title else "$title $artist", want)
        if (!primary.isNullOrEmpty() || primary == null || artist.isBlank()) return primary
        return searchOnce(rt, platform, title, want)
    }

    private suspend fun searchOnce(
        rt: PluginRuntime, platform: String, query: String, want: String
    ): List<JSONObject>? {
        val res = try {
            rt.callParallel(platform, PluginMethod.SEARCH, listOf(query, "1", "music"), timeoutMs = FALLBACK_CALL_TIMEOUT_MS)
        } catch (e: Exception) {
            android.util.Log.w("PlayerManager", "fallback search failed on $platform ($query): ${e.message}")
            return null
        }
        if (res is NotImplementedError) return emptyList()
        val arr = (res as? JSONObject)?.optJSONArray("data") ?: (res as? JSONArray) ?: return emptyList()
        val out = ArrayList<JSONObject>(FALLBACK_MAX_MATCHES)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = normalizeName(o.optString("title", ""))
            if (t.isNotEmpty() && (t == want || t.contains(want) || want.contains(t))) {
                if (o.optString("platform").isBlank()) o.put("platform", platform)
                out.add(o)
                if (out.size >= FALLBACK_MAX_MATCHES) break
            }
        }
        return out
    }

    /** 插件 duration 可能是秒或毫秒；>10000 视为毫秒。 */
    private fun rawDurationMs(raw: org.json.JSONObject): Long {
        val d = raw.optLong("duration", 0L)
        if (d <= 0L) return 0L
        return if (d > 10_000L) d else d * 1000L
    }

    /** 已知时长低于最低播放设定则不合格；未知时长放行（等 READY 再判）。 */
    private fun meetsMinPlayDuration(raw: org.json.JSONObject): Boolean {
        val min = com.tvmusic.config.MetaSettings.minPlaySeconds
        if (min <= 0) return true
        val ms = rawDurationMs(raw)
        if (ms <= 0L) return true
        return ms >= min * 1000L - 1000L
    }

    /** 歌名归一化：转小写、去掉空格与常见标点，便于跨插件标题比对。 */
    private fun normalizeName(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    fun skipTo(index: Int) {
        val st = _uiState.value
        if (index in st.queue.indices && index != st.queueIndex) {
            val entry = st.queue[index]
            _uiState.update { it.copy(queueIndex = index) }
            // 当前索引变化：调度防抖写入恢复快照（next/prev 最终都走到这里）
            scheduleResumeSave()
            play(entry.plugin, entry, st.queue, index)
        }
    }

    fun next() {
        val st = _uiState.value
        if (st.playMode == PlayMode.SHUFFLE) {
            val idx = shuffleTarget(st)
            // 队列只剩一首时随机目标无效：重播当前曲，给"下一首"明确反馈而不是无反应
            if (idx >= 0) { skipTo(idx); return }
            replayCurrent(); return
        }
        val target = if (st.queueIndex + 1 in st.queue.indices) st.queueIndex + 1 else 0
        // 队列只有一首时目标==当前索引，skipTo 会静默跳过（远程端推歌场景点下一首像"无效"）
        if (target == st.queueIndex) { replayCurrent(); return }
        skipTo(target)
    }

    fun prev() {
        val st = _uiState.value
        val target = if (st.queueIndex - 1 >= 0) st.queueIndex - 1 else st.queue.lastIndex
        // 同 next：单曲队列点"上一首"从头重播而非无反应
        if (target == st.queueIndex) { replayCurrent(); return }
        skipTo(target)
    }

    // ---------------- 播放模式 ----------------

    /** 设置播放模式并持久化。 */
    fun setPlayMode(mode: PlayMode) {
        qualityPrefs?.edit()?.putString(KEY_PLAY_MODE, mode.name)?.apply()
        if (mode == PlayMode.SHUFFLE) rebuildShuffle(_uiState.value.queue.size)
        _uiState.update { it.copy(playMode = mode) }
    }

    /** 循环切换：顺序 → 单曲循环 → 随机 → 顺序。 */
    fun cyclePlayMode(): PlayMode {
        val next = when (_uiState.value.playMode) {
            PlayMode.ORDER -> PlayMode.LOOP_ONE
            PlayMode.LOOP_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.ORDER
        }
        setPlayMode(next)
        return next
    }

    // ---------------- 随机播放（洗牌模式）----------------

    /**
     * 随机播放采用「洗牌队列」而非逐曲随机：
     * 旧实现每次 Random.nextInt(queue.size) 只排除当前一首，小队列（<20）时
     * 重复率极高——5 首歌随机 10 次平均出现 2-3 次重复。
     * 改为 Fisher-Yates 洗牌一次生成完整乱序队列，顺序消费；播完一轮重新洗牌。
     */
    private var shuffleOrder: IntArray = IntArray(0)
    private var shufflePos = -1

    /** 重新生成洗牌队列（切歌/队列变化/播完一轮时调用）。 */
    private fun rebuildShuffle(queueSize: Int) {
        if (queueSize <= 0) { shuffleOrder = IntArray(0); shufflePos = -1; return }
        shuffleOrder = IntArray(queueSize) { it }
        for (i in queueSize - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            shuffleOrder[i] = shuffleOrder[j].also { shuffleOrder[j] = shuffleOrder[i] }
        }
        shufflePos = -1 // 首曲尚未选定
    }

    /** 随机模式：从洗牌队列取下一曲；播完一轮重新洗牌。 */
    private fun shuffleTarget(st: PlayerUiState): Int {
        if (st.queue.size <= 1) return -1
        if (shuffleOrder.size != st.queue.size) rebuildShuffle(st.queue.size)
        if (shuffleOrder.isEmpty()) return -1
        if (shufflePos < 0) {
            // 首次启动：从洗牌队列首部开始
            shufflePos = 0
        } else {
            shufflePos = (shufflePos + 1) % shuffleOrder.size
            // 播完一轮：重新洗牌，避免下一轮回播顺序与本轮相同
            if (shufflePos == 0) {
                rebuildShuffle(st.queue.size)
                // rebuildShuffle 会把 shufflePos 重置为 -1（首曲未选定语义），
                // 而此处紧接着就要取本分支确定好的目标——必须拉回 0，
                // 否则 shuffleOrder[-1] 崩溃（ArrayIndexOutOfBoundsException，
                // 2026-10-01 真机 192.168.1.45 随机模式播完一轮复现）。
                shufflePos = 0
            }
        }
        return shuffleOrder[shufflePos]
    }

    // ---------------- 试听截断提前识别 ----------------

    /** 典型试听截断总时长（秒）：流总时长落在 ±8s 内视为版权试听掐断。 */
    private val CUTOFF_SIGNATURES_SEC = intArrayOf(15, 30, 45, 60, 90)

    /** 已触发过「提前换源」的条目 key：每条目一次，防 params 抖动重复触发。 */
    private val earlyCutoffFired: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun isCutoffDuration(durMs: Long): Boolean =
        durMs > 0 && CUTOFF_SIGNATURES_SEC.any { kotlin.math.abs(durMs - it * 1000L) <= 8_000L }

    /**
     * 截断流提前识别：播放器报出的总时长恰为典型试听截断长度，而条目元数据时长
     * 明显更长（或未知）→ 不等掐断段播完，立即换源重播同一首（省 15-90 秒死等）。
     * 复用短播连环状态（同一重试预算与排除集），不在已证明截断的插件间来回跳。
     */
    private fun maybeEarlyCutoffSwitch(streamDurMs: Long) {
        if (!com.tvmusic.config.MetaSettings.fallbackOtherSource) return
        if (com.tvmusic.config.MetaSettings.minPlaySeconds <= 0) return
        val minMs = com.tvmusic.config.MetaSettings.minPlaySeconds * 1000L
        val shortVsMin = minMs > 0 && streamDurMs > 0 && streamDurMs < minMs
        if (!isCutoffDuration(streamDurMs) && !shortVsMin) return
        val st = _uiState.value
        val entry = st.current ?: return
        val key = entryKeyOf(entry)
        if (key in earlyCutoffFired) return
        // 只处理刚提交的媒体：换源/切歌瞬间 params 可能还属于上一首
        val p = player ?: return
        if (p.currentMediaItem?.mediaId != pendingMediaId) return
        // 元数据时长本就 ≈ 流时长且本身已达最低时长：是真短歌不是截断
        val rawDurMs = rawDurationMs(entry.raw)
        if (rawDurMs > 0 && rawDurMs <= streamDurMs + 15_000 && rawDurMs >= minMs) return
        if (rawDurMs > 0 && rawDurMs < minMs) { /* 元数据已不足设定，必须换源 */ }
        else if (!shortVsMin && rawDurMs > 0 && rawDurMs <= streamDurMs + 15_000) return
        earlyCutoffFired.add(key)
        // 超限时只清约一半旧条目而非全清：保留近期触发记录，防止刚播过的截断曲被重复识别。
        if (earlyCutoffFired.size > 64) {
            val it0 = earlyCutoffFired.iterator()
            repeat(earlyCutoffFired.size / 2) {
                if (it0.hasNext()) { it0.next(); it0.remove() }
            }
        }
        if (shortPlayFor != key) {
            shortPlayFor = key
            shortPlayCount = 0
            shortPlayExclude.clear()
        }
        playingVia?.let { shortPlayExclude.add(it) }
        if (shortPlayCount >= SHORT_PLAY_MAX_RETRY) return
        shortPlayCount++
        fallbackTriedFor = null
        fallbackTriedCount = 0
        android.util.Log.i("PlayerManager", "cutoff stream [$key] dur=${streamDurMs}ms, early switch via other source")
        showSourceNotice("检测到试听截断，提前换源…")
        play(entry.plugin, entry, st.queue, st.queueIndex, forceFallback = true)
    }

    /** 一首播完后的推进逻辑（在主线程由 playerListener 调用）。 */
    private fun handleMediaEnded() {
        val st = _uiState.value
        if (st.queue.isEmpty()) return
        // 版权短播检测：走到 ENDED 说明不是用户主动切歌（切歌走 skipTo/playAt）。
        // 播放远低于「最低播放时长」就自然结束，多为版权试听掐断 → 逐个换其他插件
        // 重播同一首（只换取流地址，队列/歌单不变）。已受限插件进排除集不复用，
        // 连续 [SHORT_PLAY_MAX_RETRY] 次仍短播则放弃（防死循环），按原逻辑推进队列。
        val minMs = com.tvmusic.config.MetaSettings.minPlaySeconds * 1000L
        val pos = player?.currentPosition ?: 0L
        val entry = st.current
        val key = entry?.let { entryKeyOf(it) }
        // 歌曲真实时长只能取插件元数据 raw.duration（秒）：版权截断流的 ExoPlayer
        // duration≈截断点（如 30s），用它比较会永远不满足"歌曲本身比阈值长"而漏检
        // 与 rawDurationMs 保持同一启发式：插件 duration 可能是秒或毫秒（>10000 视为毫秒）
        val realDurMs = entry?.let { rawDurationMs(it.raw) } ?: 0L
        val streamDurMs = player?.duration?.takeIf { it > 0 } ?: 0L
        // 受限判定：元数据时长可用时以元数据为准；缺失时要求掐断点/流总时长
        // 命中典型试听截断长度才算受限——真短歌（如 40s 单曲播完全程）不再触发
        // 整链无效换源（换过去还是同一首短歌，白等几分钟）。pos 从 0 起算覆盖 0 秒掐断。
        val looksRestricted = if (realDurMs > 0) realDurMs > minMs + 1000
            else isCutoffDuration(pos) || isCutoffDuration(streamDurMs)
        if (entry != null && key != null &&
            com.tvmusic.config.MetaSettings.fallbackOtherSource && minMs > 0 &&
            pos in 0 until minMs && looksRestricted
        ) {
            if (shortPlayFor != key) {
                shortPlayFor = key
                shortPlayCount = 0
                shortPlayExclude.clear()
            }
            // 本次实际取流的插件（可能=原插件，也可能=已换过的备选）已证明受限
            playingVia?.let { shortPlayExclude.add(it) }
            if (shortPlayCount < SHORT_PLAY_MAX_RETRY) {
                shortPlayCount++
                fallbackTriedFor = null // 连环链内取流再出错允许继续救场（换下一个插件）
                fallbackTriedCount = 0
                android.util.Log.i(
                    "PlayerManager",
                    "short play ended [$key] pos=${pos}ms < ${minMs}ms, " +
                        "retry #${shortPlayCount} via other source (excluded=$shortPlayExclude)"
                )
                showSourceNotice("疑似版权受限，换源中…")
                play(entry.plugin, entry, st.queue, st.queueIndex, forceFallback = true)
                return
            }
            showSourceNotice("各音源均受限，已跳过")
            // 重试耗尽：强制推进到下一曲，绝不能走 LOOP_ONE/单曲的 replayCurrent——
            // 否则会重播同一首受限曲 → 又短播 → 又重播，无限循环刷通知。
            // 队列只剩一首时无处可跳，直接停在 ENDED（不重播）。
            if (st.queue.size <= 1) return
            val nxt = if (st.playMode == PlayMode.SHUFFLE) shuffleTarget(st)
                else (st.queueIndex + 1).let { if (it in st.queue.indices) it else 0 }
            if (nxt >= 0 && nxt != st.queueIndex) skipTo(nxt) else next()
            return
        }
        when (st.playMode) {
            PlayMode.LOOP_ONE -> replayCurrent()
            PlayMode.SHUFFLE -> {
                val idx = shuffleTarget(st)
                if (idx >= 0) skipTo(idx) else replayCurrent()
            }
            PlayMode.ORDER -> {
                // 队列只剩一首时 next() 的目标等于当前索引，skipTo 会直接跳过，
                // 播放器将永远停在 ENDED 不再出声——此时从头重播。
                if (st.queue.size <= 1) replayCurrent() else next()
            }
        }
    }

    /** 从头重播当前曲目（不重新解析音源）。可能从 HTTP 线程调用，ExoPlayer 操作切主线程。 */
    private fun replayCurrent() {
        ticker.post {
            player?.let { p ->
                p.seekTo(0)
                p.playWhenReady = true
            }
        }
    }

    fun playPause() {
        // 可能从 HTTP 线程调用：ExoPlayer 只能在主线程访问
        ticker.post {
            val p = player ?: return@post
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    /** 无条件暂停（退出应用时调用；playPause 在已暂停场景会误恢复播放）。 */
    fun pause() {
        ticker.post { player?.pause() }
    }

    /** 收藏 / 取消收藏当前曲目（可指定目标专辑），返回该专辑内收藏后的状态。 */
    fun toggleFavorite(listId: String = com.tvmusic.data.PlaybackStore.DEFAULT_FAV_ID): Boolean {
        val entry = _uiState.value.current ?: return false
        val fav = playbackStore?.toggleFavorite(entry.raw, listId) ?: false
        // 与函数契约一致：isFavorite 表示"该条目是否在任意专辑内"，收藏/取消后重新查询
        _uiState.update { it.copy(isFavorite = playbackStore?.isFavorite(entry.raw) ?: fav) }
        return fav
    }

    fun isCurrentFavorite(): Boolean {
        val entry = _uiState.value.current ?: return false
        return playbackStore?.isFavorite(entry.raw) ?: false
    }

    fun seek(toMs: Long) {
        ticker.post {
            player?.seekTo(toMs.coerceIn(0, Long.MAX_VALUE))
        }
    }

    /** 相对快进/快退：以播放器实时位置为基准（而非 UI 最旧 1s 的 tick 快照），连按不丢失。 */
    fun seekRelative(deltaMs: Long) {
        ticker.post {
            val p = player ?: return@post
            val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, dur))
        }
    }

    /** 相对调整音量（供 web 端调用）。 */
    fun adjustVolume(delta: Float) {
        ticker.post {
            val p = player ?: return@post
            p.volume = (p.volume + delta).coerceIn(0f, 1f)
        }
    }

    // ---------------- 进程被杀恢复（resume.json） ----------------

    /** 上次会话的恢复快照（启动时异步加载；恢复播放后清空）。 */
    @Volatile
    private var resumeSnapshot: JSONObject? = null

    private val _resumeAvailable = MutableStateFlow(false)
    /** 是否存在可恢复的上次播放（供首页「继续播放」对话框响应式订阅）。 */
    val resumeAvailable: StateFlow<Boolean> = _resumeAvailable.asStateFlow()

    /** 防抖写恢复快照：1s 内队列/索引/暂停的多次变化合并为一次落盘。 */
    private val resumeSaver = Handler(Looper.getMainLooper())
    private val saveResumeRunnable = Runnable { flushResumeNow() }

    private fun scheduleResumeSave() {
        resumeSaver.removeCallbacks(saveResumeRunnable)
        resumeSaver.postDelayed(saveResumeRunnable, 1000)
    }

    /**
     * 立即落盘当前播放快照（应用退出 / release 前调用，防抖等不及的场景）。
     * 必须在主线程调用（读取 player.currentPosition）。
     */
    fun flushResumeNow() {
        resumeSaver.removeCallbacks(saveResumeRunnable)
        val store = playbackStore ?: return
        val st = _uiState.value
        if (st.queue.isEmpty() || st.queueIndex !in st.queue.indices) return
        val arr = JSONArray()
        st.queue.forEach { arr.put(it.raw) }
        val json = JSONObject()
            .put("queue", arr)
            .put("index", st.queueIndex)
            .put("positionMs", player?.currentPosition ?: 0L)
        // 来源标签一并落盘：退出重启恢复播放后，迷你播放器/播放页仍显示「插件 · 歌单」
        if (!st.sourceLabel.isNullOrBlank()) json.put("sourceLabel", st.sourceLabel)
        store.saveResume(json)
    }

    /** 启动时异步读取 resume.json（Application 调用，避免在主线程做文件 IO）。 */
    fun loadResumeAsync() {
        scope.launch(Dispatchers.IO) {
            val json = playbackStore?.loadResume() ?: return@launch
            val queue = json.optJSONArray("queue")
            if (queue == null || queue.length() == 0) return@launch
            resumeSnapshot = json
            _resumeAvailable.value = true
        }
    }

    /** 是否有可恢复的上次播放。 */
    fun hasResume(): Boolean = _resumeAvailable.value

    /**
     * 从快照恢复播放：重建队列 → 定位到 index → 走一次 play 流程（复用 playSession
     * 守卫与解析链路，解析结果带过期丢弃）→ prepare 后 seekTo 上次进度再起播。
     * 恢复后立即清除快照，避免重复弹窗/重复恢复。
     */
    fun resumePlayback() {
        val snap = resumeSnapshot ?: return
        try {
            val arr = snap.optJSONArray("queue") ?: return
            val entries = (0 until arr.length()).mapNotNull { i ->
                val raw = arr.optJSONObject(i) ?: return@mapNotNull null
                val plugin = raw.optString("platform")
                if (plugin.isBlank()) null else QueueEntry(plugin, raw)
            }
            if (entries.isEmpty()) return
            val index = snap.optInt("index", 0).coerceIn(0, entries.lastIndex)
            val positionMs = snap.optLong("positionMs", 0L).coerceAtLeast(0)
            resumeSnapshot = null
            _resumeAvailable.value = false
            playbackStore?.clearResume()
            val target = entries[index]
            val label = snap.optString("sourceLabel", "").ifBlank { null }
            play(target.plugin, target, entries, index, positionMs, source = label)
        } catch (e: Exception) {
            android.util.Log.w("PlayerManager", "resume failed: ${e.message}")
        }
    }

    /** H8：内存紧张时收缩缓存（由 TvMusicApp.onTrimMemory 调用）。
     *  清空换源预加载缓存与负缓存，释放内存；不影响正在播放的曲目。 */
    fun onTrimMemory() {
        synchronized(preloadCache) { preloadCache.clear() }
        fallbackSearchCache.clear()
        aggregateCache.clear()
        android.util.Log.i("PlayerManager", "onTrimMemory: preload/fallback caches cleared")
    }

    fun release() {
        // 释放前立即落盘恢复快照（此时队列/进度还有效），顺带清掉防抖任务
        flushResumeNow()
        disconnectSession()
        ticker.removeCallbacksAndMessages(null)
        lyricJob?.cancel()
        lyricJob = null
        lyricNoticeJob?.cancel()
        lyricNoticeJob = null
        sourceNoticeJob?.cancel()
        sourceNoticeJob = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        synchronized(preloadCache) { preloadCache.clear() }
        preloadTriggeredFor = null
        fallbackSearchCache.clear()
        aggregateCache.clear()
        shortPlayExclude.clear()
        _uiState.value = PlayerUiState()
    }

    // ---------------- 歌词 ----------------

    /** 歌词请求代数：防止慢响应覆盖新歌歌词（旧请求返回时已过期，直接丢弃）。 */
    @Volatile
    private var lrcGeneration = 0

    /** 当前歌词解析协程：切歌时取消，避免旧解析堵在引擎串行队列里拖慢新歌。 */
    private var lyricJob: kotlinx.coroutines.Job? = null

    /** 提示自动清除协程：各自计时，同类新提示替换旧提示并重新计时。 */
    private var lyricNoticeJob: kotlinx.coroutines.Job? = null
    private var sourceNoticeJob: kotlinx.coroutines.Job? = null

    /** 歌词/封面兜底提示：lrc.cx 兜底进行中/成功/失败的可见反馈，显示 20 秒后自动消失。 */
    private fun showLyricNotice(text: String) {
        lyricNoticeJob?.cancel()
        _uiState.update { it.copy(lyricNotice = text) }
        lyricNoticeJob = scope.launch {
            kotlinx.coroutines.delay(20_000)
            _uiState.update { it.copy(lyricNotice = null) }
        }
    }

    /** 音源切换提示：与歌词提示分行显示、互不覆盖，同样 20 秒后自动消失。 */
    private fun showSourceNotice(text: String) {
        sourceNoticeJob?.cancel()
        _uiState.update { it.copy(sourceNotice = text) }
        sourceNoticeJob = scope.launch {
            kotlinx.coroutines.delay(20_000)
            _uiState.update { it.copy(sourceNotice = null) }
        }
    }

    /** 最近一次已发起歌词请求的条目：onMediaItemTransition 与 play() 末尾都会触发，按条目去重。 */
    @Volatile
    private var lastLyricKey: String? = null

    /** M20：起播耗时起点（play() 发起时刻 elapsedRealtime）。READY 首次命中后记录并清零。 */
    @Volatile
    private var playStartMs = 0L

    private fun fetchLyric(entry: QueueEntry) {
        val key = entryKeyOf(entry)
        if (key == lastLyricKey) return // 同一首的重复触发（transition + play 末尾）不重复占用引擎
        lastLyricKey = key
        val gen = ++lrcGeneration
        lyricJob?.cancel()
        lyricJob = scope.launch(Dispatchers.Default) {
            try {
                val rt = runtime ?: return@launch
                // 与 getMediaSource 同理：走粘性 home 引擎，避免挤占 primary。
                // 歌词不是关键路径：15s 超时足够，避免无响应的 getLyric 长期占住引擎串行队列
                val result = try {
                    rt.callParallel(
                        entry.plugin, PluginMethod.LYRIC, listOf(entry.raw.toString()),
                        timeoutMs = 15_000
                    )
                } catch (_: Exception) {
                    null
                }
                if (gen != lrcGeneration) return@launch // 已切歌，丢弃过期歌词
                var lines = result?.let { parseLyricResult(it) } ?: emptyList()
                // 插件没返回歌词且开启兜底时，按 曲名/歌手/专辑 从 lrc.cx 补齐
                if (lines.isEmpty() && com.tvmusic.config.MetaSettings.isEnabled) {
                    showLyricNotice("正在搜索歌词…")
                    val fallback = try {
                        fetchFallbackLyric(entry) // null = 网络失败（已重试），空列表 = 确实没有
                    } catch (_: Exception) {
                        null
                    }
                    lines = fallback ?: emptyList()
                    when {
                        fallback == null -> showLyricNotice("lrc.cx 请求失败")
                        lines.isNotEmpty() -> showLyricNotice("歌词已用 lrc.cx 补充")
                        else -> showLyricNotice("未找到该歌曲歌词")
                    }
                }
                _uiState.update { it.copy(lrcLines = lines.sortedBy { it.timeMs }, lrcIndex = -1) }
            } catch (_: Exception) {
                if (gen == lrcGeneration) {
                    _uiState.update { it.copy(lrcLines = emptyList()) }
                }
            }
        }
    }

    /** 回退封面：条目无 artwork/coverImg 且开启补全时，返回带入 lrc.cx 合成封面地址的新条目。 */
    private fun withFallbackArtwork(item: QueueEntry): QueueEntry {
        if (!com.tvmusic.config.MetaSettings.isEnabled) return item
        if (item.artwork.isNotBlank()) return item
        val fb = com.tvmusic.config.MetaSettings.coverUrl(item.title, item.artist, item.album)
        if (fb.isBlank()) return item
        return item.copy(artwork = fb)
    }

    /**
     * lrc.cx /lyrics 兜底：通常直接返回 LRC 文本，也兼容批量 JSON 形态（取第一条）。
     * 返回 null 表示网络/TLS/超时失败（内部已对全部候选重试一轮）；返回空列表表示确实没有歌词。
     * 候选按优先级：完整歌手 → 净化歌手（去掉“·专辑”等后缀）→ 仅曲名。
     */
    private suspend fun fetchFallbackLyric(entry: QueueEntry): List<LrcLine>? {
        // M2 负缓存：同一曲目兜底失败后 10 分钟内不再重试，防弱网下连续曲目重试风暴
        val negKey = entryKeyOf(entry)
        lrcNegativeCache[negKey]?.let {
            if (android.os.SystemClock.elapsedRealtime() - it < LRC_NEG_TTL_MS) return null
        }
        // M2：候选只试前 2 条（原为全候选 × 2 轮，最坏 2×N×20s）
        val urls = com.tvmusic.config.MetaSettings.lyricsCandidates(entry.title, entry.artist, entry.album)
            .take(LRC_FALLBACK_CANDIDATES)
        if (urls.isEmpty()) return emptyList()
        var networkFailed = false
        repeat(2) {
            for (url in urls) {
                try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "MusicFreeTV/1.0")
                        .build()
                    // M2：歌词兜底走短超时客户端（8s），不再用 20s 的 metaHttp
                    val lines = lyricsHttp.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: return@use emptyList()
                        if (!resp.isSuccessful) return@use emptyList()
                        // 仅当"数组首元素是对象且带非空 lyrics"时才按 JSON 响应处理。
                        // 直接裸试 JSONArray 会把 [Verse] 这类 LRC 行宽松解析成 ["Verse"]，
                        // optJSONObject(0) 为 null 会提前 return 把真实歌词整个丢弃（实际发生过）。
                        val json = runCatching { org.json.JSONArray(body) }.getOrNull()
                        val parsed = json
                            ?.takeIf { it.length() > 0 && it.optJSONObject(0) != null }
                            ?.let { it.optJSONObject(0)!!.optString("lyrics") }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { parseLrc(it) }
                            ?: parseLrc(body)
                        parsed
                    }
                    if (lines.isNotEmpty()) {
                        lrcNegativeCache.remove(negKey)
                        return lines
                    }
                } catch (_: Exception) {
                    networkFailed = true // 网络/TLS/超时：本候选失败，继续下一候选/下一轮
                }
            }
            if (networkFailed) kotlinx.coroutines.delay(400)
        }
        if (networkFailed) {
            lrcNegativeCache[negKey] = android.os.SystemClock.elapsedRealtime()
            // 上限保护：超 64 条时清一半（与 fallbackSearchCache 同策略）
            if (lrcNegativeCache.size > 64) {
                val it = lrcNegativeCache.keys.iterator()
                var n = lrcNegativeCache.size / 2
                while (it.hasNext() && n > 0) { it.next(); it.remove(); n-- }
            }
            return null
        }
        return emptyList()
    }

    /**
     * 解析 getLyric 返回值。标准协议为 { rawLrc, translation }（均为 LRC 文本，参考 musicfree 插件协议）；
     * 也兼容扩展形态 { lyricList: {time, lyric, translation?}[] } 与独立的 translationList: {time, translation}[]。
     */
    private fun parseLyricResult(result: Any): List<LrcLine> {
        val obj = result as? JSONObject ?: return emptyList()
        val raw = obj.optString("rawLrc")
        val translation = obj.optString("translation")
        val separateTrans = extractTranslations(obj.optJSONArray("translationList"))

        if (raw.isNotBlank()) {
            val base = parseLrc(raw)
            val fromText = if (translation.isNotBlank()) parseLrc(translation) else emptyList()
            return mergeTranslation(base, fromText, separateTrans)
        }
        val arr = obj.optJSONArray("lyricList") ?: return emptyList()
        val base = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val time = (o.optDouble("time") * 1000).toLong().coerceAtLeast(0)
            val lyric = o.optString("lyric")
            if (lyric.isBlank()) return@mapNotNull null
            LrcLine(time, lyric, o.optString("translation").takeIf { it.isNotBlank() })
        }
        return mergeTranslation(base, emptyList(), separateTrans)
    }

    /** 结构化翻译数组 { time, translation } -> LrcLine[time, 译文]。 */
    private fun extractTranslations(arr: JSONArray?): List<LrcLine> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("translation").takeIf { it.isNotBlank() }
            if (text == null) null
            else LrcLine((o.optDouble("time") * 1000).toLong().coerceAtLeast(0), text)
        }
    }

    /** 把文本/结构化两种翻译行按时间对齐到歌词行（每行取时间最接近的译文，已带译文的不覆盖）。 */
    private fun mergeTranslation(
        lines: List<LrcLine>,
        textTrans: List<LrcLine>,
        listTrans: List<LrcLine>
    ): List<LrcLine> {
        if (lines.isEmpty() || (textTrans.isEmpty() && listTrans.isEmpty())) return lines
        val pool = (textTrans + listTrans).sortedBy { it.timeMs }
        // 注：逐行 minByOrNull 为 O(n·m)；歌词行数有限（通常几百行），暂不重写为双指针，改动风险大于收益。
        return lines.map { line ->
            if (line.translation != null) line
            else pool.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                ?.let { line.copy(translation = it.text) } ?: line
        }
    }

    /** LRC 时间戳正则：提升为 object 级预编译常量，避免 parseLrc 逐行重复编译。 */
    private val LRC_TIME_REGEX = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]")

    fun parseLrc(raw: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        for (line in raw.lineSequence()) {
            // 形如：[mm:ss.xx][mm:ss.xx]歌词
            val m = LRC_TIME_REGEX.findAll(line).toList()
            var lastEnd = 0
            val times = mutableListOf<Long>()
            for (mm in m) {
                val min = mm.groupValues[1].toLong()
                val sec = mm.groupValues[2].toLong()
                val frac = mm.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').take(3).toLong()
                times.add(min * 60_000 + sec * 1000 + frac)
                lastEnd = mm.range.last + 1
            }
            if (times.isEmpty()) continue
            val text = line.substring(lastEnd.coerceAtMost(line.length)).trim()
            if (text.isEmpty()) continue
            times.forEach { lines.add(LrcLine(it, text)) }
        }
        return lines.sortedBy { it.timeMs }
    }

    // ---------------- 通知栏封面 ----------------

    /** 最近一次 getMediaSource 返回的请求头：封面与音源同域时沿用同一套请求头下载。 */
    @Volatile
    private var activeHeaders: Map<String, String> = emptyMap()

    fun activeRequestHeaders(): Map<String, String> = activeHeaders

    /**
     * 用音源请求头下载封面 Bitmap，供播放服务的通知栏 BitmapLoader 调用。
     * 阻塞式：media3 会在自身的后台任务线程里调用，出错时返回 null（通知回退默认图标）。
     */
    fun loadArtworkBitmap(url: String): android.graphics.Bitmap? {
        if (!url.startsWith("http")) return null
        return try {
            val builder = Request.Builder().url(url)
            activeHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
            artworkLoader.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }?.let { decodeArtwork(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** 解码封面并限制最大边长，避免大图吃爆内存。 */
    private fun decodeArtwork(raw: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) > 1024 || bounds.outHeight / (sample * 2) > 1024) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }
}