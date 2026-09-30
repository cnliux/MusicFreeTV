package com.tvmusic.model

import org.json.JSONObject

/**
 * 搜索结果条目：保留原始 JSON 以便后续调用 getMediaSource / getAlbumInfo。
 * 对应 RN 的 IMusicItem / IAlbumItem / IArtistItem / IMusicSheetItem 通用基类。
 */
data class SearchEntry(
    override val plugin: String,
    override val raw: JSONObject
) : PluginEntry(plugin, raw) {
    /** 歌手条目用 avatar / name 字段。 */
    val avatar: String get() = raw.optString("avatar", "").ifBlank { artwork }
    val name: String get() = raw.optString("name", "").ifBlank { title }
    val worksNum: Int get() = raw.optInt("worksNum", 0)
    val descriptionText: String get() = description
    val duration: Long get() = durationMs
}

/** 推荐歌单标签（getRecommendSheetTags 返回的 pinned / data 组内元素）。 */
data class RecommendTag(
    val id: String,
    val title: String
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title)
}

/** 推荐/搜索结果里的歌单卡片。 */
data class SheetEntry(
    override val plugin: String,
    override val raw: JSONObject
) : PluginEntry(plugin, raw) {
    val musicList: List<SearchEntry> get() {
        val arr = raw.optJSONArray("musicList") ?: return emptyList()
        return (0 until arr.length()).map { SearchEntry(plugin, arr.optJSONObject(it) ?: JSONObject()) }
    }
}

/** 排行榜条目。 */
data class TopListEntry(
    override val plugin: String,
    override val raw: JSONObject
) : PluginEntry(plugin, raw)

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
