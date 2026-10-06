package com.tvmusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.drawBehind

/**
 * TV 焦点基础设施：初始焦点、焦点兜底注册、D-pad 聚焦视觉。
 *
 * 从原先 1058 行的 Components.kt 拆出——这四个 API 是全 App 遥控可用性的地基，
 * 与按钮/卡片/弹层的演进无关，独立成文件更利于回归定位焦点事故。
 */

/**
 * 焦点兜底槽（F11）：注册"焦点丢失时应回到哪个节点"。
 * MainActivity 的全屏根节点是最后的焦点捕手（对话框关闭/页面销毁时焦点自动归位到它），
 * 但全屏节点=遥控器死区+Android 7 全屏系统白圈。根节点一旦拿到焦点就立即重定向到
 * 本槽注册的最近兜底目标（子页=返回按钮/播放主按钮，主页=当前页签）。
 */
val LocalFocusFallback = compositionLocalOf<androidx.compose.runtime.MutableState<FocusRequester?>?> { null }

/** 把当前节点注册为所在页面的焦点兜底目标（配合 BackTopBar / 播放页主按钮使用）。
 *  注意：fr 需由调用方与 tvInitialFocus 共用并自行挂 focusRequester(fr)——
 *  同一节点挂两个 focusRequester 只有最后一个生效，另一个会永远 not-initialized。 */
@Composable
fun Modifier.tvFocusFallback(fr: FocusRequester): Modifier {
    val slot = LocalFocusFallback.current
    DisposableEffect(fr) {
        val prev = slot?.value
        if (slot != null) slot.value = fr
        onDispose {
            if (slot != null && slot.value === fr) slot.value = prev
        }
    }
    return this
}

/**
 * 初始焦点：进入界面时主动请求焦点。
 * 真机遥控器没有鼠标，若焦点树中无任何焦点节点，D-pad 按键无处移动——
 * 这是"模拟器能操作、真机遥控不能操作"的常见根因。
 * 首帧节点可能尚未挂载导致 requestFocus 失败，这里带重试。
 */
@Composable
fun Modifier.tvInitialFocus(fr: FocusRequester = remember { FocusRequester() }): Modifier {
    // 首帧节点可能尚未挂载/暂不可聚焦：requestFocus 会抛 IllegalStateException
    // （"FocusRequester is not initialized"，2026-09-26 连环闪退根因），必须捕获并重试。
    // 2026-09-28 真机焦点死区根因：requestFocus 成功**之后**，上一个页面被销毁的焦点节点
    // 会触发 Compose「焦点归位到最近可聚焦祖先」——即 MainActivity 的全屏 focusable 根 Box，
    // 把本页刚抢到的焦点抢走。全屏节点吞掉一切 D-pad 搜索（下方无可聚焦候选），整页遥控器失灵。
    // 因此 requestFocus 后必须校验是否留住，被抢走则重新请求，直到焦点真正留住。
    // 组合被销毁时 LaunchedEffect 自动取消，不会泄漏。
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(40) {
            try {
                fr.requestFocus()
            } catch (_: IllegalStateException) {
                // 焦点目标还没就绪，稍后重试
            }
            kotlinx.coroutines.delay(100)
            if (hasFocus) {
                // 焦点可能在上一个页面销毁时被全屏根节点抢走：再等 400ms 仍在我方才算落定
                kotlinx.coroutines.delay(400)
                if (hasFocus) return@LaunchedEffect
            }
        }
    }
    return this.focusRequester(fr).onFocusChanged { hasFocus = it.isFocused }
}

/**
 * D-pad 聚焦效果（纯视觉），读取当前主题 Token：
 * 圆角 / 焦点缩放 / 焦点边框色 / 发光强度 / 是否仅用亮度变化（杂志模式）。
 * 注意：不要再叠加 focusable()——clickable 自身已创建焦点节点，
 * 额外的 focusable 节点会抢走焦点，导致遥控器 OK 键无法触发点击。
 */
@Composable
fun Modifier.tvFocus(scaleOverride: Float? = null, circle: Boolean = false, shapeOverride: Shape? = null): Modifier {
    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
    var focused by remember { mutableStateOf(false) }
    val glow = MaterialTheme.colorScheme.primary
    val scale = scaleOverride ?: tokens.focusScale
    return this
        .onFocusChanged { focused = it.isFocused }
        // 焦点缩放/亮度/边框全部在绘制阶段读取 State（graphicsLayer/drawBehind 是延迟读），
        // 焦点在列表间移动时不再触发宿主子树重组，列表滚动与遥控切换帧率不受影响。
        .graphicsLayer {
            if (!tokens.focusBrightnessOnly) {
                scaleX = if (focused) scale else 1f
                scaleY = if (focused) scale else 1f
            }
            // H11 修复：非焦点项不再做透明度衰减（0.92/0.62 让整屏内容半透明，
            // 与背景对比度低于 WCAG 建议，10 英尺可读性差）。焦点区分仅靠边框/缩放/亮度。
            alpha = 1f
            // 不再使用 shadowElevation 做"发光"：elevation 模拟顶部光源，
            // 阴影只会向下偏移，在深色背景上表现为难看的底部阴影。
        }
        .drawBehind {
            if (focused) {
                drawRect(glow.copy(alpha = if (tokens.focusBrightnessOnly) 0.08f else 0.14f))
                /* 描边/外发光必须跟着元素自身的形状走。之前这里只认 circle 开关、
                   圆角一律用 tokens.radius，把传进来的 shapeOverride 丢掉了——播放器封面
                   选「圆形」时内容确实被裁成圆，但外面套的仍是一个圆角矩形描边，
                   看起来就像「圆形没生效、只有方形圆角生效」。 */
                val radius = focusCornerRadius(shapeOverride, circle, tokens.radius, size.minDimension)
                val stroke = 3.dp.toPx()
                // M13：激活 focusGlow token——外发光环（模糊描边），强度随主题 token 变化。
                // 不用 shadowElevation（模拟顶部光源、深色底上出现难看下偏阴影），
                // 改用 BlurMaskFilter 对称模糊。focusGlow=0 的主题（极简黑白/杂志排版）自然无发光。
                val glowPx = tokens.focusGlow.toPx()
                if (glowPx > 0f && !tokens.focusBrightnessOnly) {
                    val glowStroke = glowPx.coerceIn(2f, 14f)
                    if (android.os.Build.VERSION.SDK_INT < 26) {
                        // Android 7（API<26）amlogic 盒子实测：BlurMaskFilter 对 radius 参数
                        // 不敏感（blur 量被系统固化），且宽描边会向胶囊外扩散成大光斑。
                        // 改用紧贴边框外侧的细描边环模拟外发光：矩形向外扩 o、描边宽 w，
                        // 光晕只占边框外 ~10px，纯几何绘制跨设备一致、不会失控。
                        val rings = listOf(
                            5f to 7f to 0.30f,   // 外扩5px 环，宽7px，较亮
                            10f to 9f to 0.12f  // 外扩10px 环，宽9px，淡出
                        )
                        for ((oa, a) in rings) {
                            val (o, w) = oa
                            drawRoundRect(
                                color = glow.copy(alpha = a),
                                topLeft = Offset(-o, -o),
                                size = Size(size.width + o * 2f, size.height + o * 2f),
                                style = Stroke(width = w),
                                cornerRadius = CornerRadius(radius.x + o, radius.y + o)
                            )
                        }
                    } else {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val paint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                color = glow.toArgb()
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = glowStroke * 2f
                                alpha = (0.45f * 255).toInt()
                                maskFilter = android.graphics.BlurMaskFilter(
                                    glowPx, android.graphics.BlurMaskFilter.Blur.NORMAL
                                )
                            }
                            native.drawRoundRect(0f, 0f, size.width, size.height, radius.x, radius.y, paint)
                        }
                    }
                }
                // 边框画在自身 DrawModifier 上（不受上方 graphicsLayer 缩放影响），
                // topLeft+size 内缩半个线宽，圆角处不再溢出直角；圆角半径同步等比缩放。
                val inset = stroke / 2f
                val k = if (size.minDimension > 0f) (size.minDimension - inset * 2) / size.minDimension else 1f
                drawRoundRect(
                    color = tokens.focusBorder,
                    style = Stroke(width = stroke),
                    cornerRadius = CornerRadius(radius.x * k, radius.y * k),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke)
                )
            }
        }
}

/**
 * 由元素自身形状推导焦点描边的圆角半径。
 * circle=true 或传入圆形 shapeOverride 时取整圆（半径=短边/2）；
 * 否则按 RoundedCornerShape 的 Dp 圆角，再否则退回主题 tokens.radius。
 * DrawScope 扩展是为了拿到 Density 做 Dp→px。
 */
private fun DrawScope.focusCornerRadius(
    shapeOverride: Shape?,
    circle: Boolean,
    themeRadius: Dp,
    minDimension: Float
): CornerRadius {
    when {
        circle -> return CornerRadius(minDimension / 2f)
        // RectangleShape=纯文字按钮的直角焦点框（按钮本体无圆角，焦点框跟随无圆角）
        shapeOverride === RectangleShape -> return CornerRadius.Zero
        shapeOverride is RoundedCornerShape -> {
            // RoundedCornerShape 的圆角可能是 Dp 或 Percent，只处理 Dp（项目内只用 Dp）
            val dp = shapeOverride.topStart as? Dp
            if (dp != null) return CornerRadius(dp.toPx())
        }
        else -> {}
    }
    return CornerRadius(themeRadius.toPx())
}

/**
 * 带焦点外框的纯文字可点击块：用于「无玻璃胶囊」的次级动作（返回键、收藏心形、更多 ›）。
 *
 * 背景：这四类元素此前在 BackTopBar / SheetScreen / SearchScreen / PlayerScreen
 * 各自手写 `Box(tvFocus().clickable{}.padding())` + 文本颜色分支，共 6 处。
 *
 * [shape] 决定焦点框形状：默认 RectangleShape（纯文字按钮本体无圆角，焦点框跟随无圆角）；
 * 传 null 则退回主题 tokens.radius（即原调用点里裸 `Modifier.tvFocus()` 的行为）。
 */
@Composable
fun FocusTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = RectangleShape,
    focusScale: Float = 1.04f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .tvFocus(focusScale, shapeOverride = shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        content(focused)
    }
}
