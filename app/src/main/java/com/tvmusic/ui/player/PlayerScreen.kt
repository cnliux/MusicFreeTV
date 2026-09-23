package com.tvmusic.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.player.LrcLine
import com.tvmusic.player.PlayMode
import com.tvmusic.player.PlayerManager
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.tvFocus

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val state by PlayerManager.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback = com.tvmusic.core.TvMusicApp.from(context).playback
    val lists by playback.lists.collectAsState()
    var showFavDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showNameDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // 背景：模糊封面 + 暗色蒙层
        if (state.current != null) {
            Artwork(
                state.current!!.artwork,
                Modifier.fillMaxSize().blur(80.dp).scale(1.2f)
            )
            Box(Modifier.fillMaxSize().background(Color(0xCC0B0D12)))
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)))
        }

        if (state.current == null) {
            Text(
                "暂无播放",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {

            // 左：旋转黑胶 + 逐行歌词
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
            ) {
                RotatingVinyl(
                    artwork = state.current!!.artwork,
                    isPlaying = state.isPlaying,
                    size = 320.dp
                )
                LyricScroll(
                    lines = state.lrcLines,
                    currentIndex = state.lrcIndex,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            }

            // 右：信息 + 进度 + 控制
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = state.current!!.title,
                    fontSize = 36.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${state.current!!.artist} · ${state.current!!.album}",
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                if (state.buffering) {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("缓冲中…", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                state.error?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                }

                SeekBar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    modifier = Modifier.fillMaxWidth().padding(top = 42.dp),
                    onSeek = { PlayerManager.seek(it) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(format(state.positionMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(format(state.durationMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 主控制：上一曲 / 播放暂停 / 下一曲（居中大圆按钮）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 34.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundCtrlButton("⏮", size = 64.dp, iconSize = 26.sp, filled = false) { PlayerManager.prev() }
                    Spacer(Modifier.width(28.dp))
                    // 播放/暂停：主色实心大圆
                    RoundCtrlButton(if (state.isPlaying) "⏸" else "▶", size = 88.dp, iconSize = 36.sp, filled = true) { PlayerManager.playPause() }
                    Spacer(Modifier.width(28.dp))
                    RoundCtrlButton("⏭", size = 64.dp, iconSize = 26.sp, filled = false) { PlayerManager.next() }
                }

                // 次控制：收藏 / 播放模式 / 快退 / 快进
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerButton(if (state.isFavorite) "♥ 已收藏" else "♡ 收藏") { showFavDialog = true }
                    PlayerButton(playModeLabel(state.playMode)) { PlayerManager.cyclePlayMode() }
                    PlayerButton("⏪ 30s") { PlayerManager.seek(state.positionMs - 30_000) }
                    PlayerButton("30s ⏩") { PlayerManager.seek(state.positionMs + 30_000) }
                }

                Row(Modifier.padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .tvFocus()
                            .clickable(onClick = onBack)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    ) { Text("返回", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp) }
                }
            }
        }

        // 收藏到哪个专辑
        if (showFavDialog) {
            val entry = state.current
            FavAlbumDialog(
                lists = lists,
                inLists = entry?.let { playback.favoriteListsOf(it.raw) } ?: emptySet(),
                onDismiss = { showFavDialog = false },
                onToggle = { id -> entry?.let { playback.toggleFavorite(it.raw, id) } },
                onNewAlbum = { showNameDialog = true }
            )
        }
        if (showNameDialog) {
            com.tvmusic.ui.mylist.AlbumNameDialog(
                title = "新建收藏专辑",
                initial = "",
                onDismiss = { showNameDialog = false },
                onConfirm = { name ->
                    val id = playback.addList(name)
                    showNameDialog = false
                    if (id != null) {
                        state.current?.let { playback.toggleFavorite(it.raw, id) }
                        showFavDialog = true
                    }
                }
            )
        }
    }
}

/** 旋转黑胶：播放时匀速旋转，暂停时停住。 */
@Composable
private fun RotatingVinyl(artwork: String, isPlaying: Boolean, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "vinyl")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylAngle"
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        // 黑胶外圈纹理
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF1A1A1A))
        )
        // 封面（旋转）
        Box(
            modifier = Modifier
                .size(size * 0.78f)
                .clip(CircleShape)
                .rotate(if (isPlaying) angle else 0f)
        ) {
            Artwork(artwork, Modifier.fillMaxSize())
        }
        // 中心孔
        Box(
            modifier = Modifier
                .size(size * 0.12f)
                .clip(CircleShape)
                .background(Color(0xFF2A2A2A))
        )
    }
}

/** 逐行滚动歌词：当前行高亮并居中，其余行渐隐。 */
@Composable
private fun LyricScroll(lines: List<LrcLine>, currentIndex: Int, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in lines.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        item { Spacer(Modifier.height(80.dp)) }
        items(lines.size) { i ->
            val isCurrent = i == currentIndex
            Text(
                text = lines[i].text,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontSize = if (isCurrent) 22.sp else 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 24.dp)
                    .graphicsLayer {
                        alpha = if (isCurrent) 1f else 0.55f
                    }
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** 选择收藏专辑的弹层：列出全部专辑，点击切换收藏状态。 */
@Composable
private fun FavAlbumDialog(
    lists: List<com.tvmusic.data.FavList>,
    inLists: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
    onNewAlbum: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("收藏到…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            LazyColumn(modifier = Modifier.height(260.dp)) {
                items(lists) { fl ->
                    val inIt = fl.id in inLists
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocus()
                            .clickable { onToggle(fl.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (inIt) "✓ " else "　", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                        Text(fl.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("（${fl.items.size}）", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .tvFocus()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onNewAlbum)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) { Text("＋ 新建专辑", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp) }
                Box(
                    modifier = Modifier
                        .tvFocus()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) { Text("关闭", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun PlayerButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .tvFocus(1.06f)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
    }
}

/** 圆形控制按钮：filled=主色实心（播放/暂停），否则半透明白底。 */
@Composable
private fun RoundCtrlButton(
    symbol: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.TextUnit,
    filled: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "ctrlScale")
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (filled) primary else Color(0x22FFFFFF))
            .drawBehind { if (focused) drawRect(primary.copy(alpha = 0.3f)) }
            .border(if (focused) 2.dp else 0.dp, primary, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = iconSize
        )
    }
}

/**
 * 可拖动进度条：触摸/鼠标按住拖动或点按快进快退；
 * 遥控器聚焦后 左右=±10s、上下=±60s。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0x14FFFFFF))
            .drawBehind { if (focused) drawRect(primary.copy(alpha = 0.25f)) }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (durationMs <= 0) return@onKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionRight -> 10_000L
                    Key.DirectionLeft -> -10_000L
                    Key.DirectionUp -> 60_000L
                    Key.DirectionDown -> -60_000L
                    else -> return@onKeyEvent false
                }
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) {
                    onSeek((positionMs + delta).coerceIn(0L, durationMs))
                }
                true
            }
            .pointerInput(durationMs) {
                detectTapGestures { off ->
                    if (durationMs > 0) {
                        onSeek(((off.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> dragFraction = (off.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        val f = dragFraction
                        dragFraction = null
                        if (f != null && durationMs > 0) onSeek((f * durationMs).toLong())
                    },
                    onDragCancel = { dragFraction = null }
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    ) {
        val barWidth = maxWidth
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(primary)
        )
        Box(
            Modifier
                .size(16.dp)
                .align(Alignment.CenterStart)
                .offset(x = barWidth * fraction - 8.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

private fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.ORDER -> "🔁 顺序"
    PlayMode.LOOP_ONE -> "🔂 单曲循环"
    PlayMode.SHUFFLE -> "🔀 随机"
}

private fun format(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
