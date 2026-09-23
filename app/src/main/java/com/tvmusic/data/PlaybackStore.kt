package com.tvmusic.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个收藏专辑（歌单）。 */
data class FavList(
    val id: String,
    val name: String,
    val items: List<JSONObject>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("items", JSONArray().apply { items.forEach { put(it) } })
}

/**
 * 播放历史与收藏的本地 JSON 持久化。
 *  - 历史：自动记录、去重置顶（同一首再次播放移到顶部）
 *  - 收藏：支持多个自定义收藏专辑；默认专辑 id=[DEFAULT_FAV_ID]，不可删除
 * 文件位于 app 私有目录，不依赖外部存储。
 */
class PlaybackStore(context: Context) {

    private val dir = File(context.filesDir, "playback")
    private val historyFile = File(dir, "history.json")
    private val favoritesFile = File(dir, "favorites.json")
    private val listsFile = File(dir, "favorite_lists.json")

    private val _history = MutableStateFlow<List<JSONObject>>(emptyList())
    val history: StateFlow<List<JSONObject>> = _history.asStateFlow()

    private val _lists = MutableStateFlow<List<FavList>>(emptyList())
    val lists: StateFlow<List<FavList>> = _lists.asStateFlow()

    /** 所有收藏专辑的合集（去重），兼容旧"我的收藏"消费方。 */
    private val _favorites = MutableStateFlow<List<JSONObject>>(emptyList())
    val favorites: StateFlow<List<JSONObject>> = _favorites.asStateFlow()

    init {
        dir.mkdirs()
        _history.value = readList(historyFile)
        _lists.value = loadLists()
        backfillPlatforms()
        publishMerged()
    }

    /**
     * 兼容旧数据：早期收藏/历史条目可能缺 platform（当时未写入来源插件名），
     * 导致"我的列表"点播放时取不到插件而失败。
     * 用同一 id（或 title+artist）在别处已知的 platform 回填，并持久化修正后的文件。
     */
    private fun backfillPlatforms() {
        // 收集已知 id -> platform 映射（仅取带 platform 的条目）
        val known = HashMap<String, String>()
        fun note(o: JSONObject) {
            val platform = o.optString("platform", "")
            if (platform.isBlank()) return
            val id = o.optString("id", "")
            if (id.isNotBlank()) known.putIfAbsent("id::$id", platform)
            known.putIfAbsent(
                "ta::${o.optString("title", "")}::${o.optString("artist", "")}",
                platform
            )
        }
        _history.value.forEach(::note)
        _lists.value.forEach { l -> l.items.forEach(::note) }
        if (known.isEmpty()) return

        fun fill(o: JSONObject): JSONObject {
            if (o.optString("platform", "").isNotBlank()) return o
            val id = o.optString("id", "")
            val platform = (if (id.isNotBlank()) known["id::$id"] else null)
                ?: known["ta::${o.optString("title", "")}::${o.optString("artist", "")}"]
                ?: return o
            return JSONObject(o.toString()).put("platform", platform)
        }

        var changed = false
        val newHistory = _history.value.map {
            val f = fill(it)
            if (f !== it) changed = true
            f
        }
        val newLists = _lists.value.map { l ->
            val items = l.items.map {
                val f = fill(it)
                if (f !== it) changed = true
                f
            }
            l.copy(items = items)
        }
        if (changed) {
            _history.value = newHistory
            writeList(historyFile, newHistory)
            _lists.value = newLists
            saveLists(newLists)
            Log.i("PlaybackStore", "backfilled missing platform for legacy entries")
        }
    }

    /** 记录一首播放：去重后置顶。 */
    fun addHistory(item: JSONObject, max: Int = 200) {
        val key = primaryKey(item)
        val list = _history.value.toMutableList()
        list.removeAll { primaryKey(it) == key }
        list.add(0, item)
        val trimmed = if (list.size > max) list.subList(0, max) else list
        _history.value = trimmed
        writeList(historyFile, trimmed)
    }

    fun clearHistory() {
        _history.value = emptyList()
        writeList(historyFile, emptyList())
    }

    // ---------------- 收藏专辑 ----------------

    /** 该曲目是否收藏在任意专辑中。 */
    fun isFavorite(item: JSONObject): Boolean {
        val key = primaryKey(item)
        return _lists.value.any { l -> l.items.any { primaryKey(it) == key } }
    }

    /** 该曲目所在专辑的 id 集合。 */
    fun favoriteListsOf(item: JSONObject): Set<String> {
        val key = primaryKey(item)
        return _lists.value.filter { l -> l.items.any { primaryKey(it) == key } }.map { it.id }.toSet()
    }

    /**
     * 在指定专辑中收藏/取消收藏该曲目。
     * 返回操作后该曲目在此专辑中的收藏状态。
     */
    fun toggleFavorite(item: JSONObject, listId: String = DEFAULT_FAV_ID): Boolean {
        val key = primaryKey(item)
        val lists = _lists.value.toMutableList()
        var idx = lists.indexOfFirst { it.id == listId }
        if (idx < 0) {
            // 目标专辑不存在时落到默认专辑，保证收藏永不丢失
            idx = lists.indexOfFirst { it.id == DEFAULT_FAV_ID }.coerceAtLeast(0)
        }
        val target = lists[idx]
        val items = target.items.toMutableList()
        val at = items.indexOfFirst { primaryKey(it) == key }
        val nowFav: Boolean
        if (at >= 0) {
            items.removeAt(at)
            nowFav = false
        } else {
            items.add(0, item)
            nowFav = true
        }
        lists[idx] = target.copy(items = items)
        _lists.value = lists
        saveLists(lists)
        publishMerged()
        return nowFav
    }

    /** 从所有专辑移除该曲目。 */
    fun removeFavorite(item: JSONObject) {
        val key = primaryKey(item)
        _lists.value = _lists.value.map { l -> l.copy(items = l.items.filterNot { primaryKey(it) == key }) }
        saveLists(_lists.value)
        publishMerged()
    }

    /** 新建收藏专辑，返回新专辑 id；名称重复时返回已有专辑 id。 */
    fun addList(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        _lists.value.firstOrNull { it.name == trimmed }?.let { return it.id }
        val id = "fav_" + System.currentTimeMillis().toString(36)
        _lists.value = _lists.value + FavList(id, trimmed, emptyList())
        saveLists(_lists.value)
        publishMerged()
        return id
    }

    /** 删除专辑（默认专辑不可删）。 */
    fun removeList(id: String) {
        if (id == DEFAULT_FAV_ID) return
        _lists.value = _lists.value.filterNot { it.id == id }
        saveLists(_lists.value)
        publishMerged()
    }

    /** 重命名专辑（默认专辑不可改名）。 */
    fun renameList(id: String, name: String) {
        if (id == DEFAULT_FAV_ID) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _lists.value = _lists.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        saveLists(_lists.value)
        publishMerged()
    }

    /** 整体替换收藏专辑（导入配置用）。 */
    fun replaceAllLists(lists: List<FavList>) {
        _lists.value = lists
        saveLists(lists)
        publishMerged()
    }

    private fun publishMerged() {
        val seen = HashSet<String>()
        val merged = _lists.value.flatMap { it.items }.filter { seen.add(primaryKey(it)) }
        _favorites.value = merged
    }

    /** 旧版 favorites.json（平铺数组）迁移为默认专辑。 */
    private fun loadLists(): List<FavList> {
        if (listsFile.exists()) {
            try {
                val arr = JSONArray(listsFile.readText(Charsets.UTF_8))
                val lists = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val items = o.optJSONArray("items") ?: JSONArray()
                    FavList(
                        id = o.optString("id", DEFAULT_FAV_ID),
                        name = o.optString("name", "我的收藏"),
                        items = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    )
                }
                if (lists.isNotEmpty()) {
                    return ensureDefault(lists)
                }
            } catch (e: Exception) {
                Log.w("PlaybackStore", "read lists: ${e.message}")
            }
        }
        // 迁移旧平铺收藏
        val legacy = readList(favoritesFile)
        val lists = mutableListOf(FavList(DEFAULT_FAV_ID, "我的收藏", legacy))
        if (legacy.isNotEmpty()) {
            saveLists(lists)
        }
        return lists
    }

    private fun ensureDefault(lists: List<FavList>): List<FavList> {
        if (lists.any { it.id == DEFAULT_FAV_ID }) return lists
        return listOf(FavList(DEFAULT_FAV_ID, "我的收藏", emptyList())) + lists
    }

    private fun saveLists(lists: List<FavList>) {
        try {
            val arr = JSONArray()
            lists.forEach { arr.put(it.toJson()) }
            listsFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("PlaybackStore", "save lists: ${e.message}")
        }
    }

    /** 用插件 primaryKey 去重，缺省回退 title+artist。 */
    private fun primaryKey(o: JSONObject): String {
        val platform = o.optString("platform", "")
        val id = o.optString("id", "")
        if (id.isNotBlank()) return "$platform::$id"
        return "$platform::${o.optString("title", "")}::${o.optString("artist", "")}"
    }

    private fun readList(file: File): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            Log.w("PlaybackStore", "read ${file.name}: ${e.message}")
            emptyList()
        }
    }

    private fun writeList(file: File, list: List<JSONObject>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            file.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("PlaybackStore", "write ${file.name}: ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_FAV_ID = "fav_default"
    }
}
