package com.tvmusic.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 提供者（M1）。
 *
 * 原实现：QuickJsEngine 每台引擎一个、PlayerManager 两个、PluginRepository /
 * RemoteConfigService 各一个 —— 10+ 个独立客户端，每个自带连接池 + Dispatcher
 * 线程池 + DNS 缓存，TV 端内存与线程数被无谓放大。
 *
 * 现方案：按超时档位提供 3 个共享单例；需要不同行为（如图片代理禁自动跟随重定向）的
 * 一律用 `newBuilder()` 从共享客户端派生 —— OkHttp 的 newBuilder 会复用同一个
 * ConnectionPool 与 Dispatcher 线程池，只有配置项是新的。
 */
object HttpClients {

    /**
     * 标准档：JS 插件网络请求、插件订阅下载等常规用途。
     *
     * readTimeout 15s → 10s（M8 替代方案）：read 超时约束的是「两次收字节之间的停滞」，
     * 收紧后"连上了但不吐数据"的死源能提前 5s 释放所在引擎 lane（插件 httpRequest 在
     * JS 线程同步阻塞，lane 被占期间该平台后续调用只能排队或快速抛 busy）。
     *
     * callTimeout 保持 60s 不动：它与 primary.timeoutMillis = 60s 对齐，
     * 聚合搜索路径允许单次调用最长 60s；调低会让 OkHttp 提前掐断应用层仍允许的慢请求。
     * 即"慢但在持续吐数据的源"仍可跑满预算，只有彻底停滞的源被更早放弃。
     */
    val standard: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 宽松档：歌词/封面兜底、更新下载等允许较慢响应的用途。 */
    val relaxed: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 严格档：封面等要求快速失败的小资源。 */
    val strict: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 从指定共享客户端派生一个只改配置、复用连接池/线程池的新客户端。
     * @param base 基准客户端（默认 standard）
     */
    fun derive(base: OkHttpClient = standard): OkHttpClient.Builder = base.newBuilder()
}
