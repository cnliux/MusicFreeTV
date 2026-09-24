package com.tvmusic.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.PluginRecord
import com.tvmusic.plugin.PluginRepository
import com.tvmusic.remote.RemoteConfigService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: TvMusicApp) : ViewModel() {

    private val repo: PluginRepository = app.repository
    private val store = app.store

    val plugins: StateFlow<List<PluginRecord>> = repo.plugins
    val subscribed: StateFlow<List<String>> = repo.subscribed
    val syncing: StateFlow<Boolean> = repo.syncing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _expandedVars = MutableStateFlow<Set<String>>(emptySet())
    val expandedVars: StateFlow<Set<String>> = _expandedVars.asStateFlow()

    /** pluginKey -> (varKey -> 草稿值) */
    private val _drafts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val drafts: StateFlow<Map<String, Map<String, String>>> = _drafts.asStateFlow()

    /** 手机/电脑浏览器直接打开的【管理页】地址 */
    val remoteManageUrl: String get() =
        "http://${RemoteConfigService.hostDisplay}:${RemoteConfigService.port}/"

    fun addSubscription(url: String) {
        if (url.isBlank()) return
        repo.addSubscription(url.trim())
        _message.value = "已添加订阅：$url"
        repo.syncAll(force = true)
    }

    fun removeSubscription(url: String) {
        repo.removeSubscription(url)
        _message.value = "已移除订阅：$url"
    }

    fun sync() {
        repo.syncAll(force = true)
    }

    fun togglePlugin(name: String, enabled: Boolean) {
        repo.toggleEnabled(name, enabled)
    }

    fun uninstall(name: String) {
        repo.uninstall(name)
        _message.value = "已卸载：$name"
    }

    fun importSingle(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _message.value = "正在导入 $url …"
            val err = repo.importFromUrl(url)
            _message.value = err ?: "导入完成（若为订阅列表，请到订阅栏添加）"
        }
    }

    fun loadVarsAsDraft(pluginKey: String) {
        val existing = _drafts.value
        if (existing.containsKey(pluginKey)) return
        _drafts.value = existing + (pluginKey to store.loadVariables(pluginKey))
    }

    fun toggleExpanded(pluginKey: String) {
        val current = _expandedVars.value
        _expandedVars.value = if (pluginKey in current) current - pluginKey else current + pluginKey
    }

    fun setDraft(pluginKey: String, varKey: String, value: String) {
        val cur = _drafts.value
        val pk = cur[pluginKey] ?: store.loadVariables(pluginKey)
        _drafts.value = cur + (pluginKey to (pk + (varKey to value)))
    }

    fun saveVars(pluginKey: String) {
        val map = _drafts.value[pluginKey] ?: return
        map.forEach { (k, v) -> store.upsertVariable(pluginKey, k, v) }
        _message.value = "已保存用户变量：$pluginKey"
    }
}