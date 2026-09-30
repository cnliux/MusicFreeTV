package com.tvmusic.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    /** null = 用 Material 默认 textStyle（LocalTextStyle），与原手写代码一致。 */
    textStyle: TextStyle? = null
) {
    val shape = RoundedCornerShape(corner.dp)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.tvFocus(shapeOverride = shape),
        placeholder = placeholder?.let { { Text(it, fontSize = placeholderFontSize) } },
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
