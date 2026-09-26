package com.tvmusic.config

import android.content.Context
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

    /** LrcApi 服务根地址（只读常量，响应内容由 api.lrc.cx 决定）。 */
    const val BASE = "https://api.lrc.cx"

    private val enabled = java.util.concurrent.atomic.AtomicBoolean(true)
    private val fallbackSrc = java.util.concurrent.atomic.AtomicBoolean(true)
    private var appContext: Context? = null

    val isEnabled: Boolean get() = enabled.get()

    /**
     * 无法播放时是否尝试用其他插件播放同一首歌（不改变当前歌单队列）。
     * 默认开启。
     */
    val fallbackOtherSource: Boolean get() = fallbackSrc.get()

    fun init(context: Context) {
        appContext = context.applicationContext
        val sp = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled.set(sp?.getBoolean(KEY_ENABLED, true) ?: true)
        fallbackSrc.set(sp?.getBoolean(KEY_FALLBACK_SRC, true) ?: true)
    }

    fun setEnabled(on: Boolean) {
        enabled.set(on)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    fun setFallbackOtherSource(on: Boolean) {
        fallbackSrc.set(on)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_FALLBACK_SRC, on)?.apply()
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