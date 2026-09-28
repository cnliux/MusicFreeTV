package com.tvmusic.net

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 统一 ImageLoader（M7）：内存缓存上限 + 磁盘缓存 + 复用共享 OkHttpClient。
 * 原实现各处 AsyncImage 用 Coil 默认 ImageLoader，无内存上限，配合缺失的
 * onTrimMemory 会导致图片内存不可控（TV 内存紧张时后台被 LMK 杀）。
 */
object AppImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15) // 应用可用内存的 15%（TV 内存紧张，保守）
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB 磁盘缓存
                    .build()
            }
            // 复用共享 OkHttpClient（M1 已统一），复用连接池/线程池
            .okHttpClient { com.tvmusic.net.HttpClients.standard }
            .build()
    }

    /** 清空内存缓存（onTrimMemory 时调用）。 */
    fun trimMemory(context: Context) {
        instance?.memoryCache?.clear()
    }
}
