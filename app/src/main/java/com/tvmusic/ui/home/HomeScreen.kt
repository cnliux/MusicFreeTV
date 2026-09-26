package com.tvmusic.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.data.HomeSection
import com.tvmusic.data.PluginRecord
import com.tvmusic.player.PlayerManager
import com.tvmusic.ui.components.Artwork
import com.tvmusic.ui.components.EmptyState
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.tvFocus
import com.tvmusic.ui.sheet.DetailKind
import com.tvmusic.ui.sheet.DetailTarget
import org.json.JSONObject

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDetail: (DetailTarget) -> Unit,
    onOpenRecommend: (platform: String?) -> Unit,
    onOpenTopList: (platform: String?) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenMyList: () -> Unit
) {
    val sections by viewModel.sections.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val availablePlugins by viewModel.availablePlugins.collectAsState()
    val currentPlatform by viewModel.currentPlatform.collectAsState()

    // 「继续播放」对话框：进程被杀后保留的队列快照，询问是否从上次进度恢复。
    // 本次会话内用户取消后不再打扰（rememberSaveable 随导航返回栈保留）。
    val resumeAvailable by PlayerManager.resumeAvailable.collectAsState()
    var resumeDismissed by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // 左侧常驻「正在播放」面板
        NowPlayingPanel(onOpenPlayer = onOpenPlayer)

        // 右侧内容区
        Column(Modifier.weight(1f).fillMaxHeight()) {
            HomeTopBar(loading = loading, count = sections.size, onRefresh = viewModel::load)

            // 音源切换器：只加载当前选中插件的首页数据
            if (availablePlugins.isNotEmpty()) {
                PluginSwitcher(
                    plugins = availablePlugins,
                    currentPlatform = currentPlatform,
                    onSelect = viewModel::selectPlugin
                )
            }

            if (loading && sections.isEmpty()) {
                LoadingBox()
            } else if (sections.isEmpty()) {
                EmptyState(
                    message = "还没有可用的插件\n\n启动时已自动同步默认订阅源，请稍候或手动导入：\n" +
                        "· 设置 → 扫码同步（TVBox 式导入配置）\n" +
                        "· 设置 → 粘贴订阅 URL / 插件 JS\n" +
                        "· 手机浏览器访问本机接收地址推送配置",
                    actionLabel = "重新加载",
                    onAction = viewModel::load
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item(key = "operations") {
                        OperationsRow(
                            onOpenRecommend = { onOpenRecommend(null) },
                            onOpenTopList = { onOpenTopList(null) },
                            onOpenMyList = onOpenMyList
                        )
                    }
                    items(
                        sections.size,
                        // 稳定 key：旧实现用 Int 索引，刷新/换音源后焦点位置与复用会错位
                        key = { i ->
                            when (val s = sections[i]) {
                                is HomeSection.Recommend -> "rec-${s.plugin}-${s.tagTitle}-$i"
                                is HomeSection.Ranking -> "rank-${s.plugin}-${s.listTitle}-$i"
                                is HomeSection.Error -> "err-${s.plugin}-$i"
                            }
                        }
                    ) { i ->
                        val section = sections[i]
                        when (section) {
                            is HomeSection.Recommend -> {
                                SectionWithMore(
                                    title = section.tagTitle,
                                    onMore = { section.items.firstOrNull()?.plugin?.let(onOpenRecommend) }
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 28.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(section.items, key = { i, sheet -> "${sheet.plugin}|${sheet.raw.optString("id").ifBlank { sheet.title }}|$i" }) { i, sheet ->
                                        MediaCard(
                                            title = sheet.title,
                                            subtitle = section.plugin,
                                            artwork = sheet.artwork,
                                            onClick = {
                                                onOpenDetail(
                                                    DetailTarget.stamped(
                                                        sheet.plugin, DetailKind.SHEET, JSONObject(sheet.raw.toString())
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                            is HomeSection.Ranking -> {
                                SectionWithMore(
                                    title = section.listTitle,
                                    onMore = { section.items.firstOrNull()?.plugin?.let(onOpenTopList) }
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 28.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(section.items, key = { i, item -> "${item.plugin}|${item.raw.optString("id").ifBlank { item.title }}|$i" }) { i, item ->
                                        MediaCard(
                                            title = item.title,
                                            subtitle = section.plugin,
                                            artwork = item.artwork,
                                            onClick = {
                                                onOpenDetail(
                                                    DetailTarget.stamped(
                                                        item.plugin, DetailKind.TOPLIST, JSONObject(item.raw.toString())
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                             is HomeSection.Error -> {
                                 ErrorBox("${section.plugin}：${section.message}", onRetry = viewModel::load)
                             }
                         }
                     }
                 }
             }
         }
     }

    // 继续播放确认：全站统一 ModalCard 风格（覆盖在首页之上）
    if (resumeAvailable && !resumeDismissed) {
        androidx.activity.compose.BackHandler { resumeDismissed = true }
        com.tvmusic.ui.components.ModalCard(
            title = "继续播放",
            subtitle = "检测到上次未播完的内容，是否从上次进度继续播放？",
            onDismiss = { resumeDismissed = true },
            bottomBar = {
                Spacer(Modifier.weight(1f))
                com.tvmusic.ui.components.DialogTextButton("取消", { resumeDismissed = true })
                com.tvmusic.ui.components.DialogTextButton(
                    "继续播放",
                    onClick = {
                        resumeDismissed = true
                        PlayerManager.resumePlayback()
                    },
                    background = MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        ) {}
    }
    }
}

/** 左侧常驻「正在播放」面板：大封面 / 歌名·歌手·专辑分行 / 歌词 / 细进度+时间。
 *  外壳只订阅结构性状态（screenState 已剥离每秒字段），秒级的进度/歌词行
 *  拆到 NowPlayingLive 局部订阅，ticker 不再驱动整块面板重组。 */
@Composable
private fun NowPlayingPanel(onOpenPlayer: () -> Unit) {
    val state by PlayerManager.screenState.collectAsState(initial = PlayerManager.uiState.value)
    val entry = state.current
    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "正在播放",
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 14.dp)
        )
        if (entry == null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", fontSize = 48.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            }
            Text("未在播放", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp))
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .graphicsLayer { shadowElevation = 18f }
                    .tvFocus(1.03f)
                    .clickable(onClick = onOpenPlayer)
            ) {
                Artwork(entry.artwork, Modifier.size(200.dp))
            }
            // 歌名 / 歌手 / 专辑 分行，层级分明
            Text(
                entry.title,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                entry.artist.ifBlank { "未知歌手" },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            if (entry.album.isNotBlank()) {
                Text(
                    entry.album,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }
            NowPlayingLive()
        }
    }
}

/** 秒级动态区（当前歌词行 + 细进度 + 时间）：局部订阅 uiState，重组范围限定在本子树。 */
@Composable
private fun NowPlayingLive() {
    val ps by PlayerManager.uiState.collectAsState()
    // 当前歌词行：颜色/字号跟随歌词设置
    val lyricCfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()
    val lrcText = if (ps.lrcIndex in ps.lrcLines.indices)
        ps.lrcLines[ps.lrcIndex].text else ""
    if (lrcText.isNotBlank()) {
        Text(
            lrcText,
            fontSize = lyricCfg.fontSizeSp.coerceAtMost(16).sp,
            color = com.tvmusic.ui.theme.LyricSettings.parseColor(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )
    }
    Spacer(Modifier.height(14.dp))
    // 细进度条（主色，4dp）+ 时间
    LinearProgressIndicator(
        progress = {
            if (ps.durationMs > 0) ps.positionMs.toFloat() / ps.durationMs else 0f
        },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(fmtTime(ps.positionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fmtTime(ps.durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${"%02d".format(s % 60)}"
}

@Composable
private fun HomeTopBar(loading: Boolean, count: Int, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "发现",
            fontSize = 24.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (loading) "加载中…" else "$count 个板块",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                )
                .tvFocus(circle = true)
                .clickable(onClick = onRefresh)
                .padding(horizontal = 18.dp, vertical = 7.dp)
        ) {
            Text("⟳ 刷新", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }
    }
}

/** 对齐 RN homeBody/Operations：推荐歌单 / 排行榜 / 我的列表。 */
@Composable
private fun OperationsRow(
    onOpenRecommend: () -> Unit,
    onOpenTopList: () -> Unit,
    onOpenMyList: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ActionEntry("🔥", "推荐歌单", "按标签发现好歌单", Modifier.weight(1f), onOpenRecommend)
        ActionEntry("🏆", "排行榜", "各平台权威榜单", Modifier.weight(1f), onOpenTopList)
        ActionEntry("📻", "我的歌单", "历史 / 收藏", Modifier.weight(1f), onOpenMyList)
    }
}

@Composable
private fun ActionEntry(
    icon: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        primary.copy(alpha = 0.10f)
                    )
                )
            )
            .border(1.dp, primary.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .tvFocus(1.03f)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(primary.copy(alpha = 0.35f), primary.copy(alpha = 0.15f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 26.sp)
        }
        Column {
            Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun SectionWithMore(title: String, onMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = title,
            fontSize = 19.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 10.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .tvFocus(shapeOverride = RoundedCornerShape(16.dp))
                .clickable(onClick = onMore)
                .padding(horizontal = 16.dp, vertical = 7.dp)
        ) {
            Text("更多 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 供各 Screen 获取 VM 的便捷入口（在 ViewModelStoreOwner 作用域内）。 */
@Composable
inline fun <reified VM : androidx.lifecycle.ViewModel> rememberVm(
    crossinline create: () -> VM
): VM {
    val factory = com.tvmusic.ui.common.tvViewModelFactory { create() }
    return androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
}

/** 音源切换器：横向 FilterChip 列表，选中后只加载该插件首页数据。 */
@Composable
private fun PluginSwitcher(
    plugins: List<PluginRecord>,
    currentPlatform: String?,
    onSelect: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // key 加插件名兜底：两个插件声明同一 platform 时裸 platform key 会冲突闪退
        items(plugins, key = { "${it.name}_${it.info?.platform ?: it.name}" }) { p ->
            val platform = p.info?.platform ?: return@items
            FilterChip(
                label = p.name,
                selected = platform == currentPlatform,
                onClick = { onSelect(platform) }
            )
        }
    }
}
