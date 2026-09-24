package com.tvmusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.config.SearchSettings
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
    val selectedSources by viewModel.selectedSources.collectAsState()
    val searchablePlatforms by viewModel.searchablePlatforms.collectAsState()
    val durFilter by viewModel.durFilter.collectAsState()
    val needArtwork by viewModel.needArtwork.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val sortAsc by viewModel.sortAsc.collectAsState()

    Row(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        // 左半边：搜索控制区（输入 / 类型 / 音源 / 筛选排序）——窄栏，把空间留给结果
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight()
                .padding(end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("搜索音乐") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .tvFocus(shapeOverride = RoundedCornerShape(10.dp)),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            SubmitButton(
                "搜 索",
                Modifier.fillMaxWidth()
            ) { viewModel.submit() }

            if (phase !is SearchPhase.Idle) {
                ControlLabel("类型")
                TypeTabs(selectedType = selectedType, onSelect = viewModel::setType)
                ControlLabel("音源")
                PlatformFilterRow(
                    platforms = searchablePlatforms,
                    selected = selectedSources,
                    onToggle = viewModel::toggleSource,
                    onAll = viewModel::selectAllSources
                )
                ControlLabel("筛选与排序")
                FilterSortPanel(
                    durFilter = durFilter,
                    needArtwork = needArtwork,
                    sortBy = sortBy,
                    sortAsc = sortAsc,
                    onDur = viewModel::setDurFilter,
                    onArtwork = viewModel::setNeedArtwork,
                    onSort = viewModel::setSortBy,
                    onToggleAsc = viewModel::toggleSortAsc
                )
            }
        }

        // 右半边：结果区——占大头
        Column(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight()
                .padding(start = 20.dp)
        ) {
            when (phase) {
                is SearchPhase.Idle -> HistoryPanel(viewModel, history, query)
                is SearchPhase.Searching -> {
                    val s = phase as SearchPhase.Searching
                    if (groups.isEmpty()) {
                        LoadingBox(Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (s.done < s.total)
                                    "正在搜索 ${s.done}/${s.total} 个音源…（结果边到边显示）"
                                else
                                    "正在整理已返回的结果…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                            )
                            ResultsPanel(
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
                is SearchPhase.NoResult -> EmptyResult(message = (phase as SearchPhase.NoResult).message)
                is SearchPhase.Ready -> {
                    ResultsPanel(
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
    }
}

/** 左栏分组小标题。 */
@Composable
private fun ControlLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** 结果面板：站点过滤条 + 结果列表（Ready 与渐进式 Searching 共用）。 */
@Composable
private fun ColumnScope.ResultsPanel(
    groups: List<SearchGroup>,
    type: String,
    loadingMore: String?,
    onLoadMore: (SearchGroup) -> Unit,
    onRetry: (SearchGroup) -> Unit,
    onPlay: (SearchEntry) -> Unit,
    onOpenDetail: (SearchEntry) -> Unit
) {
    // 结果内站点过滤：仅过滤已返回的分组，不重新请求
    val plugins = remember(groups) { groups.map { it.plugin }.distinct() }
    var selectedPlugin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(plugins) { if (plugins.isEmpty()) selectedPlugin = null }
    Column(Modifier.weight(1f)) {
        if (plugins.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "__res_all__") {
                    FilterChip(
                        label = "全部",
                        selected = selectedPlugin == null,
                        onClick = { selectedPlugin = null }
                    )
                }
                items(plugins, key = { "res_$it" }) { p ->
                    FilterChip(
                        label = p,
                        selected = p == selectedPlugin,
                        onClick = { selectedPlugin = p }
                    )
                }
            }
        }
        ResultList(
            groups = if (selectedPlugin == null) groups else groups.filter { it.plugin == selectedPlugin },
            type = type,
            loadingMore = loadingMore,
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            onPlay = onPlay,
            onOpenDetail = onOpenDetail
        )
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

/** 音源筛选条：全部 + 各平台名（多选）。 */
@Composable
private fun PlatformFilterRow(
    platforms: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onAll: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "__all__") {
            FilterChip(
                label = "全部",
                selected = selected.isEmpty(),
                onClick = onAll
            )
        }
        items(platforms, key = { it }) { p ->
            FilterChip(
                label = p,
                selected = p in selected,
                onClick = { onToggle(p) }
            )
        }
    }
}

/** 结果过滤（时长/封面）与排序面板：左半栏内竖排两行（可横向滚动），避免溢出。 */
@Composable
private fun FilterSortPanel(
    durFilter: DurationFilter,
    needArtwork: Boolean,
    sortBy: String,
    sortAsc: Boolean,
    onDur: (DurationFilter) -> Unit,
    onArtwork: (Boolean) -> Unit,
    onSort: (String) -> Unit,
    onToggleAsc: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item(key = "__dur_label__") {
                Text("时长", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(DurationFilter.entries, key = { "d_${it.name}" }) { f ->
                FilterChip(label = f.label, selected = durFilter == f, onClick = { onDur(f) })
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item(key = "__art__") {
                FilterChip(
                    label = if (needArtwork) "有封面✓" else "有封面",
                    selected = needArtwork,
                    onClick = { onArtwork(!needArtwork) }
                )
            }
            item(key = "__sort_label__") {
                Text("排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val sortLabels = mapOf(
                SearchSettings.SORT_DEFAULT to "默认",
                SearchSettings.SORT_DURATION to "时长",
                SearchSettings.SORT_TITLE to "歌名",
                SearchSettings.SORT_ARTIST to "歌手"
            )
            sortLabels.forEach { (k, v) ->
                item(key = "s_$k") {
                    FilterChip(label = v, selected = sortBy == k, onClick = { onSort(k) })
                }
            }
            item(key = "__asc__") {
                FilterChip(label = if (sortAsc) "升序" else "降序", selected = true, onClick = onToggleAsc)
            }
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
            .tvFocus(shapeOverride = RoundedCornerShape(6.dp))
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
                        .tvFocus(shapeOverride = RoundedCornerShape(6.dp))
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
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history.take(8), key = { "h_$it" }) { w ->
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
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(hot, key = { "hot_$it" }) { w ->
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
            .tvFocus(shapeOverride = RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        if (onClose != null) {
            Box(
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp))
                    .tvFocus(shapeOverride = RoundedCornerShape(8.dp)).clickable(onClick = onClose)
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
private fun SubmitButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary)
            .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
