package com.tvmusic.config

import android.content.Context
import com.tvmusic.utils.PrefsHolder
import java.net.URLEncoder

/**
 * 歌词/封面缺失时的兜底补全设置（默认开启）。
 * 通过 LrcApi（https://api.lrc.cx）按 曲名/歌手/专辑 补齐：
 * - 插件未返回歌词时用 GET /lyrics 拉取 LRC 文本
 * - 条目无封面时用 GET /cover 拼出回退封面地址
 */
object MetaSettings {

    private const val PREFS = "meta_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FALLBACK_SRC = "fallbackOtherSource"
    private const val KEY_MIN_PLAY = "minPlaySeconds"
    private const val KEY_PREFER_PLUGIN = "fallbackPreferPlugin"
    private const val KEY_FALLBACK_MODE = "fallbackMode"
    private const val KEY_FALLBACK_STRATEGY = "fallbackStrategy"
    private const val KEY_FAST_BATCH = "fallbackFastBatch"
    private const val KEY_COVER_SHAPE = "coverShape"
    private const val KEY_COVER_SPIN_MS = "coverSpinMs"
    private const val KEY_COVER_SPIN_DIR = "coverSpinDir"
    private const val KEY_HEALTH_WEIGHT = "fallbackHealthWeight"
    private const val KEY_SPEC_PRELOAD = "fallbackSpecPreload"

    /** LrcApi 服务根地址（只读常量，响应内容由 api.lrc.cx 决定）。 */
    const val BASE = "https://api.lrc.cx"

    private val enabled = java.util.concurrent.atomic.AtomicBoolean(true)
    private val fallbackSrc = java.util.concurrent.atomic.AtomicBoolean(true)
    private val minPlay = java.util.concurrent.atomic.AtomicInteger(90)
    @Volatile
    private var preferPlugin: String = ""
    /** 共享偏好句柄：init() 时绑定，避免每个 setter 重复 getSharedPreferences(...).edit() 样板。 */
    private val prefs = PrefsHolder(PREFS)

    val isEnabled: Boolean get() = enabled.get()

    /**
     * 无法播放时是否尝试用其他插件播放同一首歌（不改变当前歌单队列）。
     * 默认开启。
     */
    val fallbackOtherSource: Boolean get() = fallbackSrc.get()

    /**
     * 最低播放时长（秒）：歌曲播放不足该时长就自然结束（非用户切歌），视为版权
     * 掐断，自动换其他插件重播同一首。0 = 关闭检测；默认 90；范围 0-300。
     */
    val minPlaySeconds: Int get() = minPlay.get()

    /**
     * 优先换源插件：换源时把该插件排到候选最前（空 = 不指定）。
     * 特殊值 [PREFER_AGGREGATE] = 聚合搜索：全候选并行竞速，先到先得。
     */
    val fallbackPreferPlugin: String get() = preferPlugin

    /** 远程下拉「聚合搜索」：不置顶单一插件，按最快命中播放。 */
    const val PREFER_AGGREGATE = "*"

    const val MODE_AGGREGATE = "*"
    const val MODE_FAST_FIRST = "fast_first"
    const val MODE_MUSIC_FIRST = "music_first"
    const val MODE_CURRENT = "current"

    // 换源策略：决定候选插件的启动与竞速方式
    const val STRATEGY_STAGGERED = "staggered"      // 分批错峰（默认，兼顾并发与资源）
    const val STRATEGY_ALL_PARALLEL = "allParallel"  // 全候选同时启动（最快，吃资源）
    const val STRATEGY_SEQUENTIAL = "sequential"     // 严格顺序，前一候选成功即返回
    const val STRATEGY_PREFER_FIRST = "preferFirst"  // 只试健康度第 1 名，命中不了直接放弃

    const val DEFAULT_FAST_BATCH = 3
    const val DEFAULT_HEALTH_WEIGHT = 100
    const val DEFAULT_SPEC_PRELOAD = false

    val preferAggregate: Boolean get() = preferPlugin == PREFER_AGGREGATE

    /** 换源模式（内部字段）：默认 MODE_CURRENT，允许实验位覆盖批次与权重。 */
    @Volatile
    private var _fallbackMode: String = MODE_CURRENT

    @Volatile
    private var fastBatch: Int = DEFAULT_FAST_BATCH

    @Volatile
    private var healthWeight: Int = DEFAULT_HEALTH_WEIGHT

    @Volatile
    private var specPreload: Boolean = DEFAULT_SPEC_PRELOAD

    @Volatile
    private var _fallbackStrategy: String = STRATEGY_STAGGERED

    /** 播放页封面形状：circle=黑胶圆盘，square=方形圆角。两者均支持旋转。 */
    const val COVER_SHAPE_CIRCLE = "circle"
    const val COVER_SHAPE_SQUARE = "square"
    const val DEFAULT_COVER_SHAPE = COVER_SHAPE_CIRCLE
    const val DEFAULT_COVER_SPIN_MS = 10_000

    /** 封面旋转方向：cw=顺时针，ccw=逆时针。 */
    const val SPIN_DIR_CW = "cw"
    const val SPIN_DIR_CCW = "ccw"
    const val DEFAULT_COVER_SPIN_DIR = SPIN_DIR_CW

    @Volatile
    private var coverShape: String = DEFAULT_COVER_SHAPE
    /** 封面形状/转速/方向的响应式副本：远程改设置后要立刻在播放页生效，必须可观察。 */
    private val coverShapeState = kotlinx.coroutines.flow.MutableStateFlow(DEFAULT_COVER_SHAPE)
    val coverShapeFlow: kotlinx.coroutines.flow.StateFlow<String> get() = coverShapeState

    /** 封面转一圈耗时（毫秒），0=不转。 */
    private val coverSpinMs = java.util.concurrent.atomic.AtomicInteger(DEFAULT_COVER_SPIN_MS)
    private val coverSpinMsState = kotlinx.coroutines.flow.MutableStateFlow(DEFAULT_COVER_SPIN_MS)
    val coverSpinMsFlow: kotlinx.coroutines.flow.StateFlow<Int> get() = coverSpinMsState

    @Volatile
    private var coverSpinDir: String = DEFAULT_COVER_SPIN_DIR
    private val coverSpinDirState = kotlinx.coroutines.flow.MutableStateFlow(DEFAULT_COVER_SPIN_DIR)
    val coverSpinDirFlow: kotlinx.coroutines.flow.StateFlow<String> get() = coverSpinDirState

    val fallbackMode: String get() = _fallbackMode

    val fallbackFastBatch: Int get() = fastBatch.coerceIn(1, 6)

    val fallbackHealthWeight: Int get() = healthWeight.coerceIn(0, 300)

    val fallbackSpecPreload: Boolean get() = specPreload

    /** 当前换源策略（4 选 1），见 STRATEGY_* 常量。 */
    val fallbackStrategy: String get() = _fallbackStrategy

    val playerCoverShape: String get() = coverShape
    val playerCoverSpinMs: Int get() = coverSpinMs.get()
    val playerCoverSpinDir: String get() = coverSpinDir

    fun init(context: Context) {
        prefs.attach(context)
        enabled.set(prefs.bool(KEY_ENABLED, true))
        fallbackSrc.set(prefs.bool(KEY_FALLBACK_SRC, true))
        minPlay.set(prefs.int(KEY_MIN_PLAY, 90).coerceIn(0, 300))
        preferPlugin = prefs.str(KEY_PREFER_PLUGIN, "")
        _fallbackMode = prefs.str(KEY_FALLBACK_MODE, MODE_CURRENT)
        fastBatch = prefs.int(KEY_FAST_BATCH, DEFAULT_FAST_BATCH).coerceIn(1, 6)
        healthWeight = prefs.int(KEY_HEALTH_WEIGHT, DEFAULT_HEALTH_WEIGHT).coerceIn(0, 300)
        specPreload = prefs.bool(KEY_SPEC_PRELOAD, DEFAULT_SPEC_PRELOAD)
        _fallbackStrategy = prefs.str(KEY_FALLBACK_STRATEGY, STRATEGY_STAGGERED)
        coverShape = prefs.str(KEY_COVER_SHAPE, DEFAULT_COVER_SHAPE)
        coverShapeState.value = coverShape
        val spin = prefs.int(KEY_COVER_SPIN_MS, DEFAULT_COVER_SPIN_MS).coerceIn(0, 120_000)
        coverSpinMs.set(spin)
        coverSpinMsState.value = spin
        coverSpinDir = prefs.str(KEY_COVER_SPIN_DIR, DEFAULT_COVER_SPIN_DIR)
        coverSpinDirState.value = coverSpinDir
    }

    fun setFallbackMode(mode: String) {
        val v = mode.trim()
        _fallbackMode = v
        prefs.putString(KEY_FALLBACK_MODE, v)
    }

    fun setFallbackFastBatch(count: Int) {
        val v = count.coerceIn(1, 6)
        fastBatch = v
        prefs.putInt(KEY_FAST_BATCH, v)
    }

    fun setFallbackHealthWeight(percent: Int) {
        val v = percent.coerceIn(0, 300)
        healthWeight = v
        prefs.putInt(KEY_HEALTH_WEIGHT, v)
    }

    fun setFallbackSpecPreload(on: Boolean) {
        specPreload = on
        prefs.putBoolean(KEY_SPEC_PRELOAD, on)
    }

    /**
     * 设置换源策略。传入未知值时保留旧值，避免误配置破坏换源流程。
     * 合法值：STRATEGY_STAGGERED / ALL_PARALLEL / SEQUENTIAL / PREFER_FIRST。
     */
    fun setFallbackStrategy(strategy: String) {
        val v = strategy.trim()
        if (v != STRATEGY_STAGGERED && v != STRATEGY_ALL_PARALLEL &&
            v != STRATEGY_SEQUENTIAL && v != STRATEGY_PREFER_FIRST
        ) return
        _fallbackStrategy = v
        prefs.putString(KEY_FALLBACK_STRATEGY, v)
    }

    fun setCoverShape(shape: String) {
        val v = if (shape == COVER_SHAPE_SQUARE) COVER_SHAPE_SQUARE else COVER_SHAPE_CIRCLE
        coverShape = v
        coverShapeState.value = v
        prefs.putString(KEY_COVER_SHAPE, v)
    }

    fun setCoverSpinMs(ms: Int) {
        val v = ms.coerceIn(0, 120_000)
        coverSpinMs.set(v)
        coverSpinMsState.value = v
        prefs.putInt(KEY_COVER_SPIN_MS, v)
    }

    /** 封面旋转方向：仅接受 cw/ccw，其他值静默忽略（保留旧值）。 */
    fun setCoverSpinDir(dir: String) {
        val v = dir.trim()
        if (v != SPIN_DIR_CW && v != SPIN_DIR_CCW) return
        coverSpinDir = v
        coverSpinDirState.value = v
        prefs.putString(KEY_COVER_SPIN_DIR, v)
    }

    fun setEnabled(on: Boolean) {
        enabled.set(on)
        prefs.putBoolean(KEY_ENABLED, on)
    }

    fun setFallbackOtherSource(on: Boolean) {
        fallbackSrc.set(on)
        prefs.putBoolean(KEY_FALLBACK_SRC, on)
    }

    fun setMinPlaySeconds(sec: Int) {
        val v = sec.coerceIn(0, 300)
        minPlay.set(v)
        prefs.putInt(KEY_MIN_PLAY, v)
    }

    fun setFallbackPreferPlugin(name: String) {
        val v = name.trim()
        preferPlugin = v
        prefs.putString(KEY_PREFER_PLUGIN, v)
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 歌手名净化：部分音源把 "歌手 · 专辑"（或 歌手/组合）拼进 artist，取首个分隔段更利于匹配。 */
    fun cleanArtist(artist: String): String =
        artist.split('·', '/', '／').first().trim()

    private fun buildUrl(kind: String, title: String, artist: String, album: String): String {
        if (title.isBlank() && artist.isBlank()) return ""
        val sb = StringBuilder(BASE).append('/').append(kind).append("?")
        if (title.isNotBlank()) {
            sb.append("title=").append(enc(title))
            if (artist.isNotBlank()) sb.append("&artist=").append(enc(artist))
        } else {
            sb.append("artist=").append(enc(artist))
        }
        if (album.isNotBlank()) sb.append("&album=").append(enc(album))
        return sb.toString()
    }

    /** LRC 兜底地址：title 必填，artist/album 按需附带。 */
    fun lyricsUrl(title: String, artist: String = "", album: String = ""): String =
        buildUrl("lyrics", title, artist, album)

    /**
     * 封面兜底地址：一律用净化后的歌手名，且不带 album。
     * 实测 title+artist 即命中歌曲封面并 **直接返回图片（200）**；带上 album 会触发 301
     * 重定向到 Apple 音乐 CDN（is1-ssl.mzstatic.com），国内网络常超时，故封面禁用 album。
     */
    fun coverUrl(title: String, artist: String = "", album: String = ""): String =
        buildUrl("cover", title, cleanArtist(artist), "")

    /**
     * 歌词兜底候选地址（按优先级返回）：完整歌手 → 净化歌手（去掉“·专辑”等）→ 仅曲名。
     * 请求方按序尝试，任一命中即取；全部落空才算未找到。
     */
    fun lyricsCandidates(title: String, artist: String = "", album: String = ""): List<String> {
        if (title.isBlank()) return emptyList()
        val list = mutableListOf<String>()
        if (artist.isNotBlank()) list.add(buildUrl("lyrics", title, artist, album))
        val clean = cleanArtist(artist)
        if (clean.isNotBlank() && clean != artist) list.add(buildUrl("lyrics", title, clean, album))
        list.add(buildUrl("lyrics", title, "", ""))
        return list.distinct()
    }
}
