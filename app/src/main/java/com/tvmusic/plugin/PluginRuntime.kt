package com.tvmusic.plugin

import android.content.Context
import com.tvmusic.runtime.JsEngine
import com.tvmusic.runtime.PluginCallException
import com.tvmusic.runtime.QuickJsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件运行时外观。
 * 所有方法默认在 IO 线程执行，UI 层无需手动切线程。
 */
class PluginRuntime private constructor(private val engine: JsEngine) {

    companion object {
        @Volatile private var INSTANCE: PluginRuntime? = null

        fun create(context: Context, store: com.tvmusic.data.PluginStore): PluginRuntime {
            val existing = INSTANCE
            if (existing != null) return existing
            val rt = PluginRuntime(
                QuickJsEngine(context.applicationContext) { store.allVariablesMerged() }
            )
            rt.engine.initialize()
            INSTANCE = rt
            return rt
        }

        fun get(): PluginRuntime = INSTANCE ?: error("PluginRuntime not initialized")
    }

    suspend fun loadPlugin(platform: String, source: String): Boolean = withContext(Dispatchers.IO) {
        engine.registerPlugin(platform, source)
    }

    suspend fun hasPlugin(platform: String): Boolean = withContext(Dispatchers.IO) {
        engine.hasPlugin(platform)
    }

    /** 插件是否实现指定方法（用于按能力筛选，如 getRecommendSheetTags / getTopLists）。 */
    suspend fun hasMethod(platform: String, method: String): Boolean = withContext(Dispatchers.IO) {
        engine.hasMethod(platform, method)
    }

    suspend fun readInfo(platform: String): JSONObject? = withContext(Dispatchers.IO) {
        val raw = engine.readPluginInfo(platform) ?: return@withContext null
        try { JSONObject(raw) } catch (_: Exception) { null }
    }

    suspend fun callAsync(
        platform: String,
        method: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = engine.timeoutMillis
    ): Any {
        return withContext(Dispatchers.IO) {
            val argsJson = JSONArray(args).toString()
            val result = engine.invoke(platform, method, argsJson, timeoutMs)
            parseJsonResult(result)
        }
    }

    suspend fun callAsync(platform: String, method: String, vararg args: Any): Any {
        return withContext(Dispatchers.IO) {
            val arr = JSONArray()
            args.forEach { a ->
                when (a) {
                    is String -> arr.put(a)
                    is JSONObject -> arr.put(a)
                    is JSONArray -> arr.put(a)
                    is Number -> arr.put(a)
                    is Boolean -> arr.put(a)
                    is Nothing -> arr.put(JSONObject.NULL)
                    else -> arr.put(a.toString())
                }
            }
            val result = engine.invoke(platform, method, arr.toString())
            parseJsonResult(result)
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { engine.close() }

    private fun parseJsonResult(raw: String): Any {
        if (raw.isBlank() || raw == "null") return JSONObject()
        try {
            val obj = JSONObject(raw)
            if (obj.optBoolean("__notImplemented")) {
                return NotImplementedError()
            }
            return obj
        } catch (_: Exception) {
            // 不少插件方法直接返回数组（如 getTopLists / importMusicSheet / search 旧协议）
            try {
                return JSONArray(raw)
            } catch (_: Exception) {
                return raw
            }
        }
    }
}
