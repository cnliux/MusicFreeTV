package com.tvmusic.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 初始焦点：进入界面时主动请求焦点。
 * 真机遥控器没有鼠标，若焦点树中无任何焦点节点，D-pad 按键无处移动——
 * 这是"模拟器能操作、真机遥控不能操作"的常见根因。
 * 首帧节点可能尚未挂载导致 requestFocus 失败，这里带重试。
 */
@Composable
fun Modifier.tvInitialFocus(): Modifier {
    val fr = androidx.compose.ui.focus.FocusRequester()
    var acquired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(8) {
            if (acquired) return@LaunchedEffect
            runCatching { fr.requestFocus() }
            kotlinx.coroutines.delay(100)
        }
    }
    return this
        .focusRequester(fr)
        .onFocusChanged { if (it.isFocused) acquired = true }
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
    val animated by animateFloatAsState(if (focused) scale else 1f, label = "tvScale")
    val shape = shapeOverride ?: if (circle) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(tokens.radius)
    return this
        .onFocusChanged { focused = it.isFocused }
        // 边框必须画在 graphicsLayer 之外：调用方的 clip 位于本 Modifier 外侧，
        // 若 border 在 graphicsLayer 内会被放大 1.08x 后超出 clip 边界，
        // 圆角被裁掉只剩上下直边——这就是"白条"的真正根因。
        .let {
            if (focused) it.border(3.dp, tokens.focusBorder, shape) else it
        }
        .graphicsLayer {
            if (!tokens.focusBrightnessOnly) {
                scaleX = animated
                scaleY = animated
            }
            alpha = when {
                tokens.focusBrightnessOnly -> if (focused) 1f else 0.62f
                else -> if (focused) 1f else 0.92f
            }
            if (tokens.focusGlow > 0.dp) {
                shadowElevation = if (focused) tokens.focusGlow.toPx() else 0f
                ambientShadowColor = glow
                spotShadowColor = glow
            }
        }
        .drawBehind {
            if (focused) drawRect(glow.copy(alpha = if (tokens.focusBrightnessOnly) 0.08f else 0.14f))
        }
}

/**
 * 全局悬浮歌词层：叠加在非播放页最上层，不拦截焦点（userScrollEnabled=false）。
 * 位置（顶部/居中/底部 + 垂直微调）、字号、颜色、透明度均由歌词设置控制。
 * 播放页有独立的逐行歌词视图，故仅在非 player 路由下由 MainActivity 挂载本层。
 */
@Composable
fun LyricOverlay(lines: List<com.tvmusic.player.LrcLine>, currentIndex: Int) {
    val cfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()
    if (!cfg.enabled) return
    val lrcColor = com.tvmusic.ui.theme.LyricSettings.parseColor()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in lines.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val align = when (cfg.position) {
        com.tvmusic.ui.theme.LyricPosition.TOP -> Alignment.TopCenter
        com.tvmusic.ui.theme.LyricPosition.BOTTOM -> Alignment.BottomCenter
        else -> Alignment.Center
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 64.dp, end = 64.dp, top = 24.dp, bottom = 120.dp)
            .offset { androidx.compose.ui.unit.IntOffset(0, with(density) { cfg.offsetY.dp.roundToPx() }) }
            .graphicsLayer { alpha = cfg.opacity },
        contentAlignment = align
    ) {
        if (lines.isEmpty()) {
            Text(
                "暂无歌词",
                color = lrcColor.copy(alpha = 0.45f),
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 30.dp)
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxWidth().height(300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item { Spacer(Modifier.height(120.dp)) }
                items(lines.size) { i ->
                    val isCurrent = i == currentIndex
                    val line = lines[i]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .graphicsLayer { alpha = if (isCurrent) 1f else 0.6f }
                    ) {
                        Text(
                            text = line.text,
                            color = if (isCurrent) lrcColor else lrcColor.copy(alpha = 0.35f),
                            fontSize = if (isCurrent) (cfg.fontSizeSp + 4).sp else cfg.fontSizeSp.sp,
                            fontWeight = if (isCurrent)
                                androidx.compose.ui.text.font.FontWeight.Bold
                            else androidx.compose.ui.text.font.FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        line.translation?.takeIf { it.isNotBlank() }?.let { t ->
                            Text(
                                text = t,
                                color = if (isCurrent) lrcColor.copy(alpha = 0.85f) else lrcColor.copy(alpha = 0.28f),
                                fontSize = (cfg.fontSizeSp - 2).coerceAtLeast(10).sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
fun Artwork(url: String, modifier: Modifier = Modifier) {
    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radius))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF232C38), Color(0xFF12161D))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (url.startsWith("http")) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
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
        TabItem("首页", "home", selected, onSelect, initialFocus = true)
        TabItem("搜索", "search", selected, onSelect)
        TabItem("设置", "settings", selected, onSelect)
        TabItem("关于", "about", selected, onSelect)
    }
}

@Composable
private fun TabItem(
    label: String,
    key: String,
    selected: String,
    onSelect: (String) -> Unit,
    initialFocus: Boolean = false
) {
    val isSelected = selected == key
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
            .let { if (initialFocus) it.tvInitialFocus() else it }
            .tvFocus(shapeOverride = RoundedCornerShape(20.dp))
            .clickable { onSelect(key) }
            .padding(horizontal = 24.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold
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

/** 通用筛选 chip：胶囊形，选中=主色填充，聚焦=白边（tvFocus）。 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .tvFocus(shapeOverride = RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            fontSize = 14.sp,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    artwork: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
    Column(
        modifier = modifier
            .width(168.dp)
            .tvFocus()
            .clickable(onClick = onClick)
    ) {
        // 封面：1:1，圆角随主题 Token，加载失败/无图时深灰渐变+音符兜底（Artwork 内置）
        Artwork(
            artwork,
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .graphicsLayer {
                    shadowElevation = 8.dp.toPx()
                    clip = true
                    shape = RoundedCornerShape(tokens.radius)
                }
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
            fontSize = 11.sp,
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
    onClick: () -> Unit
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
            modifier = Modifier.width(180.dp)
        )
        Text(
            text = album,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(180.dp)
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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