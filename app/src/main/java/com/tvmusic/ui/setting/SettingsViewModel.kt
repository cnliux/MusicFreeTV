package com.tvmusic.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.PluginRecord
import com.tvmusic.plugin.PlatformHealth
import com.tvmusic.plugin.PluginRepository
import com.tvmusic.plugin.PluginRuntime
import com.tvmusic.remote.RemoteConfigService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsViewModel(app: TvMusicApp) : ViewModel() {

    private val repo: PluginRepository = app.repository
    private val store = app.store

    val plugins: StateFlow<List<PluginRecord>> = repo.plugins
    val subscribed: StateFlow<List<String>> = repo.subscribed
    val syncing: StateFlow<Boolean> = repo.syncing

    /** 最近一次手动"检查更新"的结果（null 表示还未手动同步过）。 */
    val syncReport: StateFlow<PluginRepository.SyncReport?> = repo.syncReport

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _expandedVars = MutableStateFlow<Set<String>>(emptySet())
    val expandedVars: StateFlow<Set<String>> = _expandedVars.asStateFlow()

    /** pluginKey -> (varKey -> 草稿值) */
    private val _drafts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val drafts: StateFlow<Map<String, Map<String, String>>> = _drafts.asStateFlow()

    /** 各平台健康度快照（本次运行内存统计，按平台名排序）。 */
    private val _health = MutableStateFlow<List<PlatformHealth>>(emptyList())
    val health: StateFlow<List<PlatformHealth>> = _health.asStateFlow()

    init {
        // 进入设置页立即取一次快照，之后定时刷新（健康度是内存态，随时变化）
        viewModelScope.launch {
            while (isActive) {
                refreshHealth()
                delay(3_000L)
            }
        }
    }

    /** 读取 PluginRuntime 的平台健康度快照（运行时未初始化时静默跳过）。 */
    private fun refreshHealth() {
        val runtime = runCatching { PluginRuntime.get() }.getOrNull() ?: return
        _health.value = runtime.healthSnapshot()
    }

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
        // SQLite 查询放后台，避免展开变量区时阻塞主线程
        viewModelScope.launch(Dispatchers.IO) {
            val vars = store.loadVariables(pluginKey)
            _drafts.value = _drafts.value + (pluginKey to vars)
        }
    }

    fun toggleExpanded(pluginKey: String) {
        val current = _expandedVars.value
        _expandedVars.value = if (pluginKey in current) current - pluginKey else current + pluginKey
    }

    fun setDraft(pluginKey: String, varKey: String, value: String) {
        val cur = _drafts.value
        // 草稿在展开时已由 loadVarsAsDraft 后台加载；此处缺失用空表即可，
        // 绝不在主线程同步查库（旧实现在每次输入时都可能触发一次全键查询）
        val pk = cur[pluginKey] ?: emptyMap()
        _drafts.value = cur + (pluginKey to (pk + (varKey to value)))
    }

    fun saveVars(pluginKey: String) {
        val map = _drafts.value[pluginKey] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // 逐行写改单次事务，大幅减少 SQLite 磁盘同步次数
            store.replaceVariables(pluginKey, map)
            _message.value = "已保存用户变量：$pluginKey"
        }
    }
}