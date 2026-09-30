package com.tvmusic.model

import com.tvmusic.constants.ARTWORK_KEYS
import com.tvmusic.constants.ARTWORK_KEYS_FALLBACK
import com.tvmusic.constants.TITLE_KEYS
import com.tvmusic.utils.firstStr
import org.json.JSONObject

/**
 * 插件条目基类：把 SearchEntry / SheetEntry / TopListEntry 三者重复的
 * 「plugin + raw JSON → 标题/封面/描述」解析收口到一处。
 *
 * 原先三个 data class 各自复制了一份 5~7 级 `ifBlank` 封面回退链
 * （Models.kt 里 artwork 出现 3 次、回退链共 21 个 ifBlank），字段名还各不相同；
 * 现统一走 [ARTWORK_KEYS] + [ARTWORK_KEYS_FALLBACK] 两段候选表。
 */
abstract class PluginEntry(
    /** 来源插件名（platform）。 */
    open val plugin: String,
    /** 插件返回的原始 JSON，回放/换源时必须原样传回插件。 */
    open val raw: JSONObject
    ) {
    /** 条目类型（music / album / artist / musicSheetInfo…），缺失时按音乐条目处理。 */
    val type: String get() = raw.optString("type", "music").ifBlank { "music" }

    val id: String get() = raw.optString("id", "")

    /** 歌单/榜单标题。artist 类条目字段是 name，缺 name 时回退 title。 */
    val title: String get() = raw.firstStr(*TITLE_KEYS)

    /** 封面 URL：跨音源字段名不统一，按候选表顺序取第一个非空值。 */
    val artwork: String
        get() = raw.firstStr(*ARTWORK_KEYS).ifBlank { raw.firstStr(*ARTWORK_KEYS_FALLBACK) }

    val artist: String get() = raw.optString("artist", "")

    val album: String get() = raw.optString("album", "")

    /** 简介：部分音源用 desc 而非 description。 */
    val description: String get() = raw.optString("description", "").ifBlank { raw.optString("desc", "") }

    /** 时长（毫秒），部分音源用 time（秒）。 */
    val durationMs: Long get() = raw.optLong("duration", 0L).let { if (it > 0) it else (raw.optDouble("time", 0.0) * 1000).toLong() }
}
