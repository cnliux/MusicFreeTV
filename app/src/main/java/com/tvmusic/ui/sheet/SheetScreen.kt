package com.tvmusic.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.core.TvMusicApp
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.BackTopBar
import com.tvmusic.ui.components.CollectSongsDialog
import com.tvmusic.ui.components.DialogTextButton
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.LoadMoreFooter
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MusicRow
import com.tvmusic.ui.components.PickFavDialog
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.components.withPlatform
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
    val favorites by playback.favorites.collectAsState()
    // 收藏主键集合（任一收藏变化时重算），供行级 ♡ 状态判断
    val favKeys = remember(favorites) { playback.favoriteKeys() }
    // 收藏用条目：补全 platform 字段，保证收藏后能在"我的歌单"里正常回放
    val savableEntries = remember(entries) {
        entries.map { withPlatform(it, viewModel.pluginName) }
    }
    var showCollectAll by remember { mutableStateOf(false) }
    // 单曲收藏弹层目标：null 表示未打开；点击行尾 ♡ 时填入待收藏曲目
    var pickFavItem by remember { mutableStateOf<JSONObject?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(
            title = title.ifBlank { "详情" },
            onBack = onBack,
            titleSize = 24.sp,
            trailing = {
                if (entries.isNotEmpty()) {
                    DialogTextButton(
                        "▶ 播放全部",
                        onClick = viewModel::playAll,
                        background = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimary
                    )
                    DialogTextButton("♡ 全部收藏", onClick = { showCollectAll = true })
                }
            }
        )

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
                    val key = playback.primaryKey(item)
                    MusicRow(
                        index = index,
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        onClick = { viewModel.play(index) },
                        trailing = {
                            // 单曲收藏：弹出收藏夹选择（可加入任意自定义收藏夹）
                            val fav = key in favKeys
                            Box(
                                modifier = Modifier
                                    .tvFocus()
                                    .clickable { pickFavItem = savableEntries[index] }
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
                    LoadMoreFooter(
                        loading = loadingMore,
                        hasMore = hasMore,
                        error = error,
                        allLoadedText = if (entries.isNotEmpty()) "— 已加载全部 ${entries.size} 首 —" else null,
                        onLoadMore = viewModel::loadMore
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showCollectAll,
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f)
    ) {
        CollectSongsDialog(
            entries = savableEntries,
            playback = playback,
            onDismiss = { showCollectAll = false }
        )
    }
    AnimatedVisibility(
        visible = pickFavItem != null,
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f)
    ) {
        pickFavItem?.let { item ->
            PickFavDialog(
                item = item,
                playback = playback,
                onDismiss = { pickFavItem = null }
            )
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