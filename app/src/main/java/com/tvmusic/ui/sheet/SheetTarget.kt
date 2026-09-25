package com.tvmusic.ui.sheet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.json.JSONObject

/**
 * 详情页类型，决定 SheetViewModel 调用哪个插件方法：
 *  - SHEET    -> getMusicSheetInfo(item, page)
 *  - TOPLIST  -> getTopListDetail(item, page)
 *  - ALBUM    -> getAlbumInfo(item, page)
 *  - ARTIST   -> getArtistWorks(item, page, "music")
 *  - IMPORT   -> importMusicSheet(urlLike)
 * 对应 RN 的 pluginSheetDetail / topListDetail / albumDetail / artistDetail 四个页面。
 */
enum class DetailKind { SHEET, TOPLIST, ALBUM, ARTIST, IMPORT }

/**
 * 详情页跳转的中转站：首页/推荐/排行榜/搜索点击入口时写入，SheetScreen 读取后执行一次并清空。
 * 避免把 JSON 塞进 Nav 路由做 URL 编码。
 */
data class DetailTarget(
    val plugin: String,
    val kind: DetailKind,
    val item: JSONObject,
    /** IMPORT 类型时传入的 URL/单曲ID 文本。 */
    val url: String? = null
) {
    companion object {
        /** 补齐插件协议要求的 platform 字段（RN resetMediaItem 做同样的事）。 */
        fun stamped(plugin: String, kind: DetailKind, raw: JSONObject, url: String? = null): DetailTarget {
            if (!raw.has("platform") || raw.optString("platform").isBlank()) {
                raw.put("platform", plugin)
            }
            return DetailTarget(plugin, kind, raw, url)
        }
    }
}

object SheetTarget {
    private val _target = MutableStateFlow<DetailTarget?>(null)

    fun set(value: DetailTarget?) {
        _target.value = value
    }

    /** 取出并清空（原子操作，防重复消费）。 */
    fun consume(): DetailTarget? = _target.getAndUpdate { null }
}
