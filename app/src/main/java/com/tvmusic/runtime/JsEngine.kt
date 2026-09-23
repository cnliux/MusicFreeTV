package com.tvmusic.runtime

import java.util.concurrent.TimeUnit

/**
 * JS 引擎抽象。实现负责：初始化运行时、注册插件源码、同步/异步调用插件方法。
 * 插件方法多为 async（Promise），调用方通过 [invoke] 阻塞等待，内部推动 microtask。
 */
interface JsEngine : AutoCloseable {

    /** 初始化引擎与原生桥。必须在任意调用前执行一次。 */
    fun initialize()

    /** 尽力推动 JS 的 Promise 微任务队列。 */
    fun drainJobs()

    /** 注册插件源码到指定 platform，返回是否成功解析出导出对象。 */
    fun registerPlugin(platform: String, source: String): Boolean

    /** 插件是否已注册。 */
    fun hasPlugin(platform: String): Boolean

    /** 插件是否实现了某个方法（对应 JS 端 typeof plugin[method] === 'function'）。 */
    fun hasMethod(platform: String, method: String): Boolean

    /** 读取插件静态信息，返回 JSON 字符串；插件不存在返回 null。 */
    fun readPluginInfo(platform: String): String?

    /**
     * 调用插件方法。argsJson 为 JSON 数组字符串，数组元素为每个参数（插件侧再各自解析）。
     * 返回方法返回值 JSON 序列化文本。
     * @param timeoutMs 该次调用最长等待时间，超时抛 [PluginCallException]（默认 60s）
     * @throws PluginCallException 调用抛错 / 超时 / 网络异常时
     */
    fun invoke(platform: String, method: String, argsJson: String, timeoutMs: Long = timeoutMillis): String

    /** 释放引擎。 */
    override fun close()

    val timeoutMillis: Long get() = TimeUnit.SECONDS.toMillis(60)
}

class PluginCallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)