package com.tvmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tvmusic.model.PluginRecord

/**
 * 媒体展示件：封面、卡片、歌曲行、插件页签。
 * 这些是「内容呈现」而非「交互控件」，故不套玻璃胶囊（AGENTS.md 明确 MediaCard/MusicRow 不加玻璃）。
 */

/**
 * 封面：1:1 圆角随主题 Token，加载失败/无图时深灰渐变 + 音符兜底。
 *
 * 高频使用（首页/推荐/排行/播放页/队列），故用 remember(url) 缓存 ImageRequest，
 * 避免列表滚动时反复构建请求对象。
 */
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

/** 歌单/榜单卡片：1:1 封面 + 标题 + 副标题。 */
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

/** 歌曲行：序号 + 曲名 + 歌手 + 专辑 + 可选行尾操作。 */
@Composable
fun MusicRow(
    index: Int,
    title: String,
    artist: String,
    album: String,
    onClick: () -> Unit,
    /** 行尾附加操作（如收藏按钮）；null 时不占位。 */
    trailing: (@Composable RowScope.() -> Unit)? = null
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

/**
 * 插件页签行：把「已启用且支持某能力」的插件排成一排 FilterChip。
 *
 * 背景：TopListScreen / RecommendScreen / HomeScreen 源切换器三处逐字复制了
 * 同一段 LazyRow + 「key 拼插件名防重复」+ 「info 为 null 跳过」逻辑。
 * key 必须包含 name：不同插件可能声明同一 platform，裸 platform 会造成重复 key 闪退。
 */
@Composable
fun PluginTabRow(
    plugins: List<PluginRecord>,
    selectedPlatform: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = plugins,
            key = { "${it.info?.platform ?: "null"}_${it.name}" }
        ) { rec ->
            val platform = rec.info?.platform ?: return@items
            FilterChip(
                label = rec.name,
                selected = platform == selectedPlatform,
                onClick = { onSelect(platform) }
            )
        }
    }
}

/**
 * 收藏心形按钮：♡/♥ 两态纯文字图标按钮。
 *
 * 背景：SheetScreen（歌曲行尾 18sp）、SearchScreen（结果行尾 22sp）两处各自手写
 * `Box(tvFocus().clickable{}.padding())` + ♡/♥ 与配色分支。
 * 两处原本的差异只有**字号**与 **padding**，故只有这两项开放为参数；
 * 配色固定为「已收藏=主色，未收藏=弱化色」（两处原本完全一致，不额外加 focused 分支），
 * 焦点框形状沿用裸 tvFocus() 的主题圆角。
 * 注意：播放页状态组的 ♡/♡ 是 RoundCtrlButton（圆形 40dp 控件，属另一族），不并入此处。
 */
@Composable
fun FavoriteButton(
    favorited: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: TextUnit = 20.sp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
) {
    FocusTextButton(
        onClick = onClick,
        modifier = modifier,
        shape = null,
        contentPadding = contentPadding
    ) { _ ->
        Text(
            text = if (favorited) "♥" else "♡",
            fontSize = iconSize,
            color = if (favorited) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
