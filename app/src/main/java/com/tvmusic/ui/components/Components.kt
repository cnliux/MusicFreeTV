package com.tvmusic.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** 弹层遮罩色：全部 ModalCard 共用一份定义。 */
private val ModalScrim = Color(0xAA000000)

/**
 * 初始焦点：进入界面时主动请求焦点。
 * 真机遥控器没有鼠标，若焦点树中无任何焦点节点，D-pad 按键无处移动——
 * 这是"模拟器能操作、真机遥控不能操作"的常见根因。
 * 首帧节点可能尚未挂载导致 requestFocus 失败，这里带重试。
 */
@Composable
fun Modifier.tvInitialFocus(): Modifier {
    val fr = androidx.compose.ui.focus.FocusRequester()
    // 首帧节点可能尚未挂载/暂不可聚焦：requestFocus 会抛 IllegalStateException
    // （"FocusRequester is not initialized"，2026-09-26 连环闪退根因），
    // 必须捕获并重试；成功后立即停止。
    // 组合被销毁时 LaunchedEffect 自动取消，不会泄漏。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(20) {
            try {
                fr.requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                // 焦点目标还没就绪，稍后重试
            }
            kotlinx.coroutines.delay(80)
        }
    }
    return this.focusRequester(fr)
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
            alpha = when {
                tokens.focusBrightnessOnly -> if (focused) 1f else 0.62f
                else -> if (focused) 1f else 0.92f
            }
            // 不再使用 shadowElevation 做"发光"：elevation 模拟顶部光源，
            // 阴影只会向下偏移，在深色背景上表现为难看的底部阴影。
        }
        .drawBehind {
            if (focused) {
                drawRect(glow.copy(alpha = if (tokens.focusBrightnessOnly) 0.08f else 0.14f))
                // 圆角来自本 Modifier 自身的圆钮开关与主题 Token（shapeOverride 仅用于焦点外型一致性，
                // 边框半径以 tokens.radius 为准；圆钮用整圆）。
                val radius = if (circle) CornerRadius(size.minDimension / 2f)
                else CornerRadius(tokens.radius.toPx())
                val stroke = 3.dp.toPx()
                // 边框画在自身 DrawModifier 上（不受上方 graphicsLayer 缩放影响），
                // topLeft+size 内缩半个线宽，圆角处不再溢出直角。
                drawRoundRect(
                    color = tokens.focusBorder,
                    style = Stroke(width = stroke),
                    cornerRadius = radius,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke)
                )
            }
        }
}

@Composable
fun Artwork(url: String, modifier: Modifier = Modifier) {
    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
    val bgBrush = remember {
        Brush.linearGradient(
            listOf(Color(0xFF232C38), Color(0xFF12161D))
        )
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radius))
            .background(bgBrush),
        contentAlignment = Alignment.Center
    ) {
        if (url.startsWith("http")) {
            // crossfade：经 ImageRequest 开启（Coil 2 API），换图/复用不再硬闪
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(url) {
                coil.request.ImageRequest.Builder(context).data(url).crossfade(220).build()
            }
            AsyncImage(
                model = request,
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
        TabItem("我的歌单", "mylist", selected, onSelect)
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
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .tvFocus(shapeOverride = RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
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
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
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
    content: @Composable ColumnScope.() -> Unit
) {
    ModalCard(title = title, subtitle = subtitle, width = 420.dp, onDismiss = onDismiss, content = content)
}

/** 新建收藏夹行：输入名称后点「新建」回调（TV 遥控可调起 IME 输入）。 */
@Composable
private fun NewFavListRow(onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
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
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
                .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                .clickable {
                    val n = name.trim()
                    if (n.isNotEmpty()) { onCreate(n); name = "" }
                }
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) { Text("新建", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp) }
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
    onDismiss: () -> Unit
) {
    val lists by playback.lists.collectAsState()
    var lastMsg by remember { mutableStateOf<String?>(null) }
    FavDialogBox(
        title = "全部收藏到…",
        subtitle = "将本页已加载的 ${entries.size} 首加入所选收藏夹（已收藏的自动跳过）",
        onDismiss = onDismiss
    ) {
        val entryKeys = remember(entries) { entries.mapTo(HashSet()) { playback.primaryKey(it) } }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.height(280.dp)) {
            items(lists, key = { it.id }) { fl ->
                val keys = remember(fl) { fl.items.mapTo(HashSet()) { playback.primaryKey(it) } }
                val have = entryKeys.count { it in keys }
                val all = entries.isNotEmpty() && have >= entries.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
        NewFavListRow { name ->
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
    FavDialogBox(
        title = "收藏到…",
        subtitle = songName + "（点击收藏夹加入/移出）",
        onDismiss = onDismiss
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.height(280.dp)) {
            items(lists, key = { it.id }) { fl ->
                val has = remember(fl) { fl.items.any { playback.primaryKey(it) == key } }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
    content: @Composable ColumnScope.() -> Unit
) {
    if (onDismiss != null) {
        androidx.activity.compose.BackHandler { onDismiss() }
    }
    // 至少有一个可关闭/操作按钮时才有可聚焦子节点，可安全接管焦点
    val focusSafe = onDismiss != null || bottomBar != null
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
                // 弹层自身可聚焦并请求初始焦点（tvInitialFocus 放在 focusable 之前，
                // 使 FocusRequester 处于外层、焦点目标在其内侧，请求焦点才能命中）。
                .let { if (focusSafe) it.tvInitialFocus().focusable() else it }
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

/** 弹层内的文字按钮（统一焦点/圆角/配色）。 */
@Composable
fun DialogTextButton(
    label: String,
    onClick: () -> Unit,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
modifier = Modifier
             .clip(RoundedCornerShape(8.dp))
             .background(background)
             .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
             .clickable(onClick = onClick)
             .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(label, color = textColor, fontSize = 14.sp)
    }
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

/** 返回 + 标题头栏。用 LazyRow 替代 horizontalScroll：焦点项自动滚入可视区，
 *  标题过长也不会把可聚焦的返回键卷出屏幕（焦点陷阱）。 */
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "back") {
            Box(
                modifier = Modifier
                    .tvFocus()
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
            }
        }
        item(key = "title") {
            Text(title, fontSize = titleSize, color = MaterialTheme.colorScheme.onBackground)
        }
        if (trailing != null) {
            item(key = "trailing") {
                Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
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
            Box(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 15.sp)
            }
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
                DialogTextButton(
                    "重试",
                    onLoadMore,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            hasMore -> Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                    .clickable(onClick = onLoadMore)
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Text("加载更多", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp)
            }
            allLoadedText != null -> Text(
                allLoadedText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}