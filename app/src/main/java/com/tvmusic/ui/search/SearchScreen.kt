package com.tvmusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.data.SearchEntry
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.components.MusicRow
import com.tvmusic.ui.components.SectionHeader
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.sheet.DetailTarget

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (DetailTarget) -> Unit
) {
    val query by viewModel.query.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val history by viewModel.history.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val filterPlatform by viewModel.filterPlatform.collectAsState()
    val searchablePlatforms by viewModel.searchablePlatforms.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        // 搜索输入行
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("搜索音乐，输入后点「搜索」") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .tvFocus(),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            SubmitButton("搜索") { viewModel.submit() }
        }

        // 类型页签：对应 RN 结果页的 单曲/专辑/歌手/歌单 TabView
        if (phase !is SearchPhase.Idle) {
            TypeTabs(selectedType = selectedType, onSelect = viewModel::setType)
        }

        // 音源筛选条：全部 + 各平台
        if (phase !is SearchPhase.Idle && searchablePlatforms.isNotEmpty()) {
            PlatformFilterRow(
                platforms = searchablePlatforms,
                selected = filterPlatform,
                onSelect = viewModel::setFilterPlatform
            )
        }

        when (phase) {
            is SearchPhase.Idle -> HistoryPanel(viewModel, history, query)
            is SearchPhase.Searching -> LoadingBox(Modifier.weight(1f).fillMaxWidth())
            is SearchPhase.NoResult -> EmptyResult(message = (phase as SearchPhase.NoResult).message)
            is SearchPhase.Ready -> ResultList(
                groups = groups,
                type = selectedType,
                loadingMore = loadingMore,
                onLoadMore = viewModel::loadMore,
                onRetry = viewModel::retry,
                onPlay = viewModel::play,
                onOpenDetail = { entry ->
                    viewModel.openDetail(entry)?.let(onOpenDetail)
                }
            )
        }
    }
}

@Composable
private fun TypeTabs(selectedType: String, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(SearchViewModel.SEARCH_TYPES, key = { it.first }) { (key, label) ->
            FilterChip(
                label = label,
                selected = key == selectedType,
                onClick = { onSelect(key) }
            )
        }
    }
}

/** 音源筛选条：全部 + 各平台名。 */
@Composable
private fun PlatformFilterRow(
    platforms: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "__all__") {
            FilterChip(
                label = "全部",
                selected = selected == null,
                onClick = { onSelect(null) }
            )
        }
        items(platforms, key = { it }) { p ->
            FilterChip(
                label = p,
                selected = p == selected,
                onClick = { onSelect(p) }
            )
        }
    }
}

@Composable
private fun ResultList(
    groups: List<SearchGroup>,
    type: String,
    loadingMore: String?,
    onLoadMore: (SearchGroup) -> Unit,
    onRetry: (SearchGroup) -> Unit,
    onPlay: (SearchEntry) -> Unit,
    onOpenDetail: (SearchEntry) -> Unit
) {
    val rows = remember(groups, loadingMore) {
        groups.flatMap { g ->
            buildList {
                add(ResultRow.Header(g))
                if (type == "music") {
                    g.entries.forEach { add(ResultRow.Song(it)) }
                } else {
                    add(ResultRow.Cards(g.entries))
                }
                when {
                    g.error != null -> add(ResultRow.Button("重试", g, isError = true))
                    !g.isEnd -> add(
                        ResultRow.Button(
                            if (loadingMore == g.plugin) "加载更多…" else "加载更多",
                            g,
                            isError = false
                        )
                    )
                    else -> add(ResultRow.End(g.entries.size))
                }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        items(rows) { row ->
            when (row) {
                is ResultRow.Header -> {
                    val g = row.g
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeaderBorderless(g.plugin)
                        Text(
                            "第 ${g.page} 页 · 共 ${g.entries.size} 条" +
                                if (g.isEnd) " · 已到底" else " · 可查看更多",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                is ResultRow.Song -> {
                    val entry = row.e
                    MusicRow(
                        index = 0,
                        title = entry.title,
                        artist = entry.artist,
                        album = entry.album,
                        onClick = { onPlay(entry) }
                    )
                }
                is ResultRow.Cards -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(row.entries, key = { "${it.plugin}-${it.id}-${it.name}" }) { e ->
                            MediaCard(
                                title = e.name.ifBlank { e.title },
                                subtitle = cardSubtitle(e),
                                artwork = e.avatar.ifBlank { e.artwork },
                                onClick = { onOpenDetail(e) }
                            )
                        }
                    }
                }
                is ResultRow.Button -> {
                    TinyButton(row.label) {
                        if (row.isError) onRetry(row.g) else onLoadMore(row.g)
                    }
                }
                is ResultRow.End -> {
                    Text(
                        "— 到底 —",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun cardSubtitle(e: SearchEntry): String = when (e.type) {
    "artist" -> buildList {
        if (e.worksNum > 0) add("作品 ${e.worksNum}")
        if (e.descriptionText.isNotBlank()) add(e.descriptionText)
    }.firstOrNull() ?: e.plugin
    "album" -> e.artist.ifBlank { e.descriptionText }.ifBlank { e.plugin }
    else -> e.descriptionText.ifBlank { e.artist }.ifBlank { e.plugin }
}

private sealed interface ResultRow {
    data class Header(val g: SearchGroup) : ResultRow
    data class Song(val e: SearchEntry) : ResultRow
    data class Cards(val entries: List<SearchEntry>) : ResultRow
    data class Button(val label: String, val g: SearchGroup, val isError: Boolean) : ResultRow
    data class End(val count: Int) : ResultRow
}

@Composable
fun SectionHeaderBorderless(title: String) {
    Text(
        text = title,
        fontSize = 17.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp)
    )
}

@Composable
private fun TinyButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 28.dp, top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .tvFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
    }
}

@Composable
private fun HistoryPanel(
    viewModel: SearchViewModel,
    history: List<String>,
    query: String
) {
    Column(Modifier.fillMaxSize()) {
        // 历史词
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("搜索历史", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            if (history.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .tvFocus()
                        .clickable(onClick = viewModel::clearHistory)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        if (history.isEmpty()) {
            Text(
                "暂无搜索历史",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                history.take(8).forEach { w ->
                    Chip(
                        label = w,
                        onSelect = { viewModel.useHistory(w) },
                        onClose = { viewModel.removeHistory(w) }
                    )
                }
            }
        }

        // 热门词（静态兜底，替代需要联网聚合的热搜榜）
        Box(Modifier.padding(top = 18.dp)) {
            SectionHeader("热门搜索")
        }
        val hot = listOf("周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "许嵩", "赵雷", "新歌榜", "纯音乐")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            hot.forEach { w ->
                Chip(label = w, onSelect = { viewModel.useHistory(w) }, onClose = null)
            }
        }
        if (query.isNotBlank()) {
            Text(
                "按回车或点「搜索」开始查找“$query”",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp).padding(top = 18.dp)
            )
        }
    }
}

@Composable
private fun Chip(label: String, onSelect: () -> Unit, onClose: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .tvFocus()
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        if (onClose != null) {
            Box(
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp))
                    .tvFocus().clickable(onClick = onClose)
                    .padding(horizontal = 4.dp)
            ) {
                Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun EmptyResult(message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text(
            message,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SubmitButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary)
            .tvFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 15.sp
        )
    }
}
