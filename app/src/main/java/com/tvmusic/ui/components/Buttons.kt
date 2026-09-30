package com.tvmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

/**
 * 按钮族：全 App 按钮统一底座（玻璃胶囊 GlassButton）及其语义包装。
 *
 * 设计约定（见 AGENTS.md「按钮统一 GlassButton」）：
 *  修饰符链顺序红线 = drawBehind(玻璃底) → border → drawWithContent(扫光) → tvFocus
 *  → onFocusChanged → clickable → padding(contentPadding)。
 *  tvFocus 不能放在玻璃绘制之前（会被玻璃底盖住焦点边框）；
 *  contentPadding 必须以 .padding() 挂在链尾（clickable 之后），否则内边距
 *  不属于可点/可聚焦范围，胶囊会比设计小一圈。
 */

/**
 * T 方案玻璃胶囊按钮：磨砂玻璃感 + 胶囊圆角 + 顶部高光缝 + 聚焦扫光。
 * Compose 无 CSS backdrop-filter（实时背景取景），玻璃底用半透明白斜向渐变模拟——
 * 播放页背景本就是 blur(40dp) 封面，胶囊透出底层光斑即磨砂观感；普通深色页面
 * 上呈现为细腻雾面胶囊。聚焦视觉沿用 tvFocus（主色描边+发光+缩放），玻璃同步
 * 增亮；聚焦瞬间一道高光从左扫到右（T 方案签名效果，对齐设计稿 tv-buttons.html）。
 * highlight=true 常亮（页签选中态等非焦点高亮场景）。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    focusScale: Float = 1.04f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused || highlight
    // 扫光：聚焦触发一次；t 为高光条左缘归一位置（-0.6 入左界 → 1.2 出右界）
    val shine = remember { Animatable(-0.6f) }
    LaunchedEffect(focused) {
        if (focused) {
            shine.snapTo(-0.6f)
            shine.animateTo(1.2f, tween(550, easing = LinearEasing))
        }
    }
    Box(
        modifier = modifier
            .drawBehind {
                val b = if (lit) 1.55f else 1f
                // 玻璃底：120° 斜向渐变
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f * b),
                            Color.White.copy(alpha = 0.05f * b),
                            Color.White.copy(alpha = 0.10f * b)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height * 0.45f)
                    ),
                    cornerRadius = CornerRadius(size.height / 2f)
                )
                // 顶部高光缝（两端收进左右弧区，不露到胶囊外）
                drawLine(
                    color = Color.White.copy(alpha = 0.30f * b),
                    start = Offset(size.height / 2f, 0.75f),
                    end = Offset(size.width - size.height / 2f, 0.75f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .border(1.dp, Color.White.copy(alpha = if (lit) 0.34f else 0.16f), CircleShape)
            .drawWithContent {
                drawContent()
                // 扫光高光条：裁剪进胶囊形，聚焦瞬间从左扫到右
                val t = shine.value
                if (t > -0.6f && t < 1.2f) {
                    clipPath(
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(Offset.Zero, size),
                                    cornerRadius = CornerRadius(size.height / 2f)
                                )
                            )
                        }
                    ) {
                        val w = size.width * 0.45f
                        val x = size.width * t
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent
                                ),
                                startX = x, endX = x + w
                            ),
                            topLeft = Offset(x, 0f),
                            size = Size(w, size.height)
                        )
                    }
                }
            }
            .tvFocus(focusScale, circle = true, shapeOverride = CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            // contentPadding 必须挂在链尾（clickable 之后）：内边距区域也属于可点/可聚焦范围，
            // 之前漏挂导致该参数形同虚设、所有玻璃胶囊比设计小一圈。
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        content(focused)
    }
}

/** 通用筛选 chip：玻璃胶囊（T 方案），选中=主色文字+加粗+玻璃常亮。 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        highlight = selected,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
    ) { focused ->
        Text(
            label,
            fontSize = 17.sp,
            maxLines = 1,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                selected -> MaterialTheme.colorScheme.primary
                focused -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** 弹层内的文字按钮：玻璃胶囊（T 方案），主操作用 textColor=primary 强调。 */
@Composable
fun DialogTextButton(
    label: String,
    onClick: () -> Unit,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    /** 供调用方把该按钮设为弹层的初始焦点目标。 */
    modifier: Modifier = Modifier
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
    ) { _ -> Text(label, color = textColor, fontSize = 17.sp) }
}
