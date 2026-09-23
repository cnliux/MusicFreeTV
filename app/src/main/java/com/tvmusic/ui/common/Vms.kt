package com.tvmusic.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * 极简 ViewModel 工厂：避免为每个 VM 写伴生工厂。
 */
inline fun <reified VM : ViewModel> tvViewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory {
    return viewModelFactory {
        initializer { create() }
    }
}