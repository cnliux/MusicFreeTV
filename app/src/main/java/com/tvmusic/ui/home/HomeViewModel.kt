package com.tvmusic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.HomeSection
import com.tvmusic.data.SheetEntry
import com.tvmusic.data.TopListEntry
import com.tvmusic.data.PluginRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页：只加载「当前选中插件」的推荐歌单与排行榜，
 * 用户可通过顶部音源切换器自由切换到其他插件。
 */
class HomeViewModel(app: TvMusicApp) : ViewModel() {

    private val runtime = app.runtime
    private val repository = app.repository

    private val _sections = MutableStateFlow<List<HomeSection>>(emptyList())
    val sections: StateFlow<List<HomeSection>> = _sections.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 当前选中的插件 platform；null 表示尚未选定。 */
    private val _currentPlatform = MutableStateFlow<String?>(null)
    val currentPlatform: StateFlow<String?> = _currentPlatform.asStateFlow()

    /** 可切换的音源列表（已启用且有元信息）。 */
    private val _availablePlugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val availablePlugins: StateFlow<List<PluginRecord>> = _availablePlugins.asStateFlow()

    private val PLUGIN_CALL_TIMEOUT_MS = 8_000L

    init {
        // 插件列表就绪后，选定第一个可用插件并加载；列表变化时刷新可选项。
        viewModelScope.launch {
            repository.ready.collect { isReady ->
                if (!isReady) return@collect
                refreshPlugins()
                // 首次选定第一个可用插件
                if (_currentPlatform.value == null) {
                    pickFirst()
                }
            }
        }
        // 插件列表变化时刷新可选项（不重新加载当前插件）
        viewModelScope.launch {
            repository.plugins
                .drop(1)
                .collect { refreshPlugins() }
        }
    }

    private fun refreshPlugins() {
        _availablePlugins.value = repository.listEnabled()
            .filter { it.info != null && it.loadError.isNullOrBlank() }
    }

    private fun pickFirst() {
        val first = _availablePlugins.value.firstOrNull() ?: return
        _currentPlatform.value = first.info!!.platform
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
        if (_loading.value) return
        viewModelScope.launch(Dispatchers.Default) {
            _loading.value = true
            _sections.value = emptyList()
            val fresh = mutableListOf<HomeSection>()
            val pf = plugin.info!!.platform

            // getTopLists
            try {
                if (runtime.hasMethod(pf, "getTopLists")) {
                    val top = runtime.callAsync(pf, "getTopLists", timeoutMs = PLUGIN_CALL_TIMEOUT_MS)
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

            // getRecommendSheetTags
            try {
                if (runtime.hasMethod(pf, "getRecommendSheetTags")) {
                    val tags = runtime.callAsync(pf, "getRecommendSheetTags", timeoutMs = PLUGIN_CALL_TIMEOUT_MS)
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
            _sections.value = fresh
            _loading.value = false
        }
    }
}
