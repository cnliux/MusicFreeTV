package com.tvmusic.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 歌词显示配置：开关/字体大小/颜色/位置（顶部/居中/底部）/垂直微调偏移(dp)。 */
data class LyricConfig(
    val enabled: Boolean = false,
    val fontSizeSp: Int = 16,
    val colorHex: String = "FFFFFF",
    val position: LyricPosition = LyricPosition.CENTER,
    val offsetY: Int = 0,
    val opacity: Float = 1.0f
)

enum class LyricPosition { TOP, CENTER, BOTTOM }

object LyricSettings {

    private const val PREFS = "lyric_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SIZE = "size"
    private const val KEY_COLOR = "color"
    private const val KEY_POS = "position"
    private const val KEY_OFFSET_Y = "offset_y"
    private const val KEY_OPACITY = "opacity"

    private val _config = MutableStateFlow(LyricConfig())
    val config: StateFlow<LyricConfig> = _config.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = prefs() ?: return
        _config.value = LyricConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            fontSizeSp = p.getInt(KEY_SIZE, 16),
            colorHex = p.getString(KEY_COLOR, "FFFFFF") ?: "FFFFFF",
            position = try { LyricPosition.valueOf(p.getString(KEY_POS, "CENTER") ?: "CENTER") }
            catch (_: Exception) { LyricPosition.CENTER },
            offsetY = p.getInt(KEY_OFFSET_Y, 0),
            opacity = p.getInt(KEY_OPACITY, 100) / 100f
        )
    }

    fun update(cfg: LyricConfig) {
        _config.value = cfg
        prefs()?.edit()
            ?.putBoolean(KEY_ENABLED, cfg.enabled)
            ?.putInt(KEY_SIZE, cfg.fontSizeSp)
            ?.putString(KEY_COLOR, cfg.colorHex)
            ?.putString(KEY_POS, cfg.position.name)
            ?.putInt(KEY_OFFSET_Y, cfg.offsetY)
            ?.putInt(KEY_OPACITY, (cfg.opacity * 100).roundToInt())
            ?.apply()
    }

    fun parseColor(): Color = runCatching {
        Color(android.graphics.Color.parseColor("#" + _config.value.colorHex))
    }.getOrDefault(Color.White)

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
