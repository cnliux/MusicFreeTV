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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
 * D-pad 聚焦放大效果（纯视觉）。
 * 注意：不要再叠加 focusable()——clickable 自身已创建焦点节点，
 * 额外的 focusable 节点会抢走焦点，导致遥控器 OK 键无法触发点击。
 */
@Composable
fun Modifier.tvFocus(scaleOnFocus: Float = 1.06f): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, label = "tvScale")
    val ring = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawBehind {
            // 焦点高亮：遥控器聚焦时整块填充主色渐变，形成明显的"变色"反馈。
            // drawBehind 画在本节点之前的 background 之上、内容之下，
            // 因此使用处应把 clip/background 放在 tvFocus 之前。
            if (focused) {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(ring.copy(alpha = 0.55f), ring.copy(alpha = 0.38f))
                    )
                )
            }
        }
        .border(if (focused) 2.dp else 0.dp, ring, RoundedCornerShape(12.dp))
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (focused) 1f else 0.9f }
}

@Composable
fun Artwork(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
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
            .background(Color(0xFF0B0D12))
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
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .let { if (initialFocus) it.tvInitialFocus() else it }
            .tvFocus()
            .clickable { onSelect(key) }
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF002030) else MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp
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

/** 通用筛选 chip：插件页签 / 标签 / 搜索类型切换共用（D-pad 可聚焦）。 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .tvFocus(1.05f)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            fontSize = 14.sp,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
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
    Column(
        modifier = modifier
            .width(156.dp)
            .tvFocus(1.05f)
            .clickable(onClick = onClick)
    ) {
        // 封面：底部渐变遮罩叠标题，沉浸感更强
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .graphicsLayer {
                    shadowElevation = 10.dp.toPx()
                    clip = true
                    shape = RoundedCornerShape(14.dp)
                }
        ) {
            Artwork(artwork, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                    )
            )
            Text(
                text = title,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        Text(
            text = subtitle,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
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
            .tvFocus()
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
                    .tvFocus()
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("重试", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
        }
    }
}