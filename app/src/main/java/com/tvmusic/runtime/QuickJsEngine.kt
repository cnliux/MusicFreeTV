package com.tvmusic.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.webkit.JavascriptInterface
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSValue
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于 io.github.taoweiji:quickjs-android 的引擎实现。
 *
 * 架构说明：
 *  1. 所有 JSContext 调用都收口到单条 JS HandlerThread 上（jsBlock），保证顺序与线程安全；
 *  2. 原生能力通过 registerJavaMethod 注册 `__bridge_*` 全局函数，再由桥垫片
 *     （BRIDGE_SHIM_JS）组装为 `nativeBridge` 对象（手动绑定替代反射绑定，
 *     见 [registerNativeBridge] 注释）：
 *     - httpRequest(method, url, headersJson, body)  -> OkHttp 同步请求
 *     - getUserVariables(platform)                    -> 该插件用户变量（env.getUserVariables）
 *     - scheduleTimer(id, ms, repeat)                 -> setTimeout / setInterval
 *     - onPluginResult / onPluginError                -> async RPC 回吐
 *  3. async 方法调用：__invoke -> Promise.then -> nativeBridge.onPluginResult。
 *     由于 QuickJS 的 Promise 微任务需要引擎推动，invoke() 采用“轮询 + drainJobs”等待。
 */
class QuickJsEngine(
    private val appContext: Context,
    private val variablesProvider: (platform: String) -> Map<String, String>
) : JsEngine {

    private var jsHandler: Handler? = null
    private var jsThreadName: String? = null

    private val quickJs = QuickJS.createRuntimeWithEventQueue()
    private val jsContext: JSContext = quickJs.createContext()

    private val pending = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val idGen = AtomicInteger(0)
    private val timers = ConcurrentHashMap<Int, Runnable>()

    // ---- QuickJS job pump（native）----
    // bundled libquickjs.so(2021-03-27) 导出 JS_ExecutePendingJob，但 taoweiji 的
    // Java 封装从不调用它，导致 async/await 续体永不执行。nativePumpJobs 在
    // QuickJS 的 EventQueue 线程上把待处理 job 跑完。
    private var pumpReady = false
    private var runtimePtr = 0L

    private external fun nativeInitPump(): Boolean
    private external fun nativePumpJobs(runtimePtr: Long): Int

    companion object {
        private const val TAG = "QuickJsEngine"

        private val parseLibsOrder = listOf(
            "crypto-js", "qs", "dayjs", "he", "big-integer", "cheerio", "webdav", "axios"
        )

        init {
            try {
                System.loadLibrary("jsjobpump")
            } catch (e: Throwable) {
                Log.e("QuickJsEngine", "load jsjobpump failed: ${e.message}")
            }
        }
    }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override val timeoutMillis: Long = TimeUnit.SECONDS.toMillis(60)

    override fun initialize() {
        val t = HandlerThread("musicfree-js").apply { start() }
        jsThreadName = t.name
        jsHandler = Handler(t.looper)
        // 解析 runtimePtr 并初始化 native job pump。
        runtimePtr = readRuntimePtr()
        pumpReady = runtimePtr != 0L && runCatching { nativeInitPump() }.getOrDefault(false)
        Log.i(TAG, "job pump ready=$pumpReady runtimePtr=$runtimePtr")
        jsBlock {
            registerNativeBridge()
            jsContext.executeVoidScript(buildBootstrap(), "bootstrap.js")
        }
    }

    /**
     * 注册原生桥：registerJavaMethod 手动绑定（替代 addJavascriptInterface 反射绑定）。
     *
     * 为什么必须弃用 addJavascriptInterface：反射绑定按 Java 方法签名严格校验参数个数，
     * 第三方插件以错误参数个数调用桥方法（如 0 参调用 getUserVariables/clearTimer）时，
     * Method.invoke 抛 IllegalArgumentException 成为 JNI pending exception，库自身
     * 随后继续调 JNI（createJSValue/FindClass）触发 CheckJNI SIGABRT，整个进程无差别
     * 崩溃（2026-09-26「搜索点击内容闪退」的根因，try/catch 无法拦截）。
     * registerJavaMethod 回调直接拿到 JSArray，由 [bridgeStr]/[bridgeInt]/[bridgeBool]
     * 自行解析：越界/类型不符一律取默认值；回调体整体 try/catch——任何插件错参调用
     * 只记日志，绝不向 JNI 抛异常。
     */
    private fun registerNativeBridge() {
        val nb = NativeBridge()
        fun voidFn(name: String, block: (JSArray?) -> Unit) {
            jsContext.registerJavaMethod(JavaVoidCallback { _, args ->
                try {
                    block(args)
                } catch (e: Throwable) {
                    Log.w(TAG, "bridge $name: ${e.message}")
                }
            }, name)
        }
        fun strFn(name: String, block: (JSArray?) -> String) {
            jsContext.registerJavaMethod(JavaCallback { _, args ->
                try {
                    block(args)
                } catch (e: Throwable) {
                    Log.w(TAG, "bridge $name: ${e.message}")
                    ""
                }
            }, name)
        }
        voidFn("__bridge_log") { args -> nb.log(args.bridgeStr(0, "log"), args.bridgeStr(1)) }
        strFn("__bridge_getUserVariables") { args -> nb.getUserVariables(args.bridgeStr(0)) }
        strFn("__bridge_httpRequest") { args ->
            nb.httpRequest(args.bridgeStr(0), args.bridgeStr(1), args.bridgeStr(2), args.bridgeStr(3))
        }
        voidFn("__bridge_scheduleTimer") { args ->
            nb.scheduleTimer(args.bridgeInt(0), args.bridgeInt(1), args.bridgeBool(2))
        }
        voidFn("__bridge_clearTimer") { args -> nb.clearTimer(args.bridgeInt(0)) }
        voidFn("__bridge_onPluginResult") { args -> nb.onPluginResult(args.bridgeInt(0), args.bridgeStr(1)) }
        voidFn("__bridge_onPluginError") { args -> nb.onPluginError(args.bridgeInt(0), args.bridgeStr(1)) }
    }

    /** QuickJS 的 runtimePtr 是包级字段，反射取出交给 native pump。 */
    private fun readRuntimePtr(): Long {
        return try {
            val f = com.quickjs.QuickJS::class.java.getDeclaredField("runtimePtr")
            f.isAccessible = true
            f.getLong(quickJs)
        } catch (e: Throwable) {
            Log.e(TAG, "readRuntimePtr failed: ${e.message}")
            0L
        }
    }

    /**
     * 在 QuickJS 的 EventQueue 线程上把待处理 job（Promise/await 续体）跑完。
     * 同步等待：postEventQueue 是异步的，用 latch 等它执行完再返回，
     * 保证调用方拿到 future 前续体已推进。
     */
    private fun pumpJobsSync(maxMs: Long): Boolean {
        if (!pumpReady) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        try {
            quickJs.postEventQueue {
                try {
                    nativePumpJobs(runtimePtr)
                    ok = true
                } catch (e: Throwable) {
                    Log.w(TAG, "pumpJobs: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "postEventQueue: ${e.message}")
            return false
        }
        return try {
            if (latch.await(maxMs, TimeUnit.MILLISECONDS)) ok else false
        } catch (e: InterruptedException) {
            false
        }
    }

    override fun drainJobs() {
        if (jsHandler == null) return
        if (!pumpReady) {
            // 兜底：pump 不可用时退回旧行为（对纯 Promise.resolve 有效，对 await 无效）
            jsBlock {
                try {
                    jsContext.executeVoidScript("Promise.resolve();", "drain")
                } catch (e: Exception) {
                    Log.w(TAG, "drainJobs: ${e.message}")
                }
            }
        } else {
            pumpJobsSync(3000)
        }
    }

    override fun registerPlugin(platform: String, source: String): Boolean {
        val t0 = System.currentTimeMillis()
        val script = "globalThis.__registerPlugin(" +
            JSONObject.quote(platform) + ", " + JSONObject.quote(source) + ");"
        val ok = try {
            jsBlock { jsContext.executeBooleanScript(script, "register_$platform") }
        } catch (e: Throwable) {
            // 脚本求值抛 QuickJSException 属正常失败路径（语法错误等），
            // 绝不能让它冒泡——冒泡后调用方 runCatching 能接住，但引擎状态可能已脏。
            Log.e(TAG, "register failed $platform: ${e.message}")
            false
        }
        val cost = System.currentTimeMillis() - t0
        if (cost > 2000) Log.w(TAG, "register slow $platform ${cost}ms")
        return ok
    }

    override fun hasPlugin(platform: String): Boolean {
        val script = "globalThis.__hasPlugin(" + JSONObject.quote(platform) + ");"
        return jsBlock { jsContext.executeBooleanScript(script, "has_$platform") }
    }

    override fun hasMethod(platform: String, method: String): Boolean {
        val script = "globalThis.__hasMethod(" +
            JSONObject.quote(platform) + ", " + JSONObject.quote(method) + ");"
        return jsBlock { jsContext.executeBooleanScript(script, "hasMethod_${platform}_$method") }
    }

    override fun readPluginInfo(platform: String): String? {
        val script = "globalThis.__readPluginInfo(" + JSONObject.quote(platform) + ");"
        return jsBlock {
            val info = jsContext.executeStringScript(script, "info_$platform")
            if (info.isNullOrBlank() || info == "null") null else info
        }
    }

    override fun invoke(
        platform: String,
        method: String,
        argsJson: String,
        timeoutMs: Long
    ): String {
        // QuickJS 引擎单线程执行脚本；多线程同时 invoke 会导致 drainJobs/事件循环互相阻塞而挂死，
        // 因此整个调用流程全局串行化（与 JS 线程本身的执行天然一致）。
        synchronized(invokeLock) {
            return doInvoke(platform, method, argsJson, timeoutMs)
        }
    }

    private val invokeLock = Any()

    private fun doInvoke(
        platform: String,
        method: String,
        argsJson: String,
        timeoutMs: Long
    ): String {
        val cbId = idGen.incrementAndGet()
        val future = CompletableFuture<String>()
        pending[cbId] = future
        Log.i(TAG, "invoke start $platform.$method cb=$cbId")
        try {
            val script = "globalThis.__invoke(" +
                JSONObject.quote(platform) + ", " +
                JSONObject.quote(method) + ", " +
                JSONObject.quote(argsJson) + ", " + cbId + ");"
            jsBlock { jsContext.executeVoidScript(script, "invoke_$cbId") }

            val deadline = System.currentTimeMillis() + timeoutMs
            if (pumpReady) {
                // 在 QuickJS EventQueue 线程上推进 Promise/await 微任务队列。
                // 每次 pumpJobsSync 把当前所有待处理 job 跑完（含插件续体与 .then 回吐）。
                // 慢 HTTP 会让单次 pump 超时返回 false，属正常，继续轮询直到 future 完成或 deadline。
                while (!future.isDone && System.currentTimeMillis() < deadline) {
                    pumpJobsSync(3000)
                    Thread.sleep(2)
                }
            } else {
                // 兜底：native pump 不可用时退回旧逻辑（对纯 Promise.resolve 有效，对 await 无效）
                var stalled = 0
                while (System.currentTimeMillis() < deadline) {
                    if (future.isDone) break
                    if (!drainWithTimeout(3000)) {
                        stalled++
                        if (stalled >= 4) {
                            Log.e(TAG, "js thread stalled $platform.$method cb=$cbId")
                            break
                        }
                    } else {
                        stalled = 0
                    }
                    Thread.sleep(5)
                }
            }
            if (!future.isDone) {
                future.completeExceptionally(
                    PluginCallException("plugin call timeout: $platform.$method")
                )
                Log.w(TAG, "invoke timeout $platform.$method cb=$cbId")
            } else {
                Log.i(TAG, "invoke done $platform.$method cb=$cbId")
            }
            // 解包 ExecutionException：调用方按异常类型分支（如 PluginCallException ->
            // 友好提示），包装类会让类型判断失效、把 cause.toString() 原样漏给 UI
            try {
                return future.get(5, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause ?: e)
            }
        } finally {
            pending.remove(cbId)
        }
    }

    /** drain 单次带超时：JS 线程如被长脚本占用则尽快返回失败，避免无限阻塞调用线程。 */
    private fun drainWithTimeout(maxMs: Long): Boolean {
        val handler = jsHandler ?: return false
        if (jsThreadName != null && Thread.currentThread().name == jsThreadName) return true
        val drained = FutureTask<Boolean> {
            try {
                jsContext.executeVoidScript("Promise.resolve();", "drain")
                true
            } catch (e: Exception) {
                Log.w(TAG, "drain: ${e.message}")
                false
            }
        }
        handler.post(drained)
        return try {
            drained.get(maxMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try {
            pending.values.forEach { it.completeExceptionally(RuntimeException("engine closed")) }
            pending.clear()
            timers.clear()
            jsContext.close()
        } catch (e: Exception) {
            Log.w(TAG, "close context: ${e.message}")
        }
        try {
            quickJs.close()
        } catch (e: Exception) {
            Log.w(TAG, "close runtime: ${e.message}")
        }
        jsHandler?.removeCallbacksAndMessages(null)
        (jsHandler?.looper?.thread as? HandlerThread)?.quitSafely()
    }

    // ---------------- 内部工具 ----------------

    /** 在 JS 线程上执行，跨线程时同步等待结果。 */
    private inline fun <T> jsBlock(crossinline block: () -> T): T {
        val handler = jsHandler ?: error("JsEngine not initialized")
        if (jsThreadName != null && Thread.currentThread().name == jsThreadName) {
            return block()
        }
        val future = FutureTask<T> { block() }
        handler.post(future)
        return future.get()
    }

    private fun loadAssets(path: String): String {
        return appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun buildBootstrap(): String {
        val sb = StringBuilder(1 shl 17)
        // 桥垫片必须在 globals.js 之前：globals.js 顶层立即捕获 global.nativeBridge。
        sb.append(BRIDGE_SHIM_JS).append('\n')
        sb.append(loadAssets("runtime/globals.js")).append('\n')
        sb.append(loadAssets("runtime/moduleLoader.js")).append('\n')
        for (lib in parseLibsOrder) {
            val src = loadAssets("runtime/libs/$lib.js")
            sb.append("globalThis.__registerCommonJS(")
            sb.append(JSONObject.quote(lib)).append(", ")
            sb.append(JSONObject.quote(src)).append(");\n")
        }
        sb.append(loadAssets("runtime/bootstrap.js")).append('\n')
        return sb.toString()
    }

    /**
     * 把 __bridge_* 全局函数组装成 nativeBridge 对象。JS 包装为直接引用，不校验参数
     * 个数——插件错参调用会以 undefined/缺参进入 Java 侧，由 bridgeStr/bridgeInt/
     * bridgeBool 兜底为默认值，不再有反射签名校验崩溃。
     */
    private val BRIDGE_SHIM_JS = """
(function (g) {
    'use strict';
    if (typeof g.__bridge_log !== 'function') { return; }
    g.nativeBridge = {
        log: g.__bridge_log,
        getUserVariables: g.__bridge_getUserVariables,
        httpRequest: g.__bridge_httpRequest,
        scheduleTimer: g.__bridge_scheduleTimer,
        clearTimer: g.__bridge_clearTimer,
        onPluginResult: g.__bridge_onPluginResult,
        onPluginError: g.__bridge_onPluginError
    };
})(globalThis);
"""

    // ---------------- 原生桥 ----------------

    /**
     * 桥实现本体：由 [registerNativeBridge] 逐方法以 registerJavaMethod 绑定。
     * 注意：quickjs-android 的 JSObject.getParameters 只支持
     * int/Integer、double/Double、boolean/Boolean、String、JSArray、JSObject、JSFunction，
     * **不支持 long**——本类所有方法不直接面对 JS（参数已由调用方从 JSArray 解析为
     * Int/String/Boolean），杜绝了类型不匹配崩溃。
     */
    private inner class NativeBridge {

        fun log(level: String, msg: String) {
            when (level) {
                "error" -> Log.e(TAG, msg)
                "warn" -> Log.w(TAG, msg)
                "info" -> Log.i(TAG, msg)
                else -> Log.d(TAG, msg)
            }
        }

        fun getUserVariables(platform: String): String {
            return try {
                JSONObject(variablesProvider(platform.orEmpty()) as Map<*, *>).toString()
            } catch (e: Exception) {
                "{}"
            }
        }

        fun httpRequest(method: String, url: String, headersJson: String, body: String): String {
            val t0 = System.currentTimeMillis()
            return try {
                val headersObj = try {
                    JSONObject(headersJson)
                } catch (e: Exception) {
                    JSONObject()
                }
                val builder = Request.Builder().url(url)
                val names = headersObj.names()
                if (names != null) {
                    for (i in 0 until names.length()) {
                        val name = names.getString(i)
                        // gzip 由 OkHttp 透明处理；插件手写 Accept-Encoding 会导致拿到压缩原文
                        if (name.equals("Accept-Encoding", ignoreCase = true)) continue
                        builder.addHeader(name, headersObj.optString(name))
                    }
                }
                val m = method.uppercase(Locale.ROOT)
                val reqBody = if (m == "GET" || m == "HEAD") null else body.toRequestBody(null)
                builder.method(m, reqBody)
                okHttp.newCall(builder.build()).execute().use { resp ->
                    Log.i(TAG, "http $method $url ${System.currentTimeMillis() - t0}ms")
                    val respBody = resp.body?.string() ?: ""
                    val hh = JSONObject()
                    for (i in 0 until resp.headers.size) {
                        val k = resp.headers.name(i)
                        val v = resp.headers.value(i)
                        hh.put(k, if (hh.has(k)) hh.getString(k) + "," + v else v)
                    }
                    JSONObject()
                        .put("status", resp.code)
                        .put("statusText", resp.message)
                        .put("headers", hh)
                        .put("body", respBody)
                        .toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "http error $method $url ${System.currentTimeMillis() - t0}ms ${e.message}")
                JSONObject().put("__error", e.message ?: "network error").toString()
            }
        }

        fun scheduleTimer(id: Int, ms: Int, repeat: Boolean) {
            val handler = jsHandler ?: return
            val delay = ms.toLong().coerceAtLeast(0L)
            var runnable: Runnable? = null
            runnable = Runnable {
                val target = runnable ?: return@Runnable
                jsBlock {
                    try {
                        jsContext.executeVoidScript("globalThis.__runTimer($id);", "timer_$id")
                    } catch (e: Exception) {
                        Log.w(TAG, "runTimer $id: ${e.message}")
                        timers.remove(id)
                    }
                }
                if (repeat && timers.containsKey(id) && jsHandler != null) {
                    jsHandler?.postDelayed(target, delay)
                }
            }
            timers[id] = runnable
            handler.postDelayed(runnable, delay)
        }

        fun clearTimer(id: Int) {
            timers.remove(id)?.let { jsHandler?.removeCallbacks(it) }
        }

        fun onPluginResult(cbId: Int, json: String) {
            pending.remove(cbId)?.complete(json)
        }

        fun onPluginError(cbId: Int, message: String) {
            pending.remove(cbId)?.completeExceptionally(PluginCallException(message))
        }
    }
}

// ---------------- 桥参数防御性解析 ----------------
// 插件 JS 可能以任意参数个数/类型调用桥方法。以下扩展一律：null/越界/类型不符/
// 抛异常 → 返回默认值，绝不向 JNI 抛异常（否则 CheckJNI SIGABRT 杀死整个进程）。

/** 第 i 个参数按字符串取；对象/数组序列化为 JSON 文本。 */
private fun JSArray?.bridgeStr(i: Int, def: String = ""): String {
    if (this == null) return def
    return try {
        if (i < 0 || i >= length()) def
        else when (getType(i)) {
            JSValue.TYPE.STRING -> getString(i) ?: def
            JSValue.TYPE.INTEGER -> getInteger(i).toString()
            JSValue.TYPE.DOUBLE -> getDouble(i).toString()
            JSValue.TYPE.BOOLEAN -> getBoolean(i).toString()
            JSValue.TYPE.JS_OBJECT -> getObject(i)?.toJSONObject()?.toString() ?: def
            JSValue.TYPE.JS_ARRAY -> getArray(i)?.toJSONArray()?.toString() ?: def
            else -> def
        }
    } catch (_: Throwable) {
        def
    }
}

/** 第 i 个参数按 Int 取（接受数字/布尔/数字字符串）。 */
private fun JSArray?.bridgeInt(i: Int, def: Int = 0): Int {
    if (this == null) return def
    return try {
        if (i < 0 || i >= length()) def
        else when (getType(i)) {
            JSValue.TYPE.INTEGER -> getInteger(i)
            JSValue.TYPE.DOUBLE -> getDouble(i).toInt()
            JSValue.TYPE.STRING -> getString(i)?.trim()?.toDoubleOrNull()?.toInt() ?: def
            JSValue.TYPE.BOOLEAN -> if (getBoolean(i)) 1 else 0
            else -> def
        }
    } catch (_: Throwable) {
        def
    }
}

/** 第 i 个参数按 Boolean 取。 */
private fun JSArray?.bridgeBool(i: Int, def: Boolean = false): Boolean {
    if (this == null) return def
    return try {
        if (i < 0 || i >= length()) def
        else when (getType(i)) {
            JSValue.TYPE.BOOLEAN -> getBoolean(i)
            JSValue.TYPE.INTEGER -> getInteger(i) != 0
            JSValue.TYPE.DOUBLE -> getDouble(i) != 0.0
            JSValue.TYPE.STRING -> getString(i)?.let { it.equals("true", true) || it == "1" } ?: def
            else -> def
        }
    } catch (_: Throwable) {
        def
    }
}