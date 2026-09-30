package com.tvmusic.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * 统一时长格式化：`m:ss`。
 * 实现已迁至 [com.tvmusic.utils.fmtDuration]（与其它格式化工具同包），
 * 此处保留别名以兼容既有 `com.tvmusic.ui.common.fmtDuration` 调用。
 */
@Deprecated("已迁移到 com.tvmusic.utils.fmtDuration", ReplaceWith("com.tvmusic.utils.fmtDuration(ms)"))
fun fmtDuration(ms: Long): String = com.tvmusic.utils.fmtDuration(ms)

/**
 * 极简 ViewModel 工厂：避免为每个 VM 写伴生工厂。
 */
inline fun <reified VM : ViewModel> tvViewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory {
    return viewModelFactory {
        initializer { create() }
    }
}