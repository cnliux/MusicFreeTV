package com.tvmusic.ui.search

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.tvmusic.ui.components.EmptyState
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.components.MusicRow
import com.tvmusic.ui.components.SectionHeader
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.components.withPlatform
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
                is SearchPhase.NoResult -> EmptyState(message = (phase as SearchPhase.NoResult).message)
                else -> {
                    // Searching（已有渐进结果）与 Ready 共用同一 ResultsPanel 插槽，
                    // 避免搜索进行中→完成切换阶段时丢失列表滚动与分页状态
                    val s = phase as? SearchPhase.Searching
                    if (s != null && groups.isEmpty()) {
                        LoadingBox(Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Column(Modifier.weight(1f)) {
                            if (s != null) {
                                Text(
                                    if (s.done < s.total)
                                        "正在搜索 ${s.done}/${s.total} 个音源…（结果边到边显示）"
                                    else
                                        "正在整理已返回的结果…",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                                )
                            }
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

/** 结果面板：站点过滤条 + 全部收藏 + 结果列表（Ready 与渐进式 Searching 共用）。 */
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

    val playback = com.tvmusic.core.TvMusicApp.from(
        androidx.compose.ui.platform.LocalContext.current
    ).playback
    val favorites by playback.favorites.collectAsState()
    val favKeys = remember(favorites) { playback.favoriteKeys() }
    var showCollectAll by remember { mutableStateOf(false) }
    // 单曲收藏弹层目标：null 表示未打开
    var pickFavItem by remember { mutableStateOf<org.json.JSONObject?>(null) }

    // 当前过滤结果中可收藏的曲目（仅歌曲类型；补全 platform 保证收藏后可回放）
    val visibleSongs = remember(groups, selectedPlugin, type) {
        if (type != "music") emptyList()
        else (if (selectedPlugin == null) groups else groups.filter { it.plugin == selectedPlugin })
            .flatMap { g -> g.entries.map { e -> withPlatform(e.raw, e.plugin) } }
    }

    Column(Modifier.weight(1f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (plugins.size > 1) {
                LazyRow(
                    modifier = Modifier.weight(1f),
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
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            if (visibleSongs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                        .clickable { showCollectAll = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        "♡ 全部收藏（${visibleSongs.size}）",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                }
            }
        }
        ResultList(
            groups = if (selectedPlugin == null) groups else groups.filter { it.plugin == selectedPlugin },
            type = type,
            loadingMore = loadingMore,
            favKeys = favKeys,
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            onPlay = onPlay,
            onPickFav = { pickFavItem = it },
            onOpenDetail = onOpenDetail
        )
    }

    AnimatedVisibility(
        visible = showCollectAll,
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f)
    ) {
        com.tvmusic.ui.components.CollectSongsDialog(
            entries = visibleSongs,
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
            com.tvmusic.ui.components.PickFavDialog(
                item = item,
                playback = playback,
                onDismiss = { pickFavItem = null }
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
    favKeys: Set<String>,
    onLoadMore: (SearchGroup) -> Unit,
    onRetry: (SearchGroup) -> Unit,
    onPlay: (SearchEntry) -> Unit,
    onPickFav: (org.json.JSONObject) -> Unit,
    onOpenDetail: (SearchEntry) -> Unit
) {
    val rows = remember(groups, loadingMore, type) {
        val used = HashSet<String>()
        // 生成全局唯一的稳定 key：同一内容条目重复出现时追加序号，避免 LazyColumn key 冲突崩溃
        fun uniqueKey(base: String): String {
            var k = base
            var n = 1
            while (!used.add(k)) k = "$base#${n++}"
            return k
        }
        groups.flatMap { g ->
            buildList {
                add(ResultRow.Header(uniqueKey("header_" + g.plugin), g))
                if (type == "music") {
                    g.entries.forEach { e ->
                        // 歌曲条目：plugin + "_" + id（SearchEntry.id 已是 String）
                        add(ResultRow.Song(uniqueKey("song_" + e.plugin + "_" + e.id), e))
                    }
                } else {
                    add(ResultRow.Cards(uniqueKey("cards_" + g.plugin), g.entries))
                }
                when {
                    g.error != null -> add(
                        ResultRow.Button(uniqueKey("btn_" + g.plugin), "重试", g, isError = true)
                    )
                    !g.isEnd -> add(
                        ResultRow.Button(
                            uniqueKey("btn_" + g.plugin),
                            if (loadingMore == g.plugin) "加载更多…" else "加载更多",
                            g,
                            isError = false
                        )
                    )
                    else -> add(ResultRow.End(uniqueKey("end_" + g.plugin), g.entries.size))
                }
            }
        }
    }

    // 渲染分页（仅 UI 层，数据层不变）：初始只渲染前 50 行，滚动接近末尾时继续放量
    var visibleCount by remember { mutableIntStateOf(INITIAL_VISIBLE_ROWS) }
    // 新搜索发起时 ViewModel 会先清空 groups，此时重置分页进度
    LaunchedEffect(groups) { if (groups.isEmpty()) visibleCount = INITIAL_VISIBLE_ROWS }

    val listState = rememberLazyListState()
    // 最后一个可见行进入已渲染区倒数 8 行内且还有未渲染行时，自动追加一页渲染量
    // （TV 上焦点滚到列表末尾继续按向下键即触发；读 visibleCount 状态以便放量后重新判定）
    val nearEnd by remember(rows) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= visibleCount - 8 && visibleCount < rows.size
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) visibleCount = (visibleCount + PAGE_STEP_ROWS).coerceAtMost(rows.size)
    }

    val shown = rows.take(visibleCount)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        items(shown, key = { it.key }) { row ->
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
                    // 补全 platform 后算收藏主键（与 PlaybackStore 同规则）
                    val favRaw = remember(entry) { withPlatform(entry.raw, entry.plugin) }
                    val favKey = remember(favRaw) { com.tvmusic.data.PlaybackStore.favKeyOf(favRaw) }
                    MusicRow(
                        index = 0,
                        title = entry.title,
                        artist = entry.artist,
                        album = entry.album,
                        onClick = { onPlay(entry) },
                        trailing = {
                            // 单曲收藏：弹出收藏夹选择（可加入任意自定义收藏夹）
                            val fav = favKey in favKeys
                            Box(
                                modifier = Modifier
                                    .tvFocus()
                                    .clickable { onPickFav(favRaw) }
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

/** 渲染分页常量：初始渲染行数 / 每次放量行数。 */
private const val INITIAL_VISIBLE_ROWS = 50
private const val PAGE_STEP_ROWS = 50

private sealed interface ResultRow {
    /** LazyColumn 稳定 key（构建时已做全局去重，前缀区分类型防止跨 items 块冲突）。 */
    val key: String

    data class Header(override val key: String, val g: SearchGroup) : ResultRow
    data class Song(override val key: String, val e: SearchEntry) : ResultRow
    data class Cards(override val key: String, val entries: List<SearchEntry>) : ResultRow
    data class Button(override val key: String, val label: String, val g: SearchGroup, val isError: Boolean) : ResultRow
    data class End(override val key: String, val count: Int) : ResultRow
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
