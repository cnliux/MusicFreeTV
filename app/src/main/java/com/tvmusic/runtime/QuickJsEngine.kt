package com.tvmusic.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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

    /**
     * P0-7：JS 执行中断能力是否可用（native 成功安装了 interrupt handler）。
     * 不可用时退化为"超时就判定引擎已死"——由 [PluginRuntime] 重建该 lane。
     */
    @Volatile
    private var interruptReady = false

    private external fun nativeInitPump(): Boolean
    private external fun nativePumpJobs(runtimePtr: Long): Int

    /** 给 runtime 安装 JS 执行中断回调（解决插件死循环永久占用 JS 线程）。 */
    private external fun nativeInstallInterrupt(runtimePtr: Long): Boolean

    /**
     * 设置当前线程的 JS 执行时限（绝对毫秒，<=0 不限）。
     * 必须在真正执行 JS 的线程上调用（JS 线程或 EventQueue 线程），native 侧是线程局部变量。
     */
    private external fun nativeSetInterruptDeadline(deadlineMs: Long)

    /**
     * P0-7：引擎是否已被判定不可用。
     *
     * 两种来源：
     *  1. 执行超时且中断不可用/未生效（脚本可能卡在 native 或中断没装上）→ JS 线程已被占死；
     *  2. 执行确实被中断（脚本跑成死循环）→ runtime 里可能残留异常状态。
     * 两种都由 [PluginRuntime] 用新引擎替换（注册表按需重建），不再让 lane 永久哑掉。
     */
    @Volatile
    private var poisonedFlag = false

    @Volatile
    private var poisonedReason: String? = null

    override val poisoned: Boolean get() = poisonedFlag

    override val poisonReason: String? get() = poisonedReason

    private fun markPoisoned(reason: String) {
        if (poisonedFlag) return
        poisonedFlag = true
        poisonedReason = reason
        Log.e(TAG, "engine poisoned: $reason")
    }

    /** QuickJS 中断后抛 InternalError: interrupted，据此判定为死循环脚本。 */
    private fun isInterruptError(t: Throwable): Boolean {
        val m = generateSequence<Throwable>(t) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.contains("interrupt", ignoreCase = true) }
        return m != null
    }

    companion object {
        private const val TAG = "QuickJsEngine"

        /** jsBlock 跨线程同步等待的超时（ms）：防 JS 线程挂死引发 ANR（H6）。 */
        private const val JS_BLOCK_TIMEOUT_MS = 5_000L

        /**
         * P0-7：JS 执行时限比调用方等待预算早到的余量（ms）。
         * 提前中断，脚本能退栈并把 "interrupted" 异常抛回，而不是留下仍在跑的脚本。
         */
        private const val INTERRUPT_GRACE_MS = 300L

        /** pump 结果：出错或被中断回调打断（job 不可能再有进展）。 */
        private const val PUMP_ERROR = -1

        /** pump 结果：单次 pump 超时，job 可能还在跑（慢 HTTP 等）。 */
        private const val PUMP_TIMEOUT = -2

        /**
         * bootstrap 预算（ms）：桥垫片 + globals/moduleLoader + 8 个解析库（cheerio/
         * big-integer/axios/webdav 等，合计数 MB 源码）要一次性求值，低配 TV 上
         * 明显慢于通用 5s 预算。原来超时只是放弃等待（引擎照样初始化成功），
         * 有了执行中断后必须给足预算，否则会把正常 bootstrap 中途打断。
         */
        private const val JS_BOOTSTRAP_TIMEOUT_MS = 60_000L

        /**
         * 插件注册专用预算（ms）：插件源码普遍 300KB~1.5MB，注册要把整段源码
         * JSON.quote 后交给 QuickJS 求值。沿用 5s 通用预算时，低配 TV 上极易超时——
         * 而超时只是放弃等待，**JS 线程仍在跑那段大脚本**，后续所有 jsBlock 调用
         * 继续排队超时，整台引擎就此"假死"，表现为"导入插件后 APK/插件一直加载不出来"。
         * 故注册路径给足独立预算。
         */
        private const val JS_REGISTER_TIMEOUT_MS = 60_000L

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

    // M1：JS 引擎网络请求从共享 standard 客户端派生（原来每台引擎独立建客户端，
    // 最多 6 台引擎 = 6 套连接池/线程池；newBuilder 派生后复用同一套）。
    private val okHttp = com.tvmusic.net.HttpClients.derive().build()

    override val timeoutMillis: Long = TimeUnit.SECONDS.toMillis(60)

    override fun initialize() {
        val t = HandlerThread("musicfree-js").apply { start() }
        jsThreadName = t.name
        jsHandler = Handler(t.looper)
        // 解析 runtimePtr 并初始化 native job pump。
        runtimePtr = readRuntimePtr()
        pumpReady = runtimePtr != 0L && runCatching { nativeInitPump() }.getOrDefault(false)
        // P0-7：装 JS 执行中断回调（bootstrap 之前装上，插件注册等大脚本同样受保护）。
        interruptReady = runCatching { nativeInstallInterrupt(runtimePtr) }.getOrDefault(false)
        Log.i(TAG, "job pump ready=$pumpReady interrupt=$interruptReady runtimePtr=$runtimePtr")
        jsBlock(timeoutMs = JS_BOOTSTRAP_TIMEOUT_MS) {
            registerNativeBridge()
            jsContext.executeVoidScript(buildBootstrap(), "bootstrap.js")
        }
        // P0-7：只有 pump 与中断都可用时才让 JS 把插件方法体推迟到微任务执行
        // （微任务跑在 job 路径上，是本版 quickjs 唯一会查 interrupt handler 的地方）。
        // 任一能力缺失就保持原来的同步调用，避免"既不能被中断、又等不到结果"。
        val defer = pumpReady && interruptReady
        runCatching {
            jsBlock(timeoutMs = JS_BLOCK_TIMEOUT_MS) {
                jsContext.executeVoidScript(
                    "globalThis.__deferInvoke = ${if (defer) "true" else "false"};", "deferFlag")
            }
        }.onFailure { Log.w(TAG, "set __deferInvoke failed: ${it.message}") }
        Log.i(TAG, "__deferInvoke=$defer")
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
    /**
     * 在 QuickJS 的 EventQueue 线程上推进 job 队列（Promise/await 续体）。
     * @return 执行掉的 job 数（>=0 正常）；[PUMP_ERROR] 执行出错或被中断回调打断；
     *         [PUMP_TIMEOUT] 单次 pump 超时（job 还在跑）
     */
    private fun pumpJobsSync(maxMs: Long): Int {
        if (!pumpReady) return PUMP_ERROR
        val latch = java.util.concurrent.CountDownLatch(1)
        var ran = 0
        var ok = false
        try {
            quickJs.postEventQueue {
                // 时限是 native 线程局部变量，必须在真正解释 JS 的这个线程上设置。
                // 续体（Promise/await 之后）同样可能跑成死循环，这里给 maxMs + 余量作兜底：
                // 故意晚于等待预算，不打断正常偏慢的续体，只拦真正的死循环。
                armInterrupt(maxMs + INTERRUPT_GRACE_MS)
                try {
                    val n = nativePumpJobs(runtimePtr)
                    if (n < 0) {
                        // job 执行报错或被中断：JS_ExecutePendingJob 返回负值。
                        // 续体被中断意味着这次的 promise 永远不会有结果了。
                        Log.w(TAG, "pumpJobs aborted (n=$n)")
                    } else {
                        ran = n
                        ok = true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "pumpJobs: ${e.message}")
                } finally {
                    disarmInterrupt()
                    latch.countDown()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "postEventQueue: ${e.message}")
            return PUMP_ERROR
        }
        return try {
            if (latch.await(maxMs, TimeUnit.MILLISECONDS)) {
                if (ok) ran else PUMP_ERROR
            } else {
                PUMP_TIMEOUT
            }
        } catch (e: InterruptedException) {
            PUMP_ERROR
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
            // 注册用独立的长预算（见 JS_REGISTER_TIMEOUT_MS），不用通用 5s：
            // 大插件求值本就可能超过 5s，误超时会让引擎被大脚本长期占住。
            jsBlock(timeoutMs = JS_REGISTER_TIMEOUT_MS) {
                jsContext.executeBooleanScript(script, "register_$platform")
            }
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
        // timeoutMs 预算覆盖「排队等待 + 执行」两段：拿不到锁就按超时抛错，调用方
        // （如换源扫描）可快速跳过被慢调用占住的引擎。旧逻辑超时从拿到锁后才起算，
        // 排队时长无限，一个 60s 慢调用能把整条换源链无声拖死。
        val deadline = System.currentTimeMillis() + timeoutMs
        if (!invokeLock.tryLock(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "invoke busy (queue timeout) $platform.$method")
            throw PluginCallException("plugin engine busy: $platform.$method")
        }
        try {
            val remaining = deadline - System.currentTimeMillis()
            return doInvoke(platform, method, argsJson, remaining.coerceAtLeast(2_000L))
        } finally {
            invokeLock.unlock()
        }
    }

    private val invokeLock = java.util.concurrent.locks.ReentrantLock()

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
                // 慢 HTTP 会让单次 pump 超时返回 PUMP_TIMEOUT，属正常，继续轮询；
                // 但 PUMP_ERROR（含被中断回调打断）意味着这次调用的续体已经废了，
                // 再等也只是空转到 deadline，不如立刻收场（P0-7：死循环插件不再占满预算）。
                while (!future.isDone && System.currentTimeMillis() < deadline) {
                    if (pumpJobsSync(3000) == PUMP_ERROR) {
                        Log.w(TAG, "pump error, abort $platform.$method cb=$cbId")
                        break
                    }
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

    /**
     * 在 JS 线程上执行，跨线程时同步等待结果。
     *
     * P0-7：真正给 JS 执行设上限，而不是"放弃等待"。跨线程提交任务时给执行线程装上
     * 中断时限（JS 线程局部），脚本超时会被 QuickJS 中断抛回，JS 线程随即回到空闲，
     * lane 可继续服务；同步等待超时且中断没生效说明线程已被占死，标记引擎不可用
     * 交由 [PluginRuntime] 重建。
     *
     * @param timeoutMs 等待预算，默认 [JS_BLOCK_TIMEOUT_MS]；插件注册等重活需显式放宽
     *                  （同步等待与执行时限同源，预算过小会中断尚未跑完的大脚本）。
     */
    private inline fun <T> jsBlock(
        timeoutMs: Long = JS_BLOCK_TIMEOUT_MS,
        crossinline block: () -> T
    ): T {
        val handler = jsHandler ?: error("JsEngine not initialized")
        if (jsThreadName != null && Thread.currentThread().name == jsThreadName) {
            // 已在 JS 线程内执行（如 pump 回调里再调用）：就地装时限，不重复排队。
            armInterrupt(interruptBudget(timeoutMs))
            try {
                return block()
            } catch (t: Throwable) {
                if (isInterruptError(t)) markPoisoned("script interrupted after ${timeoutMs}ms")
                throw t
            } finally {
                disarmInterrupt()
            }
        }
        val future = FutureTask<T> {
            armInterrupt(interruptBudget(timeoutMs))
            try {
                block()
            } catch (t: Throwable) {
                if (isInterruptError(t)) markPoisoned("script interrupted after ${timeoutMs}ms")
                throw t
            } finally {
                disarmInterrupt()
            }
        }
        handler.post(future)
        // H6 修复：future.get() 无超时会在 JS 线程挂死（插件死循环）时引发 ANR。
        // 与 invoke 的 5s 兜底对齐：超时抛出后由调用方按 PluginCallException 处理，
        // 避免主线程/启动线程无限等待。
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // 到点还没返回：中断要么不可用（symbol 缺失）、要么脚本卡在 native 调用里
            // （桥 HTTP 有独立超时，但第三方 JNI/正则没有），两种情况 JS 线程都回不来。
            markPoisoned("jsBlock timeout ${timeoutMs}ms (interrupt=$interruptReady)")
            throw PluginCallException("js engine busy/timeout (jsBlock)")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /**
     * 执行时限比同步等待预算早 [INTERRUPT_GRACE_MS]：让脚本先被中断、干净退栈，
     * 调用方拿到的是明确的 "interrupted" 异常，而不是"等待超时但脚本还在跑"。
 */
private fun interruptBudget(timeoutMs: Long): Long =
        (timeoutMs - INTERRUPT_GRACE_MS).coerceAtLeast(1_000L)

    /** 给当前线程装上 JS 执行时限（native 线程局部，需在执行 JS 的线程上调用）。 */
    private fun armInterrupt(budgetMs: Long) {
        if (!interruptReady) return
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        runCatching { nativeSetInterruptDeadline(deadline) }
            .onFailure { interruptReady = false }
    }

    /** 清除执行时限：脚本执行期间（含插件起的后台回调线程首次进入时）不限时。 */
    private fun disarmInterrupt() {
        if (!interruptReady) return
        runCatching { nativeSetInterruptDeadline(0) }
    }

    private fun loadAssets(path: String): String {
        return appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** M19：URL 脱敏——query 可能带签名 token/Cookie，日志只保留 host+path。 */
    private fun sanitizeUrl(url: String): String = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}${u.path ?: ""}?…"
    } catch (_: Exception) {
        url.substringBefore('?').take(80)
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
            // M19：第三方插件的 console.log 不再污染 release logcat（无采样/截断风险），
            // 仅 debug 构建输出，且单条截断 512 字符。
            if (!com.tvmusic.BuildConfig.DEBUG) return
            val safe = if (msg.length > 512) msg.take(512) + "…(${msg.length})" else msg
            when (level) {
                "error" -> Log.e(TAG, safe)
                "warn" -> Log.w(TAG, safe)
                "info" -> Log.i(TAG, safe)
                else -> Log.d(TAG, safe)
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
                // M19：URL 脱敏——query 可能带签名 token/Cookie，只打 host+path
                Log.w(TAG, "http error $method ${sanitizeUrl(url)} ${System.currentTimeMillis() - t0}ms ${e.message}")
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