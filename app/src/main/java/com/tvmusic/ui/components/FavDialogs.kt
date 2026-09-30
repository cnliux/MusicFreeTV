package com.tvmusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.data.PlaybackStore
import com.tvmusic.utils.withPlatform
import org.json.JSONObject

/**
 * 收藏弹层家族：批量收藏 / 单曲收藏 / 收藏夹选择行。
 * 三者共用「ModalCard 骨架 + 收藏夹行 + 新建行」三层结构，收口到一处。
 */

/** 补全条目的 platform 字段（收藏/历史需要来源插件名才能回放）。
 *  实现见 [com.tvmusic.utils.withPlatform]；此处保留原函数名以兼容既有调用点。 */
fun withPlatform(o: JSONObject, plugin: String): JSONObject = o.withPlatform(plugin)

/** 收藏弹层公共骨架：经 ModalCard 统一遮罩/宽度/底栏。 */
@Composable
private fun FavDialogBox(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester? = null,
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
        TvTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f),
            placeholder = "新建收藏夹名称",
            corner = 10,
            placeholderFontSize = 13.sp
        )
        GlassButton(
            onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) { onCreate(n); name = "" }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) { _ -> Text("新建", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp) }
    }
}

/**
 * 收藏夹选择行：前导标记 + 名称 + 右侧计数。
 *
 * 背景：CollectSongsDialog（批量，"✓ " + 「已收 n/总数」）、
 * PickFavDialog（单曲，"♥"/"♡" + 「总数」）、
 * 播放页 FavAlbumDialog（多选，"✓ "/"　" + 「计数」）三处骨架完全一致，
 * 差别仅在前导标记的**字色/字号/固定宽**与右侧文案，故全部由参数显式给出——
 * 这几个值在三处原本就各不相同，靠推导会悄悄改掉其中一处的观感。
 */
@Composable
fun FavListPickRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 前导标记文案（"✓ " / "♥" / "♡" / "　"）。 */
    leading: String,
    /** 前导标记字色：主色（已收录/已全收）还是弱化色。 */
    leadingActive: Boolean,
    /** 前导标记字号。 */
    leadingFontSize: TextUnit = if (leadingActive) 18.sp else 16.sp,
    /** 前导标记固定宽（避免 ♥/♡/✓ 宽度不同导致名称列左右跳动）；null = 自然宽。 */
    leadingWidth: Dp? = null,
    /** 右侧计数文案（"（12）" / "（3/20）" / null 表示不显示）。 */
    trailing: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocus()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = leading,
            color = if (leadingActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = leadingFontSize,
            modifier = leadingWidth?.let { Modifier.width(it) } ?: Modifier
        )
        Text(name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * 全部收藏弹层：把 entries 批量加入所选收藏夹（已收录的自动去重）。
 * 收藏夹列表含「我的收藏」与全部自定义收藏夹；底部可直接新建收藏夹并加入，
 * 不必先退出到「我的歌单」页建夹。
 */
@Composable
fun CollectSongsDialog(
    entries: List<JSONObject>,
    playback: PlaybackStore,
    sheetName: String = "",
    onDismiss: () -> Unit
) {
    val lists by playback.lists.collectAsState()
    var lastMsg by remember { mutableStateOf<String?>(null) }
    // 初始焦点给第一个收藏夹行（主要交互）；列表为空时 ModalCard 自动退回容器兜底
    val firstRowFocus = remember { FocusRequester() }
    FavDialogBox(
        title = "全部收藏到…",
        subtitle = "将本页已加载的 ${entries.size} 首加入所选收藏夹（已收藏的自动跳过）",
        onDismiss = onDismiss,
        initialFocus = firstRowFocus
    ) {
        val entryKeys = remember(entries) { entries.mapTo(HashSet()) { playback.primaryKey(it) } }
        LazyColumn(modifier = Modifier.height(280.dp)) {
            itemsIndexed(lists, key = { _, it -> it.id }) { idx, fl ->
                val keys = remember(fl) { fl.items.mapTo(HashSet()) { playback.primaryKey(it) } }
                val have = entryKeys.count { it in keys }
                val all = entries.isNotEmpty() && have >= entries.size
                FavListPickRow(
                    name = fl.name,
                    modifier = Modifier.let { if (idx == 0) it.focusRequester(firstRowFocus) else it },
                    leading = if (all) "✓ " else "　",
                    // 原实现此处恒为主色（不随是否全收变化），保持不变
                    leadingActive = true,
                    leadingFontSize = 16.sp,
                    trailing = "（$have/${entries.size}）",
                    onClick = {
                        val added = playback.addAllToList(fl.id, entries)
                        lastMsg = if (added > 0) "已加入「${fl.name}」$added 首"
                        else "「${fl.name}」内已全部收藏"
                    }
                )
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
    item: JSONObject,
    playback: PlaybackStore,
    onDismiss: () -> Unit
) {
    val lists by playback.lists.collectAsState()
    val key = remember(item) { playback.primaryKey(item) }
    val songName = item.optString("title", "").ifBlank { "该曲目" }
    // 初始焦点给第一个收藏夹行（主要交互）；列表为空时 ModalCard 自动退回容器兜底
    val firstRowFocus = remember { FocusRequester() }
    FavDialogBox(
        title = "收藏到…",
        subtitle = songName + "（点击收藏夹加入/移出）",
        onDismiss = onDismiss,
        initialFocus = firstRowFocus
    ) {
        LazyColumn(modifier = Modifier.height(280.dp)) {
            itemsIndexed(lists, key = { _, it -> it.id }) { idx, fl ->
                val has = remember(fl) { fl.items.any { playback.primaryKey(it) == key } }
                FavListPickRow(
                    name = fl.name,
                    modifier = Modifier.let { if (idx == 0) it.focusRequester(firstRowFocus) else it },
                    leading = if (has) "♥" else "♡",
                    leadingActive = has,
                    leadingFontSize = 18.sp,
                    leadingWidth = 28.dp,
                    trailing = "（${fl.items.size}）",
                    onClick = { playback.toggleFavorite(item, fl.id) }
                )
            }
        }
        NewFavListRow { name ->
            playback.addList(name)?.let { id -> playback.toggleFavorite(item, id) }
        }
    }
}
