package com.tvmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 导航与分区标题：主顶栏、页签、子页返回头栏、分区标题。
 */

@Composable
fun AppTitleBar(
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MusicFree TV",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 22.sp,
            modifier = Modifier.padding(end = 28.dp)
        )
        // H13：选中态高亮跟随当前页签（焦点记忆）。
        // 注意：页签**不主动抢焦点**（会与子页返回按钮的 tvInitialFocus 抢焦点打乒乓，
        // 两边都抢输、焦点落到全屏根节点死区）。页签只注册为焦点兜底目标：
        // 焦点丢失到根节点时由 MainActivity 重定向回当前页签（F11）。
        TabItem("首页", "home", selected, onSelect)
        TabItem("搜索", "search", selected, onSelect)
        TabItem("设置", "settings", selected, onSelect)
        TabItem("我的歌单", "mylist", selected, onSelect)
        TabItem("关于", "about", selected, onSelect)
    }
}

@Composable
private fun TabItem(
    label: String,
    key: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    val isSelected = selected == key
    val fr = remember { FocusRequester() }
    // 冷启动抢焦点不在这里做（FocusManager 无 hasFocus API，无法安全判断"整个焦点树无焦点"；
    // 无条件 requestFocus 是抢占式的，会抢走启动弹框的 initialFocus，2026-09-29 实测）。
    // 已上移到 MainActivity.App()：用根节点外层观察者的 hasFocus 判定全树无焦点后才抢。
    // 选中页签注册为焦点兜底目标：主页上弹层关闭/子页销毁等场景，
    // 根节点收到焦点后重定向到这里（F11）。
    val slot = LocalFocusFallback.current
    DisposableEffect(fr, isSelected) {
        if (!isSelected) return@DisposableEffect onDispose {}
        val prev = slot?.value
        if (slot != null) slot.value = fr
        onDispose { if (slot != null && slot.value === fr) slot.value = prev }
    }
    GlassButton(
        onClick = { onSelect(key) },
        modifier = Modifier
            .padding(end = 10.dp)
            .focusRequester(fr),
        highlight = isSelected,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) { focused ->
        Text(
            text = label,
            color = if (isSelected || focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 19.sp,
            fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 分区标题：主色竖条 + 标题。 */
@Composable
fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 主色竖条：分区标题统一视觉锚点
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
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/** 返回 + 标题头栏。用普通 Row（非 LazyRow）：LazyRow 的懒加载焦点作用域会吞掉
 *  tvInitialFocus 的 requestFocus，并把后续 D-pad 焦点搜索困在空作用域里，导致
 *  整页焦点丢失到根节点（真机遥控器上下左右全失灵，2026-09-28 推荐/排行/歌单详情
 *  共同根因）。标题 weight(1f)+省略号，长标题不再把返回键/trailing 挤出屏幕。 */
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 22.sp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** 进入页面时是否把初始焦点落到「返回」按钮。默认 true：从首页等带焦点页跳入
     *  子页面时，旧焦点节点销毁会导致焦点丢失（D-pad 无处移动/跳错），返回按钮
     *  拿到焦点后方向键即可向下进入内容区。 */
    initialFocus: Boolean = true
) {
    // 返回按钮共用同一个 FocusRequester：初始焦点抢占 + 焦点兜底注册必须指向同一节点
    // （同节点挂两个 focusRequester 只有最后一个生效）。
    val backFr = remember { FocusRequester() }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FocusTextButton(
            onClick = onBack,
            modifier = Modifier
                .let { if (initialFocus) it.tvInitialFocus(backFr) else it.focusRequester(backFr) }
                .tvFocusFallback(backFr),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
        }
        Text(
            text = title,
            fontSize = titleSize,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { trailing() }
        }
    }
}

/**
 * 「分区标题 + 右侧『更多 ›』」单行标题栏。
 * 首页推荐/排行榜两个分区都带这个行尾入口，收口为一份。
 * 布局与首页原实现逐像素一致：竖条 + 标题(weight 1f) + 右侧玻璃按钮，
 * 外边距 start 28 / end 20 / top 14 / bottom 6（与 SectionHeader 的 10dp 竖向不同，
 * 故不复用 SectionHeader，避免带出分区下方的额外留白）。
 */
@Composable
fun SectionWithMore(
    title: String,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
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
        GlassButton(
            onClick = onMore,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) { _ ->
            Text("更多 ›", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
