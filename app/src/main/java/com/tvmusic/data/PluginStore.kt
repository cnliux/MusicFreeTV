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

    @Synchronized
    fun upsertPlugin(record: PluginRecord) {
        val db = helper.writableDatabase
        try {
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
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun markLoadErrorByName(name: String, error: String) {
        val db = helper.writableDatabase
        try {
            val cv = ContentValues().apply { put("load_error", error) }
            db.update("plugins", cv, "name = ?", arrayOf(name))
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun loadPlugins(): List<PluginRecord> {
        val out = mutableListOf<PluginRecord>()
        val db = helper.readableDatabase
        try {
            val c = db.query(
                "plugins", null, null, null, null, null,
                "installed_at ASC"
            )
            while (c.moveToNext()) {
                out.add(
                    PluginRecord(
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        version = c.getString(c.getColumnIndexOrThrow("version")),
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                        installedAt = c.getLong(c.getColumnIndexOrThrow("installed_at")),
                        source = c.getString(c.getColumnIndexOrThrow("source")),
                        info = parseInfo(c.getString(c.getColumnIndexOrThrow("plugin_info"))),
                        loadError = c.getString(c.getColumnIndexOrThrow("load_error")),
                        hash = c.getString(c.getColumnIndexOrThrow("hash")) ?: ""
                    )
                )
            }
            c.close()
        } finally {
            db.close()
        }
        return out
    }

    private fun parseInfo(json: String?): PluginInfo? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val userVars = o.optJSONArray("userVariables")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val x = arr.optJSONObject(i) ?: JSONObject()
                    UserVarDef(x.optString("key"), x.optString("name"), x.optString("type", null))
                }
            } ?: emptyList()
            PluginInfo(
                platform = o.optString("platform"),
                version = o.optString("version", null),
                author = o.optString("author", null),
                srcUrl = o.optString("srcUrl", null),
                appVersion = o.optString("appVersion", null),
                description = o.optString("description", null),
                cacheControl = o.optString("cacheControl", null),
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
        val db = helper.writableDatabase
        try {
            val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
            db.update("plugins", cv, "name=?", arrayOf(name))
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun deletePlugin(name: String) {
        val db = helper.writableDatabase
        try {
            db.delete("plugins", "name=?", arrayOf(name))
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun listSubscriptions(): List<SubscriptionRecord> {
        val out = mutableListOf<SubscriptionRecord>()
        val db = helper.readableDatabase
        try {
            val c = db.query("subscriptions", null, null, null, null, null, "added_at ASC")
            while (c.moveToNext()) {
                out.add(
                    SubscriptionRecord(
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at"))
                    )
                )
            }
            c.close()
        } finally {
            db.close()
        }
        return out
    }

    @Synchronized
    fun addSubscription(url: String) {
        val db = helper.writableDatabase
        try {
            val cv = ContentValues().apply {
                put("url", url)
                put("added_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("subscriptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun removeSubscription(url: String) {
        val db = helper.writableDatabase
        try {
            db.delete("subscriptions", "url=?", arrayOf(url))
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun upsertVariable(pluginKey: String, varKey: String, varValue: String) {
        val db = helper.writableDatabase
        try {
            val cv = ContentValues().apply {
                put("plugin_key", pluginKey)
                put("var_key", varKey)
                put("var_value", varValue)
            }
            db.insertWithOnConflict(
                "user_variables", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    @Synchronized
    fun loadVariables(pluginKey: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val db = helper.readableDatabase
        try {
            val c = db.query(
                "user_variables", arrayOf("var_key", "var_value"),
                "plugin_key=?", arrayOf(pluginKey), null, null, null
            )
            while (c.moveToNext()) {
                out[c.getString(c.getColumnIndexOrThrow("var_key"))] =
                    c.getString(c.getColumnIndexOrThrow("var_value"))
            }
            c.close()
        } finally {
            db.close()
        }
        return out
    }

    @Synchronized
    fun allVariablesMerged(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val db = helper.readableDatabase
        try {
            val c = db.query("user_variables", arrayOf("var_key", "var_value"), null, null, null, null, null)
            while (c.moveToNext()) {
                out[c.getString(c.getColumnIndexOrThrow("var_key"))] =
                    c.getString(c.getColumnIndexOrThrow("var_value"))
            }
            c.close()
        } finally {
            db.close()
        }
        return out
    }
}