package com.tvmusic.model

import org.json.JSONObject

/**
 * 插件元信息（来自插件 JS 的静态字段）。
 */
data class PluginInfo(
    val platform: String,
    val version: String? = null,
    val author: String? = null,
    val srcUrl: String? = null,
    val appVersion: String? = null,
    val description: String? = null,
    val cacheControl: String? = null,
    val primaryKey: List<String> = emptyList(),
    val supportedSearchType: List<String> = emptyList(),
    val userVariables: List<UserVarDef> = emptyList(),
    val hints: JSONObject? = null
)

/** 插件声明的用户可配变量（cookie/token 等）。 */
data class UserVarDef(
    val key: String,
    val name: String,
    val type: String? = null
)

/**
 * 已安装插件记录（持久化）。
 *
 * [hash] 是插件源码的指纹（sha1 前 12 位），作为插件的唯一 id：
 * 同一份源码无论从哪个订阅源、以什么名字出现，都只会被安装一次，
 * 避免重复注册进 JS 引擎导致 platform 互相覆盖。
 */
data class PluginRecord(
    val name: String,
    val url: String?,
    val version: String?,
    val enabled: Boolean = true,
    val installedAt: Long,
    val source: String? = null,
    val info: PluginInfo? = null,
    val loadError: String? = null,
    val hash: String = ""
) {
    /** 引擎内唯一标识：platform + hash，用于日志与去重展示。 */
    val id: String get() = "${info?.platform ?: name}#$hash"

    /** 便捷访问：插件在 JS 引擎中的 platform 名（加载失败时回退记录名）。 */
    val platform: String get() = info?.platform ?: name
}

/** 订阅源记录。 */
data class SubscriptionRecord(
    val url: String,
    val addedAt: Long
)

/** 用户填写的插件变量值。 */
data class UserVariable(
    val pluginKey: String,
    val varKey: String,
    val varValue: String
)
