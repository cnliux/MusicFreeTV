package com.tvmusic.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.core.TvMusicApp
import com.tvmusic.data.FavList
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MusicRow
import com.tvmusic.ui.components.tvFocus
import org.json.JSONObject

@Composable
fun SheetScreen(
    viewModel: SheetViewModel,
    onBack: () -> Unit
) {
    val title by viewModel.title.collectAsState()
    val artwork by viewModel.artwork.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val error by viewModel.error.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    val playback = TvMusicApp.from(LocalContext.current).playback
    val lists by playback.lists.collectAsState()
    val favorites by playback.favorites.collectAsState()
    // 收藏主键集合（任一收藏变化时重算），供行级 ♡ 状态判断
    val favKeys = remember(favorites) { playback.favoriteKeys() }
    // 收藏用条目：补全 platform 字段，保证收藏后能在"我的歌单"里正常回放
    val savableEntries = remember(entries) {
        entries.map { withPlatform(it, viewModel.pluginName) }
    }
    var showCollectAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .tvFocus()
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            }
            Text(
                text = title.ifBlank { "详情" },
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(16.dp))
            if (entries.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                        .clickable(onClick = viewModel::playAll)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        "▶ 播放全部",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                        .clickable { showCollectAll = true }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        "♡ 全部收藏",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }
            }
        }

        when {
            loading -> LoadingBox()
            error != null && entries.isEmpty() -> ErrorBox(error, onRetry = viewModel::retry)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") {
                    DetailHeader(
                        artwork = artwork,
                        title = title,
                        count = entries.size
                    )
                }
                itemsIndexed(entries, key = { i, item ->
                    "${item.optString("platform")}-${item.optString("id")}-$i"
                }) { index, item ->
                    val key = favoriteKey(item)
                    MusicRow(
                        index = index,
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        onClick = { viewModel.play(index) },
                        trailing = {
                            // 单独收藏：♡/♥ 切换到默认专辑「我的收藏」
                            val fav = key in favKeys
                            Box(
                                modifier = Modifier
                                    .tvFocus()
                                    .clickable {
                                        playback.toggleFavorite(savableEntries[index])
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (fav) "♥" else "♡",
                                    fontSize = 18.sp,
                                    color = if (fav) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
                item(key = "footer") {
                    ListFooter(
                        count = entries.size,
                        hasMore = hasMore,
                        loadingMore = loadingMore,
                        error = error,
                        onLoadMore = viewModel::loadMore
                    )
                }
            }
        }
    }

    if (showCollectAll) {
        CollectAllDialog(
            entries = savableEntries,
            lists = lists,
            onCollect = { listId ->
                val added = playback.addAllToList(listId, savableEntries)
                added
            },
            onDismiss = { showCollectAll = false }
        )
    }
}

/** 补全条目的 platform 字段（收藏/历史需要来源插件名才能回放）。 */
private fun withPlatform(o: JSONObject, plugin: String): JSONObject =
    if (o.optString("platform").isNotBlank() || plugin.isBlank()) o
    else JSONObject(o.toString()).put("platform", plugin)

/** 与 PlaybackStore 主键同规则：platform::id，缺失时回退 platform::标题::歌手。 */
private fun favoriteKey(o: JSONObject): String {
    val platform = o.optString("platform", "")
    val id = o.optString("id", "")
    return if (id.isNotBlank()) "$platform::$id"
    else "$platform::${o.optString("title", "")}::${o.optString("artist", "")}"
}

/**
 * 全部收藏弹层：点击任一专辑，把当前歌单已加载的全部曲目加入该专辑。
 * 单独收藏（默认「我的收藏」）与加入其他收藏夹共用一个入口；已收藏的曲目自动去重。
 */
@Composable
private fun CollectAllDialog(
    entries: List<JSONObject>,
    lists: List<FavList>,
    onCollect: (String) -> Int,
    onDismiss: () -> Unit
) {
    var lastMsg by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("全部收藏到…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "将本页已加载的 ${entries.size} 首加入所选收藏夹（已收藏的自动跳过）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(modifier = Modifier.height(280.dp)) {
                items(lists, key = { it.id }) { fl ->
                    val keys = remember(fl) { fl.items.map { favoriteKey(it) }.toSet() }
                    val have = entries.count { favoriteKey(it) in keys }
                    val all = have >= entries.size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocus()
                            .clickable {
                                val added = onCollect(fl.id)
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
            lastMsg?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
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
private fun DetailHeader(artwork: String, title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Artwork(artwork, Modifier.size(96.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title.ifBlank { "详情" },
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "共 $count 首",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ListFooter(
    count: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loadingMore -> Text(
                "加载中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .tvFocus(shapeOverride = RoundedCornerShape(6.dp))
                        .clickable(onClick = onLoadMore)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("重试", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                }
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
            count > 0 -> Text(
                "— 已加载全部 $count 首 —",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
