package com.tvmusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun MusicFreeTheme(content: @Composable () -> Unit) {
    // 主题由 ThemeManager 统一持有（SharedPreferences 持久化，远程端也可切换），
    // 这里订阅其 StateFlow：切换主题时整棵 Compose 树用新 ColorScheme 重组。
    val theme by ThemeManager.current.collectAsState()
    MaterialTheme(
        colorScheme = theme.scheme,
        content = content
    )
}
