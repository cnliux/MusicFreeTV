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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.ui.components.ErrorBox
import com.tvmusic.ui.components.SectionHeader
import com.tvmusic.ui.components.tvFocus

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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

        item(key = "theme") {
            SectionHeader("界面主题")
            SettingsCard {
                Text(
                    "选择配色方案，立即生效（手机远程管理页同步切换）。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                val currentTheme by com.tvmusic.ui.theme.ThemeManager.current.collectAsState()
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    com.tvmusic.ui.theme.ThemeManager.themes.forEach { t ->
                        val selected = t.id == currentTheme.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .tvFocus()
                                .clickable { com.tvmusic.ui.theme.ThemeManager.set(t.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(t.scheme.primary)
                            )
                            Text(
                                t.name,
                                fontSize = 12.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "lyric") {
            SectionHeader("歌词显示")
            SettingsCard {
                val cfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "显示歌词",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = cfg.enabled,
                        onCheckedChange = { on ->
                            com.tvmusic.ui.theme.LyricSettings.update(cfg.copy(enabled = on))
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                // 字号
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("字号", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    StepperButton("－") {
                        com.tvmusic.ui.theme.LyricSettings.update(
                            cfg.copy(fontSizeSp = (cfg.fontSizeSp - 2).coerceIn(10, 40))
                        )
                    }
                    Text(
                        "${cfg.fontSizeSp} sp",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                    StepperButton("＋") {
                        com.tvmusic.ui.theme.LyricSettings.update(
                            cfg.copy(fontSizeSp = (cfg.fontSizeSp + 2).coerceIn(10, 40))
                        )
                    }
                }
                // 颜色
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("颜色", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    val colorPresets = listOf("FFFFFF" to "白", "4A7DFF" to "蓝", "FF6B9D" to "粉", "FFB74D" to "橙", "34D399" to "绿")
                    colorPresets.forEach { (hex, name) ->
                        val sel = cfg.colorHex.equals(hex, true)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .tvFocus()
                                .clickable {
                                    com.tvmusic.ui.theme.LyricSettings.update(cfg.copy(colorHex = hex))
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(Modifier.size(18.dp).clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor("#$hex"))))
                            Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                // 位置（播放页歌词区对齐方式）
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("位置", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    listOf(
                        com.tvmusic.ui.theme.LyricPosition.TOP to "顶部",
                        com.tvmusic.ui.theme.LyricPosition.CENTER to "居中",
                        com.tvmusic.ui.theme.LyricPosition.BOTTOM to "底部"
                    ).forEach { (pos, name) ->
                        val sel = cfg.position == pos
                        Box(
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .tvFocus()
                                .clickable {
                                    com.tvmusic.ui.theme.LyricSettings.update(cfg.copy(position = pos))
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(name, fontSize = 13.sp,
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 字号加减按钮。 */
@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .tvFocus()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
    }
}