package com.tvmusic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 弹层基础设施：遮罩色、ModalCard 骨架、统一进出场动画。
 */

/** 弹层遮罩色：全部 ModalCard 共用一份定义。
 *  遮罩色与主题无关——纯黑半透明是通用语义（任何主题下遮罩都希望是"压暗背景"），
 *  因此收敛为文件级常量而不走 Theme。 */
val ModalScrim = Color(0xAA000000)

/** 弹层统一进场/退场动画：淡入 + 0.96 倍缩放。
 *  背景：全 App 12 处弹层逐字复制了 `enter = fadeIn() + scaleIn(initialScale = 0.96f)` /
 *  `exit = fadeOut() + scaleOut(targetScale = 0.96f)`，任何一次调参都要改 12 处。 */
private val ModalEnter = fadeIn() + scaleIn(initialScale = 0.96f)
private val ModalExit = fadeOut() + scaleOut(targetScale = 0.96f)

/**
 * 弹层进出容器：等价于 `AnimatedVisibility` + 统一缩放淡入淡出动画。
 * 所有 ModalCard 型弹层的可见性包装统一走本组件（当前 12 处调用）。
 */
@Composable
fun ModalVisibility(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = ModalEnter, exit = ModalExit) {
        content()
    }
}

/** 只淡入淡出、不缩放的容器：用于播放页顶部提示胶囊（避免文字被缩放抖动）。 */
@Composable
fun FadeVisibility(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        content()
    }
}

/**
 * 居中弹层通用骨架：半透明遮罩 + 标题/副标题 + 内容插槽 + 底栏插槽（默认「关闭」按钮）。
 * 全应用弹层统一经此渲染，宽度/遮罩色不再各自为政。
 */
@Composable
fun ModalCard(
    title: String,
    subtitle: String? = null,
    width: Dp = 420.dp,
    onDismiss: (() -> Unit)? = null,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    /**
     * 弹层打开时的初始焦点目标（通常是主操作按钮的 FocusRequester）。
     *
     * 为什么需要它：弹层容器自身 focusable 但**不可点击**，旧实现把初始焦点给了容器，
     * 结果是遥控器按 OK 键毫无反应、视觉上也没有任何焦点框——用户看到的正是
     * "没有聚焦退出按钮"。焦点必须落在真正可操作的按钮上。
     * 传 null 时回退到容器（保持其余弹层的既有行为不变）。
     */
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onDismiss != null) {
        androidx.activity.compose.BackHandler { onDismiss() }
    }
    // 至少有一个可关闭/操作按钮时才有可聚焦子节点，可安全接管焦点
    val focusSafe = onDismiss != null || bottomBar != null
    val containerRequester = remember { FocusRequester() }
    LaunchedEffect(initialFocus, focusSafe) {
        if (!focusSafe) return@LaunchedEffect
        val target = initialFocus ?: containerRequester
        // 首帧节点可能尚未挂载，requestFocus 会抛 IllegalStateException，带重试
        repeat(20) {
            try {
                target.requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                kotlinx.coroutines.delay(80)
            }
        }
        // 目标迟迟挂不上（如空列表的首行不存在）：退回容器，保证焦点至少留在弹层内
        if (initialFocus != null) {
            try { containerRequester.requestFocus() } catch (_: IllegalStateException) {}
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModalScrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(14.dp))
                // 容器焦点仅作兜底 / D-pad 停留点，不再承担初始焦点。
                // focusRequester 必须排在 focusable 之前，请求才能命中该节点。
                .let {
                    if (focusSafe) it.focusRequester(containerRequester).focusable() else it
                }
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
            if (bottomBar != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) { bottomBar() }
            } else if (onDismiss != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    DialogTextButton("关闭", onDismiss)
                }
            }
        }
    }
}
