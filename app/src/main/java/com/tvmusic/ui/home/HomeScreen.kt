package com.tvmusic.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.data.HomeSection
import com.tvmusic.data.PluginRecord
import com.tvmusic.player.PlayerManager
import com.tvmusic.ui.components.Artwork
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
                EmptyGuide(onRefresh = viewModel::load)
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
                    items(sections.size, key = { it }) { i ->
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
                                    items(section.items) { sheet ->
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
                                    items(section.items) { item ->
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
}

/** 左侧常驻正在播放面板：大封面 / 歌名歌手 / 当前歌词行 / 进度。 */
@Composable
private fun NowPlayingPanel(onOpenPlayer: () -> Unit) {
    val state by PlayerManager.uiState.collectAsState()
    val entry = state.current
    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "正在播放",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (entry == null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(14.dp))
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
                    .clip(RoundedCornerShape(14.dp))
                    .tvFocus(1.04f)
                    .clickable(onClick = onOpenPlayer)
            ) {
                Artwork(entry.artwork, Modifier.size(200.dp))
            }
            Text(
                entry.title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            )
            Text(
                entry.artist,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            // 当前歌词行
            val lrcText = if (state.lrcIndex in state.lrcLines.indices)
                state.lrcLines[state.lrcIndex].text else ""
            Text(
                lrcText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = {
                    if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun HomeTopBar(loading: Boolean, count: Int, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "发现",
            fontSize = 22.sp,
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
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .tvFocus()
                .clickable(onClick = onRefresh)
                .padding(horizontal = 16.dp, vertical = 7.dp)
        ) {
            Text("刷新", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp)
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ActionEntry("🔥", "推荐歌单", "按标签发现好歌单", Modifier.weight(1f), onOpenRecommend)
        ActionEntry("🏆", "排行榜", "各平台权威榜单", Modifier.weight(1f), onOpenTopList)
        ActionEntry("📻", "我的列表", "历史 / 收藏", Modifier.weight(1f), onOpenMyList)
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .tvFocus(1.03f)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
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
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 19.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .tvFocus()
                .clickable(onClick = onMore)
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(plugins, key = { it.info?.platform ?: it.name }) { p ->
            val platform = p.info?.platform ?: return@items
            FilterChip(
                label = p.name,
                selected = platform == currentPlatform,
                onClick = { onSelect(platform) }
            )
        }
    }
}

@Composable
private fun EmptyGuide(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "还没有可用的插件",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "启动时已自动同步默认订阅源，请稍候或手动导入：\n" +
                "· 设置 → 扫码同步（TVBox 式导入配置）\n" +
                "· 设置 → 粘贴订阅 URL / 插件 JS\n" +
                "· 手机浏览器访问本机接收地址推送配置",
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .tvFocus()
                .clickable(onClick = onRefresh)
                .padding(horizontal = 24.dp, vertical = 10.dp)
        ) {
            Text(
                "重新加载",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 15.sp
            )
        }
    }
}
