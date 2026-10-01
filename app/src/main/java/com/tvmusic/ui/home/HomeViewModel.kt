package com.tvmusic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.model.HomeSection
import com.tvmusic.model.SheetEntry
import com.tvmusic.model.TopListEntry
import com.tvmusic.model.PluginRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.tvmusic.constants.PluginMethod

/**
 * 首页：只加载「当前选中插件」的推荐歌单与排行榜，
 * 用户可通过顶部音源切换器自由切换到其他插件。
 */
class HomeViewModel(private val app: TvMusicApp) : ViewModel() {

    /** 引擎/仓库异步初始化，构造时不直接访问 lateinit（未就绪即崩溃）。
     *  init 协程中 awaitEngineReady 后再获取，后续访问用 ?. 安全调用。 */
    private var runtime: com.tvmusic.plugin.PluginRuntime? = null
    private var repository: com.tvmusic.plugin.PluginRepository? = null
    private val appContext = app.applicationContext

    private val _sections = MutableStateFlow<List<HomeSection>>(emptyList())
    val sections: StateFlow<List<HomeSection>> = _sections.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 初始化级错误（P0-6）：引擎就绪等待失败/未赋值时显示，用户可点"重试"。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 当前选中的插件 platform；null 表示尚未选定。 */
    private val _currentPlatform = MutableStateFlow<String?>(null)
    val currentPlatform: StateFlow<String?> = _currentPlatform.asStateFlow()

    /** 可切换的音源列表（已启用且有元信息）。 */
    private val _availablePlugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val availablePlugins: StateFlow<List<PluginRecord>> = _availablePlugins.asStateFlow()

    private val PLUGIN_CALL_TIMEOUT_MS = 15_000L

    /** 首页加载代数：切换音源时 +1，旧加载结果回写前比对，防止旧内容覆盖新音源。 */
    private var loadGeneration = 0
    private var loadJob: kotlinx.coroutines.Job? = null

    /** 插件列表收集器只挂一次（retry 重入保护）。 */
    private val pluginsCollectorStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // 引擎/仓库异步初始化：先在后台等待就绪，再获取引用并开始监听插件列表。
        viewModelScope.launch(Dispatchers.IO) { bootstrap() }
    }

    /** 等待引擎就绪并挂上插件监听；失败写 _error（由 UI 显示重试）。 */
    private suspend fun bootstrap() {
        // P0-6：awaitEngineReady 的返回值原来被忽略，初始化失败时下面直接取
        // app.runtime（lateinit 未赋值）→ UninitializedPropertyAccessException 开屏即崩。
        if (!app.awaitEngineReady() || app.runtimeOrNull() == null || app.repositoryOrNull() == null) {
            _error.value = "引擎初始化失败，请重试或重启应用"
            _loading.value = false
            return
        }
        runtime = app.runtimeOrNull()
        repository = app.repositoryOrNull()
        // 插件列表变化时刷新可选项（不重新加载当前插件）。
        // 必须在 repository 赋值之后再挂收集器——原来放 init 顶层时 repository 还是 null，
        // `repository?.plugins` 直接返回 null，收集器从未挂上，装新插件后首页音源
        // 列表永远不刷新（要重启应用才可见）。
        // bootstrap 自己会一直挂在 ready.collect 上，故这里走 viewModelScope 起独立协程；
        // retry() 会重入 bootstrap，用 CAS 保证收集器只挂一次。
        if (pluginsCollectorStarted.compareAndSet(false, true)) {
            viewModelScope.launch(Dispatchers.IO) {
                repository?.plugins?.collect { refreshPlugins() }
            }
        }
        // 插件列表就绪后，选定第一个可用插件并加载；列表变化时刷新可选项。
        repository?.ready?.collect { isReady ->
            if (!isReady) return@collect
            refreshPlugins()
            // 首次选定第一个可用插件
            if (_currentPlatform.value == null) {
                pickFirst()
            }
        }
    }

    /** 「重试」按钮（P0-6）：引擎可能只是瞬时失败，重试比让用户重启应用代价小。 */
    fun retry() {
        if (_error.value == null) {
            load()
            return
        }
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) { bootstrap() }
    }

    private fun refreshPlugins() {
        // 按用户配置的插件优先级排列（远程管理「音源与插件」的顺序），
        // 配置里没提到的保持在末尾原次序；默认选中即配置里的第一个音源
        val order = com.tvmusic.config.SearchSettings.load(appContext).sourceOrder
        val enabled = repository?.listEnabled()?.filter { it.info != null && it.loadError.isNullOrBlank() }
            ?: emptyList()
        _availablePlugins.value = com.tvmusic.config.SearchSettings.ordered(
            enabled,
            order
        ) { it.info?.platform ?: it.name }
    }

    private fun pickFirst() {
        val first = _availablePlugins.value.firstOrNull() ?: return
        _currentPlatform.value = first.info?.platform ?: return
        load()
    }

    /** 切换到指定插件并加载。 */
    fun selectPlugin(platform: String) {
        if (platform == _currentPlatform.value) return
        _currentPlatform.value = platform
        load()
    }

    fun load() {
        val platform = _currentPlatform.value ?: return
        val plugin = _availablePlugins.value.firstOrNull { it.info?.platform == platform } ?: return
        // 切换音源时旧加载必须作废：旧实现 loading 中直接 return，导致切换请求被丢弃，
        // 旧协程跑完还会把旧音源内容写回（新音源名下显示旧内容）
        val gen = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            _loading.value = true
            _sections.value = emptyList()
            val fresh = mutableListOf<HomeSection>()
            val pf = plugin.info?.platform ?: return@launch

            // getTopLists：走并行引擎池（与搜索同路由），不被播放/搜索独占主引擎而挤成 busy
            try {
                if (runtime?.hasMethod(pf, PluginMethod.TOP_LISTS) == true) {
                    val top = runtime?.callParallel(pf, PluginMethod.TOP_LISTS, timeoutMs = PLUGIN_CALL_TIMEOUT_MS)
                    if (top !is NotImplementedError) {
                        val arr = top as? JSONArray
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val listObj = arr.optJSONObject(i) ?: continue
                                val data = listObj.optJSONArray("data") ?: continue
                                val items = (0 until data.length()).mapNotNull { j ->
                                    val it2 = data.optJSONObject(j) ?: return@mapNotNull null
                                    it2.optString("title").takeIf { t -> t.isNotBlank() }
                                        ?.let { TopListEntry(pf, it2) }
                                }
                                if (items.isEmpty()) continue
                                fresh += HomeSection.Ranking(
                                    plugin.name,
                                    listObj.optString("title", "排行榜"),
                                    items
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                fresh += HomeSection.Error(plugin.name, "排行榜失败: ${e.message}")
            }

            // getRecommendSheetTags：同走并行引擎池，避免主引擎队列空闲等待
            try {
                if (runtime?.hasMethod(pf, PluginMethod.RECOMMEND_TAGS) == true) {
                    val tags = runtime?.callParallel(pf, PluginMethod.RECOMMEND_TAGS, timeoutMs = PLUGIN_CALL_TIMEOUT_MS)
                    if (tags !is NotImplementedError) {
                        val groups: JSONArray? = (tags as? JSONObject)?.optJSONArray("data")
                            ?: (tags as? JSONArray)
                        if (groups != null) {
                            for (i in 0 until groups.length()) {
                                val tag = groups.optJSONObject(i) ?: continue
                                val data = tag.optJSONArray("data") ?: continue
                                val sheets = (0 until data.length()).mapNotNull { j ->
                                    val s = data.optJSONObject(j) ?: return@mapNotNull null
                                    s.optString("title").takeIf { it.isNotBlank() }
                                        ?.let { SheetEntry(pf, s) }
                                }
                                if (sheets.isEmpty()) continue
                                fresh += HomeSection.Recommend(
                                    plugin.name,
                                    tag.optString("title", "推荐"),
                                    sheets
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                fresh += HomeSection.Error(plugin.name, "推荐歌单失败: ${e.message}")
            }

            if (fresh.isEmpty()) {
                fresh += HomeSection.Error(plugin.name, "该插件未提供首页推荐/排行榜数据")
            }
            // 回写前校验：期间用户已切换音源则丢弃本次结果（阻塞中的 JS 调用无法中断，
            // 但至少保证旧内容不覆盖新音源页面）
            if (gen == loadGeneration && platform == _currentPlatform.value) {
                _sections.value = fresh
                _loading.value = false
            }
        }
    }
}
