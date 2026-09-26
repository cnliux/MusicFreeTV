package com.tvmusic.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 SQLite 封装，避免引入注解处理器。
 *
 * 表：
 *  - plugins( name PK, url, version, enabled, installed_at, source, plugin_info, load_error )
 *  - subscriptions( url PK, added_at )
 *  - user_variables( plugin_key, var_key, var_value, PK(plugin_key, var_key) )
 */
class PluginStore(context: Context) {

    private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE plugins(" +
                    "name TEXT PRIMARY KEY," +
                    "url TEXT," +
                    "version TEXT," +
                    "enabled INTEGER NOT NULL DEFAULT 1," +
                    "installed_at INTEGER NOT NULL," +
                    "source TEXT," +
                    "plugin_info TEXT," +
                    "load_error TEXT," +
                    "hash TEXT)"
            )
            db.execSQL(
                "CREATE TABLE subscriptions(" +
                    "url TEXT PRIMARY KEY," +
                    "added_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE user_variables(" +
                    "plugin_key TEXT NOT NULL," +
                    "var_key TEXT NOT NULL," +
                    "var_value TEXT," +
                    "PRIMARY KEY(plugin_key, var_key))"
            )
            db.execSQL(
                "CREATE TABLE uninstalled_plugins(" +
                    "name TEXT PRIMARY KEY," +
                    "uninstalled_at INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                runCatching { db.execSQL("ALTER TABLE plugins ADD COLUMN hash TEXT") }
            }
            if (oldVersion < 3) {
                runCatching {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS uninstalled_plugins(" +
                            "name TEXT PRIMARY KEY," +
                            "uninstalled_at INTEGER NOT NULL)"
                    )
                }
            }
        }
    }

    companion object {
        private const val DB_NAME = "musicfreetv.db"
        private const val DB_VERSION = 3
    }

    private val helper = DbHelper(context.applicationContext)

    // 说明：不要每次操作后 db.close()——SQLiteOpenHelper 缓存连接，
    // 反复 close 会迫使下次调用重新打开文件（WAL 初始化 + 文件 I/O），
    // 搜索/首页等高频路径的每次 DB 访问都付出额外开销。连接由 helper 常驻管理。

    @Synchronized
    fun upsertPlugin(record: PluginRecord) {
        val db = helper.writableDatabase
        val cv = ContentValues().apply {
            put("name", record.name)
            put("url", record.url)
            put("version", record.version)
            put("enabled", if (record.enabled) 1 else 0)
            put("installed_at", record.installedAt)
            put("source", record.source)
            put("plugin_info", record.info?.let { infoToJson(it) })
            put("load_error", record.loadError)
            put("hash", record.hash)
        }
        db.insertWithOnConflict("plugins", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun markLoadErrorByName(name: String, error: String) {
        val db = helper.writableDatabase
        val cv = ContentValues().apply { put("load_error", error) }
        db.update("plugins", cv, "name = ?", arrayOf(name))
    }

    @Synchronized
    fun loadPlugins(): List<PluginRecord> {
        val out = mutableListOf<PluginRecord>()
        val db = helper.readableDatabase
        val c = db.query(
            "plugins", null, null, null, null, null,
            "installed_at ASC"
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    PluginRecord(
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        url = it.getString(it.getColumnIndexOrThrow("url")),
                        version = it.getString(it.getColumnIndexOrThrow("version")),
                        enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1,
                        installedAt = it.getLong(it.getColumnIndexOrThrow("installed_at")),
                        source = it.getString(it.getColumnIndexOrThrow("source")),
                        info = parseInfo(it.getString(it.getColumnIndexOrThrow("plugin_info"))),
                        loadError = it.getString(it.getColumnIndexOrThrow("load_error")),
                        hash = it.getString(it.getColumnIndexOrThrow("hash")) ?: ""
                    )
                )
            }
        }
        return out
    }

    /**
     * 元数据轻量查询：不读 source 大字段（单个插件源码可达数百 KB~MB）。
     * 供列表展示 / 仓库 StateFlow / 远程管理使用——这些场景只看 name/info/enabled/
     * loadError/hash，从不碰源码。旧实现每次刷新都物化全部源码，多插件时造成
     * 数 MB 级分配抖动，电视端表现为远程管理插件页操作后整机卡死。
     * 需要源码的场景（warmup 注册引擎）仍走 [loadPlugins]。
     */
    @Synchronized
    fun loadPluginMetas(): List<PluginRecord> {
        val out = mutableListOf<PluginRecord>()
        val db = helper.readableDatabase
        val c = db.query(
            "plugins",
            arrayOf("name", "url", "version", "enabled", "installed_at", "plugin_info", "load_error", "hash"),
            null, null, null, null,
            "installed_at ASC"
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    PluginRecord(
                        name = it.getString(0),
                        url = it.getString(1) ?: "",
                        version = it.getString(2) ?: "",
                        enabled = it.getInt(3) == 1,
                        installedAt = it.getLong(4),
                        source = null,
                        info = parseInfo(it.getString(5)),
                        loadError = it.getString(6),
                        hash = it.getString(7) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 读取可选字符串字段：缺失/NULL/空串统一返回 null（避免 optString(key, null) 的类型不匹配警告）。 */
    private fun optStr(o: JSONObject, key: String): String? = o.optString(key).takeIf { it.isNotEmpty() }

    /** 把 PluginInfo 写成可 round-trip 的 JSON：userVariables 必须是对象数组，不能塞 data class toString。 */
    private fun infoToJson(info: PluginInfo): String {
        fun strings(list: List<String>): JSONArray {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            return arr
        }
        val vars = JSONArray()
        info.userVariables.forEach { d ->
            vars.put(
                JSONObject()
                    .put("key", d.key)
                    .put("name", d.name)
                    .put("type", d.type ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("platform", info.platform)
            .put("version", info.version)
            .put("author", info.author)
            .put("srcUrl", info.srcUrl)
            .put("appVersion", info.appVersion)
            .put("description", info.description)
            .put("cacheControl", info.cacheControl)
            .put("primaryKey", strings(info.primaryKey))
            .put("supportedSearchType", strings(info.supportedSearchType))
            .put("userVariables", vars)
            .toString()
    }

    private fun parseInfo(json: String?): PluginInfo? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val userVars = o.optJSONArray("userVariables")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val x = arr.optJSONObject(i) ?: return@mapNotNull null
                    val key = x.optString("key")
                    if (key.isBlank()) return@mapNotNull null
                    UserVarDef(key, x.optString("name").ifBlank { key }, optStr(x, "type"))
                }
            } ?: emptyList()
            PluginInfo(
                platform = o.optString("platform"),
                version = optStr(o, "version"),
                author = optStr(o, "author"),
                srcUrl = optStr(o, "srcUrl"),
                appVersion = optStr(o, "appVersion"),
                description = optStr(o, "description"),
                cacheControl = optStr(o, "cacheControl"),
                primaryKey = o.optJSONArray("primaryKey")?.let { a ->
                    (0 until a.length()).map { i -> a.getString(i) }
                } ?: emptyList(),
                supportedSearchType = o.optJSONArray("supportedSearchType")?.let { a ->
                    (0 until a.length()).map { i -> a.getString(i) }
                } ?: emptyList(),
                userVariables = userVars
            )
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    /**
     * 按「插件名」或「platform」定位实际存储名。
     * 远程管理页只拿得到 /api/plugins 里的 platform（如 kugou / 酷狗），
     * 而 plugins 表的主键是插件名（如 酷狗音乐），两者经常不同（实测 24/31 条不同），
     * 直接用 platform 去 update/delete 会 0 行命中且不报错。
     *
     * 解析优先级（保证唯一命中，避免 name 恰好等于别的插件 platform 时串行）：
     *  1) 插件名精确匹配；若名字唯一命中，直接返回。
     *  2) platform 精确匹配（按 installed_at 取第一个，重复 platform 只操作第一个）。
     *  3) 回退：name 命中多行时（插件名重复），取 installed_at 最早的那个。
     * 全部未命中返回 null，由调用方回 404，不再静默成功。
     */
    private fun resolvePluginName(nameOrPlatform: String): String? {
        val want = nameOrPlatform.trim()
        if (want.isEmpty()) return null
        val c = helper.readableDatabase.query(
            "plugins", arrayOf("name", "plugin_info"), null, null, null, null, "installed_at ASC"
        )
        val byName = ArrayList<String>()
        var byPlatform: String? = null
        c.use {
            while (it.moveToNext()) {
                val storedName = it.getString(0)
                if (storedName == want) byName.add(storedName)
                if (byPlatform == null && parseInfo(it.getString(1))?.platform == want) byPlatform = storedName
            }
        }
        if (byName.size == 1) return byName[0]
        if (byPlatform != null) return byPlatform
        // name 重复的罕见情况：取最早安装的那个
        return byName.firstOrNull()
    }

    /** @return 是否命中并更新了行（未命中返回 false，由调用方回 404，不再静默成功）。 */
    fun setPluginEnabled(nameOrPlatform: String, enabled: Boolean): Boolean {
        val name = resolvePluginName(nameOrPlatform) ?: return false
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        return helper.writableDatabase.update("plugins", cv, "name=?", arrayOf(name)) > 0
    }

    /**
     * 删除单个插件及其用户变量。
     * @return 命中时返回 (DB主键name, platform)，未命中返回 null（由调用方回 404）。
     */
    @Synchronized
    fun deletePlugin(nameOrPlatform: String): Pair<String, String?>? {
        val name = resolvePluginName(nameOrPlatform) ?: return null
        val db = helper.writableDatabase
        var pk: String? = name
        db.beginTransaction()
        try {
            val c = db.query("plugins", arrayOf("plugin_info"), "name=?", arrayOf(name), null, null, null)
            c.use {
                if (it.moveToFirst()) {
                    parseInfo(it.getString(0))?.platform?.takeIf { p -> p.isNotBlank() }?.let { p -> pk = p }
                }
            }
            db.delete("plugins", "name=?", arrayOf(name))
            db.delete("user_variables", "plugin_key=?", arrayOf(pk))
            if (pk != name) db.delete("user_variables", "plugin_key=?", arrayOf(name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        invalidateVarCache()
        return name to pk
    }

    /**
     * 一键全部卸载：清空插件与用户变量，并把所有插件（含 platform 别名）记入卸载名单，
     * 防止订阅同步立即复装。
     * @return 删除的插件数量。
     */
    @Synchronized
    fun deleteAllPlugins(): Int {
        val db = helper.writableDatabase
        val names = ArrayList<Pair<String, String?>>()
        db.beginTransaction()
        try {
            db.query("plugins", arrayOf("name", "plugin_info"), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    names.add(c.getString(0) to parseInfo(c.getString(1))?.platform?.takeIf { it.isNotBlank() })
                }
            }
            if (names.isEmpty()) return 0
            val now = System.currentTimeMillis()
            for ((name, platform) in names) {
                for (key in linkedSetOf(name, platform)) {
                    if (key.isNullOrBlank()) continue
                    val cv = ContentValues().apply {
                        put("name", key)
                        put("uninstalled_at", now)
                    }
                    db.insertWithOnConflict("uninstalled_plugins", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.delete("plugins", null, null)
            db.delete("user_variables", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        invalidateVarCache()
        return names.size
    }

    /** 卸载名单：记录被用户手动卸载的插件名/platform，订阅同步时跳过以免复装。 */
    @Synchronized
    fun markUninstalled(name: String) {
        if (name.isBlank()) return
        val cv = ContentValues().apply {
            put("name", name)
            put("uninstalled_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict("uninstalled_plugins", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun listUninstalled(): Set<String> {
        val out = HashSet<String>()
        helper.readableDatabase.query("uninstalled_plugins", arrayOf("name"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** 从卸载名单移除（手动导入或重新添加订阅时调用，允许再次安装）。 */
    @Synchronized
    fun clearUninstalled(names: Collection<String>) {
        if (names.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (n in names) {
                if (n.isNotBlank()) db.delete("uninstalled_plugins", "name=?", arrayOf(n))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun listSubscriptions(): List<SubscriptionRecord> {
        val out = mutableListOf<SubscriptionRecord>()
        val c = helper.readableDatabase.query("subscriptions", null, null, null, null, null, "added_at ASC")
        c.use {
            while (it.moveToNext()) {
                out.add(
                    SubscriptionRecord(
                        url = it.getString(it.getColumnIndexOrThrow("url")),
                        addedAt = it.getLong(it.getColumnIndexOrThrow("added_at"))
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun addSubscription(url: String) {
        val cv = ContentValues().apply {
            put("url", url)
            put("added_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict("subscriptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun removeSubscription(url: String) {
        helper.writableDatabase.delete("subscriptions", "url=?", arrayOf(url))
    }

    @Synchronized
    fun upsertVariable(pluginKey: String, varKey: String, varValue: String) {
        val cv = ContentValues().apply {
            put("plugin_key", pluginKey)
            put("var_key", varKey)
            put("var_value", varValue)
        }
        helper.writableDatabase.insertWithOnConflict(
            "user_variables", null, cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        invalidateVarCache()
    }

    /** 整组替换某插件的用户变量：单事务删旧+写新，避免逐行写入的多次磁盘同步。 */
    @Synchronized
    fun replaceVariables(pluginKey: String, vars: Map<String, String>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("user_variables", "plugin_key=?", arrayOf(pluginKey))
            vars.forEach { (k, v) ->
                if (k.isBlank()) return@forEach
                val cv = ContentValues().apply {
                    put("plugin_key", pluginKey)
                    put("var_key", k)
                    put("var_value", v)
                }
                db.insertWithOnConflict("user_variables", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        invalidateVarCache()
    }

    /**
     * 用户变量内存缓存：插件每次经 JS 桥取变量都会调 allVariablesMerged()，
     * 旧实现每次全表查 SQLite 并在 JS 线程上阻塞。写入后统一失效。
     */
    private var mergedVarCache: Map<String, String>? = null
    private val pluginVarCache = HashMap<String, Map<String, String>>()

    private fun invalidateVarCache() {
        mergedVarCache = null
        pluginVarCache.clear()
    }

    @Synchronized
    fun loadVariables(pluginKey: String): Map<String, String> {
        pluginVarCache[pluginKey]?.let { return it }
        val out = LinkedHashMap<String, String>()
        val c = helper.readableDatabase.query(
            "user_variables", arrayOf("var_key", "var_value"),
            "plugin_key=?", arrayOf(pluginKey), null, null, null
        )
        c.use {
            while (it.moveToNext()) {
                out[it.getString(it.getColumnIndexOrThrow("var_key"))] =
                    it.getString(it.getColumnIndexOrThrow("var_value"))
            }
        }
        pluginVarCache[pluginKey] = out
        return out
    }

    @Synchronized
    fun allVariablesMerged(): Map<String, String> {
        mergedVarCache?.let { return it }
        val out = LinkedHashMap<String, String>()
        val c = helper.readableDatabase.query("user_variables", arrayOf("var_key", "var_value"), null, null, null, null, null)
        c.use {
            while (it.moveToNext()) {
                out[it.getString(it.getColumnIndexOrThrow("var_key"))] =
                    it.getString(it.getColumnIndexOrThrow("var_value"))
            }
        }
        mergedVarCache = out
        return out
    }
}