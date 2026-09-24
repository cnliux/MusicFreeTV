package com.tvmusic.config

import android.content.Context
import org.json.JSONArray

/**
 * 搜索配置：App 端与 web 管理台共享（同一 App 进程、同一 SharedPreferences）。
 * 后台编辑，两处搜索同时生效。
 */
data class SearchSettings(
    val sourceOrder: List<String> = emptyList(),
    val sortBy: String = SORT_DEFAULT,
    val asc: Boolean = true,
    val maxTotal: Int = 60
) {
    companion object {
        const val SORT_DEFAULT = "default"
        const val SORT_DURATION = "duration"
        const val SORT_TITLE = "title"
        const val SORT_ARTIST = "artist"

        private const val PREFS = "search_config"
        private const val KEY_ORDER = "sourceOrder"
        private const val KEY_SORT = "sortBy"
        private const val KEY_ASC = "asc"
        private const val KEY_MAX = "maxTotal"

        fun load(context: Context): SearchSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val order = runCatching {
                val a = JSONArray(p.getString(KEY_ORDER, "[]") ?: "[]")
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            return SearchSettings(
                sourceOrder = order,
                sortBy = p.getString(KEY_SORT, SORT_DEFAULT) ?: SORT_DEFAULT,
                asc = p.getBoolean(KEY_ASC, true),
                maxTotal = p.getInt(KEY_MAX, 60).coerceIn(20, 200)
            )
        }

        fun save(context: Context, s: SearchSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ORDER, JSONArray().apply { s.sourceOrder.forEach { put(it) } }.toString())
                .putString(KEY_SORT, s.sortBy)
                .putBoolean(KEY_ASC, s.asc)
                .putInt(KEY_MAX, s.maxTotal.coerceIn(20, 200))
                .apply()
        }

        /**
         * 把待搜索的 sources 按配置优先级排列；config 里没提到的保持在末尾原次序。
         */
        fun ordered(sources: List<String>, order: List<String>): List<String> {
            if (order.isEmpty()) return sources
            val index = order.withIndex().associate { it.value to it.index }
            return sources.sortedBy { index[it] ?: (order.size + 1) }
        }

        /**
         * 结果排序比较器。sortBy=default 或为空时返回稳定序（不改变原有顺序）。
         */
        fun <T> comparator(
            sortBy: String,
            asc: Boolean,
            title: (T) -> String,
            artist: (T) -> String,
            duration: (T) -> Long
        ): Comparator<T> {
            val sign = if (asc) 1 else -1
            return when (sortBy) {
                SORT_DURATION -> Comparator { a, b ->
                    val d = duration(a).compareTo(duration(b))
                    if (d != 0) d * sign else title(a).compareTo(title(b))
                }
                SORT_TITLE -> comparatorForStrings(asc) { title(it) }
                SORT_ARTIST -> Comparator { a, b ->
                    val ar = artist(a).compareTo(artist(b))
                    if (ar != 0) ar * sign else title(a).compareTo(title(b))
                }
                else -> Comparator { _, _ -> 0 }
            }
        }

        private fun <T> comparatorForStrings(asc: Boolean, key: (T) -> String): Comparator<T> =
            if (asc) Comparator { a, b -> key(a).compareTo(key(b)) }
            else Comparator { a, b -> key(b).compareTo(key(a)) }
    }
}