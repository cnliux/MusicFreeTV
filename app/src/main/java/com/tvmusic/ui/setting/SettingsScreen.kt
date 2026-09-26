package com.tvmusic.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.tvmusic.plugin.PlatformHealth
import kotlinx.coroutines.delay
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
    val health by viewModel.health.collectAsState()
    val syncReport by viewModel.syncReport.collectAsState()
    val healthByPlatform = remember(health) { health.associateBy { it.platform } }

    LaunchedEffect(message) {
        if (message != null) {
            delay(3_000)
            viewModel.clearMessage()
        }
    }

    var subUrl by remember { mutableStateOf("") }
    var pluginUrl by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize()) {
        // 提示信息置于首项：打开设置即可见，3s 后自动消失
        message?.let { msg ->
            item(key = "__message__") {
                Box(Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) { ErrorBox(msg) }
            }
        }

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

        item(key = "eq") {
            SectionHeader("音效")
            SettingsCard {
                // 预设列表来自播放器音效实例：进入设置页即确保播放器已创建（否则列表只有"原声"）
                androidx.compose.runtime.LaunchedEffect(Unit) { com.tvmusic.player.PlayerManager.ensurePlayer() }
                val pmState by com.tvmusic.player.PlayerManager.screenState.collectAsState(initial = com.tvmusic.player.PlayerManager.uiState.value)
                val presets by com.tvmusic.player.PlayerManager.eqPresets.collectAsState()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "均衡器 / 低音增强",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = pmState.eqEnabled,
                        onCheckedChange = { com.tvmusic.player.PlayerManager.setEqEnabled(it) },
                        modifier = Modifier.tvFocus()
                    )
                }
                if (pmState.eqEnabled) {
                    Text(
                        "预设",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        presets.forEachIndexed { idx, name ->
                            val active = idx == pmState.eqPreset
Box(
                                 modifier = Modifier
                                     .clip(RoundedCornerShape(8.dp))
                                     .background(
                                         if (active) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.surfaceVariant
                                     )
                                     .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                                     .clickable { com.tvmusic.player.PlayerManager.setEqPreset(idx) }
                                     .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    name,
                                    fontSize = 12.sp,
                                    color = if (active) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "低音增强  ${pmState.bassStrength / 10}%",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton("－") { com.tvmusic.player.PlayerManager.setBassStrength(pmState.bassStrength - 100) }
                        Spacer(Modifier.width(10.dp))
                        ActionButton("＋") { com.tvmusic.player.PlayerManager.setBassStrength(pmState.bassStrength + 100) }
                    }
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
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).tvFocus(shapeOverride = RoundedCornerShape(10.dp))
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ActionButton("添加订阅并同步") { viewModel.addSubscription(subUrl) }
                    ActionButton(if (syncing) "检查中…" else "检查插件更新") { viewModel.sync() }
                }
                // 手动"检查更新"的结果汇总：新装 / 更新 / 失败分组列出
                val report = syncReport
                if (report != null && !syncing) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(
                            buildString {
                                append("本次检查：订阅声明 ${report.totalSeen} 个插件")
                                if (report.updated.isEmpty() && report.installed.isEmpty() && report.failed.isEmpty()) {
                                    append("，全部已是最新")
                                }
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                        if (report.updated.isNotEmpty()) {
                            Text(
                                "更新（${report.updated.size}）：${report.updated.joinToString("、")}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (report.installed.isNotEmpty()) {
                            Text(
                                "新装（${report.installed.size}）：${report.installed.joinToString("、")}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (report.failed.isNotEmpty()) {
                            Text(
                                "失败（${report.failed.size}）：${report.failed.joinToString("、")}",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "plugins-header") {
            SectionHeader("插件（${plugins.size}）")
            // 健康状态为本次运行统计（内存态，重启后清零）
            Text(
                "健康状态为本次运行统计（内存态，重启后清零）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        items(plugins, key = { it.name }) { plugin ->
            val info = plugin.info
            val h = info?.platform?.let { healthByPlatform[it] }
            SettingsCard {
                SettingsRow(
                    title = plugin.name.ifBlank { info?.platform ?: "?" },
                    subtitle = buildString {
                        if (h != null && h.calls > 0) {
                            append("调用${h.calls}次/失败${h.fails} · ")
                        }
                        append(
                            listOf(
                                info?.platform,
                                plugin.version ?: info?.version,
                                plugin.loadError?.let { "加载失败" }
                            ).filterNotNull().joinToString(" · ")
                        )
                        if (h != null && h.fails > 0 && !h.lastError.isNullOrBlank()) {
                            append(" · 最近错误：${h.lastError}")
                        }
                    },
                    dotColor = healthDotColor(h)
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
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).tvFocus(shapeOverride = RoundedCornerShape(8.dp))
                                )
                            }
                            Row(Modifier.padding(top = 8.dp)) {
                                ActionButton("保存变量") { viewModel.saveVars(info.platform) }
                            }
                        }
                    }
                }
            }
        }

        item(key = "plugins-import") {
            SettingsCard {
                OutlinedTextField(
                    value = pluginUrl,
                    onValueChange = { pluginUrl = it },
                    label = { Text("粘贴单个插件 JS 地址（导入）") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).tvFocus(shapeOverride = RoundedCornerShape(10.dp))
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
                                 .clickable { com.tvmusic.player.PlayerManager.setQuality(key) }
                                 .background(
                                     if (selected) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surfaceVariant,
                                     RoundedCornerShape(8.dp)
                                 )
                                 .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
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
                                .tvFocus(shapeOverride = RoundedCornerShape(12.dp))
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
            SectionHeader("播放页歌词")
            SettingsCard {
                val cfg by com.tvmusic.ui.theme.LyricSettings.config.collectAsState()
                // 字号
                Row(
                    Modifier.fillMaxWidth(),
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
                                .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
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
    dotColor: Color? = null,
    actions: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 健康度小圆点（绿/黄/红/灰），无统计信息时不占位
                if (dotColor != null) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

/** 健康圆点颜色：成功率 ≥90% 绿、≥60% 黄、否则红；无统计或调用数为 0 显示灰。 */
private fun healthDotColor(h: PlatformHealth?): Color {
    if (h == null || h.calls <= 0) return Color(0xFF8A8A8A)
    val rate = (h.calls - h.fails).toFloat() / h.calls.toFloat()
    return when {
        rate >= 0.9f -> Color(0xFF34D399)
        rate >= 0.6f -> Color(0xFFFFB74D)
        else -> Color(0xFFEF5350)
    }
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .semantics { contentDescription = label }
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(8.dp)
            )
            .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
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
            .semantics { contentDescription = label }
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
    }
}