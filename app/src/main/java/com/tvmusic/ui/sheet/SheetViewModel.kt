package com.tvmusic.ui.sheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.player.PlayerManager
import com.tvmusic.player.QueueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用详情页：歌单 / 排行榜 / 专辑 / 歌手作品 / 外链导入。
 * 协议对齐 MusicFree RN：
 *  - getMusicSheetInfo(item, page) -> { isEnd, sheetItem, musicList }
 *  - getTopListDetail(item, page)  -> { isEnd, topListItem, musicList }
 *  - getAlbumInfo(item, page)     -> { isEnd, albumItem, musicList }
 *  - getArtistWorks(item, page, "music") -> { isEnd, data }
 *  - importMusicSheet(urlLike)    -> IMusicItem[]
 */
class SheetViewModel(app: TvMusicApp) : ViewModel() {

    private val runtime = app.runtime

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _artwork = MutableStateFlow("")
    val artwork: StateFlow<String> = _artwork.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _entries = MutableStateFlow<List<JSONObject>>(emptyList())
    val entries: StateFlow<List<JSONObject>> = _entries.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 下一页页码（首页加载成功后为 2）。 */
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var plugin: String = ""
    private var kind: DetailKind = DetailKind.SHEET
    private var item: JSONObject = JSONObject()
    private var importUrl: String? = null

    /** 当前详情页的来源插件名（收藏条目时写入 platform 字段用）。 */
    val pluginName: String get() = plugin

    /** 记录确认未实现的方法，后续翻页不再重复尝试。 */
    private val notImplemented = mutableSetOf<String>()

    /** 下一次请求的页码（RN useTopListDetail 的 pageRef 思路）。 */
    private var nextPage = 1

    init {
        load()
    }

    private fun load() {
        val target = SheetTarget.consume() ?: run {
            _loading.value = false
            _error.value = "缺少详情数据"
            return
        }
        plugin = target.plugin
        kind = target.kind
        item = target.item
        importUrl = target.url
        loadInitial()
    }

    /** 首次加载/重试共用：SheetTarget 是一次性的，重试不能再读它（否则失败后永远"缺少详情数据"）。 */
    private fun loadInitial() {
        if (plugin.isBlank()) {
            _loading.value = false
            _error.value = "缺少详情数据"
            return
        }
        nextPage = 1
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _title.value = item.optString("title", "")
                .ifBlank { item.optString("name", "") }
            _artwork.value = item.optString("artwork", "")
                .ifBlank { item.optString("coverImg", "") }
                .ifBlank { item.optString("avatar", "") }
            // 条目自带 musicList（少数搜索结果直接内嵌）
            item.optJSONArray("musicList")?.let { arr ->
                val list = arr.toObjectList()
                if (list.isNotEmpty()) {
                    _entries.value = list
                    _hasMore.value = false
                    _loading.value = false
                    return@launch
                }
            }
            val page = fetchPage(nextPage)
            if (page == null) {
                _error.value = "无法解析详情内容（可能需要配置用户变量、插件不支持或已失效）"
            } else {
                applyHeader(page.header)
                _entries.value = page.music
                _hasMore.value = !page.isEnd && page.music.isNotEmpty()
                if (_hasMore.value) nextPage += 1
            }
            _loading.value = false
        }
    }

    /** 翻页：底部「加载更多」触发。 */
    fun loadMore() {
        if (!_hasMore.value || _loading.value || _loadingMore.value) return
        val pageNo = nextPage
        viewModelScope.launch {
            _loadingMore.value = true
            try {
                val page = fetchPage(pageNo)
                if (page != null) {
                    applyHeader(page.header)
                    _entries.value = _entries.value + page.music
                    _hasMore.value = !page.isEnd && page.music.isNotEmpty()
                    if (_hasMore.value) nextPage = pageNo + 1
                    _error.value = null
                } else {
                    _hasMore.value = false
                }
            } catch (e: Exception) {
                _error.value = "加载更多失败: ${e.message}"
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun play(index: Int) {
        val list = _entries.value
        if (index !in list.indices) return
        val queue = list.map { QueueEntry(plugin, it) }
        PlayerManager.play(plugin, queue[index], queue, index)
    }

    /** 播放全部：从头开始按顺序播放整个歌单。 */
    fun playAll() = play(0)

    fun retry() {
        notImplemented.clear()
        if (_loadingMore.value) {
            loadMore()
        } else if (plugin.isNotBlank()) {
            loadInitial()
        } else {
            load()
        }
    }

    private data class FetchedPage(
        val music: List<JSONObject>,
        val isEnd: Boolean,
        /** 返回结果里用于更新头部信息的子对象（topListItem / sheetItem / albumItem）。 */
        val header: JSONObject?
    )

    /** 按类型调用对应插件方法；首页时按优先级回退，未实现的方法记入黑名单。 */
    private suspend fun fetchPage(page: Int): FetchedPage? {
        return when (kind) {
            DetailKind.SHEET -> {
                callMusicListPage("getMusicSheetInfo", page, "sheetItem")
                    ?: callMusicListPage("getTopListDetail", page, "topListItem")
                    ?: if (page == 1) callImport() else null
            }
            DetailKind.TOPLIST -> {
                callMusicListPage("getTopListDetail", page, "topListItem")
                    ?: callMusicListPage("getMusicSheetInfo", page, "sheetItem")
            }
            DetailKind.ALBUM -> callMusicListPage("getAlbumInfo", page, "albumItem")
            DetailKind.ARTIST -> callArtistWorks(page)
            DetailKind.IMPORT -> if (page == 1) callImport() else null
        }
    }

    /** 调用返回 { isEnd, musicList } 形态的方法。 */
    private suspend fun callMusicListPage(
        method: String,
        page: Int,
        headerKey: String
    ): FetchedPage? {
        if (method in notImplemented) return null
        val res = try {
            runtime.callAsync(plugin, method, item, page)
        } catch (e: Exception) {
            // 首页允许回退到下一个候选方法；翻页直接抛给 UI 提示
            if (page == 1) {
                android.util.Log.w("SheetVM", "$method page1 failed: ${e.message}")
                return null
            } else throw e
        }
        if (res is NotImplementedError) {
            notImplemented += method
            return null
        }
        val obj = res as? JSONObject ?: return null
        val arr = obj.optJSONArray("musicList") ?: return null
        val music = arr.toObjectList()
        if (music.isEmpty() && page == 1) return null
        return FetchedPage(
            music = music,
            isEnd = obj.optBoolean("isEnd", true),
            header = obj.optJSONObject(headerKey)
        )
    }

    /** getArtistWorks(item, page, "music") -> { isEnd, data: IMusicItem[] }。 */
    private suspend fun callArtistWorks(page: Int): FetchedPage? {
        val method = "getArtistWorks"
        if (method in notImplemented) return null
        val res = try {
            runtime.callAsync(plugin, method, item, page, "music")
        } catch (e: Exception) {
            if (page == 1) return null else throw e
        }
        if (res is NotImplementedError) {
            notImplemented += method
            return null
        }
        val obj = res as? JSONObject
        val arr = obj?.optJSONArray("data") ?: (res as? JSONArray)
        val music = arr?.toObjectList().orEmpty()
        if (music.isEmpty() && page == 1) return null
        return FetchedPage(music, obj?.optBoolean("isEnd", true) ?: true, null)
    }

    /** importMusicSheet(urlLike) -> IMusicItem[]（数组或旧协议 {data:[]}）。 */
    private suspend fun callImport(): FetchedPage? {
        val url = importUrl ?: item.optString("url", "")
        if (url.isBlank()) return null
        val method = "importMusicSheet"
        if (method in notImplemented) return null
        val res = try {
            runtime.callAsync(plugin, method, listOf(url))
        } catch (e: Exception) {
            return null
        }
        if (res is NotImplementedError) {
            notImplemented += method
            return null
        }
        val arr = res as? JSONArray
            ?: (res as? JSONObject)?.optJSONArray("data")
            ?: return null
        val music = arr.toObjectList()
        return FetchedPage(music, isEnd = true, header = null)
    }

    private fun applyHeader(header: JSONObject?) {
        if (header == null) return
        header.optString("title").takeIf { it.isNotBlank() }?.let { _title.value = it }
        val art = header.optString("artwork", "")
            .ifBlank { header.optString("coverImg", "") }
            .ifBlank { header.optString("avatar", "") }
        if (art.isNotBlank()) _artwork.value = art
    }

    private fun JSONArray.toObjectList(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
