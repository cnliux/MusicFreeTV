package com.tvmusic.player

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

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
    /** 瞬时提示（如 lrc.cx 兜底进度/结果），播放页短暂展示后自动清除。 */
    val metaNotice: String? = null,
    /** 来源标签（「插件名 · 歌单名」，如「wx · 华语热歌」），迷你播放器与播放页展示。 */
    val sourceLabel: String? = null
)

/**
 * 统一播放控制器：负责调用插件 getMediaSource 取播放地址、管理媒体队列、透传请求头。
 * 全部状态通过 [uiState] 暴露给 Compose UI。
 */
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
    val screenState: Flow<PlayerUiState> = _uiState
        .map {
            it.copy(
                positionMs = 0, durationMs = 0, bufferedPositionMs = 0,
                lrcIndex = -1, lrcLines = emptyList(), sleepRemainingMs = 0
            )
        }
        .distinctUntilChanged()

    var player: ExoPlayer? = null
        private set

    /** 供服务/UI 获取播放器实例（不存在则创建）。 */
    fun ensurePlayer(): ExoPlayer = requirePlayer()

    /** 通知栏封面下载器：封面 URL 通常与音源同源，需带上 getMediaSource 返回的请求头才能加载。 */
    private val artworkLoader = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** lrc.cx 兜底下载器：独立超时（放宽 + 总时限），便于网络抖动时重试。 */
    private val metaHttp = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var runtime: PluginRuntime? = null
    private var playbackStore: com.tvmusic.data.PlaybackStore? = null
    private var mediaSourceFactory: androidx.media3.exoplayer.source.DefaultMediaSourceFactory? = null

    /** 连续播放失败计数：达到阈值则停止自动跳下一曲，避免整队列快速空转。 */
    private var errorStreak = 0

    /** 已尝试过「换插件救场」重试的条目 key（取流失败每条目只自动换源一次，防死循环）。 */
    @Volatile
    private var fallbackTriedFor: String? = null

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

    private data class PreloadedMedia(val media: JSONObject, val quality: String, val atMs: Long)

    /** 预加载缓存：entryKey -> 解析出的 media JSON。容量 4，按访问序淘汰最旧。 */
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
        scope.launch(Dispatchers.Default) {
            try {
                // 与正式播放同走粘性 home 引擎；此时处于歌曲后半段，
                // 引擎队列通常空闲，预解析不与用户操作抢资源
                val result = rt.callParallel(
                    next.plugin, "getMediaSource",
                    listOf(next.raw.toString(), quality)
                )
                val media = when (result) {
                    is JSONObject -> result
                    else -> (result as? JSONArray)?.optJSONObject(0)
                } ?: return@launch
                if (media.optString("url").isBlank()) return@launch
                synchronized(preloadCache) {
                    preloadCache[nk] = PreloadedMedia(media, quality, android.os.SystemClock.elapsedRealtime())
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
        ticker.postDelayed({
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

    /** 音质档位：插件 getMediaSource 的第二个参数。standard/high/low/super 等。 */
    var quality: String = "standard"
        private set

    private val qualityPrefs by lazy {
        context?.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
    }

    fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        quality = qualityPrefs?.getString(KEY_QUALITY, "standard") ?: "standard"
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
        quality = q
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
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(msf)
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
            for (i in 0 until eq.numberOfPresets.toInt()) names.add(eq.getPresetName(i.toShort()))
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
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // 解析成功但取流失败（链接过期/404 等）：给「换插件救场」一次机会。
            // 每条目只试一次（fallbackTriedFor 按条目 key 去重），换源后仍失败则照常报错跳曲。
            val st = _uiState.value
            val entry = st.current
            val key = entry?.let { entryKeyOf(it) }
            if (com.tvmusic.config.MetaSettings.fallbackOtherSource &&
                entry != null && key != null && key != fallbackTriedFor &&
                st.queue.isNotEmpty()
            ) {
                fallbackTriedFor = key
                android.util.Log.i("PlayerManager", "player error [$key], retry via other source")
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
        lastLyricKey = null // 新会话重置：重播/切歌后重试同一首歌时不再因旧 key 被跳过
        if (!forceFallback) fallbackTriedFor = null // 用户主动播放：重新给一次换源机会
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
                if (shown !== item) showMetaNotice("已用 lrc.cx 补充封面")
                _uiState.update {
                    it.copy(
                        current = shown,
                        queue = effectiveQueue,
                        queueIndex = startIndex,
                        error = null,
                        // 来源标签随新会话更新：null=保留（切歌/续播），空串=清除
                        sourceLabel = if (source == null) it.sourceLabel else source.ifBlank { null }
                    )
                }
                // 队列内容/索引已变化：调度防抖写入恢复快照
                scheduleResumeSave()
                val rt = runtime ?: error("PlayerManager runtime not attached")
                // 预加载命中：本曲在上一首后半段已解析过，直接复用（音质一致且未过期）
                val key = entryKeyOf(item)
                val cached = synchronized(preloadCache) { preloadCache.remove(key) }
                    ?.takeIf {
                        it.quality == quality &&
                            android.os.SystemClock.elapsedRealtime() - it.atMs < PRELOAD_TTL_MS
                    }
                // 走粘性引擎路由（平台 home 引擎）：1) 不与 primary 上的首页/详情/
                // 搜索分页等流量互相排队（引擎 invokeLock 全局串行，主引擎被慢源
                // 占住时点击播放会延迟几十秒才出声）；2) 复用该平台搜索时在 home
                // 引擎上建立的模块级状态（cookie/token），解析更快更稳。
                // 预加载命中时直接复用缓存的 media，跳过 QuickJS 解析。
                // forceFallback（取流失败重试）：跳过本插件解析，直接换其他音源。
                val cachedMedia = if (forceFallback) null else cached?.media
                val media = cachedMedia ?: if (forceFallback) null else resolveMediaSource(rt, plugin, item.raw)
                // media == null 表示 getMediaSource 抛错（版权/会员/插件过期）。
                val primaryFailed = media == null && !forceFallback
                var url = media?.optString("url").orEmpty()
                var headers = media?.let { parseMediaHeaders(it) } ?: emptyMap()
                var viaPlugin = plugin
                // FLV 直链 ExoPlayer 无对应解封装器，必然失败（换下一曲也一样）。
                val flv = url.isNotBlank() &&
                    url.substringBefore('?').substringBefore('#').endsWith(".flv", ignoreCase = true)
                // 主音源不可播放（解析异常 / 空地址 / FLV / 取流失败重试）时，尝试用「其他插件」播放同一首歌：
                // 只替换实际取流来源，队列条目 / mediaId / 历史 / 歌词 / 来源标签全部保持原样，
                // 因此不改变歌单，也不与 playSession 切歌守卫、onMediaItemTransition 定位冲突。
                if (url.isBlank() || flv || forceFallback) {
                    if (com.tvmusic.config.MetaSettings.fallbackOtherSource) {
                        tryFallbackSource(rt, item, my)?.let { fb ->
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
                if (my != playSession.get()) return@launch
                if (viaPlugin != plugin) showMetaNotice("已切换「$viaPlugin」音源播放本曲")
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
        rt: PluginRuntime, plugin: String, raw: JSONObject
    ): JSONObject? = try {
        when (val result = rt.callParallel(plugin, "getMediaSource", listOf(raw.toString(), quality))) {
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
        rt: PluginRuntime, item: QueueEntry, my: Int
    ): FallbackSource? {
        val repo = repository ?: return null
        if (item.title.isBlank()) return null
        val order = com.tvmusic.config.SearchSettings.load(context ?: return null).sourceOrder
        val candidates = com.tvmusic.config.SearchSettings.ordered(
            repo.listEnabled()
                .filter { it.info != null && it.loadError == null }
                .map { it.info!!.platform }
                .filter { it != item.plugin },
            order
        )
        if (candidates.isEmpty()) return null
        showMetaNotice("正在尝试其他音源播放「${item.title}」…")
        val artist = item.artist
        for (platform in candidates) {
            if (my != playSession.get()) return null
            val matched = searchMusicOn(rt, platform, item.title, artist) ?: continue
            if (my != playSession.get()) return null
            val media = resolveMediaSource(rt, platform, matched) ?: continue
            val url = media.optString("url")
            if (url.isBlank()) continue
            if (url.substringBefore('?').substringBefore('#').endsWith(".flv", ignoreCase = true)) continue
            return FallbackSource(platform, url, parseMediaHeaders(media))
        }
        return null
    }

    /** 在 [platform] 上搜「title artist」，返回标题（近似）匹配的第一条 music 条目 raw；无则 null。 */
    private suspend fun searchMusicOn(
        rt: PluginRuntime, platform: String, title: String, artist: String
    ): JSONObject? {
        val res = try {
            val query = if (artist.isBlank()) title else "$title $artist"
            rt.callParallel(platform, "search", listOf(query, "1", "music"), timeoutMs = 12_000)
        } catch (e: Exception) {
            return null
        }
        if (res is NotImplementedError) return null
        val arr = (res as? JSONObject)?.optJSONArray("data") ?: (res as? JSONArray) ?: return null
        val want = normalizeName(title)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = normalizeName(o.optString("title", ""))
            if (t.isNotEmpty() && (t == want || t.contains(want) || want.contains(t))) {
                if (o.optString("platform").isBlank()) o.put("platform", platform)
                return o
            }
        }
        return null
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

    /** 随机模式下选一个不同于当前的索引；其余模式返回 -1 表示用顺序逻辑。 */
    private fun shuffleTarget(st: PlayerUiState): Int {
        if (st.queue.size <= 1) return -1
        var idx: Int
        do { idx = Random.nextInt(st.queue.size) } while (idx == st.queueIndex)
        return idx
    }

    /** 一首播完后的推进逻辑（在主线程由 playerListener 调用）。 */
    private fun handleMediaEnded() {
        val st = _uiState.value
        if (st.queue.isEmpty()) return
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
            play(target.plugin, target, entries, index, positionMs)
        } catch (e: Exception) {
            android.util.Log.w("PlayerManager", "resume failed: ${e.message}")
        }
    }

    fun release() {
        // 释放前立即落盘恢复快照（此时队列/进度还有效），顺带清掉防抖任务
        flushResumeNow()
        ticker.removeCallbacksAndMessages(null)
        lyricJob?.cancel()
        lyricJob = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        synchronized(preloadCache) { preloadCache.clear() }
        preloadTriggeredFor = null
        _uiState.value = PlayerUiState()
    }

    // ---------------- 歌词 ----------------

    /** 歌词请求代数：防止慢响应覆盖新歌歌词（旧请求返回时已过期，直接丢弃）。 */
    @Volatile
    private var lrcGeneration = 0

    /** 当前歌词解析协程：切歌时取消，避免旧解析堵在引擎串行队列里拖慢新歌。 */
    private var lyricJob: kotlinx.coroutines.Job? = null

    /** metaNotice 自动清除协程：同一条提示只保留一次，5 秒后清空。 */
    private var metaNoticeJob: kotlinx.coroutines.Job? = null

    /** 播放页瞬时提示：lrc.cx 兜底进行中/成功/失败时的可见反馈。 */
    private fun showMetaNotice(text: String) {
        metaNoticeJob?.cancel()
        _uiState.update { it.copy(metaNotice = text) }
        metaNoticeJob = scope.launch {
            kotlinx.coroutines.delay(5_000)
            _uiState.update { it.copy(metaNotice = null) }
        }
    }

    /** 最近一次已发起歌词请求的条目：onMediaItemTransition 与 play() 末尾都会触发，按条目去重。 */
    @Volatile
    private var lastLyricKey: String? = null

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
                        entry.plugin, "getLyric", listOf(entry.raw.toString()),
                        timeoutMs = 15_000
                    )
                } catch (_: Exception) {
                    null
                }
                if (gen != lrcGeneration) return@launch // 已切歌，丢弃过期歌词
                var lines = result?.let { parseLyricResult(it) } ?: emptyList()
                // 插件没返回歌词且开启兜底时，按 曲名/歌手/专辑 从 lrc.cx 补齐
                if (lines.isEmpty() && com.tvmusic.config.MetaSettings.isEnabled) {
                    showMetaNotice("正在从 lrc.cx 搜索「${entry.title}」歌词…")
                    val fallback = try {
                        fetchFallbackLyric(entry) // null = 网络失败（已重试），空列表 = 确实没有
                    } catch (_: Exception) {
                        null
                    }
                    lines = fallback ?: emptyList()
                    when {
                        fallback == null -> showMetaNotice("lrc.cx 请求失败（网络不稳定，已重试）")
                        lines.isNotEmpty() -> showMetaNotice("已从 lrc.cx 补充歌词")
                        else -> showMetaNotice("lrc.cx 未找到该歌曲歌词")
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
        val urls = com.tvmusic.config.MetaSettings.lyricsCandidates(entry.title, entry.artist, entry.album)
        if (urls.isEmpty()) return emptyList()
        var networkFailed = false
        repeat(2) {
            for (url in urls) {
                try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "MusicFreeTV/1.0")
                        .build()
                    val lines = metaHttp.newCall(req).execute().use { resp ->
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
                    if (lines.isNotEmpty()) return lines
                } catch (_: Exception) {
                    networkFailed = true // 网络/TLS/超时：本候选失败，继续下一候选/下一轮
                }
            }
            if (networkFailed) kotlinx.coroutines.delay(800)
        }
        return if (networkFailed) null else emptyList()
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
        return lines.map { line ->
            if (line.translation != null) line
            else pool.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                ?.let { line.copy(translation = it.text) } ?: line
        }
    }

    fun parseLrc(raw: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        for (line in raw.lineSequence()) {
            // 形如：[mm:ss.xx][mm:ss.xx]歌词
            val m = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]").findAll(line).toList()
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