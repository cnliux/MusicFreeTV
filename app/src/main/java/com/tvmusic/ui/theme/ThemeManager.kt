package com.tvmusic.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题行为 Token：圆角 / 焦点缩放 / 焦点边框 / 发光强度 / 聚焦模式。
 * 与 ColorScheme 一起构成完整主题，切换时首页与播放页同步生效。
 */
data class ThemeTokens(
    /** 卡片/控件圆角 */
    val radius: Dp = 12.dp,
    /** D-pad 聚焦缩放倍数（focusBrightnessOnly=true 时忽略） */
    val focusScale: Float = 1.08f,
    /** 焦点边框颜色 */
    val focusBorder: Color = Color.White,
    /** 焦点阴影发光强度（dp），0=不发光 */
    val focusGlow: Dp = 12.dp,
    /** 杂志模式：聚焦用亮度提升而非缩放 */
    val focusBrightnessOnly: Boolean = false,
    /** 卡片是否半透明（玻璃拟态） */
    val translucentCard: Boolean = false
)

/** 主题预设：TV 端 ColorScheme+Tokens + 远程页面 CSS 变量共用一份定义。 */
data class AppTheme(
    val id: String,
    val name: String,
    /** 远程页面强调色（hex，不带 #） */
    val accent: String,
    /** 远程页面次强调色 */
    val accent2: String,
    /** 远程页面背景色 */
    val bg: String,
    /** 远程页面卡片色 */
    val card: String,
    /** 远程页面圆角 px */
    val radiusPx: Int,
    val tokens: ThemeTokens,
    val scheme: ColorScheme
)

object ThemeManager {

    private const val PREFS = "theme_prefs"
    private const val KEY = "theme_id"

    val themes: List<AppTheme> = listOf(
        AppTheme(
            // 1. 深海蓝：深蓝黑底 + #4A7DFF，圆角 12，焦点 1.08x
            id = "blue", name = "深海蓝", accent = "4A7DFF", accent2 = "7AA5FF",
            bg = "0B0E14", card = "1A1D24", radiusPx = 12,
            tokens = ThemeTokens(radius = 12.dp, focusScale = 1.08f, focusBorder = Color.White, focusGlow = 12.dp),
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
            // 2. 暖琥珀：深棕底 + 降饱和橙 #FFA726，圆角 16，焦点 1.06x
            id = "amber", name = "暖琥珀", accent = "FFA726", accent2 = "FFCC80",
            bg = "14100B", card = "211A12", radiusPx = 16,
            tokens = ThemeTokens(radius = 16.dp, focusScale = 1.06f, focusBorder = Color(0xFFFFE0B2), focusGlow = 10.dp),
            scheme = darkColorScheme(
                primary = Color(0xFFFFA726), onPrimary = Color(0xFF3A2200),
                primaryContainer = Color(0xFF5C3A10), onPrimaryContainer = Color(0xFFFFE0B2),
                secondary = Color(0xFFE8C9A0), onSecondary = Color(0xFF3A2A18),
                background = Color(0xFF14100B), onBackground = Color(0xFFF2EAE0),
                surface = Color(0xFF211A12), onSurface = Color(0xFFF2EAE0),
                surfaceVariant = Color(0xFF2E251A), onSurfaceVariant = Color(0xFFB8A893),
                error = Color(0xFFFF6B5A), onError = Color(0xFFFFFFFF)
            )
        ),
        AppTheme(
            // 3. 极简黑白：纯黑底 + 白色主色，小圆角 4，焦点 1.04x
            id = "mono", name = "极简黑白", accent = "F5F5F5", accent2 = "9E9E9E",
            bg = "000000", card = "121212", radiusPx = 4,
            tokens = ThemeTokens(radius = 4.dp, focusScale = 1.04f, focusBorder = Color.White, focusGlow = 0.dp),
            scheme = darkColorScheme(
                primary = Color(0xFFF5F5F5), onPrimary = Color(0xFF000000),
                primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color(0xFFEEEEEE),
                secondary = Color(0xFF9E9E9E), onSecondary = Color(0xFF000000),
                background = Color(0xFF000000), onBackground = Color(0xFFF5F5F5),
                surface = Color(0xFF121212), onSurface = Color(0xFFF5F5F5),
                surfaceVariant = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFF9E9E9E),
                error = Color(0xFFEF9A9A), onError = Color(0xFF000000)
            )
        ),
        AppTheme(
            // 4. 玻璃拟态：半透明卡片 + 大圆角，焦点白边发光
            id = "glass", name = "玻璃拟态", accent = "8AB4F8", accent2 = "C3E8FF",
            bg = "0A101C", card = "16243A", radiusPx = 20,
            tokens = ThemeTokens(radius = 20.dp, focusScale = 1.06f, focusBorder = Color.White, focusGlow = 22.dp, translucentCard = true),
            scheme = darkColorScheme(
                primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0A1A33),
                primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFD6E6FF),
                secondary = Color(0xFFC3E8FF), onSecondary = Color(0xFF00344A),
                background = Color(0xFF0A101C), onBackground = Color(0xFFE8EEF7),
                surface = Color(0xE61B2A40), onSurface = Color(0xFFE8EEF7),
                surfaceVariant = Color(0xCC243A57), onSurfaceVariant = Color(0xFF93A7C4),
                error = Color(0xFFFF8A80), onError = Color(0xFF300)
            )
        ),
        AppTheme(
            // 5. 霓虹赛博：黑紫底 + 青/品红发光边框，焦点强阴影
            id = "neon", name = "霓虹赛博", accent = "00FFC8", accent2 = "FF2E97",
            bg = "0A0714", card = "170F2A", radiusPx = 8,
            tokens = ThemeTokens(radius = 8.dp, focusScale = 1.08f, focusBorder = Color(0xFF00FFC8), focusGlow = 28.dp),
            scheme = darkColorScheme(
                primary = Color(0xFF00FFC8), onPrimary = Color(0xFF00332A),
                primaryContainer = Color(0xFF0C4A4A), onPrimaryContainer = Color(0xFFB8FFE9),
                secondary = Color(0xFFFF2E97), onSecondary = Color(0xFF3D0020),
                background = Color(0xFF0A0714), onBackground = Color(0xFFEDE6FF),
                surface = Color(0xFF170F2A), onSurface = Color(0xFFEDE6FF),
                surfaceVariant = Color(0xFF241640), onSurfaceVariant = Color(0xFF9F8FC4),
                error = Color(0xFFFF2E97), onError = Color(0xFFFFFFFF)
            )
        ),
        AppTheme(
            // 6. 杂志排版：近黑底 + 暖白主色，直角细分割，焦点仅亮度变化
            id = "magazine", name = "杂志排版", accent = "E8E4DC", accent2 = "B0AAA0",
            bg = "101010", card = "1A1A1A", radiusPx = 2,
            tokens = ThemeTokens(radius = 2.dp, focusScale = 1f, focusBorder = Color(0xFFE8E4DC), focusGlow = 0.dp, focusBrightnessOnly = true),
            scheme = darkColorScheme(
                primary = Color(0xFFE8E4DC), onPrimary = Color(0xFF1A1A1A),
                primaryContainer = Color(0xFF333333), onPrimaryContainer = Color(0xFFF5F2EC),
                secondary = Color(0xFFB0AAA0), onSecondary = Color(0xFF1A1A1A),
                background = Color(0xFF101010), onBackground = Color(0xFFF2EFE9),
                surface = Color(0xFF1A1A1A), onSurface = Color(0xFFF2EFE9),
                surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFF8F8A82),
                error = Color(0xFFE57373), onError = Color(0xFF1A1A1A)
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
