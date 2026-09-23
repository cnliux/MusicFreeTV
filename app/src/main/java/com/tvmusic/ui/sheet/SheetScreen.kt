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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MusicRow
import com.tvmusic.ui.components.tvFocus

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
                        .tvFocus()
                        .clickable(onClick = viewModel::playAll)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        "▶ 播放全部",
                        color = MaterialTheme.colorScheme.onPrimary,
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
                    MusicRow(
                        index = index,
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        onClick = { viewModel.play(index) }
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
                        .tvFocus()
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
                    .tvFocus()
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
