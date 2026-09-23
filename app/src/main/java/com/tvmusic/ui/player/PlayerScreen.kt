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

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 上：左大封面（圆角+阴影） + 右逐行歌词
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
                    Box(
                        modifier = Modifier
                            .size(340.dp)
                            .graphicsLayer {
                                shadowElevation = 26.dp.toPx()
                                clip = true
                                shape = RoundedCornerShape(tokens.radius * 2)
                            }
                            .tvFocus(1.03f)
                            .clickable(onClick = onBack)
                    ) {
                        Artwork(state.current!!.artwork, Modifier.fillMaxSize())
                        if (state.buffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(36.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.current!!.title,
                            fontSize = 30.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.current!!.artist} · ${state.current!!.album}",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.error?.let {
                            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }

                // 下：控制条（进度 + 时间 + 全部控制按钮）
                SeekBar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    onSeek = { PlayerManager.seek(it) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(format(state.positionMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(format(state.durationMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 收藏
                    RoundCtrlButton(
                        if (state.isFavorite) "♥" else "♡",
                        size = 52.dp, iconSize = 22.sp, filled = false,
                        onClick = { showFavDialog = true }
                    )
                    Spacer(Modifier.width(22.dp))
                    RoundCtrlButton("⏪", size = 52.dp, iconSize = 20.sp, filled = false) {
                        PlayerManager.seek(state.positionMs - 30_000)
                    }
                    Spacer(Modifier.width(22.dp))
                    RoundCtrlButton("⏮", size = 60.dp, iconSize = 26.sp, filled = false) { PlayerManager.prev() }
                    Spacer(Modifier.width(26.dp))
                    RoundCtrlButton(if (state.isPlaying) "⏸" else "▶", size = 84.dp, iconSize = 34.sp, filled = true) { PlayerManager.playPause() }
                    Spacer(Modifier.width(26.dp))
                    RoundCtrlButton("⏭", size = 60.dp, iconSize = 26.sp, filled = false) { PlayerManager.next() }
                    Spacer(Modifier.width(22.dp))
                    RoundCtrlButton("⏩", size = 52.dp, iconSize = 20.sp, filled = false) {
                        PlayerManager.seek(state.positionMs + 30_000)
                    }
                    Spacer(Modifier.width(22.dp))
                    RoundCtrlButton(playModeIcon(state.playMode), size = 52.dp, iconSize = 20.sp, filled = false) {
                        PlayerManager.cyclePlayMode()
                    }
                    Spacer(Modifier.width(22.dp))
                    RoundCtrlButton("↩", size = 52.dp, iconSize = 22.sp, filled = false, onClick = onBack)
                }
            }
        }

        // 悬浮歌词层：叠加在最上层，位置/字号/颜色由歌词设置控制
        LyricOverlay(
            lines = state.lrcLines,
            currentIndex = state.lrcIndex
        )

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

/**
 * 悬浮歌词层：叠加在播放页最上层，不拦截焦点。
 * 位置（顶部/居中/底部 + 垂直微调）与字号/颜色由歌词设置控制。
 */
@Composable
private fun LyricOverlay(lines: List<LrcLine>, currentIndex: Int) {
    val cfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()
    if (!cfg.enabled) return
    val lrcColor = com.tvmusic.ui.theme.LyricSettings.parseColor()
    val listState = rememberLazyListState()
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
            // 底部留出控制条区域，避免歌词悬浮层盖住进度条和按钮
            .padding(start = 64.dp, end = 64.dp, top = 24.dp, bottom = 220.dp)
            .offset { androidx.compose.ui.unit.IntOffset(0, with(density) { cfg.offsetY.dp.roundToPx() }) },
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
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxWidth().height(300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item { Spacer(Modifier.height(120.dp)) }
                items(lines.size) { i ->
                    val isCurrent = i == currentIndex
                    Text(
                        text = lines[i].text,
                        color = if (isCurrent) lrcColor else lrcColor.copy(alpha = 0.35f),
                        fontSize = if (isCurrent) (cfg.fontSizeSp + 4).sp else cfg.fontSizeSp.sp,
                        fontWeight = if (isCurrent)
                            androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .graphicsLayer {
                                alpha = if (isCurrent) 1f else 0.6f
                            }
                    )
                }
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
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

/** 圆形控制按钮：filled=主色实心（播放/暂停），否则半透明白底；焦点样式跟随主题 Token。 */
@Composable
private fun RoundCtrlButton(
    symbol: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.TextUnit,
    filled: Boolean,
    onClick: () -> Unit
) {
    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) tokens.focusScale else 1f, label = "ctrlScale")
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = if (tokens.focusBrightnessOnly) (if (focused) 1f else 0.62f) else 1f
                if (tokens.focusGlow > 0.dp) {
                    shadowElevation = if (focused) tokens.focusGlow.toPx() else 0f
                    ambientShadowColor = primary
                    spotShadowColor = primary
                }
            }
            .clip(CircleShape)
            .background(if (filled) primary else Color(0x22FFFFFF))
            .drawBehind { if (focused) drawRect(primary.copy(alpha = 0.3f)) }
            .let { if (focused) it.border(3.dp, tokens.focusBorder, CircleShape) else it }
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
                // 只消费左右键做 ±10s 微调；上下键放行给焦点系统，
                // 否则焦点会卡在进度条上无法移动到下方控制按钮（焦点陷阱）。
                val delta = when (event.key) {
                    Key.DirectionRight -> 10_000L
                    Key.DirectionLeft -> -10_000L
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

private fun playModeIcon(mode: PlayMode): String = when (mode) {
    PlayMode.ORDER -> "🔁"
    PlayMode.LOOP_ONE -> "🔂"
    PlayMode.SHUFFLE -> "🔀"
}

private fun format(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
