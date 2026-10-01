package com.tvmusic.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.config.SearchSettings
import com.tvmusic.core.TvMusicApp
import com.tvmusic.model.DetailKind
import com.tvmusic.model.DetailTarget
import com.tvmusic.model.SearchEntry
import com.tvmusic.player.PlayerManager
import com.tvmusic.player.QueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.tvmusic.constants.PluginMethod

/**
 * 单个插件的一页搜索结果（参考 MusicFree 2.0 的 per-plugin 状态 + lx-music 的
 * loading/idle/end/error 状态机）。
 */
data class SearchGroup(
    val plugin: String,
    val type: String,
    val entries: List<SearchEntry>,
    val page: Int,
    val isEnd: Boolean,
    val error: String? = null
) {
    val hasContent: Boolean get() = entries.isNotEmpty()
}

/** 结果时长筛选预设（duration 为 0 的未知条目在不限时放行，选具体范围时排除）。 */
enum class DurationFilter(val label: String, val minSec: Int?, val maxSec: Int?) {
    ALL("不限", null, null),
    SHORT("<1分钟", 1, 59),
    MID("1-5分钟", 60, 299),
    LONG(">5分钟", 300, null)
}

/** 搜索页面的整体阶段。 */
sealed interface SearchPhase {
    object Idle : SearchPhase
    data class Searching(val done: Int, val total: Int) : SearchPhase
    object Ready : SearchPhase
    data class NoResult(val message: String = "没有搜索结果，换个关键词，或确认已启用能搜索的插件。") : SearchPhase
}

class SearchViewModel(app: TvMusicApp) : ViewModel() {

    private val application = app
    /** 引擎/仓库异步初始化，构造时不直接访问 lateinit。 */
    private var runtime: com.tvmusic.plugin.PluginRuntime? = null
    private var repository: com.tvmusic.plugin.PluginRepository? = null

    private val prefs = app.getSharedPreferences("search_prefs", android.content.Context.MODE_PRIVATE)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 当前搜索类型：music / album / artist / sheet（对应 RN 结果页四个 Tab）。 */
    private val _selectedType = MutableStateFlow(TYPE_MUSIC)
    val selectedType: StateFlow<String> = _selectedType.asStateFlow()

    /** 音源筛选：空集合表示全平台聚合，非空表示只搜选中的音源。 */
    private val _selectedSources = MutableStateFlow<Set<String>>(emptySet())
    val selectedSources: StateFlow<Set<String>> = _selectedSources.asStateFlow()

    /** 结果属性过滤。 */
    private val _durFilter = MutableStateFlow(DurationFilter.ALL)
    val durFilter: StateFlow<DurationFilter> = _durFilter.asStateFlow()

    private val _needArtwork = MutableStateFlow(false)
    val needArtwork: StateFlow<Boolean> = _needArtwork.asStateFlow()

    /** 结果排序：分组内排序字段（与后台配置共享语义），default = 保持插件顺序。 */
    private val _sortBy = MutableStateFlow(SearchSettings.SORT_DEFAULT)
    val sortBy: StateFlow<String> = _sortBy.asStateFlow()

    private val _sortAsc = MutableStateFlow(true)
    val sortAsc: StateFlow<Boolean> = _sortAsc.asStateFlow()

    /** 用户手动改过排序后，不再跟随后台默认排序。 */
    private var sortManuallyTouched = false

    /** 当前可搜索的音源名称列表（用于筛选条）。 */
    private val _searchablePlatforms = MutableStateFlow<List<String>>(emptyList())
    val searchablePlatforms: StateFlow<List<String>> = _searchablePlatforms.asStateFlow()

    private val _phase = MutableStateFlow<SearchPhase>(SearchPhase.Idle)
    val phase: StateFlow<SearchPhase> = _phase.asStateFlow()

    private val _groups = MutableStateFlow<List<SearchGroup>>(emptyList())
    val groups: StateFlow<List<SearchGroup>> = _groups.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** 正在追加下一页的插件名。 */
    private val _loadingMore = MutableStateFlow<String?>(null)
    val loadingMore: StateFlow<String?> = _loadingMore.asStateFlow()

    /** 防过期会话号：搜索词/类型变化后旧请求结果直接丢弃（lx-music 的 key 思路）。 */
    private var session = 0
    private var currentQuery = ""

    /** 当前聚合搜索 Job：新一次 submit 先取消旧任务，避免旧请求继续占用引擎/网络。 */
    private var searchJob: Job? = null

    init {
        loadHistory()
        val cfg = SearchSettings.load(app)
        _sortBy.value = cfg.sortBy
        _sortAsc.value = cfg.asc
        // 插件列表变化（如远程启用/停用）时刷新：仅已在结果页时重建。
        // 引擎/仓库异步初始化：先在后台等待就绪，再获取引用并开始监听。
        viewModelScope.launch(Dispatchers.IO) {
            // P0-6：等待失败/未就绪直接返回：原来无条件取 lateinit 的 app.runtime，
            // 引擎未就绪（低配盒子 30s 超时/ABI 不符）会让搜索页开屏崩溃。
            if (!app.awaitEngineReady() || app.repositoryOrNull() == null) return@launch
            runtime = app.runtimeOrNull()
            repository = app.repositoryOrNull()
            repository?.plugins?.collect {
                _searchablePlatforms.value = repository?.listEnabled()
                    ?.mapNotNull { it.info }
                    ?.filter { it.supportedSearchType.isEmpty() || TYPE_MUSIC in it.supportedSearchType }
                    ?.map { it.platform }
                    ?.distinct() ?: emptyList()
                if (_phase.value is SearchPhase.Ready) rebuildFromCache()
            }
        }
    }

    private fun loadHistory() {
        val s = prefs.getString(KEY_HISTORY, null) ?: return
        _history.value = try {
            JSONArray(s).let { a -> (0 until a.length()).map { a.optString(it) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<String>) {
        prefs.edit()
            .putString(KEY_HISTORY, JSONArray().apply { list.forEach { put(it) } }.toString())
            .apply()
    }

    fun setQuery(text: String) {
        if (_query.value != text) {
            _query.value = text
            if (text.isBlank() && _phase.value !is SearchPhase.Searching) {
                _phase.value = SearchPhase.Idle
            }
        }
    }

    /** 切换结果类型；若当前已有搜索词，立即按新类型重搜。 */
    fun setType(type: String) {
        if (type == _selectedType.value) return
        _selectedType.value = type
        if (currentQuery.isNotBlank()) {
            submit(currentQuery, type)
        }
    }

    /** 切换音源多选；空集 = 全部。已搜索则立即重搜。 */
    fun toggleSource(platform: String) {
        val cur = _selectedSources.value
        _selectedSources.value = if (platform in cur) cur - platform else cur + platform
        if (currentQuery.isNotBlank()) submit(currentQuery, _selectedType.value)
    }

    fun selectAllSources() {
        if (_selectedSources.value.isEmpty()) return
        _selectedSources.value = emptySet()
        if (currentQuery.isNotBlank()) submit(currentQuery, _selectedType.value)
    }

    fun setDurFilter(filter: DurationFilter) {
        if (filter == _durFilter.value) return
        _durFilter.value = filter
        if (currentQuery.isNotBlank()) submit(currentQuery, _selectedType.value)
    }

    fun setNeedArtwork(need: Boolean) {
        if (need == _needArtwork.value) return
        _needArtwork.value = need
        if (currentQuery.isNotBlank()) submit(currentQuery, _selectedType.value)
    }

    fun setSortBy(sort: String) {
        if (sort == _sortBy.value) return
        sortManuallyTouched = true
        _sortBy.value = sort
        if (currentQuery.isNotBlank()) submit(currentQuery, _selectedType.value)
    }

    fun toggleSortAsc() {
        sortManuallyTouched = true
        _sortAsc.value = !_sortAsc.value
        if (currentQuery.isNotBlank()) submit(currentQuery, _selectedType.value)
    }

    fun submit(phrase: String = _query.value, type: String = _selectedType.value) {
        val q = phrase.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            // 并发发起各音源搜索请求；QuickJS 引擎内部路由串行消化，不会真并发执行 JS。
            // session 计数仍保留：与 Job 取消配合，双重防止旧结果覆盖新结果。
            // 每次重读后台配置，保证 web 管理台改的优先级/默认排序实时生效。
            val cfg = SearchSettings.load(application)
            if (!sortManuallyTouched) {
                _sortBy.value = cfg.sortBy
                _sortAsc.value = cfg.asc
            }
            _query.value = q
            _selectedType.value = type
            _groups.value = emptyList()
            _loadingMore.value = null
            currentQuery = q
            val my = ++session
            val filter = _selectedSources.value
            val records = (repository?.listEnabled() ?: emptyList())
                .filter { it.info != null && it.loadError == null }
            val byPlatform = records.associateBy { it.info?.platform ?: it.name }
            val ordered = SearchSettings.ordered(
                records.map { it.info?.platform ?: it.name }
                    .filter { filter.isEmpty() || it in filter },
                cfg.sourceOrder
            )
            _phase.value = SearchPhase.Searching(0, ordered.size)
            val groupsArr = arrayOfNulls<SearchGroup>(ordered.size)
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val pubLock = Any()
            val searchStarted = android.os.SystemClock.elapsedRealtime()
            val firstResultLogged = java.util.concurrent.atomic.AtomicBoolean(false)
            coroutineScope {
                ordered.forEachIndexed { idx, platform ->
                    launch(Dispatchers.IO) {
                        if (idx >= SEARCH_FAST_BATCH) kotlinx.coroutines.delay(SEARCH_STAGGER_MS)
                        if (my != session) return@launch
                        val sourceStarted = android.os.SystemClock.elapsedRealtime()
                        val plugin = byPlatform.getValue(platform)
                        // 对齐 RN getSearchablePlugins(type)：插件声明了 supportedSearchType 时必须包含该类型
                        val supported = plugin.info?.supportedSearchType ?: emptyList()
                        val group: SearchGroup?
                        if (supported.isNotEmpty() && type !in supported) {
                            group = null
                        } else {
                            group = try {
                                val res = runtime?.callParallel(platform, PluginMethod.SEARCH, listOf(q, "1", type))
                                if (my != session) return@launch
                                if (res is NotImplementedError) {
                                    null
                                } else {
                                    val obj = res as? JSONObject
                                    val arr = obj?.optJSONArray("data") ?: (res as? JSONArray)
                                    val isEnd = obj?.optBoolean("isEnd", false) ?: true
                                    if (arr == null) null
                                    else {
                                        val list = parseEntries(arr, type, platform).filter { passFilter(it) }
                                        if (list.isEmpty()) null
                                        else SearchGroup(platform, type, sortEntries(list), page = 1, isEnd = isEnd)
                                    }
                                }
                            } catch (e: Exception) {
                                if (my != session) return@launch
                                SearchGroup(platform, type, emptyList(), page = 1, isEnd = true, error = e.message ?: "搜索失败")
                            }
                        }
                        var visible: List<SearchGroup>? = null
                        synchronized(pubLock) {
                            if (group != null) groupsArr[idx] = group
                            completed.incrementAndGet()
                            if (my == session) visible = groupsArr.filterNotNull()
                        }
                        val sourceElapsed = android.os.SystemClock.elapsedRealtime() - sourceStarted
                        android.util.Log.i(
                            "PerfSearch",
                            "source=$platform elapsed=${sourceElapsed}ms results=${group?.entries?.size ?: 0} strategy=staggered3"
                        )
                        if (my != session) return@launch
                        if (visible != null && visible.isNotEmpty()) {
                            if (firstResultLogged.compareAndSet(false, true)) {
                                android.util.Log.i(
                                    "PerfSearch",
                                    "first=${android.os.SystemClock.elapsedRealtime() - searchStarted}ms source=$platform"
                                )
                            }
                            _phase.value = SearchPhase.Searching(completed.get(), ordered.size)
                            _groups.value = visible
                        }
                    }
                }
            }
            if (my != session) return@launch
            android.util.Log.i(
                "PerfSearch",
                "done=${android.os.SystemClock.elapsedRealtime() - searchStarted}ms sources=${ordered.size} strategy=staggered3"
            )
            _searchingDone(groupsArr.none { it?.hasContent == true })
            addHistory(q)
        }
    }

    private fun _searchingDone(empty: Boolean) {
        _phase.value = if (empty) SearchPhase.NoResult() else SearchPhase.Ready
    }

    /** 追加某一插件的下一页（lx 的 onEndReached 守卫：仅当还有更多时）。 */
    fun loadMore(group: SearchGroup) {
        if (group.isEnd || group.error != null || _loadingMore.value != null) return
        val q = currentQuery
        if (q.isEmpty()) return
        val next = group.page + 1
        val type = _selectedType.value
        viewModelScope.launch(Dispatchers.Default) {
            _loadingMore.value = group.plugin
            val my = session
            try {
                // 粘性 home 引擎：与聚合搜索同路由，避免翻页请求长期占用 primary 拖慢播放解析
                val res = runtime?.callParallel(group.plugin, PluginMethod.SEARCH, listOf(q, next.toString(), type))
                if (my != session) return@launch
                val obj = res as? JSONObject
                val arr = obj?.optJSONArray("data") ?: (res as? JSONArray)
                val isEnd = obj?.optBoolean("isEnd", false) ?: true
                _groups.value = _groups.value.map { g ->
                    if (g.plugin == group.plugin && arr != null) {
                        val more = parseEntries(arr, type, group.plugin).filter { passFilter(it) }
                        g.copy(page = next, isEnd = isEnd, entries = g.entries + sortEntries(more), error = null)
                    } else g
                }
            } catch (e: Exception) {
                if (my != session) return@launch
                _groups.value = _groups.value.map { g ->
                    if (g.plugin == group.plugin) g.copy(error = e.message ?: "加载失败")
                    else g
                }
            } finally {
                _loadingMore.value = null
            }
        }
    }

    /** 单独重试某一插件的首页搜索。 */
    fun retry(group: SearchGroup) {
        val q = currentQuery
        if (q.isEmpty()) return
        val type = _selectedType.value
        viewModelScope.launch(Dispatchers.Default) {
            val my = session
            try {
                // 粘性 home 引擎：与聚合搜索同路由，避免占用 primary 拖慢播放解析
                val res = runtime?.callParallel(group.plugin, PluginMethod.SEARCH, listOf(q, "1", type))
                if (my != session) return@launch
                val obj = res as? JSONObject
                val arr = obj?.optJSONArray("data") ?: (res as? JSONArray)
                val isEnd = obj?.optBoolean("isEnd", false) ?: true
                _groups.value = _groups.value.map { g ->
                    if (g.plugin == group.plugin && arr != null) {
                        val list = parseEntries(arr, type, group.plugin).filter { passFilter(it) }
                        g.copy(type = type, entries = sortEntries(list), page = 1, isEnd = isEnd, error = null)
                    } else g
                }
            } catch (e: Exception) {
                if (my != session) return@launch
                _groups.value = _groups.value.map { g ->
                    if (g.plugin == group.plugin) g.copy(error = e.message ?: "搜索失败")
                    else g
                }
            }
        }
    }

    /** 插件启停变化后，过滤掉已禁用插件的分组。 */
    private fun rebuildFromCache() {
        val enabled = (repository?.listEnabled() ?: emptyList()).filter { it.info != null && it.loadError == null }
            .map { it.info?.platform ?: it.name }.toSet()
        _groups.value = _groups.value.filter { it.plugin in enabled }
    }

    /** 结果属性过滤：时长范围 + 必须要有封面。 */
    private fun passFilter(e: SearchEntry): Boolean {
        val f = _durFilter.value
        if (f != DurationFilter.ALL) {
            val sec = e.duration / 1000
            if (sec <= 0) return false
            if (f.minSec != null && sec < f.minSec) return false
            if (f.maxSec != null && sec > f.maxSec) return false
        }
        if (_needArtwork.value && e.artwork.isBlank()) return false
        return true
    }

    /** 按当前排序配置排序；default 时保持插件原始顺序。 */
    private fun sortEntries(list: List<SearchEntry>): List<SearchEntry> {
        if (_sortBy.value == SearchSettings.SORT_DEFAULT) return list
        val cmp = SearchSettings.comparator<SearchEntry>(
            _sortBy.value, _sortAsc.value,
            { it.title }, { it.artist }, { it.duration }
        )
        return list.sortedWith(cmp)
    }

    private fun parseEntries(arr: JSONArray, type: String, fallbackPlatform: String): List<SearchEntry> {
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // 歌手条目用 name，其余用 title
            val display = if (type == TYPE_ARTIST) {
                o.optString("name", "").ifBlank { o.optString("title", "") }
            } else {
                o.optString("title", "")
            }
            if (display.isBlank()) return@mapNotNull null
            // RN resetMediaItem：插件返回的条目必须带 platform，缺失时用当前插件补齐
            if (!o.has("platform") || o.optString("platform").isBlank()) {
                o.put("platform", fallbackPlatform)
            }
            SearchEntry(o.optString("platform", fallbackPlatform), o)
        }
    }

    /** 播放某条结果：当前所有单曲结果作为播放队列（参考 lx handlePlay → playList）。 */
    fun play(entry: SearchEntry) {
        val queue = _groups.value
            .flatMap { g -> g.entries }
            .filter { it.type == TYPE_MUSIC }
            .map { QueueEntry(it.plugin, it.raw) }
        // JSONObject == 是引用相等，跨 session 重建队列后永远 -1 会静默播第 0 首（R6 记录的事故形态）
        val targetKey = com.tvmusic.data.PlaybackStore.favKeyOf(entry.raw)
        val idx = queue.indexOfFirst { com.tvmusic.data.PlaybackStore.favKeyOf(it.raw) == targetKey }.coerceAtLeast(0)
        if (queue.isNotEmpty()) {
            PlayerManager.play(entry.plugin, queue[idx], queue, idx, source = "${entry.plugin} · 搜索结果")
        }
    }

    /** 专辑 / 歌手 / 歌单结果点击 → 对应详情页。 */
    fun openDetail(entry: SearchEntry): DetailTarget? {
        val kind = when (entry.type) {
            TYPE_ALBUM -> DetailKind.ALBUM
            TYPE_SHEET -> DetailKind.SHEET
            TYPE_ARTIST -> DetailKind.ARTIST
            else -> return null
        }
        return DetailTarget.stamped(entry.plugin, kind, JSONObject(entry.raw.toString()))
    }

    fun addHistory(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val list = (listOf(w) + _history.value.filter { it != w }).take(MAX_HISTORY)
        _history.value = list
        saveHistory(list)
    }

    fun removeHistory(word: String) {
        val list = _history.value.filter { it != word }
        _history.value = list
        saveHistory(list)
    }

    fun clearHistory() {
        _history.value = emptyList()
        saveHistory(emptyList())
    }

    fun useHistory(word: String) {
        setQuery(word)
        submit(word)
    }

    companion object {
        const val TYPE_MUSIC = "music"
        const val TYPE_ALBUM = "album"
        const val TYPE_ARTIST = "artist"
        const val TYPE_SHEET = "sheet"

        val SEARCH_TYPES = listOf(
            TYPE_MUSIC to "单曲",
            TYPE_ALBUM to "专辑",
            TYPE_ARTIST to "歌手",
            TYPE_SHEET to "歌单"
        )

        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 15
        /** 首批立即搜索的音源数；其余源短暂错峰，降低低配 TV 瞬时争抢。 */
        private const val SEARCH_FAST_BATCH = 3
        private const val SEARCH_STAGGER_MS = 350L
    }
}
