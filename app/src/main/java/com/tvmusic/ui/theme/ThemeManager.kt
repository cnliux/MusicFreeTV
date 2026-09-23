package com.tvmusic.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题预设：TV 端 ColorScheme + 远程页面 CSS 变量共用一份定义。 */
data class AppTheme(
    val id: String,
    val name: String,
    /** 远程页面强调色（hex，不带 #） */
    val accent: String,
    /** 远程页面背景色 */
    val bg: String,
    val scheme: ColorScheme
)

object ThemeManager {

    private const val PREFS = "theme_prefs"
    private const val KEY = "theme_id"

    val themes: List<AppTheme> = listOf(
        AppTheme(
            // 默认主题：TV 友好的深蓝系（低饱和主色 + 粉紫点缀），背景近纯黑
            id = "blue", name = "深海蓝", accent = "4A7DFF", bg = "0B0E14",
            scheme = darkColorScheme(
                primary = Color(0xFF4A7DFF), onPrimary = Color(0xFF06122E),
                primaryContainer = Color(0xFF182A4D), onPrimaryContainer = Color(0xFFC9D9FF),
                secondary = Color(0xFFFF6B9D), onSecondary = Color(0xFF3D0018),
                background = Color(0xFF0B0E14), onBackground = Color(0xFFF0F2F5),
                surface = Color(0xFF1A1D24), onSurface = Color(0xFFF0F2F5),
                surfaceVariant = Color(0xFF252A33), onSurfaceVariant = Color(0xFF8B919C),
                error = Color(0xFFFF6B6B), onError = Color(0xFFFFFFFF)
            )
        ),
        AppTheme(
            id = "green", name = "森林绿", accent = "34D399", bg = "0A0F0D",
            scheme = darkColorScheme(
                primary = Color(0xFF34D399), onPrimary = Color(0xFF00281A),
                primaryContainer = Color(0xFF0F3D2C), onPrimaryContainer = Color(0xFFBFF5DF),
                secondary = Color(0xFFFFB46B), onSecondary = Color(0xFF3A2000),
                background = Color(0xFF0A0F0D), onBackground = Color(0xFFE7EFEA),
                surface = Color(0xFF161D1A), onSurface = Color(0xFFE7EFEA),
                surfaceVariant = Color(0xFF212B27), onSurfaceVariant = Color(0xFF93A39B),
                error = Color(0xFFEF5A6F), onError = Color(0xFFFFFFFF)
            )
        ),
        AppTheme(
            id = "purple", name = "暮光紫", accent = "B388FF", bg = "0D0A16",
            scheme = darkColorScheme(
                primary = Color(0xFFB388FF), onPrimary = Color(0xFF1E0033),
                primaryContainer = Color(0xFF3A2260), onPrimaryContainer = Color(0xFFE6D9FF),
                secondary = Color(0xFF69D2E7),
                background = Color(0xFF0D0A16), onBackground = Color(0xFFEAE6F2),
                surface = Color(0xFF181322), onSurface = Color(0xFFEAE6F2),
                surfaceVariant = Color(0xFF241D31), onSurfaceVariant = Color(0xFFA79BBC),
                error = Color(0xFFFF6B81), onError = Color(0xFFFFFFFF)
            )
        ),
        AppTheme(
            id = "amber", name = "落日橙", accent = "FFB74D", bg = "120E09",
            scheme = darkColorScheme(
                primary = Color(0xFFFFB74D), onPrimary = Color(0xFF3A2200),
                primaryContainer = Color(0xFF5C3A10), onPrimaryContainer = Color(0xFFFFE0B2),
                secondary = Color(0xFFE8C9A0),
                background = Color(0xFF120E09), onBackground = Color(0xFFF2EAE0),
                surface = Color(0xFF1E1812), onSurface = Color(0xFFF2EAE0),
                surfaceVariant = Color(0xFF2A221A), onSurfaceVariant = Color(0xFFB8A893),
                error = Color(0xFFFF6B5A), onError = Color(0xFFFFFFFF)
            )
        ),
        AppTheme(
            id = "rose", name = "玫瑰红", accent = "FF7096", bg = "120A0E",
            scheme = darkColorScheme(
                primary = Color(0xFFFF7096), onPrimary = Color(0xFF3D0016),
                primaryContainer = Color(0xFF5C1A30), onPrimaryContainer = Color(0xFFFFD6E2),
                secondary = Color(0xFFE8B0C0),
                background = Color(0xFF120A0E), onBackground = Color(0xFFF2E6EA),
                surface = Color(0xFF1E1418), onSurface = Color(0xFFF2E6EA),
                surfaceVariant = Color(0xFF2A1D23), onSurfaceVariant = Color(0xFFB89AA4),
                error = Color(0xFFFF6B6B), onError = Color(0xFFFFFFFF)
            )
        )
    )

    private val _current = MutableStateFlow(themes[0])
    val current: StateFlow<AppTheme> = _current.asStateFlow()

    private var appContext: Context? = null

    /** 在 Application.onCreate 调用，恢复上次选择的主题。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        val id = prefs()?.getString(KEY, null)
        themes.firstOrNull { it.id == id }?.let { _current.value = it }
    }

    /** 按 id 切换主题并持久化；返回是否成功。 */
    fun set(id: String): Boolean {
        val t = themes.firstOrNull { it.id == id } ?: return false
        _current.value = t
        prefs()?.edit()?.putString(KEY, id)?.apply()
        return true
    }

    fun currentId(): String = _current.value.id

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
