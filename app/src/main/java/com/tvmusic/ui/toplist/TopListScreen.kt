package com.tvmusic.ui.toplist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.ui.components.BackTopBar
import com.tvmusic.ui.components.EmptyState
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.FilterChip
import com.tvmusic.ui.components.LoadingBox
import com.tvmusic.ui.components.MediaCard
import com.tvmusic.ui.sheet.DetailKind
import com.tvmusic.ui.sheet.DetailTarget
import org.json.JSONObject

@Composable
fun TopListScreen(
    viewModel: TopListViewModel,
    onBack: () -> Unit,
    onOpenDetail: (DetailTarget) -> Unit
) {
    val plugins by viewModel.plugins.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = "排行榜", onBack = onBack)

        if (plugins.isEmpty() && !loading) {
            EmptyState(error ?: "已启用的插件均不提供排行榜")
            return@Column
        }

        // 插件页签
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(plugins, key = { it.info!!.platform }) { rec ->
                val platform = rec.info!!.platform
                FilterChip(
                    label = rec.name,
                    selected = platform == selectedPlatform,
                    onClick = { viewModel.selectPlugin(platform) }
                )
            }
        }

        when {
            loading && groups.isEmpty() -> LoadingBox()
            groups.isEmpty() -> ErrorBox(error, onRetry = viewModel::retry)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(groups, key = { it.title }) { group ->
                    Text(
                        text = group.title,
                        fontSize = 19.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(group.boards, key = { it.title + it.raw.optString("id") }) { board ->
                            MediaCard(
                                title = board.title,
                                subtitle = selectedPlatform,
                                artwork = board.artwork,
                                onClick = {
                                    onOpenDetail(
                                        DetailTarget.stamped(
                                            board.plugin,
                                            DetailKind.TOPLIST,
                                            JSONObject(board.raw.toString())
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}