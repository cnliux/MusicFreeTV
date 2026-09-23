package com.tvmusic.ui.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.PluginRecord
import com.tvmusic.data.RecommendTag
import com.tvmusic.data.SheetEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推荐歌单页 VM。对齐 RN recommendSheets：
 *  1. 找出实现 getRecommendSheetsByTag 的插件作为页签；
 *  2. 进插件先调 getRecommendSheetTags 拿 pinned + 分组标签；
 *  3. getRecommendSheetsByTag(tag, page) 分页拉取歌单网格。
 */
class RecommendViewModel(
    app: TvMusicApp,
    /** 首页「更多」跳转时希望优先选中的插件 platform。 */
    private val initialPlatform: String? = null
) : ViewModel() {

    private val runtime = app.runtime
    private val repository = app.repository

    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    private val _selectedPlatform = MutableStateFlow("")
    val selectedPlatform: StateFlow<String> = _selectedPlatform.asStateFlow()

    private val _tags = MutableStateFlow<List<RecommendTag>>(emptyList())
    val tags: StateFlow<List<RecommendTag>> = _tags.asStateFlow()

    private val _selectedTag = MutableStateFlow(DEFAULT_TAG)
    val selectedTag: StateFlow<RecommendTag> = _selectedTag.asStateFlow()

    private val _sheets = MutableStateFlow<List<SheetEntry>>(emptyList())
    val sheets: StateFlow<List<SheetEntry>> = _sheets.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _isEnd = MutableStateFlow(true)
    val isEnd: StateFlow<Boolean> = _isEnd.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var page = 1
    private var switchSession = 0

    init {
        viewModelScope.launch {
            repository.ready.first { it }
            probePlugins()
            repository.plugins.collectLatest {
                // 插件启停 / 新装后重新探测
                probePlugins(keepSelection = true)
            }
        }
    }

    private suspend fun probePlugins(keepSelection: Boolean = false) {
        val able = repository.listEnabled().filter { rec ->
            rec.info != null && rec.loadError == null &&
                runCatching { runtime.hasMethod(rec.info.platform, "getRecommendSheetsByTag") }
                    .getOrDefault(false)
        }
        _plugins.value = able
        if (able.isEmpty()) {
            _loading.value = false
            _error.value = "已启用的插件均不支持「推荐歌单」"
            return
        }
        val current = _selectedPlatform.value
        val target = when {
            keepSelection && able.any { it.info!!.platform == current } -> current
            initialPlatform != null && able.any { it.info!!.platform == initialPlatform } -> initialPlatform
            else -> able.first().info!!.platform
        }
        if (!keepSelection || target != current) selectPlugin(target)
    }

    fun selectPlugin(platform: String) {
        if (platform == _selectedPlatform.value && _tags.value.isNotEmpty()) return
        val my = ++switchSession
        _selectedPlatform.value = platform
        _tags.value = emptyList()
        _selectedTag.value = DEFAULT_TAG
        _sheets.value = emptyList()
        _isEnd.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.Default) {
            _loading.value = true
            _tags.value = loadTags(platform)
            if (my != switchSession) return@launch
            loadFirstPage(platform, DEFAULT_TAG, my)
        }
    }

    fun selectTag(tag: RecommendTag) {
        if (tag.id == _selectedTag.value.id) return
        _selectedTag.value = tag
        loadFirstPage(_selectedPlatform.value, tag, ++switchSession)
    }

    private fun loadFirstPage(platform: String, tag: RecommendTag, session: Int) {
        page = 1
        viewModelScope.launch(Dispatchers.Default) {
            _loading.value = true
            _error.value = null
            _sheets.value = emptyList()
            val fetched = fetchPage(platform, tag, 1)
            if (session != switchSession) return@launch
            if (fetched == null) {
                _error.value = "歌单加载失败，换个标签或插件试试"
            } else {
                _sheets.value = fetched.first
                _isEnd.value = fetched.second
                if (!fetched.second) page = 2
            }
            _loading.value = false
        }
    }

    fun loadMore() {
        if (_loading.value || _loadingMore.value || _isEnd.value) return
        val platform = _selectedPlatform.value
        val tag = _selectedTag.value
        val pageNo = page
        val session = switchSession
        viewModelScope.launch(Dispatchers.Default) {
            _loadingMore.value = true
            try {
                val fetched = fetchPage(platform, tag, pageNo)
                if (session != switchSession) return@launch
                if (fetched != null) {
                    _sheets.value = _sheets.value + fetched.first
                    _isEnd.value = fetched.second
                    if (!fetched.second) page = pageNo + 1
                    _error.value = null
                } else {
                    _isEnd.value = true
                }
            } catch (e: Exception) {
                if (session == switchSession) _error.value = "加载更多失败: ${e.message}"
            } finally {
                _loadingMore.value = false
            }
        }
    }

    private suspend fun fetchPage(
        platform: String,
        tag: RecommendTag,
        pageNo: Int
    ): Pair<List<SheetEntry>, Boolean>? {
        // 插件方法内部抛错（如某些音源的 getRecommendSheetsByTag 未实现/网络失败）
        // 会以 PluginCallException 冒泡，必须在这里兜住，否则 viewModelScope 未捕获异常直接杀进程。
        val res = try {
            runtime.callAsync(platform, "getRecommendSheetsByTag", tag.toJson(), pageNo)
        } catch (e: Exception) {
            android.util.Log.w("RecommendVM", "getRecommendSheetsByTag $platform p$pageNo failed: ${e.message}")
            return null
        }
        if (res is NotImplementedError) return null
        val obj = res as? JSONObject
        val arr = obj?.optJSONArray("data") ?: (res as? JSONArray) ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("title").isBlank()) return@mapNotNull null
            SheetEntry(platform, o)
        }
        val isEnd = obj?.optBoolean("isEnd", true) ?: true
        return list to isEnd
    }

    /** getRecommendSheetTags -> { pinned: [tag], data: [{ title, data: [tag] }] }。 */
    private suspend fun loadTags(platform: String): List<RecommendTag> {
        val out = linkedMapOf<String, RecommendTag>()
        out[DEFAULT_TAG.id] = DEFAULT_TAG
        if (!runCatching { runtime.hasMethod(platform, "getRecommendSheetTags") }.getOrDefault(false)) {
            return out.values.toList()
        }
        return try {
            val res = runtime.callAsync(platform, "getRecommendSheetTags")
            if (res is NotImplementedError) return out.values.toList()
            val root = res as? JSONObject ?: return out.values.toList()
            root.optJSONArray("pinned")?.let { pinned ->
                addTags(pinned, out)
            }
            root.optJSONArray("data")?.let { groups ->
                for (i in 0 until groups.length()) {
                    // 分组元素的 data 才是标签列表；没有 data 时元素自身也可能是标签
                    val g = groups.optJSONObject(i) ?: continue
                    val inner = g.optJSONArray("data")
                    if (inner != null) addTags(inner, out) else addTag(g, out)
                }
            }
            out.values.toList()
        } catch (e: Exception) {
            out.values.toList()
        }
    }

    private fun addTags(arr: JSONArray, out: LinkedHashMap<String, RecommendTag>) {
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { addTag(it, out) }
        }
    }

    private fun addTag(o: JSONObject, out: LinkedHashMap<String, RecommendTag>) {
        val id = o.optString("id")
        val title = o.optString("title")
        if (id.isBlank() || title.isBlank()) return
        out.putIfAbsent(id, RecommendTag(id, title))
    }

    companion object {
        val DEFAULT_TAG = RecommendTag("", "默认")
    }
}
