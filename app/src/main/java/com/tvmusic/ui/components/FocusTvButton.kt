@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.tvmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults

/**
 * TV 焦点美化按钮（androidx.tv.material3.Button，1.0.x 稳定版）。
 *
 * 四态表现：
 * - 默认：深色底、轻投影
 * - 聚焦：放大 [focusedScale]（默认 1.12 倍）+ 3dp 高亮描边 + 投影加深且带主色光晕
 * - 按下：缩小到 [pressedScale]（默认 0.95 倍）
 * - 禁用：灰化、不缩放、无描边、不可聚焦
 *
 * @param autoFocus        页面进入时自动吸焦（FocusRequester）
 * @param lockFocusOnEdges 方向锁焦：焦点到达按钮边缘后不再向外溢出（四向 Cancel）
 */
@Composable
fun FocusTvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    lockFocusOnEdges: Boolean = false,
    focusedScale: Float = 1.12f,
    pressedScale: Float = 0.95f,
    accent: Color = Color(0xFF3D7BFF)
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isFocused by remember { mutableStateOf(false) }

    val btnShape = RoundedCornerShape(14.dp)

    // 目标缩放：禁用优先（不放大）> 按下 > 聚焦 > 默认 1f
    val targetScale = when {
        !enabled -> 1f
        isPressed -> pressedScale
        isFocused -> focusedScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(150),
        label = "focusBtnScale"
    )

    // 聚焦时投影加深，并带主色光晕（Modifier.shadow 提供 shape 轮廓投影）
    val shadowElev by animateDpAsState(
        targetValue = if (isFocused && enabled) 18.dp else 6.dp,
        animationSpec = tween(150),
        label = "focusBtnShadow"
    )

    // 3dp 焦点高亮边框：非聚焦态透明（border 不影响布局）
    val borderColor by animateColorAsState(
        targetValue = if (isFocused && enabled) Color(0xFF82B1FF) else Color.Transparent,
        animationSpec = tween(150),
        label = "focusBtnBorder"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFF8A8F99)   // 禁用灰
            isFocused -> Color(0xFF0B0E14)  // 聚焦：亮底配深字
            else -> Color(0xFFEDEFF4)       // 默认浅字
        },
        animationSpec = tween(150),
        label = "focusBtnContent"
    )

    // 进入页面自动吸焦
    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .shadow(
                elevation = shadowElev,
                shape = btnShape,
                clip = false,
                ambientColor = Color(0x66000000),
                spotColor = if (isFocused) accent.copy(alpha = 0.6f) else Color(0x33000000)
            )
            .graphicsLayer {
                // graphicsLayer 接管缩放动画（投影由上面的 shadow 承担）
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .focusProperties {
                // 方向锁焦：焦点在本按钮边缘时四向都取消外移，不溢出
                if (lockFocusOnEdges) {
                    exit = { _: FocusDirection -> FocusRequester.Cancel }
                }
            }
            .onFocusChanged { isFocused = it.isFocused }
            .border(width = 3.dp, color = borderColor, shape = btnShape),
        enabled = enabled,
        // 内部缩放全部置 1，统一由上面的 graphicsLayer 控制，避免双重缩放；
        // ButtonScale 是 1.0.x 公开的缩放配置入口（ButtonScale 构造器为 internal）
        scale = ButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f,
            disabledScale = 1f,
            focusedDisabledScale = 1f
        ),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF232838),
            contentColor = contentColor,
            focusedContainerColor = accent,
            focusedContentColor = contentColor,
            disabledContainerColor = Color(0x339E9E9E),
            disabledContentColor = contentColor
        ),
        shape = ButtonDefaults.shape(btnShape),
        interactionSource = interactionSource
    ) {
        Text(text = text, fontSize = 16.sp, color = contentColor)
    }
}
