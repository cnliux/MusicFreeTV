package com.tvmusic.ui.mylist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.data.PlaybackStore
import com.tvmusic.player.PlayerManager
import com.tvmusic.player.QueueEntry
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.BackTopBar
import com.tvmusic.ui.components.DialogTextButton
import com.tvmusic.ui.components.EmptyState
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.ModalCard
import com.tvmusic.ui.components.tvFocus
import org.json.JSONObject

/** rememberSaveable 用：进程重建/转屏后仍保持"勾选了哪些条目"。 */
private val setStringSaver = Saver<Set<String>, List<String>>(
    save = { it.toList() },
    restore = { it.toSet() }
)

/** 我的列表：当前播放队列 / 播放历史 / 多个自定义收藏专辑。 */
@Composable
fun MyListScreen(
    playback: PlaybackStore,
    onBack: () -> Unit
) {
    val history by playback.history.collectAsState()
    val lists by playback.lists.collectAsState()
    val playerState by PlayerManager.screenState.collectAsState(initial = PlayerManager.uiState.value)

    // null = 播放历史；QUEUE_ID = 当前播放队列；其他 = 所选收藏专辑 id
    var selectedListId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNameDialog by rememberSaveable { mutableStateOf<String?>(null) } // null=不显示；""=新建；其他=重命名的当前名称
    // 批量管理模式：勾选条目后一次性删除（历史 / 收藏专辑通用）
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var checkedKeys by rememberSaveable(stateSaver = setStringSaver) { mutableStateOf(setOf<String>()) }

    val queue = playerState.queue
    val isQueue = selectedListId == QUEUE_ID
    val currentList = lists.firstOrNull { it.id == selectedListId }
    val list = when {
        isQueue -> emptyList()
        selectedListId == null -> history
        else -> currentList?.items ?: emptyList()
    }

    // 条目稳定 key：与 PlaybackStore 主键同规则（platform+id，缺失时回退 标题+歌手）
    fun itemKey(item: JSONObject): String {
        val platform = item.optString("platform", "")
        val id = item.optString("id", "")
        return if (id.isNotBlank()) "$platform::$id"
        else "$platform::${item.optString("title", "")}::${item.optString("artist", "")}"
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = "我的歌单", onBack = onBack)

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "__queue__") {
                FilterChip("播放列表 (${queue.size})", isQueue, onClick = { selectedListId = QUEUE_ID })
            }
            item(key = "__history__") {
                FilterChip("播放历史 (${history.size})", selectedListId == null, onClick = { selectedListId = null })
            }
            items(lists, key = { it.id }) { fl ->
                FilterChip("${fl.name} (${fl.items.size})", selectedListId == fl.id, onClick = { selectedListId = fl.id })
            }
            item(key = "__add__") {
                FilterChip("＋ 新建专辑", selected = false, onClick = { showNameDialog = "" })
            }
        }

        // 切换页签时退出批量模式，避免勾选状态跨列表残留
        androidx.compose.runtime.LaunchedEffect(selectedListId) {
            batchMode = false
            checkedKeys = emptySet()
        }

        // 播放全部 + 当前专辑操作条（队列页签没有播放全部——它本身就是队列）
        if (!isQueue && list.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!batchMode) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                            .clickable { playAll(list) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "▶ 播放全部 (${list.size})",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
                SmallAction(if (batchMode) "取消多选" else "批量删除") {
                    batchMode = !batchMode
                    checkedKeys = emptySet()
                }
                if (batchMode) {
                    SmallAction("全选") { checkedKeys = list.map(::itemKey).toSet() }
                    if (checkedKeys.isNotEmpty()) {
                        SmallAction("删除选中 (${checkedKeys.size})") {
                            val targets = list.filter { itemKey(it) in checkedKeys }
                            val lid = selectedListId
                            if (lid != null) playback.removeFromList(lid, targets)
                            else playback.removeHistory(targets)
                            batchMode = false
                            checkedKeys = emptySet()
                        }
                    }
                }
                if (!batchMode && currentList != null && currentList.id != PlaybackStore.DEFAULT_FAV_ID) {
                    SmallAction("重命名") { showNameDialog = currentList.name }
                    SmallAction("删除专辑") {
                        playback.removeList(currentList.id)
                        selectedListId = null
                    }
                }
            }
        }

        if (isQueue) {
            if (queue.isEmpty()) {
                EmptyState("当前没有播放队列，去播放一首歌或一个歌单吧")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    itemsIndexed(
                        queue,
                        // 稳定 key：用来源插件+条目 id；id 缺失（少数插件）退回索引保证唯一
key = { i, e ->
                             val id = e.raw.optString(
                                 "id",
                                 e.raw.optString("songmid", e.raw.optString("lid", ""))
                             )
                             "q-${e.plugin}-$id-$i"
                         }
                    ) { index, entry ->
                        QueueRow(
                            index = index,
                            title = entry.title,
                            artist = entry.artist,
                            plugin = entry.plugin,
                            artwork = entry.artwork,
                            isCurrent = index == playerState.queueIndex,
                            onPlay = { PlayerManager.skipTo(index) }
                        )
                    }
                }
            }
        } else if (list.isEmpty()) {
            EmptyState(
                when {
                    selectedListId == null -> "还没有播放记录，去首页搜一首歌吧"
                    else -> "这个专辑还没有歌，播放时点 ♡ 收藏到它"
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(list, key = { item ->
                    // 稳定 key：与 PlaybackStore 主键同规则（platform+id，缺失时回退 标题+歌手），
                    // 替代原先的 toString().hashCode()（Int 可碰撞，且类型与其他页面 key 不一致）；
                    // 历史记录入库时已按主键去重，列表内不会出现重复 key
                    itemKey(item)
                }) { item ->
                    val key = itemKey(item)
                    HistoryRow(
                        item = item,
                        onPlay = { if (!batchMode) playItem(item) },
                        onRemove = {
                            val lid = selectedListId
                            if (lid != null) {
                                playback.toggleFavorite(item, lid)
                            }
                        },
                        showRemove = !batchMode && selectedListId != null,
                        batchMode = batchMode,
                        checked = key in checkedKeys,
                        onToggleCheck = {
                            checkedKeys = if (key in checkedKeys) checkedKeys - key else checkedKeys + key
                        }
                    )
                }
            }
        }
    }

    // 新建 / 重命名专辑对话框
    AnimatedVisibility(
        visible = showNameDialog != null,
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f)
    ) {
        showNameDialog?.let { initial ->
            AlbumNameDialog(
                title = if (initial.isEmpty()) "新建收藏专辑" else "重命名专辑",
                initial = initial,
                onDismiss = { showNameDialog = null },
                onConfirm = { name ->
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        if (initial.isEmpty()) {
                            val id = playback.addList(trimmed)
                            if (id != null) selectedListId = id
                        } else {
                            currentList?.let { playback.renameList(it.id, trimmed) }
                        }
                    }
                    showNameDialog = null
                }
            )
        }
    }
}

private const val QUEUE_ID = "__queue__"

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tvFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
    }
}

/** 专辑命名对话框：D-pad 可聚焦输入框与按钮，系统输入法输入名称。 */
@Composable
fun AlbumNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    ModalCard(
        title = title,
        width = 480.dp,
        onDismiss = onDismiss,
        bottomBar = {
            DialogTextButton(
                "确定",
                onClick = { onConfirm(text) },
                background = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onPrimary
            )
            DialogTextButton("取消", onClick = onDismiss)
        }
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text("专辑名称", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                }
                inner()
            }
        )
    }
}

@Composable
private fun QueueRow(
    index: Int,
    title: String,
    artist: String,
    plugin: String,
    artwork: String,
    isCurrent: Boolean,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .tvFocus(shapeOverride = RoundedCornerShape(10.dp))
            .clickable(onClick = onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.width(36.dp)
        )
        Artwork(artwork, Modifier.size(44.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                title, fontSize = 15.sp,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "$artist · $plugin",
                fontSize = 12.sp,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (isCurrent) {
            Text("正在播放", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HistoryRow(
    item: JSONObject,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    showRemove: Boolean,
    batchMode: Boolean = false,
    checked: Boolean = false,
    onToggleCheck: () -> Unit = {}
) {
    val title = item.optString("title", "未知")
    val artist = item.optString("artist", "")
    val plugin = item.optString("platform", "")
    val artwork = item.optString("artwork", "").ifBlank { item.optString("coverImg", "") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .tvFocus(shapeOverride = RoundedCornerShape(10.dp))
            .clickable(onClick = if (batchMode) onToggleCheck else onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (batchMode) {
            Text(
                if (checked) "☑" else "☐",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        Artwork(artwork, Modifier.size(48.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$artist · $plugin",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showRemove) {
            Box(
                modifier = Modifier
                    .tvFocus()
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("移除", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }
}

/** 播放单首：把该条目作为单元素队列送入 PlayerManager。 */
private fun playItem(item: JSONObject) {
    val plugin = item.optString("platform", "")
    if (plugin.isBlank()) return
    val entry = QueueEntry(plugin, item)
    PlayerManager.play(plugin, entry, listOf(entry), 0)
}

/** 播放整个列表：所有有效条目组成队列，从第一首开始播。 */
private fun playAll(items: List<JSONObject>) {
    val entries = items.mapNotNull { item ->
        val plugin = item.optString("platform", "")
        if (plugin.isBlank()) null else QueueEntry(plugin, item)
    }
    if (entries.isEmpty()) return
    PlayerManager.play(entries[0].plugin, entries[0], entries, 0)
}
