package com.tvmusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** 弹层遮罩色：全部 ModalCard 共用一份定义。
 *  遮罩色与主题无关——纯黑半透明是通用语义（任何主题下遮罩都希望是"压暗背景"），
 *  因此收敛为文件级常量而不走 Theme。 */
private val ModalScrim = Color(0xAA000000)

/**
 * 焦点兜底槽（F11）：注册"焦点丢失时应回到哪个节点"。
 * MainActivity 的全屏根节点是最后的焦点捕手（对话框关闭/页面销毁时焦点自动归位到它），
 * 但全屏节点=遥控器死区+Android 7 全屏系统白圈。根节点一旦拿到焦点就立即重定向到
 * 本槽注册的最近兜底目标（子页=返回按钮/播放主按钮，主页=当前页签）。
 */
val LocalFocusFallback = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<androidx.compose.ui.focus.FocusRequester?>?> { null }

/** 把当前节点注册为所在页面的焦点兜底目标（配合 BackTopBar / 播放页主按钮使用）。
 *  注意：fr 需由调用方与 tvInitialFocus 共用并自行挂 focusRequester(fr)——
 *  同一节点挂两个 focusRequester 只有最后一个生效，另一个会永远 not-initialized。 */
@Composable
fun Modifier.tvFocusFallback(fr: androidx.compose.ui.focus.FocusRequester): Modifier {
    val slot = LocalFocusFallback.current
    androidx.compose.runtime.DisposableEffect(fr) {
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
fun Modifier.tvInitialFocus(fr: androidx.compose.ui.focus.FocusRequester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }): Modifier {
    // 首帧节点可能尚未挂载/暂不可聚焦：requestFocus 会抛 IllegalStateException
    // （"FocusRequester is not initialized"，2026-09-26 连环闪退根因），必须捕获并重试。
    // 2026-09-28 真机焦点死区根因：requestFocus 成功**之后**，上一个页面被销毁的焦点节点
    // 会触发 Compose「焦点归位到最近可聚焦祖先」——即 MainActivity 的全屏 focusable 根 Box，
    // 把本页刚抢到的焦点抢走。全屏节点吞掉一切 D-pad 搜索（下方无可聚焦候选），整页遥控器失灵。
    // 因此 requestFocus 后必须校验是否留住，被抢走则重新请求，直到焦点真正留住。
    // 组合被销毁时 LaunchedEffect 自动取消，不会泄漏。
    var hasFocus by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
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
fun Modifier.tvFocus(scaleOverride: Float? = null, circle: Boolean = false, shapeOverride: androidx.compose.ui.graphics.Shape? = null): Modifier {
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
    shapeOverride: androidx.compose.ui.graphics.Shape?,
    circle: Boolean,
    themeRadius: Dp,
    minDimension: Float
): CornerRadius {
    when {
        circle -> return CornerRadius(minDimension / 2f)
        // RectangleShape=纯文字按钮的直角焦点框（按钮本体无圆角，焦点框跟随无圆角）
        shapeOverride === androidx.compose.ui.graphics.RectangleShape -> return CornerRadius.Zero
        shapeOverride is RoundedCornerShape -> {
            // RoundedCornerShape 的圆角可能是 Dp 或 Percent，只处理 Dp（项目内只用 Dp）
            val dp = shapeOverride.topStart as? Dp
            if (dp != null) return CornerRadius(dp.toPx())
        }
        else -> {}
    }
    return CornerRadius(themeRadius.toPx())
}

@Composable
fun Artwork(url: String, modifier: Modifier = Modifier) {
    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
    val bgBrush = remember {
        Brush.linearGradient(
            // 封面占位的装饰性渐变底色，与主题无关故不走 ThemeTokens（改动需保持视觉一致）
            listOf(Color(0xFF232C38), Color(0xFF12161D))
        )
    }
    // M7：加载失败（坏链/弱网超时）回退到音符占位，不再留空白块
    var loadFailed by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radius))
            .background(bgBrush),
        contentAlignment = Alignment.Center
    ) {
        if (url.startsWith("http") && !loadFailed) {
            // crossfade：经 ImageRequest 开启（Coil 2 API），换图/复用不再硬闪
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(url) {
                coil.request.ImageRequest.Builder(context).data(url).crossfade(220).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is coil.compose.AsyncImagePainter.State.Error) loadFailed = true
                }
            )
        } else {
            // 占位：音符图标（无封面图时不再是空黑块）
            Text(
                "♪",
                fontSize = 34.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
fun AppTitleBar(
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MusicFree TV",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 22.sp,
            modifier = Modifier.padding(end = 28.dp)
        )
        // H13：选中态高亮跟随当前页签（焦点记忆）。
        // 注意：页签**不主动抢焦点**（会与子页返回按钮的 tvInitialFocus 抢焦点打乒乓，
        // 两边都抢输、焦点落到全屏根节点死区）。页签只注册为焦点兜底目标：
        // 焦点丢失到根节点时由 MainActivity 重定向回当前页签（F11）。
        TabItem("首页", "home", selected, onSelect)
        TabItem("搜索", "search", selected, onSelect)
        TabItem("设置", "settings", selected, onSelect)
        TabItem("我的歌单", "mylist", selected, onSelect)
        TabItem("关于", "about", selected, onSelect)
    }
}

@Composable
private fun TabItem(
    label: String,
    key: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    val isSelected = selected == key
    val fr = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    // 冷启动抢焦点不在这里做（FocusManager 无 hasFocus API，无法安全判断"整个焦点树无焦点"；
    // 无条件 requestFocus 是抢占式的，会抢走启动弹框的 initialFocus，2026-09-29 实测）。
    // 已上移到 MainActivity.App()：用根节点外层观察者的 hasFocus 判定全树无焦点后才抢。
    // 选中页签注册为焦点兜底目标：主页上弹层关闭/子页销毁等场景，
    // 根节点收到焦点后重定向到这里（F11）。
    val slot = LocalFocusFallback.current
    androidx.compose.runtime.DisposableEffect(fr, isSelected) {
        if (!isSelected) return@DisposableEffect onDispose {}
        val prev = slot?.value
        if (slot != null) slot.value = fr
        onDispose { if (slot != null && slot.value === fr) slot.value = prev }
    }
    GlassButton(
        onClick = { onSelect(key) },
        modifier = Modifier
            .padding(end = 10.dp)
            .focusRequester(fr),
        highlight = isSelected,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 9.dp)
    ) { focused ->
        Text(
            text = label,
            color = if (isSelected || focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = if (isSelected || focused) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Normal
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 主色竖条：分区标题统一视觉锚点
        Box(
            Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = title,
            fontSize = 19.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/* ---------------- 玻璃拟态胶囊按钮（T 方案：全 App 按钮统一底座） ---------------- */

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
    focusScale: Float = 1.07f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    content: @Composable androidx.compose.foundation.layout.BoxScope.(focused: Boolean) -> Unit
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
            .clickable(enabled = enabled, onClick = onClick),
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) { focused ->
        Text(
            label,
            fontSize = 13.sp,
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

@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    artwork: String,
    onClick: () -> Unit,
    /** LazyRow 场景默认 168dp 固定宽；网格内传 fillMaxWidth() 跟随格宽 */
    modifier: Modifier = Modifier.width(168.dp)
) {
    Column(
        modifier = modifier
            .tvFocus()
            .clickable(onClick = onClick)
    ) {
        // 封面：1:1，圆角随主题 Token，加载失败/无图时深灰渐变+音符兜底（Artwork 内置）
        // aspectRatio(1f)：默认 168dp 宽时高度同前；网格 fillMaxWidth 时跟随列宽保持正方形
        Artwork(
            artwork,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        // 标题/平台分层：14sp 白 / 11sp 灰
        Text(
            text = title,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        )
        Text(
            text = subtitle,
            fontSize = 12.sp, // M11：TV 10 英尺可读性，辅助信息不低于 12sp
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
        )
    }
}

@Composable
fun MusicRow(
    index: Int,
    title: String,
    artist: String,
    album: String,
    onClick: () -> Unit,
    /** 行尾附加操作（如收藏按钮）；null 时不占位。 */
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 16.dp)
        )
        Text(
            text = artist,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight(fill=false)：宽屏最多 180dp 等比列，窄窗按比例收缩不再溢出裁切
            modifier = Modifier.weight(1.1f, fill = false).padding(end = 16.dp)
        )
        Text(
            text = album,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 16.dp)
        )
        if (trailing != null) trailing()
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/* ---------------- 收藏对话框（详情页 / 搜索结果共用） ---------------- */

/** 补全条目的 platform 字段（收藏/历史需要来源插件名才能回放）。 */
fun withPlatform(o: org.json.JSONObject, plugin: String): org.json.JSONObject =
    if (o.optString("platform").isNotBlank() || plugin.isBlank()) o
    else org.json.JSONObject(o.toString()).put("platform", plugin)

/** 收藏弹层公共骨架：经 ModalCard 统一遮罩/宽度/底栏。 */
@Composable
private fun FavDialogBox(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    initialFocus: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalCard(title = title, subtitle = subtitle, width = 420.dp, onDismiss = onDismiss, initialFocus = initialFocus, content = content)
}

/** 新建收藏夹行：输入名称后点「新建」回调（TV 遥控可调起 IME 输入）。 */
@Composable
private fun NewFavListRow(initialName: String = "", onCreate: (String) -> Unit) {
    // 预填名称（如歌单名），但只作为输入框初始值——用户需手动点「新建」才会创建，
    // 不会在打开弹层时自动新建（满足"预填但不直接新建"的需求）。
    var name by remember(initialName) { mutableStateOf(initialName) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("新建收藏夹名称", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .weight(1f)
                .tvFocus(shapeOverride = RoundedCornerShape(10.dp)),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
        GlassButton(
            onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) { onCreate(n); name = "" }
            },
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
        ) { _ -> Text("新建", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
    }
}

/**
 * 全部收藏弹层：把 entries 批量加入所选收藏夹（已收录的自动去重）。
 * 收藏夹列表含「我的收藏」与全部自定义收藏夹；底部可直接新建收藏夹并加入，
 * 不必先退出到「我的歌单」页建夹。
 */
@Composable
fun CollectSongsDialog(
    entries: List<org.json.JSONObject>,
    playback: com.tvmusic.data.PlaybackStore,
    sheetName: String = "",
    onDismiss: () -> Unit
) {
    val lists by playback.lists.collectAsState()
    var lastMsg by remember { mutableStateOf<String?>(null) }
    // 初始焦点给第一个收藏夹行（主要交互）；列表为空时 ModalCard 自动退回容器兜底
    val firstRowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FavDialogBox(
        title = "全部收藏到…",
        subtitle = "将本页已加载的 ${entries.size} 首加入所选收藏夹（已收藏的自动跳过）",
        onDismiss = onDismiss,
        initialFocus = firstRowFocus
    ) {
        val entryKeys = remember(entries) { entries.mapTo(HashSet()) { playback.primaryKey(it) } }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.height(280.dp)) {
            itemsIndexed(lists, key = { _, it -> it.id }) { idx, fl ->
                val keys = remember(fl) { fl.items.mapTo(HashSet()) { playback.primaryKey(it) } }
                val have = entryKeys.count { it in keys }
                val all = entries.isNotEmpty() && have >= entries.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (idx == 0) it.focusRequester(firstRowFocus) else it }
                        .tvFocus()
                        .clickable {
                            val added = playback.addAllToList(fl.id, entries)
                            lastMsg = if (added > 0) "已加入「${fl.name}」$added 首"
                            else "「${fl.name}」内已全部收藏"
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (all) "✓ " else "　",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp
                    )
                    Text(fl.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "（$have/${entries.size}）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        lastMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        // 预填歌单名到新建框（用户可改、也可直接点新建），不自动创建
        NewFavListRow(initialName = sheetName) { name ->
            val id = playback.addList(name)
            if (id != null) {
                val added = playback.addAllToList(id, entries)
                lastMsg = "已新建「$name」并收藏 $added 首"
            }
        }
    }
}

/**
 * 单曲收藏弹层：点击收藏夹切换该曲在其中的收藏状态（♥ 表示已收录）。
 * 可加入任意自定义收藏夹（而非固定「我的收藏」）；底部可新建收藏夹并直接收藏。
 */
@Composable
fun PickFavDialog(
    item: org.json.JSONObject,
    playback: com.tvmusic.data.PlaybackStore,
    onDismiss: () -> Unit
) {
    val lists by playback.lists.collectAsState()
    val key = remember(item) { playback.primaryKey(item) }
    val songName = item.optString("title", "").ifBlank { "该曲目" }
    // 初始焦点给第一个收藏夹行（主要交互）；列表为空时 ModalCard 自动退回容器兜底
    val firstRowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FavDialogBox(
        title = "收藏到…",
        subtitle = songName + "（点击收藏夹加入/移出）",
        onDismiss = onDismiss,
        initialFocus = firstRowFocus
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.height(280.dp)) {
            itemsIndexed(lists, key = { _, it -> it.id }) { idx, fl ->
                val has = remember(fl) { fl.items.any { playback.primaryKey(it) == key } }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (idx == 0) it.focusRequester(firstRowFocus) else it }
                        .tvFocus()
                        .clickable { playback.toggleFavorite(item, fl.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (has) "♥" else "♡",
                        color = if (has) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 18.sp,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(fl.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "（${fl.items.size}）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        NewFavListRow { name ->
            playback.addList(name)?.let { id -> playback.toggleFavorite(item, id) }
        }
    }
}

@Composable
fun ErrorBox(message: String?, onRetry: (() -> Unit)? = null) {
    if (message == null) return
    Box(Modifier.fillMaxWidth().padding(28.dp)) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp
        )
        if (onRetry != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .tvFocus(shapeOverride = RoundedCornerShape(6.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("重试", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
        }
    }
}

/* ---------------- 弹层骨架：ModalCard 统一遮罩 / 圆角 / 宽度 / 底栏按钮 ---------------- */

/**
 * 居中弹层通用骨架：半透明遮罩 + 标题/副标题 + 内容插槽 + 底栏插槽（默认「关闭」按钮）。
 * 全应用弹层统一经此渲染，宽度/遮罩色不再各自为政。
 */
@Composable
fun ModalCard(
    title: String,
    subtitle: String? = null,
    width: Dp = 420.dp,
    onDismiss: (() -> Unit)? = null,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    /**
     * 弹层打开时的初始焦点目标（通常是主操作按钮的 FocusRequester）。
     *
     * 为什么需要它：弹层容器自身 focusable 但**不可点击**，旧实现把初始焦点给了容器，
     * 结果是遥控器按 OK 键毫无反应、视觉上也没有任何焦点框——用户看到的正是
     * "没有聚焦退出按钮"。焦点必须落在真正可操作的按钮上。
     * 传 null 时回退到容器（保持其余弹层的既有行为不变）。
     */
    initialFocus: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onDismiss != null) {
        androidx.activity.compose.BackHandler { onDismiss() }
    }
    // 至少有一个可关闭/操作按钮时才有可聚焦子节点，可安全接管焦点
    val focusSafe = onDismiss != null || bottomBar != null
    val containerRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(initialFocus, focusSafe) {
        if (!focusSafe) return@LaunchedEffect
        val target = initialFocus ?: containerRequester
        // 首帧节点可能尚未挂载，requestFocus 会抛 IllegalStateException，带重试
        repeat(20) {
            try {
                target.requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                kotlinx.coroutines.delay(80)
            }
        }
        // 目标迟迟挂不上（如空列表的首行不存在）：退回容器，保证焦点至少留在弹层内
        if (initialFocus != null) {
            try { containerRequester.requestFocus() } catch (_: IllegalStateException) {}
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModalScrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(14.dp))
                // 容器焦点仅作兜底 / D-pad 停留点，不再承担初始焦点。
                // focusRequester 必须排在 focusable 之前，请求才能命中该节点。
                .let {
                    if (focusSafe) it.focusRequester(containerRequester).focusable() else it
                }
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
            if (bottomBar != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) { bottomBar() }
            } else if (onDismiss != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    DialogTextButton("关闭", onDismiss)
                }
            }
        }
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
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
    ) { _ -> Text(label, color = textColor, fontSize = 14.sp) }
}

/* ---------------- 逐行歌词行块：播放页歌词区共用 ---------------- */

/** 单行歌词渲染（主行 + 译文），当前行加粗放大高亮。 */
@Composable
fun LyricLineBlock(
    line: com.tvmusic.player.LrcLine,
    isCurrent: Boolean,
    lrcColor: Color,
    fontSizeSp: Float
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = line.text,
            color = if (isCurrent) lrcColor else lrcColor.copy(alpha = 0.35f),
            fontSize = if (isCurrent) (fontSizeSp + 4).sp else fontSizeSp.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        line.translation?.takeIf { it.isNotBlank() }?.let { t ->
            Text(
                text = t,
                color = if (isCurrent) lrcColor.copy(alpha = 0.85f) else lrcColor.copy(alpha = 0.28f),
                fontSize = (fontSizeSp - 2f).coerceAtLeast(10f).sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/* ---------------- 页级统一组件：返回头栏 / 空态 / 加载更多 ---------------- */

/** 返回 + 标题头栏。用普通 Row（非 LazyRow）：LazyRow 的懒加载焦点作用域会吞掉
 *  tvInitialFocus 的 requestFocus，并把后续 D-pad 焦点搜索困在空作用域里，导致
 *  整页焦点丢失到根节点（真机遥控器上下左右全失灵，2026-09-28 推荐/排行/歌单详情
 *  共同根因）。标题 weight(1f)+省略号，长标题不再把返回键/trailing 挤出屏幕。 */
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** 进入页面时是否把初始焦点落到「返回」按钮。默认 true：从首页等带焦点页跳入
     *  子页面时，旧焦点节点销毁会导致焦点丢失（D-pad 无处移动/跳错），返回按钮
     *  拿到焦点后方向键即可向下进入内容区。 */
    initialFocus: Boolean = true
) {
    // 返回按钮共用同一个 FocusRequester：初始焦点抢占 + 焦点兜底注册必须指向同一节点
    // （同节点挂两个 focusRequester 只有最后一个生效）。
    val backFr = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .let { if (initialFocus) it.tvInitialFocus(backFr) else it.focusRequester(backFr) }
                .tvFocusFallback(backFr)
                .tvFocus()
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
        }
        Text(
            text = title,
            fontSize = titleSize,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { trailing() }
        }
    }
}

/** 居中空态：统一图标?/文案 + 可选操作按钮。 */
@Composable
fun EmptyState(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        if (actionLabel != null && onAction != null) {
            GlassButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 20.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) { _ -> Text(actionLabel, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp) }
        }
    }
}

/** 列表底部加载态：加载中 / 错误重试 / 可加载更多 / 已到底，四态统一。 */
@Composable
fun LoadMoreFooter(
    loading: Boolean,
    hasMore: Boolean,
    error: String?,
    allLoadedText: String?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Text(
                "加载中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                DialogTextButton("重试", onLoadMore, MaterialTheme.colorScheme.primary)
            }
            hasMore -> GlassButton(
                onClick = onLoadMore,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
            ) { _ -> Text("加载更多", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            allLoadedText != null -> Text(
                allLoadedText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}