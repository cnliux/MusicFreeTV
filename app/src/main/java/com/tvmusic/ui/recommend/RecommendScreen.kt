package com.tvmusic.ui.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvmusic.ui.components.BackTopBar
import com.tvmusic.ui.components.EmptyState
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.LoadMoreFooter
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.sheet.DetailKind
import com.tvmusic.ui.sheet.DetailTarget
import org.json.JSONObject

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
        BackTopBar(title = "推荐歌单", onBack = onBack)

        if (plugins.isEmpty() && !loading) {
            EmptyState(error ?: "已启用的插件均不支持推荐歌单")
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
                sheets.isEmpty() -> {
                    val retry = { viewModel.selectTag(selectedTag) }
                    EmptyState(
                        message = error ?: "该标签下暂无歌单",
                        actionLabel = error?.let { "重试" },
                        onAction = error?.let { retry }
                    )
                }
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
                            modifier = Modifier.fillMaxWidth(),
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

            // 底部浮层：加载中提示；分页失败时提供重试（自动翻页已触发过才会出现此层）
            if (loadingMore || (error != null && sheets.isNotEmpty())) {
                LoadMoreFooter(
                    loading = loadingMore,
                    hasMore = false,
                    error = if (!loadingMore) error else null,
                    allLoadedText = null,
                    onLoadMore = { viewModel.loadMore() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}