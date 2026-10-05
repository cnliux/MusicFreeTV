package com.tvmusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.model.SearchEntry
import com.tvmusic.ui.components.EmptyState
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.GlassButton
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.components.SectionHeader
import com.tvmusic.ui.components.TvKeyboard
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.components.withPlatform
import com.tvmusic.model.DetailTarget
import com.tvmusic.ui.components.TvTextField
import com.tvmusic.ui.components.FavoriteButton
import com.tvmusic.ui.components.ModalVisibility

/** M14：热门搜索占位词——取自 SearchViewModel.HOT_SEARCH_WORDS（同时是拼音联想的兜底语料）。 */
private val HOT_SEARCHES get() = SearchViewModel.HOT_SEARCH_WORDS

/**
 * KTV 点歌风格搜索页：
 * - 顶部超大搜索框（遥控器输入友好）+ 大号「搜索」按钮
 * - 类型 / 音源 / 筛选排序收进一行横向滚动条，不占竖向空间
 * - 结果全宽：大字号歌单头 + 大号歌曲行（歌名 22sp），行内直接播放，右侧收藏
 */
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
    val suggestions by viewModel.suggestions.collectAsState()
    val effectiveQuery by viewModel.effectiveQuery.collectAsState()
    val pinyinHits by viewModel.pinyinHits.collectAsState()

    Row(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        // ── 左栏：搜索框 + 简化筛选（窄栏，按钮缩小防溢出）──
        Column(
            modifier = Modifier
                .weight(0.32f)
                .fillMaxHeight()
                .padding(end = 20.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TvTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                placeholder = "歌名 / 歌手 / 拼音首字母",
                textStyle = MaterialTheme.typography.bodyLarge
            )

            // 拼音联想：输入 zjl 立刻（零网络）列出本地命中的中文词，点一下即按该词搜。
            // 固定预留高度：出现/消失时下方键盘不跳位，D-pad 肌肉记忆不被打断。
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                if (suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { "py_$it" }) { w ->
                            KtvChip(label = w, onSelect = { viewModel.useHistory(w) })
                        }
                    }
                }
            }

            // 左栏固定为「搜索框 + 26 键字母盘」：搜索页所有时间都在输入，
            // 类型/筛选类控件已全部移除（默认搜歌曲已覆盖 99% 场景），右侧留给内容。
            TvKeyboard(
                // 一律走 ViewModel 现值拼接，不闭包捕获 query：快速连按两键时
                // 捕获旧值会丢字/重字（StateFlow 是唯一真相源）
                onText = viewModel::appendText,
                onBackspace = viewModel::backspace,
                onClear = viewModel::clearQuery,
                onSubmit = { viewModel.submit() }
            )
        }

        // ── 右栏：结果区（占大头）──
        Column(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxHeight()
                .padding(start = 20.dp)
        ) {
            // 拼音输入期间的右侧实时内容：还没触发/没拿到插件结果时，用本地收藏 + 播放历史
            // 里声母命中的条目填住右侧，避免用户敲 `zjl` 时右侧一片空白。
            // 插件一旦返回结果就立刻让位给结果区；搜索中但一个音源都没回来时也继续显示，
            // 这样"边敲边出内容"的实时感不会在 500ms 防抖之后突然断掉。
            val p = phase
            val localFirst = pinyinHits.isNotEmpty() &&
                (p is SearchPhase.Idle || p is SearchPhase.NoResult ||
                    (p is SearchPhase.Searching && groups.isEmpty()))
            if (localFirst) {
                PinyinRelatedPanel(query, pinyinHits)
            } else when (val ph = phase) {
                is SearchPhase.Idle -> HistoryPanel(viewModel, history, query)
                is SearchPhase.NoResult -> EmptyState(ph.message, actionLabel = "重试", onAction = { viewModel.submit() })
                else -> {
                    val s = ph as? SearchPhase.Searching
                    if (s != null && groups.isEmpty()) {
                        LoadingBox(Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Column(Modifier.weight(1f)) {
                            if (s != null) {
                                // 拼音展开时明确告诉用户"实际搜的是哪个词"，否则他会以为自己敲的
                                // 那串字母真被插件认得了（展开错了他也能立刻看出来并改输入）
                                val expanded = effectiveQuery.isNotBlank() && effectiveQuery != query.trim()
                                Text(
                                    (if (expanded) "拼音「$query」→ 已按「$effectiveQuery」搜索 · " else "") +
                                        if (s.done < s.total)
                                            "正在搜索 ${s.done}/${s.total} 个音源…（结果边到边显示）"
                                        else
                                            "正在整理已返回的结果…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
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

/** 结果面板：站点过滤条 + 全部收藏 + 大号结果列表。 */
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
        if (plugins.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "__res_all__") {
                    FilterChip(
                        label = "全部结果",
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
        if (visibleSongs.isNotEmpty()) {
            GlassButton(
                onClick = { showCollectAll = true },
                modifier = Modifier.padding(top = 6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
            ) { _ ->
                Text(
                    "♡ 全部收藏（${visibleSongs.size}）",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp
                )
            }
        }
        KtvResultList(
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

    ModalVisibility(showCollectAll) {
        com.tvmusic.ui.components.CollectSongsDialog(
            entries = visibleSongs,
            playback = playback,
            onDismiss = { showCollectAll = false }
        )
    }
    ModalVisibility(pickFavItem != null) {
        pickFavItem?.let { item ->
            com.tvmusic.ui.components.PickFavDialog(
                item = item,
                playback = playback,
                onDismiss = { pickFavItem = null }
            )
        }
    }
}

/** KTV 大号结果列表：复用分页与行类型逻辑，行渲染换为大字号样式。 */
@Composable
private fun KtvResultList(
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
                            if (loadingMore == g.plugin) "加载中…" else "加载更多",
                            g,
                            isError = false
                        )
                    )
                    else -> add(ResultRow.End(uniqueKey("end_" + g.plugin), g.entries.size))
                }
            }
        }
    }

    // 渲染分页（仅 UI 层）：初始 50 行，滚动接近末尾时继续放量
    var visibleCount by remember { mutableIntStateOf(INITIAL_VISIBLE_ROWS) }
    LaunchedEffect(groups) { if (groups.isEmpty()) visibleCount = INITIAL_VISIBLE_ROWS }

    val listState = rememberLazyListState()
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
        itemsIndexed(shown, key = { _, row -> row.key }) { _, row ->
            when (row) {
                is ResultRow.Header -> {
                    val g = row.g
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // KTV 大号分组头：色条 + 大字号音源名
                        Box(
                            Modifier
                                .padding(start = 28.dp)
                                .size(width = 5.dp, height = 22.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(3.dp)
                                )
                        )
                        Text(
                            g.plugin,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                        Text(
                            "第 ${g.page} 页 · ${g.entries.size} 条" +
                                if (g.isEnd) " · 已到底" else "",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                is ResultRow.Song -> {
                    val entry = row.e
                    val favRaw = remember(entry) { withPlatform(entry.raw, entry.plugin) }
                    val favKey = remember(favRaw) { com.tvmusic.data.PlaybackStore.favKeyOf(favRaw) }
                    KtvSongRow(
                        title = entry.title,
                        artist = entry.artist,
                        artwork = entry.artwork,
                        plugin = entry.plugin,
                        onClick = { onPlay(entry) },
                        trailing = {
                            FavoriteButton(
                                favorited = favKey in favKeys,
                                onClick = { onPickFav(favRaw) },
                                iconSize = 22.sp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    )
                }
                is ResultRow.Cards -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(
                            row.entries,
                            key = { i, e -> "c_${i}_${e.plugin}-${e.id}-${e.name}" }
                        ) { _, e ->
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
                    KtvSecondaryButton(row.label, Modifier.padding(start = 28.dp, top = 8.dp)) {
                        if (row.isError) onRetry(row.g) else onLoadMore(row.g)
                    }
                }
                is ResultRow.End -> {
                    Text(
                        "— 到底 —",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/** KTV 大号歌曲行：长圆形 pill 背景（跟随主题色深浅变化）+ 歌名 20sp + 歌手 15sp + 可选封面缩略图。 */
@Composable
private fun KtvSongRow(
    title: String,
    artist: String,
    artwork: String,
    plugin: String = "",
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    // 背景色：主题 primary 的深色变体（透明度 25%），默认深青；不同音源用不同透明度区分。
    // 原方案用 plugin.hashCode().mod(3) 选档：String.hashCode 虽内容相关，但不同 JVM/ART
    // 版本及不同平台名的分布不可控，同一音源底色可能漂移；改为按平台名显式映射固定档位，
    // 未知平台用长度取模兜底，保证同一平台跨版本颜色稳定。
    val primary = MaterialTheme.colorScheme.primary
    val alphaTier = when (plugin) {
        "kugou" -> 0
        "kuwo" -> 1
        "qq" -> 2
        "netease" -> 0
        "migu" -> 1
        "bilibili" -> 2
        else -> plugin.length % 3 // 未知平台：确定性兜底
    }
    val baseAlpha = if (plugin.isNotBlank()) 0.22f + (alphaTier * 0.08f) else 0.25f
    val bgColor = primary.copy(alpha = baseAlpha.coerceIn(0.15f, 0.4f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bgColor)
            .tvFocus(shapeOverride = RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artwork.isNotBlank()) {
            coil.compose.AsyncImage(
                model = artwork,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 20.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (artist.isNotBlank()) {
                Text(
                    artist,
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 播放按钮（KTV 行内直接点歌）
        Text(
            "▶ 播放",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.padding(end = 10.dp)
        )
        trailing()
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

/** 渲染分页常量。 */
private const val INITIAL_VISIBLE_ROWS = 50
private const val PAGE_STEP_ROWS = 50

private sealed interface ResultRow {
    /** LazyColumn 稳定 key（构建时全局去重）。 */
    val key: String

    data class Header(override val key: String, val g: SearchGroup) : ResultRow
    data class Song(override val key: String, val e: SearchEntry) : ResultRow
    data class Cards(override val key: String, val entries: List<SearchEntry>) : ResultRow
    data class Button(override val key: String, val label: String, val g: SearchGroup, val isError: Boolean) : ResultRow
    data class End(override val key: String, val count: Int) : ResultRow
}

/** KTV 次按钮：加载更多 / 重试。玻璃胶囊。 */
@Composable
private fun KtvSecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
    ) { _ -> Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp) }
}

@Composable
private fun HistoryPanel(
    viewModel: SearchViewModel,
    history: List<String>,
    query: String
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("搜索历史", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
            if (history.isNotEmpty()) {
                GlassButton(
                    onClick = viewModel::clearHistory,
                    modifier = Modifier.padding(start = 16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                ) { _ -> Text("清空", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp) }
            }
        }
        if (history.isEmpty()) {
            Text(
                "暂无搜索历史",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history.take(8), key = { "h_$it" }) { w ->
                    KtvChip(label = w, onSelect = { viewModel.useHistory(w) }, onClose = { viewModel.removeHistory(w) })
                }
            }
        }

        Box(Modifier.padding(top = 20.dp)) {
            SectionHeader("热门搜索")
        }
        val hot = HOT_SEARCHES
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(hot, key = { "hot_$it" }) { w ->
                KtvChip(label = w, onSelect = { viewModel.useHistory(w) }, onClose = null)
            }
        }
        if (query.isNotBlank()) {
            Text(
                "按回车或点「搜索」开始查找“$query”",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp).padding(top = 20.dp)
            )
        }
    }
}

/**
 * 拼音输入期间的右侧实时面板：收藏 + 播放历史里声母命中的条目，点击即播。
 *
 * 存在的意义：用户敲 `zjl` 时插件往往还没返回（或本地根本没听过这串声母、压根没发请求），
 * 右侧若一片空白会让人以为键盘没生效。这里用本地数据即时填内容，纯内存查找、零网络。
 */
@Composable
private fun PinyinRelatedPanel(query: String, hits: List<org.json.JSONObject>) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("拼音「$query」的相关内容", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "来自收藏与播放历史",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(hits, key = { "pyh_${it.optString("platform")}::${it.optString("id")}::${it.optString("title")}" }) { o ->
                val plugin = o.optString("platform", "")
                KtvSongRow(
                    title = o.optString("title"),
                    artist = o.optString("artist", ""),
                    artwork = o.optString("artwork", ""),
                    plugin = plugin,
                    onClick = {
                        if (plugin.isBlank()) return@KtvSongRow
                        val qe = com.tvmusic.player.QueueEntry(plugin, o)
                        com.tvmusic.player.PlayerManager.play(
                            plugin, qe, listOf(qe), 0, source = "$plugin · 拼音相关"
                        )
                    }
                )
            }
        }
    }
}

/**
 * M24：关闭按钮从「嵌套在可点击 Row 内」改为「兄弟节点」。
 * 原实现两个焦点节点嵌套，D-pad 命中依赖内部 focus 搜索顺序，易出现
 * "看得到 × 却选不中"（与 SheetScreen 收藏按钮同类问题，已用兄弟结构修复）。
 * 无 onClose 时保持单一焦点节点，视觉与原样式一致。
 */
@Composable
private fun KtvChip(label: String, onSelect: () -> Unit, onClose: (() -> Unit)? = null) {
    if (onClose == null) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .tvFocus(shapeOverride = RoundedCornerShape(18.dp))
                .clickable(onClick = onSelect)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        }
        return
    }
    // 有删除按钮：胶囊背景 + 两个并列的焦点兄弟节点（标签区 / 删除区）
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .tvFocus(shapeOverride = RoundedCornerShape(18.dp))
                .clickable(onClick = onSelect)
                .padding(start = 18.dp, top = 9.dp, bottom = 9.dp, end = 8.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                .clickable(onClick = onClose)
                .padding(horizontal = 5.dp, vertical = 4.dp)
                .padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "×",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                modifier = Modifier.semantics { contentDescription = "删除 $label" }
            )
        }
    }
}
