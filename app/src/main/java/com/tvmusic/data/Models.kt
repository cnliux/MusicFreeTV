package com.tvmusic.data

import org.json.JSONObject

/**
 * 插件元信息（来自插件 JS 的静态字段）。
 */
data class PluginInfo(
    val platform: String,
    val version: String? = null,
    val author: String? = null,
    val srcUrl: String? = null,
    val appVersion: String? = null,
    val description: String? = null,
    val cacheControl: String? = null,
    val primaryKey: List<String> = emptyList(),
    val supportedSearchType: List<String> = emptyList(),
    val userVariables: List<UserVarDef> = emptyList(),
    val hints: JSONObject? = null
)

data class UserVarDef(
    val key: String,
    val name: String,
    val type: String? = null
)

/**
 * 已安装插件记录（持久化）。
 *
 * [hash] 是插件源码的指纹（sha1 前 12 位），作为插件的唯一 id：
 * 同一份源码无论从哪个订阅源、以什么名字出现，都只会被安装一次，
 * 避免重复注册进 JS 引擎导致 platform 互相覆盖。
 */
data class PluginRecord(
    val name: String,
    val url: String?,
    val version: String?,
    val enabled: Boolean = true,
    val installedAt: Long,
    val source: String? = null,
    val info: PluginInfo? = null,
    val loadError: String? = null,
    val hash: String = ""
) {
    /** 引擎内唯一标识：platform + hash，用于日志与去重展示。 */
    val id: String get() = "${info?.platform ?: name}#$hash"
}

data class SubscriptionRecord(
    val url: String,
    val addedAt: Long
)

data class UserVariable(
    val pluginKey: String,
    val varKey: String,
    val varValue: String
)

/**
 * 搜索结果条目：保留原始 JSON 以便后续调用 getMediaSource / getAlbumInfo。
 * 对应 RN 的 IMusicItem / IAlbumItem / IArtistItem / IMusicSheetItem 通用基类。
 */
data class SearchEntry(
    val plugin: String,
    val raw: JSONObject
) {
    val type: String get() = raw.optString("type", "music").ifBlank { "music" }
    val id: String get() = raw.optString("id", "")
    val title: String get() = raw.optString("title", "")
    val artist: String get() = raw.optString("artist", "")
    val album: String get() = raw.optString("album", "")
    val artwork: String get() = raw.optString("artwork", "")
        .ifBlank { raw.optString("coverImg", "") }
    /** 歌手条目用 avatar / name 字段。 */
    val avatar: String get() = raw.optString("avatar", "").ifBlank { artwork }
    val name: String get() = raw.optString("name", "").ifBlank { title }
    val worksNum: Int get() = raw.optInt("worksNum", 0)
    val descriptionText: String
        get() = raw.optString("description", "").ifBlank { raw.optString("desc", "") }
    val duration: Long get() = raw.optLong("duration", 0L)
}

/** 推荐歌单标签（getRecommendSheetTags 返回的 pinned / data 组内元素）。 */
data class RecommendTag(
    val id: String,
    val title: String
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title)
}

data class SheetEntry(
    val plugin: String,
    val raw: JSONObject
) {
    val title: String get() = raw.optString("title", "")
    val artwork: String get() = raw.optString("artwork", "")
        .ifBlank { raw.optString("coverImg", "") }
    val description: String get() = raw.optString("description", "")
    val musicList: List<SearchEntry> get() {
        val arr = raw.optJSONArray("musicList") ?: return emptyList()
        return (0 until arr.length()).map { SearchEntry(plugin, arr.optJSONObject(it) ?: JSONObject()) }
    }
}

data class TopListEntry(
    val plugin: String,
    val raw: JSONObject
) {
    val title: String get() = raw.optString("title", "")
    val artwork: String get() = raw.optString("artwork", "")
        .ifBlank { raw.optString("coverImg", "") }
}

/**
 * 首屏的一个分区。
 */
sealed class HomeSection {
    data class Recommend(
        val plugin: String,
        val tagTitle: String,
        val items: List<SheetEntry>
    ) : HomeSection()

    data class Ranking(
        val plugin: String,
        val listTitle: String,
        val items: List<TopListEntry>
    ) : HomeSection()

    data class Error(val plugin: String, val message: String) : HomeSection()
}