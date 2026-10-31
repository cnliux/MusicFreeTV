package com.tvmusic.data

import android.content.Context
import com.tvmusic.model.PluginInfo
import com.tvmusic.model.PluginRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON 文件式插件存储：
 * - plugins.json: 所有插件元数据
 * - plugins/{name}.js: 源码文件（按平台名存储）
 * - subscriptions.json: 订阅源列表
 *
 * 相比 SQLite 的优势：
 * 1. 无需迁移，跨版本兼容
 * 2. 可直接读取/备份
 * 3. 调试方便
 */
class PluginStore(context: Context) {

    private val filesDir = context.filesDir
    private val pluginsFile = File(filesDir, "plugins.json")
    private val subscriptionsFile = File(filesDir, "subscriptions.json")
    private val varsDir = File(filesDir, "variables")
    private val blockedFile = File(filesDir, "blocked_plugins.json")
    
    // 内存缓存
    private var pluginsCache: MutableList<PluginRecord> = mutableListOf()
    private var subscriptionsCache: MutableList<String> = mutableListOf()
    private var blockedCache: MutableSet<String> = mutableSetOf()

    init {
        varsDir.mkdirs()
        loadCache()
    }

    private fun loadCache() {
        pluginsCache = loadPluginsData().toMutableList()
        subscriptionsCache = loadSubscriptions()
        blockedCache = loadBlockedPlugins()
    }

    private fun savePlugins() {
        val arr = JSONArray()
        for (p in pluginsCache) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("url", p.url ?: "")
            obj.put("version", p.version ?: "")
            obj.put("enabled", p.enabled)
            obj.put("installed_at", p.installedAt)
            obj.put("load_error", p.loadError ?: "")
            obj.put("hash", p.hash)
            if (p.info != null) {
                val infoJson = JSONObject()
                infoJson.put("platform", p.info.platform)
                if (p.info.version != null) infoJson.put("version", p.info.version)
                if (p.info.author != null) infoJson.put("author", p.info.author)
                if (p.info.description != null) infoJson.put("description", p.info.description)
                obj.put("info", infoJson)
            }
            arr.put(obj)
        }
        pluginsFile.writeText(arr.toString(2))
        refreshSourceFiles()
    }

    private fun refreshSourceFiles() {
        // 清理旧的 source 文件
        filesDir.listFiles { _, name -> name.endsWith(".js") }?.forEach { it.delete() }
        // 写入新的 source 文件
        for (p in pluginsCache) {
            if (p.source != null && p.info?.platform != null) {
                val srcFile = File(filesDir, "${p.info.platform}.js")
                srcFile.writeText(p.source)
            }
        }
    }

    private fun saveSubscriptions() {
        val arr = JSONArray()
        for (url in subscriptionsCache) {
            arr.put(url)
        }
        subscriptionsFile.writeText(arr.toString(2))
    }

    private fun saveBlocked() {
        val arr = JSONArray()
        for (name in blockedCache) {
            arr.put(name)
        }
        blockedFile.writeText(arr.toString(2))
    }

    private fun loadPluginsData(): List<PluginRecord> {
        if (!pluginsFile.exists()) return emptyList()
        try {
            val text = pluginsFile.readText()
            val arr = JSONArray(text)
            val out = mutableListOf<PluginRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val url = obj.optString("url", null)
                val version = obj.optString("version", null)
                val enabled = obj.optBoolean("enabled", true)
                val installedAt = obj.optLong("installed_at", 0L)
                val loadError = obj.optString("load_error", null).takeIf { it.isNotEmpty() }
                val hash = obj.optString("hash", "")
                val infoJson = obj.optJSONObject("info")
                val info = if (infoJson != null) PluginInfo(
                    platform = infoJson.optString("platform", name),
                    version = infoJson.optString("version", null),
                    author = infoJson.optString("author", null),
                    description = infoJson.optString("description", null)
                ) else null
                // 从文件加载 source
                val platform = info?.platform ?: name
                val source = File(filesDir, "$platform.js").takeIf { it.exists() }?.readText()
                out.add(PluginRecord(
                    name = name,
                    url = url,
                    version = version,
                    enabled = enabled,
                    installedAt = installedAt,
                    source = source,
                    info = info,
                    loadError = loadError,
                    hash = hash
                ))
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun loadSubscriptions(): MutableList<String> {
        if (!subscriptionsFile.exists()) return mutableListOf()
        try {
            val text = subscriptionsFile.readText()
            val arr = JSONArray(text)
            return (0 until arr.length()).mapTo(mutableListOf()) { arr.getString(it) }
        } catch (e: Exception) {
            return mutableListOf()
        }
    }

    private fun loadBlockedPlugins(): MutableSet<String> {
        if (!blockedFile.exists()) return mutableSetOf()
        try {
            val text = blockedFile.readText()
            val arr = JSONArray(text)
            return (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
        } catch (e: Exception) {
            return mutableSetOf()
        }
    }

    fun loadPlugins(): List<PluginRecord> = pluginsCache

    fun loadPluginMetas(): List<PluginRecord> = pluginsCache.map { 
        PluginRecord(
            name = it.name,
            url = it.url,
            version = it.version,
            enabled = it.enabled,
            installedAt = it.installedAt,
            source = null,
            info = it.info,
            loadError = it.loadError,
            hash = it.hash
        )
    }

    fun sourceByName(name: String): String? {
        val platform = pluginsCache.firstOrNull { it.name == name }?.info?.platform ?: return null
        return File(filesDir, "$platform.js").takeIf { it.exists() }?.readText()
    }

    fun upsertPlugin(record: PluginRecord) {
        val idx = pluginsCache.indexOfFirst { it.name == record.name || it.info?.platform == record.info?.platform }
        if (idx >= 0) {
            pluginsCache[idx] = record
        } else {
            pluginsCache.add(record)
        }
        savePlugins()
    }

    /**
     * 修改插件展示名（历史脏名自愈用）：只改 name，不动 info.platform 与源码文件。
     * @return 是否命中并写盘
     */
    fun renamePlugin(oldName: String, newName: String): Boolean {
        if (oldName.isBlank() || newName.isBlank() || oldName == newName) return false
        val idx = pluginsCache.indexOfFirst { it.name == oldName }
        if (idx < 0) return false
        pluginsCache[idx] = pluginsCache[idx].copy(name = newName)
        savePlugins()
        return true
    }

    fun markLoadErrorByName(name: String, error: String) {
        val idx = pluginsCache.indexOfFirst { it.name == name }
        if (idx >= 0) {
            pluginsCache[idx] = pluginsCache[idx].copy(loadError = error)
            savePlugins()
        }
    }

    /** 注册成功时清掉历史 load_error：否则一次瞬时失败会让 UI 永远显示“加载失败”。 */
    fun clearLoadErrorByName(name: String) {
        val idx = pluginsCache.indexOfFirst { it.name == name }
        if (idx >= 0 && pluginsCache[idx].loadError != null) {
            pluginsCache[idx] = pluginsCache[idx].copy(loadError = null)
            savePlugins()
        }
    }

    fun setPluginEnabled(nameOrPlatform: String, enabled: Boolean): Boolean {
        val idx = pluginsCache.indexOfFirst { it.name == nameOrPlatform || it.info?.platform == nameOrPlatform }
        if (idx < 0) return false
        pluginsCache[idx] = pluginsCache[idx].copy(enabled = enabled)
        savePlugins()
        return true
    }

    fun deletePlugin(nameOrPlatform: String): Pair<String, String?>? {
        val idx = pluginsCache.indexOfFirst { it.name == nameOrPlatform || it.info?.platform == nameOrPlatform }
        if (idx < 0) return null
        val record = pluginsCache[idx]
        pluginsCache.removeAt(idx)
        savePlugins()
        // 删除 source 文件
        val platform = record.info?.platform ?: record.name
        File(filesDir, "$platform.js").delete()
        return record.name to platform
    }

    fun deleteAllPlugins(): Int {
        val count = pluginsCache.size
        pluginsCache.clear()
        savePlugins()
        // 清空 variables 目录
        varsDir.listFiles()?.forEach { it.delete() }
        return count
    }

    fun listUninstalled(): Set<String> = blockedCache

    fun markUninstalled(name: String) {
        if (name.isBlank()) return
        blockedCache.add(name)
        saveBlocked()
    }

    fun clearUninstalled(names: Collection<String>) {
        blockedCache.removeAll(names)
        saveBlocked()
    }

    fun listSubscriptions(): List<SubscriptionRecord> {
        return subscriptionsCache.map { SubscriptionRecord(url = it, addedAt = 0L) }
    }

    fun addSubscription(url: String) {
        if (url !in subscriptionsCache) {
            subscriptionsCache.add(url)
            saveSubscriptions()
        }
    }

    fun removeSubscription(url: String) {
        subscriptionsCache.remove(url)
        saveSubscriptions()
    }

    fun loadVariables(pluginKey: String): Map<String, String> {
        val file = File(varsDir, "$pluginKey.json")
        if (!file.exists()) return emptyMap()
        try {
            val obj = JSONObject(file.readText())
            val out = mutableMapOf<String, String>()
            obj.keys().forEach { key -> out[key] = obj.getString(key) }
            return out
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    fun upsertVariable(pluginKey: String, varKey: String, varValue: String) {
        val file = File(varsDir, "$pluginKey.json")
        val obj = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        obj.put(varKey, varValue)
        file.writeText(obj.toString())
    }

    fun replaceVariables(pluginKey: String, vars: Map<String, String>) {
        val file = File(varsDir, "$pluginKey.json")
        val obj = JSONObject()
        vars.forEach { (k, v) -> obj.put(k, v) }
        file.writeText(obj.toString())
    }

    fun readBlocklistCounts(): Map<String, Int> = emptyMap()
    fun writeBlocklistCounts(counts: Map<String, Int>) {}
    fun removeBlocklistEntry(name: String) {}
}
