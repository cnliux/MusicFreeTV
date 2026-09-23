package com.tvmusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF002010),
    primaryContainer = Color(0xFF0F3D24),
    onPrimaryContainer = Color(0xFFBFF5D1),
    secondary = Color(0xFFB6C8D4),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE4E6EB),
    surface = Color(0xFF14181F),
    onSurface = Color(0xFFE4E6EB),
    surfaceVariant = Color(0xFF1E232C),
    onSurfaceVariant = Color(0xFF9AA3AD),
    error = Color(0xFFEF5A6F),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun MusicFreeTheme(content: @Composable () -> Unit) {
    // TV/盒子固定深色主题：浅色模式下 LightColors 仅定义了 3 个颜色，
    // 会导致整页白底、底部播放栏深色渐变上文字不可见。
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
