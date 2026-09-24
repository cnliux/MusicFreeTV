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
    val isVideo: Boolean = false
)

/**
 * 统一播放控制器：负责调用插件 getMediaSource 取播放地址、管理媒体队列、透传请求头。
 * 全部状态通过 [uiState] 暴露给 Compose UI。
 */
object PlayerManager {

    private const val KEY_QUALITY = "quality"
    private const val KEY_PLAY_MODE = "playMode"

    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ticker = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

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

    private var runtime: PluginRuntime? = null
    private var playbackStore: com.tvmusic.data.PlaybackStore? = null
    private var mediaSourceFactory: androidx.media3.exoplayer.source.DefaultMediaSourceFactory? = null

    /** 连续播放失败计数：达到阈值则停止自动跳下一曲，避免整队列快速空转。 */
    private var errorStreak = 0

    /**
     * 播放会话代数：每次 play() 自增。getMediaSource 是异步解析，快速连点两首歌时
     * 旧歌的解析结果可能后返回并 setMediaItem 覆盖新歌（表现为"点了新歌放的还是旧歌"）。
     * 过期的解析结果直接丢弃。
     */
    @Volatile
    private var playSession = 0

    /**
     * 统一处理播放失败：记录错误并自动跳到队列下一曲。
     * 单曲循环 / 队列只剩一首时不跳，避免原地打转。
     * @param autoSkip false 时只提示不自动跳曲（如 FLV 这类换一首也无法解决的硬限制）。
     */
    private fun reportPlayError(message: String, autoSkip: Boolean = true) {
        val st = _uiState.value
        _uiState.value = st.copy(error = message, buffering = false)
        android.util.Log.w("PlayerManager", "play error: $message (streak=$errorStreak)")
        if (!autoSkip) return
        if (st.queue.size <= 1 || st.playMode == PlayMode.LOOP_ONE) return
        if (errorStreak >= st.queue.size) {
            android.util.Log.w("PlayerManager", "too many consecutive errors, stop auto-skip")
            return
        }
        errorStreak++
        // 延迟一点再跳，避免错误风暴；期间用户可手动操作
        Handler(Looper.getMainLooper()).postDelayed({
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
        _uiState.value = _uiState.value.copy(playMode = savedMode)
    }

    fun setQuality(q: String) {
        quality = q
        qualityPrefs?.edit()?.putString(KEY_QUALITY, q)?.apply()
    }

    fun attach(runtime: PluginRuntime) {
        this.runtime = runtime
    }

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
        p.addListener(playerListener)
        player = p
        return p
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) errorStreak = 0
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            // 事件驱动 ticker：仅播放中周期刷新；暂停/停止时停表并做一次最终刷新，
            // 把最后的进度/歌词行写进 uiState，避免 UI 停在旧帧。
            if (isPlaying) startTicker() else stopTickerWithFinalRefresh()
            // 暂停也是一次"该快照了"的时点（防抖落盘）
            if (!isPlaying) scheduleResumeSave()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.value = _uiState.value.copy(
                buffering = playbackState == Player.STATE_BUFFERING,
                error = null
            )
            // 队列由应用层管理（每次只 setMediaItem 单曲），
            // 播完不会自动切歌，必须在此监听 ENDED 主动推进——这是歌单无法连续播放的根因。
            if (playbackState == Player.STATE_ENDED) {
                handleMediaEnded()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
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
                _uiState.value = _uiState.value.copy(isVideo = hasVideo)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            val idx = _uiState.value.queueIndex
            if (idx in _uiState.value.queue.indices) {
                val entry = _uiState.value.queue[idx]
                val fav = playbackStore?.isFavorite(entry.raw) ?: false
                _uiState.value = _uiState.value.copy(current = entry, isFavorite = fav)
                playbackStore?.addHistory(entry.raw)
                fetchLyric(entry)
            }
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
        val st = _uiState.value
        _uiState.value = st.copy(
            durationMs = p.duration,
            positionMs = p.currentPosition,
            lrcIndex = findLrcIndex(st.lrcLines, p.currentPosition),
            volume = (p.volume * 100).toInt()
        )
    }

    private fun findLrcIndex(lines: List<LrcLine>, posMs: Long): Int {
        var idx = -1
        for ((i, line) in lines.withIndex()) {
            if (line.timeMs <= posMs) idx = i else break
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
        startOffsetMs: Long = 0
    ) {
        // 插件解析（含阻塞式 JS 调用）放 Default 线程；
        // ExoPlayer 只能在主线程访问，拿到地址后必须切回主线程。
        val my = ++playSession
        scope.launch(Dispatchers.Default) {
            try {
                _uiState.value = _uiState.value.copy(
                    current = item,
                    queue = queue ?: listOf(item),
                    queueIndex = startIndex,
                    error = null
                )
                // 队列内容/索引已变化：调度防抖写入恢复快照
                scheduleResumeSave()
                val rt = runtime ?: error("PlayerManager runtime not attached")
                val result = rt.callAsync(
                    plugin, "getMediaSource",
                    listOf(item.raw.toString(), quality)
                )
                val media = when (result) {
                    is JSONObject -> result
                    is NotImplementedError -> JSONObject()
                    else -> {
                        // 部分插件按列表返回
                        val arr = result as? org.json.JSONArray
                        arr?.optJSONObject(0) ?: JSONObject()
                    }
                }
                val url = media.optString("url")
                if (url.isBlank()) {
                    reportPlayError("该音源未返回可播放地址（可能需配置用户变量或 VIP）")
                    return@launch
                }
                // FLV 直链 ExoPlayer 无对应解封装器，必然失败；换下一曲也一样，
                // 所以只明确提示、不自动跳曲，也不 setMediaItem。
                val pathOnly = url.substringBefore('?').substringBefore('#')
                if (pathOnly.endsWith(".flv", ignoreCase = true)) {
                    reportPlayError("该音源为 FLV 直链，当前设备暂不支持播放", autoSkip = false)
                    return@launch
                }
                val headers = media.optJSONObject("headers")?.let { h ->
                    val map = LinkedHashMap<String, String>()
                    val names = h.names() ?: return@let map
                    for (i in 0 until names.length()) map[names.getString(i)] = h.getString(names.getString(i))
                    map
                } ?: emptyMap()
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
                                item.artwork.takeIf { it.startsWith("http") }
                                    ?.let { android.net.Uri.parse(it) }
                            )
                            .build()
                    )
                    .build()

                withContext(Dispatchers.Main) {
                    // 会话守卫：解析期间用户又点了别的歌，本次过期结果直接丢弃，
                    // 否则旧地址 setMediaItem 会覆盖新歌（"点新歌放旧歌"）
                    if (my != playSession) return@withContext
                    // 每个音源可能带不同请求头：更新 DataSourceFactory（同一 DefaultMediaSourceFactory 实例）
                    val p = requirePlayer()
                    if (headers.isNotEmpty()) {
                        mediaSourceFactory?.setDataSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setAllowCrossProtocolRedirects(true)
                                .setDefaultRequestProperties(headers)
                        )
                    }
                    p.setMediaItem(mediaItem)
                    p.prepare()
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

    fun skipTo(index: Int) {
        val st = _uiState.value
        if (index in st.queue.indices && index != st.queueIndex) {
            val entry = st.queue[index]
            _uiState.value = st.copy(queueIndex = index)
            // 当前索引变化：调度防抖写入恢复快照（next/prev 最终都走到这里）
            scheduleResumeSave()
            play(entry.plugin, entry, st.queue, index)
        }
    }

    fun next() {
        val st = _uiState.value
        if (st.playMode == PlayMode.SHUFFLE) {
            val idx = shuffleTarget(st)
            if (idx >= 0) { skipTo(idx); return }
        }
        val target = if (st.queueIndex + 1 in st.queue.indices) st.queueIndex + 1 else 0
        skipTo(target)
    }

    fun prev() {
        val st = _uiState.value
        val target = if (st.queueIndex - 1 >= 0) st.queueIndex - 1 else st.queue.lastIndex
        skipTo(target)
    }

    // ---------------- 播放模式 ----------------

    /** 设置播放模式并持久化。 */
    fun setPlayMode(mode: PlayMode) {
        qualityPrefs?.edit()?.putString(KEY_PLAY_MODE, mode.name)?.apply()
        _uiState.value = _uiState.value.copy(playMode = mode)
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

    /** 从头重播当前曲目（不重新解析音源）。 */
    private fun replayCurrent() {
        player?.let { p ->
            p.seekTo(0)
            p.playWhenReady = true
        }
    }

    fun playPause() {
        // 可能从 HTTP 线程调用：ExoPlayer 只能在主线程访问
        Handler(Looper.getMainLooper()).post {
            val p = player ?: return@post
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    /** 收藏 / 取消收藏当前曲目（可指定目标专辑），返回该专辑内收藏后的状态。 */
    fun toggleFavorite(listId: String = com.tvmusic.data.PlaybackStore.DEFAULT_FAV_ID): Boolean {
        val entry = _uiState.value.current ?: return false
        val fav = playbackStore?.toggleFavorite(entry.raw, listId) ?: false
        _uiState.value = _uiState.value.copy(isFavorite = playbackStore?.isFavorite(entry.raw) ?: fav)
        return fav
    }

    fun isCurrentFavorite(): Boolean {
        val entry = _uiState.value.current ?: return false
        return playbackStore?.isFavorite(entry.raw) ?: false
    }

    fun seek(toMs: Long) {
        Handler(Looper.getMainLooper()).post {
            player?.seekTo(toMs.coerceIn(0, Long.MAX_VALUE))
        }
    }

    /** 相对调整音量（供 web 端调用）。 */
    fun adjustVolume(delta: Float) {
        Handler(Looper.getMainLooper()).post {
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
        player?.removeListener(playerListener)
        player?.release()
        player = null
        _uiState.value = PlayerUiState()
    }

    // ---------------- 歌词 ----------------

    /** 歌词请求代数：防止慢响应覆盖新歌歌词（旧请求返回时已过期，直接丢弃）。 */
    @Volatile
    private var lrcGeneration = 0

    private fun fetchLyric(entry: QueueEntry) {
        val gen = ++lrcGeneration
        scope.launch(Dispatchers.Default) {
            try {
                val rt = runtime ?: return@launch
                val result = rt.callAsync(entry.plugin, "getLyric", listOf(entry.raw.toString()))
                if (gen != lrcGeneration) return@launch // 已切歌，丢弃过期歌词
                val lines = parseLyricResult(result)
                _uiState.value = _uiState.value.copy(lrcLines = lines.sortedBy { it.timeMs }, lrcIndex = -1)
            } catch (_: Exception) {
                if (gen == lrcGeneration) {
                    _uiState.value = _uiState.value.copy(lrcLines = emptyList())
                }
            }
        }
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