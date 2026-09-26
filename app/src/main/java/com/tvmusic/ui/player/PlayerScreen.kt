package com.tvmusic.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.tvmusic.player.PlayMode
import com.tvmusic.player.PlayerManager
import com.tvmusic.player.PlayerUiState
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.DialogTextButton
import com.tvmusic.ui.components.LyricLineBlock
import com.tvmusic.ui.components.ModalCard
import com.tvmusic.ui.components.tvFocus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    // 订阅结构性状态（不含每秒刷新的进度/歌词），ticker 不再触发整屏重组；
    // 进度条与歌词各自内部再订阅 uiState，重组范围被限定在各自子树。
    val state by PlayerManager.screenState.collectAsState(initial = PlayerManager.uiState.value)
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback = com.tvmusic.core.TvMusicApp.from(context).playback
    val lists by playback.lists.collectAsState()
    var showFavDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showNameDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showSleepDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showEqDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val cfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()

    Box(Modifier.fillMaxSize()) {
        // 背景：模糊封面 + 暗色蒙层
        if (state.current != null) {
            Artwork(
                state.current!!.artwork,
                Modifier.fillMaxSize().blur(40.dp).scale(1.2f)
            )
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f)))
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }

        // 视频渲染层：当前条目含视频轨时全屏显示（控件仍浮在其上）
        if (state.isVideo) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false // TV 上用应用自己的 d-pad 控制条
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { it.player = PlayerManager.player },
                onRelease = { it.player = null }
            )
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

        // 瞬时提示（lrc.cx 兜底歌词/封面进行中或结果），5 秒后播放器自动清除
        state.metaNotice?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 上：左大封面（圆角+阴影） + 右逐行歌词
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    val tokens = com.tvmusic.ui.theme.LocalThemeTokens.current
                    // 视频模式隐藏大封面：画面已全屏，封面只会在视频上挡视线
                    if (!state.isVideo) {
                        Box(
                            modifier = Modifier
                                .size(340.dp)
                                .graphicsLayer {
                                    shadowElevation = 26.dp.toPx()
                                    clip = true
                                    shape = RoundedCornerShape(tokens.radius * 2)
                                }
                                .tvFocus(1.03f, shapeOverride = RoundedCornerShape(tokens.radius * 2))
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
                    }
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
                        // 逐行歌词（内嵌于播放页布局，非悬浮层；悬浮层仅用于非播放页）
                        // 视频模式不渲染歌词：画面与歌词叠加不可读
                        if (!state.isVideo) {
                            Spacer(Modifier.height(20.dp))
                            PlayerLyricLines(modifier = Modifier.fillMaxWidth().weight(1f))
                        } else {
                            Spacer(Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }

                // 下：控制条（进度 + 时间 + 全部控制按钮）；进度每秒刷新只在卡片内部重组
                ProgressSection(onSeek = { PlayerManager.seek(it) })
                // 按键分三组：状态（收藏/循环）· 主控（快退/上下首/播放/快进）· 功能（倍速/定时/音效/返回），
                // 组内紧凑、组间留大间距，d-pad 焦点沿行序自然移动
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态组
                    RoundCtrlButton(
                        if (state.isFavorite) "♥" else "♡",
                        size = 52.dp, iconSize = 22.sp, filled = false,
                        desc = "收藏",
                        onClick = { showFavDialog = true }
                    )
                    RoundCtrlButton(
                        playModeIcon(state.playMode), size = 52.dp, iconSize = 20.sp, filled = false,
                        desc = "循环模式"
                    ) {
                        PlayerManager.cyclePlayMode()
                    }
                    Spacer(Modifier.width(36.dp))
                    // 主控组
                    RoundCtrlButton("⏪", size = 52.dp, iconSize = 20.sp, filled = false, desc = "快退30秒") {
                        PlayerManager.seekRelative(-30_000)
                    }
                    RoundCtrlButton("⏮", size = 60.dp, iconSize = 26.sp, filled = false, desc = "上一首") { PlayerManager.prev() }
                    RoundCtrlButton(
                        if (state.isPlaying) "⏸" else "▶",
                        size = 84.dp, iconSize = 34.sp, filled = true,
                        desc = if (state.isPlaying) "暂停" else "播放"
                    ) { PlayerManager.playPause() }
                    RoundCtrlButton("⏭", size = 60.dp, iconSize = 26.sp, filled = false, desc = "下一首") { PlayerManager.next() }
                    RoundCtrlButton("⏩", size = 52.dp, iconSize = 20.sp, filled = false, desc = "快进30秒") {
                        PlayerManager.seekRelative(30_000)
                    }
                    Spacer(Modifier.width(36.dp))
                    // 功能组
                    // 倍速：点击循环 0.75/1/1.25/1.5/2.0，非 1x 时高亮
                    RoundCtrlButton(
                        speedLabel(state.speed),
                        size = 52.dp,
                        iconSize = 14.sp,
                        filled = state.speed != 1f,
                        desc = "倍速"
                    ) { PlayerManager.cycleSpeed() }
                    // 定时关闭：启用时显示剩余分钟
                    RoundCtrlButton(
                        if (state.sleepRemainingMs > 0) "${state.sleepRemainingMs / 60_000}m" else "🌙",
                        size = 52.dp,
                        iconSize = 16.sp,
                        filled = state.sleepRemainingMs > 0,
                        desc = "定时关闭"
                    ) { showSleepDialog = true }
                    // 音效：均衡器 / 低音增强
                    RoundCtrlButton(
                        "音效",
                        size = 52.dp,
                        iconSize = 14.sp,
                        filled = state.eqEnabled,
                        desc = "音效设置"
                    ) { showEqDialog = true }
                    RoundCtrlButton("↩", size = 52.dp, iconSize = 22.sp, filled = false, desc = "返回", onClick = onBack)
                }
            }
        }

        // 收藏到哪个专辑
        AnimatedVisibility(
            visible = showFavDialog,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            val entry = state.current
            FavAlbumDialog(
                lists = lists,
                inLists = entry?.let { playback.favoriteListsOf(it.raw) } ?: emptySet(),
                onDismiss = { showFavDialog = false },
                onToggle = { id -> entry?.let { playback.toggleFavorite(it.raw, id) } },
                onNewAlbum = { showNameDialog = true }
            )
        }
        AnimatedVisibility(
            visible = showSleepDialog,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            SleepTimerDialog(
                currentMinutes = if (state.sleepRemainingMs > 0) {
                    ((state.sleepRemainingMs + 59_999) / 60_000).toInt()
                } else 0,
                onDismiss = { showSleepDialog = false },
                onSelect = { minutes ->
                    PlayerManager.setSleepTimer(minutes)
                    showSleepDialog = false
                }
            )
        }
        AnimatedVisibility(
            visible = showEqDialog,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            EqDialog(state = state, onDismiss = { showEqDialog = false })
        }
        AnimatedVisibility(
            visible = showNameDialog,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
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

/** 播放页内嵌逐行歌词：跟随当前行滚动，当前行高亮。 */
@Composable
private fun PlayerLyricLines(modifier: Modifier = Modifier) {
    // 秒级订阅限制在本子树：ticker 刷新歌词行/当前行时不波及播放页外壳
    val st by PlayerManager.uiState.collectAsState()
    val lines = st.lrcLines
    val currentIndex = st.lrcIndex
    val cfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()
    val lrcColor = com.tvmusic.ui.theme.LyricSettings.parseColor()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(currentIndex) {
        // 列表前有一个 60.dp Spacer 占位（index 0），歌词行实际从 index 1 开始
        if (currentIndex in lines.indices) {
            listState.animateScrollToItem(currentIndex + 1)
        }
    }
    if (lines.isEmpty()) {
        Text("暂无歌词", color = lrcColor.copy(alpha = 0.45f), fontSize = 15.sp)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Spacer(Modifier.height(60.dp)) }
        itemsIndexed(lines, key = { i, line -> "${line.timeMs}_$i" }) { i, line ->
            val isCurrent = i == currentIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .graphicsLayer { alpha = if (isCurrent) 1f else 0.6f }
            ) {
                // 主文案 + 译文与悬浮歌词共用同一渲染（LyricLineBlock）
                LyricLineBlock(line, isCurrent, lrcColor, cfg.fontSizeSp.toFloat())
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
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
    ModalCard(title = "收藏到…", onDismiss = onDismiss, bottomBar = {
        DialogTextButton(
            "＋ 新建专辑",
            onNewAlbum,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        DialogTextButton("关闭", onDismiss)
    }) {
        LazyColumn(modifier = Modifier.height(260.dp)) {
            items(lists, key = { it.id }) { fl ->
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
    }
}

/** 定时关闭选择弹层：关闭 / 15 / 30 / 45 / 60 / 90 分钟。 */
@Composable
private fun SleepTimerDialog(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val options = listOf(0 to "不开启", 15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", 90 to "90 分钟")
    ModalCard(
        title = "定时关闭",
        width = 360.dp,
        onDismiss = onDismiss,
        bottomBar = {
            Spacer(Modifier.weight(1f))
            DialogTextButton("关闭", onDismiss)
        }
    ) {
        options.forEach { (minutes, label) ->
            val active = minutes == currentMinutes || (minutes == 0 && currentMinutes == 0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(minutes) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (active) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
            }
        }
    }
}

/** 倍速按钮文案：1x 显示"倍速"，其余显示如"1.25x"。 */
private fun speedLabel(speed: Float): String {
    if (kotlin.math.abs(speed - 1f) < 0.01f) return "倍速"
    val s = speed.toString().trimEnd('0').trimEnd('.')
    return "${s}x"
}

/**
 * 音效弹层：均衡器开关 + 预设选择 + 低音增强强度（10 档步进）。
 * 全部 d-pad 可达：预设为可点行，低音用 − / ＋ 按钮而非滑杆（遥控器友好）。
 */
@Composable
private fun EqDialog(state: PlayerUiState, onDismiss: () -> Unit) {
    val presets by PlayerManager.eqPresets.collectAsState()
    ModalCard(
        title = "音效（均衡器 / 低音增强）",
        onDismiss = onDismiss,
        bottomBar = {
            Spacer(Modifier.weight(1f))
            DialogTextButton("关闭", onDismiss)
        }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.Switch(
                checked = state.eqEnabled,
                onCheckedChange = { PlayerManager.setEqEnabled(it) },
                modifier = Modifier.tvFocus()
            )
        }
        if (state.eqEnabled) {
            Text("均衡器预设", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            presets.forEachIndexed { idx, name ->
                val active = idx == state.eqPreset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { PlayerManager.setEqPreset(idx) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        name,
                        fontSize = 14.sp,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (active) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
            }
            // 低音增强：0~100%，10% 步进
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("低音增强", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqStepButton("－", desc = "减少低音") { PlayerManager.setBassStrength(state.bassStrength - 100) }
                    Text(
                        "${state.bassStrength / 10}%",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(52.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    EqStepButton("＋", desc = "增加低音") { PlayerManager.setBassStrength(state.bassStrength + 100) }
                }
            }
        }
    }
}

/** 音效弹层的步进小按钮（低音 −/＋）。 */
@Composable
private fun EqStepButton(label: String, desc: String, onClick: () -> Unit) {
Box(
         modifier = Modifier
             .clip(RoundedCornerShape(8.dp))
             .background(MaterialTheme.colorScheme.surfaceVariant)
             .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
             .clickable(onClick = onClick)
             .padding(horizontal = 14.dp, vertical = 6.dp)
             .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) { Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp) }
}

/** 圆形控制按钮：filled=主色实心（播放/暂停），否则半透明白底；焦点样式走统一 tvFocus。 */
@Composable
private fun RoundCtrlButton(
    symbol: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.TextUnit,
    filled: Boolean,
    desc: String = symbol,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) MaterialTheme.colorScheme.primary else Color(0x22FFFFFF))
            .tvFocus(circle = true)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = iconSize
        )
    }
}

/** 进度区（进度条 + 时间）：秒级订阅限定在本子树，外壳不被每秒 ticker 重组。 */
@Composable
private fun ProgressSection(onSeek: (Long) -> Unit) {
    val st by PlayerManager.uiState.collectAsState()
    SeekBar(
        positionMs = st.positionMs,
        durationMs = st.durationMs,
        bufferedPositionMs = st.bufferedPositionMs,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        onSeek = onSeek
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(format(st.positionMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(format(st.durationMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 可拖动进度条：触摸/鼠标按住拖动或点按快进快退；
 * 遥控器聚焦后 左右=±10s、上下=±60s（上下放行给焦点系统避免焦点陷阱）。
 * 缓冲段以半透明主题色绘制在已播放段之下。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    // 遥控快进退的本地基准：positionMs prop 最旧 1 秒一拍，1 秒内连按多键会都基于
    // 同一个旧值而互相覆盖。手动 seek 后以本地值累加，播放器状态追上后（prop 更新）清除
    var manualPos by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(positionMs, durationMs) { if (manualPos != null) return@LaunchedEffect; manualPos = null }
    val effectivePos = manualPos ?: positionMs
    val fraction = dragFraction
        ?: if (durationMs > 0) (effectivePos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction =
        if (durationMs > 0) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0x14FFFFFF))
            .drawBehind { if (focused) drawRect(primary.copy(alpha = 0.25f)) }
            // 左右各让出一个半拇指位：手柄在两端不会溢出被外层 clip 裁掉，
            // 且 tap/拖拽映射到同一内宽，点按位置与手柄中心一致
            .padding(horizontal = 8.dp)
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
                    val target = (effectivePos + delta).coerceIn(0L, durationMs)
                    manualPos = target
                    onSeek(target)
                }
                true
            }
            // 单输入节点内并行跑 tap 与横向拖拽两个手势，避免重复输入通道
            .pointerInput(durationMs) {
                coroutineScope {
                    launch {
                        detectTapGestures { off ->
                            if (durationMs > 0) {
                                val target = ((off.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()
                                manualPos = target
                                onSeek(target)
                            }
                        }
                    }
                    launch {
                        detectHorizontalDragGestures(
                            onDragStart = { off -> dragFraction = (off.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                val f = dragFraction
                                dragFraction = null
                                if (f != null && durationMs > 0) {
                                    val target = (f * durationMs).toLong()
                                    manualPos = target
                                    onSeek(target)
                                }
                            },
                            onDragCancel = { dragFraction = null }
                        ) { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                }
            }
    ) {
        val barWidth = maxWidth - 16.dp // 手柄在两端的半圆不会溢出到侧 padding 之外
        // 缓冲段：半透明主题色
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(bufferedFraction)
                .height(6.dp)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(primary.copy(alpha = 0.35f))
        )
        // 已播放段：主题色实心
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(primary)
        )
        // 手柄：中心对准进度点，两端因外框 padding 不会出界
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
