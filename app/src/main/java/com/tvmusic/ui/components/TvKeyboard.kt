package com.tvmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV 内置键盘（遥控器友好，设备无中文输入法时唯一可用的输入手段）。
 *
 * 为什么要有它：TV 上系统输入法经常缺失或只有英文手写，用户想搜「周杰伦」连字都敲不出来。
 * 配合 [com.tvmusic.utils.Pinyin] 的首字母匹配，**只敲 26 个字母就能搜中文歌**
 * （`zjl` → 周杰伦），所以盘面上只放字母，不放任何符号页/大小写切换。
 *
 * 布局固定 **5 行 × 6 列**、按 ABC 顺序逐行填充：搜索场景输入的都是拼音首字母，
 * ABC 顺序"找字母"比 QWERTY 快得多；定行定列则保证 D-pad 上下移焦点时列能对齐
 * （FlowRow 自适应换行会让"下移一格"落到哪个键不可预测）。
 */
private const val KEY_SPACE = "空格"
private const val KEY_BACK = "⌫"
private const val KEY_CLEAR = "清空"
private const val KEY_GO = "搜索"

private val KEY_ROWS = listOf(
    listOf("a", "b", "c", "d", "e", "f"),
    listOf("g", "h", "i", "j", "k", "l"),
    listOf("m", "n", "o", "p", "q", "r"),
    listOf("s", "t", "u", "v", "w", "x"),
    listOf("y", "z", KEY_SPACE, KEY_BACK, KEY_CLEAR, KEY_GO)
)

private val KEY_SHAPE = RoundedCornerShape(8.dp)

/**
 * @param onText      追加一个字符
 * @param onBackspace 删一个字符
 * @param onClear     清空整串
 * @param onSubmit    立即搜索（不等防抖）
 */
@Composable
fun TvKeyboard(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        KEY_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { key ->
                    KeyCap(
                        label = key,
                        accent = key == KEY_GO || key == KEY_CLEAR,
                        description = when (key) {
                            KEY_BACK -> "退格"
                            KEY_SPACE -> "空格"
                            KEY_CLEAR -> "清空输入"
                            KEY_GO -> "立即搜索"
                            else -> key
                        },
                        onClick = {
                            when (key) {
                                KEY_BACK -> onBackspace()
                                KEY_SPACE -> onText(" ")
                                KEY_CLEAR -> onClear()
                                KEY_GO -> onSubmit()
                                else -> onText(key)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 单个键帽：宽度按列等分（定 6 列），48dp 高保证遥控器焦点框在 10 英尺外看得清。 */
@Composable
private fun RowScope.KeyCap(
    label: String,
    accent: Boolean = false,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .background(
                if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceVariant,
                KEY_SHAPE
            )
            // tvFocus 必须在 clickable 之前（全 App 修饰符顺序红线：焦点观测器只认自身与更内层焦点节点）
            .tvFocus(shapeOverride = KEY_SHAPE)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (label.length > 2) 17.sp else 20.sp,
            fontWeight = FontWeight.Medium,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
