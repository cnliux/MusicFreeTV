package com.tvmusic.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                runCatching { db.execSQL("ALTER TABLE plugins ADD COLUMN hash TEXT") }
            }
        }
    }

    companion object {
        private const val DB_NAME = "musicfreetv.db"
        private const val DB_VERSION = 2
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
            put("plugin_info", record.info?.let { JSONObject().put("platform", it.platform).put("version", it.version).put("author", it.author).put("srcUrl", it.srcUrl).put("appVersion", it.appVersion).put("description", it.description).put("cacheControl", it.cacheControl).put("primaryKey", it.primaryKey).put("supportedSearchType", it.supportedSearchType).put("userVariables", it.userVariables).toString() })
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

    /** 读取可选字符串字段：缺失/NULL/空串统一返回 null（避免 optString(key, null) 的类型不匹配警告）。 */
    private fun optStr(o: JSONObject, key: String): String? = o.optString(key).takeIf { it.isNotEmpty() }

    private fun parseInfo(json: String?): PluginInfo? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val userVars = o.optJSONArray("userVariables")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val x = arr.optJSONObject(i) ?: JSONObject()
                    UserVarDef(x.optString("key"), x.optString("name"), optStr(x, "type"))
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
    fun setPluginEnabled(name: String, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        helper.writableDatabase.update("plugins", cv, "name=?", arrayOf(name))
    }

    @Synchronized
    fun deletePlugin(name: String) {
        helper.writableDatabase.delete("plugins", "name=?", arrayOf(name))
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