package com.tvmusic.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences 句柄。
 *
 * 背景：项目内 4 个设置单例（MetaSettings / IdleSettings / LyricSettings / ThemeManager）
 * 各自持有 `appContext` 并在每个 setter 里重复
 * `appContext?.getSharedPreferences(PREFS, MODE)?.edit()?.putX(key, v)?.apply()`。
 * MetaSettings 一个类就有 13 处这样的样板。本类把它收口为一次 `get`/`put` 调用，
 * 语义完全一致（仍为 apply 异步落盘、空 context 时静默跳过）。
 *
 * 用法：
 * ```
 * private val prefs = PrefsHolder("meta_prefs")
 * fun init(ctx: Context) { prefs.attach(ctx); val b = prefs.bool(KEY_ENABLED, true) }
 * fun setEnabled(on: Boolean) { enabled.set(on); prefs.putBoolean(KEY_ENABLED, on) }
 * ```
 */
class PrefsHolder(private val name: String) {

    private var app: Context? = null

    /** 绑定 Application Context（[Context.getApplicationContext] 去 Activity 引用泄漏）。 */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    /** 解绑（仅在极少数需要释放的测试场景使用）。 */
    fun detach() {
        app = null
    }

    /** 原始 SharedPreferences；未 attach 时返回 null（各 getter 自动回退默认值）。 */
    fun raw(): SharedPreferences? = app?.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ---------- 读（未 attach 或 key 缺失时返回 [default]） ----------

    fun bool(key: String, default: Boolean): Boolean = raw()?.getBoolean(key, default) ?: default

    fun int(key: String, default: Int): Int = raw()?.getInt(key, default) ?: default

    fun long(key: String, default: Long): Long = raw()?.getLong(key, default) ?: default

    fun float(key: String, default: Float): Float = raw()?.getFloat(key, default) ?: default

    fun str(key: String, default: String): String = raw()?.getString(key, default) ?: default

    // ---------- 写（apply 异步落盘；未 attach 时静默跳过） ----------

    fun putBoolean(key: String, value: Boolean) = edit { it.putBoolean(key, value) }

    fun putInt(key: String, value: Int) = edit { it.putInt(key, value) }

    fun putLong(key: String, value: Long) = edit { it.putLong(key, value) }

    fun putFloat(key: String, value: Float) = edit { it.putFloat(key, value) }

    fun putString(key: String, value: String) = edit { it.putString(key, value) }

    fun remove(key: String) = edit { it.remove(key) }

    fun clear() = edit { it.clear() }

    /**
     * 一次性写多项（比多次 [putInt] 等只触发一次磁盘写）。
     * [block] 收到 [SharedPreferences.Editor]，调用方负责 put。
     */
    fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val e = raw()?.edit() ?: return
        block(e)
        e.apply()
    }
}
