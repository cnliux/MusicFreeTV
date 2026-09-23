package com.tvmusic.ui.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.sheet.DetailKind
import com.tvmusic.ui.sheet.DetailTarget
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip

@Composable
fun RecommendScreen(
    viewModel: RecommendViewModel,
    onBack: () -> Unit,
    onOpenDetail: (DetailTarget) -> Unit
) {
    val plugins by viewModel.plugins.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val sheets by viewModel.sheets.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val isEnd by viewModel.isEnd.collectAsState()
    val error by viewModel.error.collectAsState()

    val gridState = rememberLazyGridState()

    // 接近底部自动翻页（对应 RN FlashList onEndReached）
    LaunchedEffect(gridState, sheets.size, isEnd, loadingMore) {
        androidx.compose.runtime.snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            if (last >= 0 && last >= sheets.size - 8) viewModel.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.padding(end = 16.dp).tvFocus().clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            }
            Text("推荐歌单", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        if (plugins.isEmpty() && !loading) {
            EmptyHint(error ?: "已启用的插件均不支持推荐歌单")
            return@Column
        }

        // 插件页签（对应 RN recommendSheets/body 的 TabView）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = plugins,
                key = { it.info!!.platform }
            ) { rec ->
                val platform = rec.info!!.platform
                FilterChip(
                    label = rec.name,
                    selected = platform == selectedPlatform,
                    onClick = { viewModel.selectPlugin(platform) }
                )
            }
        }

        // 标签行（pinned + 全部分组标签；默认标签 = { id: "", title: "默认" }）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = tags,
                key = { "tag-${it.id}" }
            ) { tag ->
                FilterChip(
                    label = tag.title,
                    selected = tag.id == selectedTag.id,
                    onClick = { viewModel.selectTag(tag) }
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                loading && sheets.isEmpty() -> LoadingBox()
                sheets.isEmpty() -> EmptyHint(error ?: "该标签下暂无歌单")
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(164.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    gridItems(sheets, key = { "${it.plugin}-${it.raw.optString("id")}-${it.title}" }) { sheet ->
                        MediaCard(
                            title = sheet.title,
                            subtitle = sheet.description.ifBlank { selectedPlatform },
                            artwork = sheet.artwork,
                            onClick = {
                                onOpenDetail(
                                    DetailTarget.stamped(
                                        sheet.plugin,
                                        DetailKind.SHEET,
                                        JSONObject(sheet.raw.toString())
                                    )
                                )
                            }
                        )
                    }
                }
            }

            if (loadingMore) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text("加载更多…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
