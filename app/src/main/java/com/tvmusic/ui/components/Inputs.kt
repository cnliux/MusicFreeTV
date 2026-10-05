package com.tvmusic.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV 端文本输入框。
 *
 * 背景：SearchScreen（搜索框）、SettingsScreen（订阅 URL / 插件 URL / 用户变量 共 3 处）、
 * Components（新建收藏夹）共 5 处手写 OutlinedTextField，各自带
 * `shape = RoundedCornerShape(10/8.dp)` + `tvFocus(shapeOverride = 同圆角)` +
 * 主色边框/光标。两处圆角不匹配会直接导致焦点框与输入框描边错位，
 * 因此圆角与焦点形状统一由本组件内部派生，调用方无需（也无法）再传。
 *
 * @param imeEnabled 是否允许拉起系统软键盘。搜索页自带 [TvKeyboard] 26 键字母盘，
 *   必须传 false：系统输入法（真机 192.168.1.45 是搜狗）会吃掉半个屏幕、
 *   抢走方向键（真机 192.168.1.37 历史坑），且用户在盘面上已经能输字母。
 *   实现用 readOnly（系统对只读框不弹软键盘）+ 聚焦/按键时主动 hide() 双保险。
 * @param placeholderColor placeholder 颜色，null = 跟随 Material 默认。
 *   搜索页用它做"淡色提示"（`onSurfaceVariant.copy(alpha=0.55f)`），提示文字要明显弱于真实输入。
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    singleLine: Boolean = true,
    /** 圆角同时用于输入框描边与焦点框，调用方只调这一个值即可保持一致。 */
    corner: Int = 10,
    /** 仅作用于 placeholder 字号（各调用点本来就各不相同）。 */
    placeholderFontSize: TextUnit = 15.sp,
    placeholderColor: Color? = null,
    /** false = 彻底禁用系统软键盘（页面自带按键盘时用）。 */
    imeEnabled: Boolean = true,
    /** null = 用 Material 默认 textStyle（LocalTextStyle），与原手写代码一致。 */
    textStyle: TextStyle? = null
) {
    val shape = RoundedCornerShape(corner.dp)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // 只读 = 系统不弹软键盘；输入改由调用方的按键盘驱动（value 仍由外部 StateFlow 驱动）
        readOnly = !imeEnabled,
        modifier = modifier
            // 真机坑（2026-10-05）：输入法激活时方向下键被 IME/文本框消费（光标移动），
            // 焦点永远出不去输入框=遥控器死区。TV 上单行框的光标移动只需要左右键，
            // 下键在预览链直接改走焦点导航（上键原生就能跳出，无需拦截）。
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                } else {
                    // 禁用 IME 的页面：万一有残留软键盘（从别的页面带过来的）立刻收掉，
                    // 否则半屏被占、方向键被 IME 吃掉。
                    if (!imeEnabled) keyboard?.hide()
                    false
                }
            }
            // 禁用 IME 的页面拿到焦点时兜底收键盘（readOnly 已让系统不弹，这里只防残留）
            .onFocusChanged { if (it.isFocused && !imeEnabled) keyboard?.hide() }
            .tvFocus(shapeOverride = shape),
        placeholder = placeholder?.let {
            { Text(it, fontSize = placeholderFontSize, color = placeholderColor ?: Color.Unspecified) }
        },
        label = label?.let { { Text(it) } },
        singleLine = singleLine,
        shape = shape,
        textStyle = textStyle ?: LocalTextStyle.current,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}
