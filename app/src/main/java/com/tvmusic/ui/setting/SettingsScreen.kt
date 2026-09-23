package com.tvmusic.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.SectionHeader
import com.tvmusic.ui.components.tvFocus

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel
) {
    val plugins by viewModel.plugins.collectAsState()
    val subscribed by viewModel.subscribed.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val message by viewModel.message.collectAsState()
    val expandedVars by viewModel.expandedVars.collectAsState()
    val drafts by viewModel.drafts.collectAsState()

    var subUrl by remember { mutableStateOf("") }
    var pluginUrl by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize()) {

        item(key = "remote") {
            SectionHeader("远程管理（手机/电脑访问电视）")
            SettingsCard {
                val manageUrl = viewModel.remoteManageUrl
                Text(
                    "用手机相机或浏览器扫码 / 打开：",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp
                )
                Text(
                    manageUrl,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 17.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "可控制播放器与播放列表、搜索推歌、管理收藏专辑、订阅源与插件。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    com.tvmusic.ui.components.QrImage(
                        text = manageUrl,
                        modifier = Modifier.size(200.dp)
                    )
                }
            }
        }

        item(key = "sub") {
            SectionHeader("订阅源")
            SettingsCard {
                subscribed.forEach { url ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            url,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                        ActionButton("移除") { viewModel.removeSubscription(url) }
                    }
                }
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = { Text("粘贴订阅 URL（plugins.json）") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).tvFocus()
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ActionButton("添加订阅并同步") { viewModel.addSubscription(subUrl) }
                    ActionButton(if (syncing) "同步中…" else "立即同步全部") { viewModel.sync() }
                }
            }
        }

        item(key = "plugins") {
            SectionHeader("插件（${plugins.size}）")
            SettingsCard {
                plugins.forEach { plugin ->
                    val info = plugin.info
                    SettingsRow(
                        title = plugin.name.ifBlank { info?.platform ?: "?" },
                        subtitle = listOf(
                            info?.platform,
                            plugin.version ?: info?.version,
                            plugin.loadError?.let { "加载失败" }
                        ).filterNotNull().joinToString(" · ")
                    ) {
                        if (plugin.loadError.isNullOrBlank() && info != null && info.userVariables.isNotEmpty()) {
                            val key = info.platform
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ActionButton(if (key in expandedVars) "收起变量" else "配置变量") {
                                    viewModel.loadVarsAsDraft(key)
                                    viewModel.toggleExpanded(key)
                                }
                            }
                        }
                        Switch(
                            checked = plugin.enabled,
                            onCheckedChange = { viewModel.togglePlugin(plugin.name, it) },
                            modifier = Modifier.tvFocus()
                        )
                        ActionButton("卸载") { viewModel.uninstall(plugin.name) }
                    }

                    if (info != null && info.platform in expandedVars) {
                        // 变量编辑区
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                info.userVariables.forEach { v ->
                                    val pk = info.platform
                                    val draftVal = (drafts[pk] ?: emptyMap())[v.key] ?: ""
                                    OutlinedTextField(
                                        value = draftVal,
                                        onValueChange = { viewModel.setDraft(pk, v.key, it) },
                                        label = { Text("${v.name}（${v.key}）") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).tvFocus()
                                    )
                                }
                                Row(Modifier.padding(top = 8.dp)) {
                                    ActionButton("保存变量") { viewModel.saveVars(info.platform) }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = pluginUrl,
                    onValueChange = { pluginUrl = it },
                    label = { Text("粘贴单个插件 JS 地址（导入）") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).tvFocus()
                )
                Row(Modifier.padding(top = 8.dp)) {
                    ActionButton("导入插件") { viewModel.importSingle(pluginUrl) }
                }
            }
        }

        item(key = "player") {
            SectionHeader("播放设置")
            SettingsCard {
                Text("音质偏好", color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
                Text(
                    "传入插件 getMediaSource 的 quality 参数，部分音源仅部分档位可用。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val qualities = listOf("low" to "低", "standard" to "标准", "high" to "高", "super" to "无损")
                    qualities.forEach { (key, label) ->
                        val selected = com.tvmusic.player.PlayerManager.quality == key
                        Box(
                            modifier = Modifier
                                .tvFocus()
                                .clickable { com.tvmusic.player.PlayerManager.setQuality(key) }
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        item(key = "about") {
            SectionHeader("关于")
            SettingsCard {
                Text(
                    "MusicFree TV · QuickJS 运行 MusicFree 插件（musicfree-plugins 仓库）\n" +
                        "遥控器：方向键移动，OK/回车确认，返回键回退。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }

    message?.let {
        Box(Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) { ErrorBox(it) }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tvFocus()
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 14.sp
        )
    }
}