package com.tvmusic.config

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「无操作自动进入播放器页」设置（电视待机显示，默认开启、1 分钟）。
 * App 端计时与 web 管理台共享同一 SharedPreferences；后台改动即时生效
 * （MainActivity 每次重置计时时重新读取）。
 */
object IdleSettings {

    private const val PREFS = "idle_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MINUTES = "minutes"

    /** 分钟数允许范围：最短 15 秒场景用 1 分钟档也无妨，但输入框限制 1-60。 */
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60

    private val enabled = AtomicBoolean(true)
    private val minutes = AtomicInteger(1)
    private var appContext: Context? = null

    val isEnabled: Boolean get() = enabled.get()

    /** 当前生效的间隔毫秒数。 */
    val intervalMs: Long get() = minutes.get().coerceIn(MIN_MINUTES, MAX_MINUTES) * 60_000L

    val currentMinutes: Int get() = minutes.get()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled.set(p?.getBoolean(KEY_ENABLED, true) ?: true)
        minutes.set(p?.getInt(KEY_MINUTES, 1)?.coerceIn(MIN_MINUTES, MAX_MINUTES) ?: 1)
    }

    fun setEnabled(on: Boolean) {
        enabled.set(on)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    fun setMinutes(m: Int) {
        val v = m.coerceIn(MIN_MINUTES, MAX_MINUTES)
        minutes.set(v)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_MINUTES, v)?.apply()
    }
}
