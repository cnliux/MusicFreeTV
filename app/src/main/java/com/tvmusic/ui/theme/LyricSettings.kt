package com.tvmusic.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放页歌词显示配置：字号/颜色。 */
data class LyricConfig(
    val fontSizeSp: Int = 16,
    val colorHex: String = "FFFFFF"
)

object LyricSettings {

    private const val PREFS = "lyric_prefs"
    private const val KEY_SIZE = "size"
    private const val KEY_COLOR = "color"

    private val _config = MutableStateFlow(LyricConfig())
    val config: StateFlow<LyricConfig> = _config.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = prefs() ?: return
        _config.value = LyricConfig(
            fontSizeSp = p.getInt(KEY_SIZE, 16),
            colorHex = p.getString(KEY_COLOR, "FFFFFF") ?: "FFFFFF"
        )
    }

    fun update(cfg: LyricConfig) {
        _config.value = cfg
        prefs()?.edit()
            ?.putInt(KEY_SIZE, cfg.fontSizeSp)
            ?.putString(KEY_COLOR, cfg.colorHex)
            ?.apply()
    }

    fun parseColor(): Color = runCatching {
        Color(android.graphics.Color.parseColor("#" + _config.value.colorHex))
    }.getOrDefault(Color.White)

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
