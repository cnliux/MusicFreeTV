package com.tvmusic.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSONObject 扩展工具。
 *
 * 背景：插件返回的 JSON 字段名高度不统一（QQ音乐 pic / 163 cover / 各家 artwork_url…），
 * 全项目原先有 11 处近乎逐字复制的「按顺序取第一个非空字段」回退链，本文件将其收口为
 * [firstStr] / [firstInt] / [firstLong] / [firstBool] / [firstArray] / [firstObj]。
 */

/**
 * 按给定顺序取第一个「存在且非空字符串」的字段值；全部缺失/为空白时返回 ""。
 *
 * @param keys 候选字段名，按优先级排列。
 */
fun JSONObject.firstStr(vararg keys: String): String {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = optString(k, "")
        if (v.isNotBlank()) return v
    }
    return ""
}

/** 按 [firstStr] 的候选顺序取第一个可解析为 Int 的字段值；全部失败返回 [fallback]。 */
fun JSONObject.firstInt(vararg keys: String, fallback: Int = 0): Int {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = optInt(k, Int.MIN_VALUE)
        if (v != Int.MIN_VALUE) return v
    }
    return fallback
}

/** 按 [firstStr] 的候选顺序取第一个可解析为 Long 的字段值；全部失败返回 [fallback]。 */
fun JSONObject.firstLong(vararg keys: String, fallback: Long = 0L): Long {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = optLong(k, Long.MIN_VALUE)
        if (v != Long.MIN_VALUE) return v
    }
    return fallback
}

/** 按 [firstStr] 的候选顺序取第一个可解析为 Boolean 的字段值；全部失败返回 [fallback]。 */
fun JSONObject.firstBool(vararg keys: String, fallback: Boolean = false): Boolean {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val raw = opt(k)
        when (raw) {
            is Boolean -> return raw
            is Number -> return raw.toInt() != 0
            is String -> if (raw.equals("true", true)) return true
                else if (raw.equals("false", true)) return false
        }
    }
    return fallback
}

/** 按 [firstStr] 的候选顺序取第一个非空 JSONArray；全部缺失返回 null。 */
fun JSONObject.firstArray(vararg keys: String): JSONArray? {
    for (k in keys) {
        val a = optJSONArray(k) ?: continue
        if (a.length() > 0) return a
    }
    return null
}

/** 按 [firstStr] 的候选顺序取第一个非空 JSONObject；全部缺失返回 null。 */
fun JSONObject.firstObj(vararg keys: String): JSONObject? {
    for (k in keys) {
        val o = optJSONObject(k) ?: continue
        if (o.length() > 0) return o
    }
    return null
}

/**
 * 补全 platform 字段（收藏/历史回放需要来源插件名，插件返回的裸条目通常不带）。
 * 已有非空 platform 或 plugin 为空白时原样返回，避免多余拷贝。
 */
fun JSONObject.withPlatform(plugin: String): JSONObject =
    if (optString("platform").isNotBlank() || plugin.isBlank()) this
    else JSONObject(toString()).put("platform", plugin)

/**
 * 宽松解析 JSON 对象文本：非法/空文本返回 null（替代各处 runCatching + ?: JSONObject() 样板）。
 */
fun parseJsonObjectOrNull(text: String?): JSONObject? =
    if (text.isNullOrBlank()) null else runCatching { JSONObject(text) }.getOrNull()
