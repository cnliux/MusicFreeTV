package com.tvmusic.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * 统一时长格式化：`m:ss`（TV 端惯例，分不补零、秒补零）。
 * 供 Home/Player 等界面共用，替代各处私有的 fmtTime/format 实现。
 */
fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${"%02d".format(s % 60)}"
}

/**
 * 极简 ViewModel 工厂：避免为每个 VM 写伴生工厂。
 */
inline fun <reified VM : ViewModel> tvViewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory {
    return viewModelFactory {
        initializer { create() }
    }
}