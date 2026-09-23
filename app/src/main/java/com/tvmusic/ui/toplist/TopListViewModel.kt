package com.tvmusic.ui.toplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.PluginRecord
import com.tvmusic.data.TopListEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 排行榜页 VM。对齐 RN topList：
 *  - 找出实现 getTopLists 的插件作为页签；
 *  - getTopLists() -> [{ title, data: [board] }]，每组渲染一个横向榜单条带。
 */
class TopListViewModel(
    app: TvMusicApp,
    private val initialPlatform: String? = null
) : ViewModel() {

    private val runtime = app.runtime
    private val repository = app.repository

    data class BoardGroup(
        val title: String,
        val boards: List<TopListEntry>
    )

    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    private val _selectedPlatform = MutableStateFlow("")
    val selectedPlatform: StateFlow<String> = _selectedPlatform.asStateFlow()

    private val _groups = MutableStateFlow<List<BoardGroup>>(emptyList())
    val groups: StateFlow<List<BoardGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var session = 0

    init {
        viewModelScope.launch {
            repository.ready.first { it }
            probePlugins()
            repository.plugins.collectLatest {
                probePlugins(keepSelection = true)
            }
        }
    }

    private suspend fun probePlugins(keepSelection: Boolean = false) {
        val able = repository.listEnabled().filter { rec ->
            rec.info != null && rec.loadError == null &&
                runCatching { runtime.hasMethod(rec.info.platform, "getTopLists") }
                    .getOrDefault(false)
        }
        _plugins.value = able
        if (able.isEmpty()) {
            _loading.value = false
            _error.value = "已启用的插件均不提供排行榜"
            return
        }
        val current = _selectedPlatform.value
        val target = when {
            keepSelection && able.any { it.info!!.platform == current } -> current
            initialPlatform != null && able.any { it.info!!.platform == initialPlatform } -> initialPlatform
            else -> able.first().info!!.platform
        }
        if (!keepSelection || target != current) {
            selectPlugin(target)
        } else if (_groups.value.isEmpty()) {
            loadBoards(target, ++session)
        }
    }

    fun selectPlugin(platform: String) {
        if (platform == _selectedPlatform.value && _groups.value.isNotEmpty()) return
        _selectedPlatform.value = platform
        loadBoards(platform, ++session)
    }

    fun retry() = loadBoards(_selectedPlatform.value, ++session)

    private fun loadBoards(platform: String, mySession: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            _loading.value = true
            _error.value = null
            _groups.value = emptyList()
            try {
                val res = runtime.callAsync(platform, "getTopLists")
                if (mySession != session) return@launch
                if (res is NotImplementedError) {
                    _error.value = "该插件不提供排行榜"
                } else {
                    val arr = res as? JSONArray
                    if (arr == null) {
                        _error.value = "排行榜数据格式异常"
                    } else {
                        val groups = mutableListOf<BoardGroup>()
                        for (i in 0 until arr.length()) {
                            val g = arr.optJSONObject(i) ?: continue
                            val data = g.optJSONArray("data") ?: continue
                            val boards = (0 until data.length()).mapNotNull { j ->
                                val o = data.optJSONObject(j) ?: return@mapNotNull null
                                o.optString("title").takeIf { it.isNotBlank() }
                                    ?.let { TopListEntry(platform, o) }
                            }
                            if (boards.isNotEmpty()) {
                                groups += BoardGroup(g.optString("title", "排行榜"), boards)
                            }
                        }
                        _groups.value = groups
                        if (groups.isEmpty()) _error.value = "暂无排行榜数据"
                    }
                }
            } catch (e: Exception) {
                if (mySession == session) _error.value = "排行榜加载失败: ${e.message}"
            } finally {
                if (mySession == session) _loading.value = false
            }
        }
    }
}
