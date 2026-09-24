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
 *
 * 并行搜索：单 QuickJS 引擎是单线程（插件内部 HTTP 为同步阻塞桥调用，且全局
 * invokeLock 串行），一个音源一个音源地搜非常慢。这里为搜索维护一个小型
 * 引擎池——每台引擎有独立 JS 线程与独立 QuickJS runtime，且注册全量插件；
 * [callParallel] 按“最空闲引擎”把调用分发到不同引擎上，实现真正的并行 I/O。
 * 其余能力（播放/详情/导入导出）仍走主引擎，避免跨引擎共享状态问题。
 */
class PluginRuntime private constructor(
    private val primary: JsEngine,
    private val extras: List<JsEngine>
) {

    private val engines: List<JsEngine> = listOf(primary) + extras

    /** 搜索并发引擎数量（主引擎 + extras）。 */
    private val poolSize = engines.size

    /** 各引擎在途调用计数（取最小值做负载均衡）。 */
    private val laneBusy = IntArray(poolSize)
    private val laneLock = Any()

    private fun pickLane(): Int = synchronized(laneLock) {
        var best = 0
        for (i in 1 until poolSize) if (laneBusy[i] < laneBusy[best]) best = i
        laneBusy[best]++
        best
    }

    private fun releaseLane(lane: Int) = synchronized(laneLock) {
        laneBusy[lane]--
    }

    companion object {
        /** 并行搜索的引擎总数。Amlogic p230 上 3 台足够，过大会推高 CPU/内存。 */
        private const val SEARCH_ENGINES = 3

        @Volatile private var INSTANCE: PluginRuntime? = null

        fun create(context: Context, store: com.tvmusic.data.PluginStore): PluginRuntime {
            val existing = INSTANCE
            if (existing != null) return existing
            val provider = { store.allVariablesMerged() }
            val primary = QuickJsEngine(context.applicationContext, provider)
            primary.initialize()
            val extras = ArrayList<JsEngine>()
            while (extras.size < SEARCH_ENGINES - 1) {
                val e = runCatching {
                    QuickJsEngine(context.applicationContext, provider)
                        .also { it.initialize() }
                }.getOrNull()
                if (e == null) break
                extras.add(e)
            }
            val rt = PluginRuntime(primary, extras)
            INSTANCE = rt
            return rt
        }

        fun get(): PluginRuntime = INSTANCE ?: error("PluginRuntime not initialized")
    }

    /** 插件注册需要同步到所有引擎，否则并行搜索分发到的引擎上找不到该插件。 */
    suspend fun loadPlugin(platform: String, source: String): Boolean = withContext(Dispatchers.IO) {
        engines.all { it.registerPlugin(platform, source) }
    }

    suspend fun hasPlugin(platform: String): Boolean = withContext(Dispatchers.IO) {
        primary.hasPlugin(platform)
    }

    /** 插件是否实现指定方法（用于按能力筛选，如 getRecommendSheetTags / getTopLists）。 */
    suspend fun hasMethod(platform: String, method: String): Boolean = withContext(Dispatchers.IO) {
        primary.hasMethod(platform, method)
    }

    suspend fun readInfo(platform: String): JSONObject? = withContext(Dispatchers.IO) {
        val raw = primary.readPluginInfo(platform) ?: return@withContext null
        try { JSONObject(raw) } catch (_: Exception) { null }
    }

    suspend fun callAsync(
        platform: String,
        method: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = primary.timeoutMillis
    ): Any {
        return withContext(Dispatchers.IO) {
            val argsJson = JSONArray(args).toString()
            val result = primary.invoke(platform, method, argsJson, timeoutMs)
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
            val result = primary.invoke(platform, method, arr.toString())
            parseJsonResult(result)
        }
    }

    /**
     * 并行搜索调用：分发到最空闲的引擎执行，多个音源可真正同时搜。
     * 要求插件已通过 [loadPlugin] 注册到所有引擎。
     */
    suspend fun callParallel(
        platform: String,
        method: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = primary.timeoutMillis
    ): Any {
        val lane = pickLane()
        return withContext(Dispatchers.IO) {
            try {
                val argsJson = JSONArray(args).toString()
                val result = engines[lane].invoke(platform, method, argsJson, timeoutMs)
                parseJsonResult(result)
            } finally {
                releaseLane(lane)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        engines.forEach { runCatching { (it as? AutoCloseable)?.close() } }
    }

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
