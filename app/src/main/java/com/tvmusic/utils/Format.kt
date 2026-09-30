package com.tvmusic.utils

/**
 * 展示层格式化工具。
 */

/**
 * 统一时长格式化：`h:mm:ss`（超过 1 小时才带小时段）/ `m:ss`，秒补零。
 * 首页迷你条、播放页、远程管理播放页共用一份，避免各处时间轴对不上。
 */
fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:${"%02d".format(m)}:${"%02d".format(s)}" else "$m:${"%02d".format(s)}"
}
