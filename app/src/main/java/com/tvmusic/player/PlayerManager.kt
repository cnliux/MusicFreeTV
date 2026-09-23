package com.tvmusic.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
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

data class LrcLine(val timeMs: Long, val text: String)

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
    val playMode: PlayMode = PlayMode.ORDER
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

    private var runtime: PluginRuntime? = null
    private var playbackStore: com.tvmusic.data.PlaybackStore? = null
    private var mediaSourceFactory: androidx.media3.exoplayer.source.DefaultMediaSourceFactory? = null

    /** 连续播放失败计数：达到阈值则停止自动跳下一曲，避免整队列快速空转。 */
    private var errorStreak = 0

    /**
     * 统一处理播放失败：记录错误并自动跳到队列下一曲。
     * 单曲循环 / 队列只剩一首时不跳，避免原地打转。
     */
    private fun reportPlayError(message: String) {
        val st = _uiState.value
        _uiState.value = st.copy(error = message, buffering = false)
        android.util.Log.w("PlayerManager", "play error: $message (streak=$errorStreak)")
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
        startTicker()
        return p
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) errorStreak = 0
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
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
            reportPlayError("播放失败：${error.errorCodeName}")
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

    private fun startTicker() {
        ticker.removeCallbacksAndMessages(null)
        ticker.post(object : Runnable {
            override fun run() {
                val p = player
                if (p != null) {
                    val st = _uiState.value
                    _uiState.value = st.copy(
                        durationMs = p.duration,
                        positionMs = p.currentPosition,
                        lrcIndex = findLrcIndex(st.lrcLines, p.currentPosition),
                        volume = (p.volume * 100).toInt()
                    )
                }
                ticker.postDelayed(this, 500)
            }
        })
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
     */
    fun play(
        plugin: String,
        item: QueueEntry,
        queue: List<QueueEntry>? = null,
        startIndex: Int = 0
    ) {
        // 插件解析（含阻塞式 JS 调用）放 Default 线程；
        // ExoPlayer 只能在主线程访问，拿到地址后必须切回主线程。
        scope.launch(Dispatchers.Default) {
            try {
                _uiState.value = _uiState.value.copy(
                    current = item,
                    queue = queue ?: listOf(item),
                    queueIndex = startIndex,
                    error = null
                )
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
                val headers = media.optJSONObject("headers")?.let { h ->
                    val map = LinkedHashMap<String, String>()
                    val names = h.names() ?: return@let map
                    for (i in 0 until names.length()) map[names.getString(i)] = h.getString(names.getString(i))
                    map
                } ?: emptyMap()

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
                    p.play()
                }
                fetchLyric(item)
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
            PlayMode.LOOP_ONE -> {
                // 单曲循环：从头重播当前曲，无需重新解析音源
                player?.let { p ->
                    p.seekTo(0)
                    p.playWhenReady = true
                }
            }
            PlayMode.SHUFFLE -> {
                val idx = shuffleTarget(st)
                if (idx >= 0) skipTo(idx) else { player?.let { it.seekTo(0); it.playWhenReady = true } }
            }
            PlayMode.ORDER -> next()
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

    fun release() {
        ticker.removeCallbacksAndMessages(null)
        player?.removeListener(playerListener)
        player?.release()
        player = null
        _uiState.value = PlayerUiState()
    }

    // ---------------- 歌词 ----------------

    private fun fetchLyric(entry: QueueEntry) {
        scope.launch(Dispatchers.Default) {
            try {
                val rt = runtime ?: return@launch
                val result = rt.callAsync(entry.plugin, "getLyric", listOf(entry.raw.toString()))
                val lines = when (result) {
                    is JSONObject -> {
                        val raw = result.optString("rawLrc")
                        if (raw.isNotBlank()) parseLrc(raw)
                        else {
                            val arr = result.optJSONArray("lyricList")
                            if (arr != null) {
                                (0 until arr.length()).mapNotNull { i ->
                                    val o = arr.optJSONObject(i)
                                    if (o == null) null
                                    else LrcLine((o.optDouble("time") * 1000).toLong().coerceAtLeast(0), o.optString("lyric"))
                                }
                            } else emptyList()
                        }
                    }
                    is NotImplementedError -> emptyList()
                    else -> emptyList()
                }
                _uiState.value = _uiState.value.copy(lrcLines = lines, lrcIndex = -1)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(lrcLines = emptyList())
            }
        }
    }

    fun parseLrc(raw: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        for (line in raw.lineSequence()) {
            // 形如：[mm:ss.xx][mm:ss.xx]歌词
            val m = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]").findAll(line).toList()
            var text = line
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
            text = line.substring(lastEnd.coerceAtMost(line.length)).trim()
            if (text.isEmpty()) continue
            times.forEach { lines.add(LrcLine(it, text)) }
        }
        return lines.sortedBy { it.timeMs }
    }
}