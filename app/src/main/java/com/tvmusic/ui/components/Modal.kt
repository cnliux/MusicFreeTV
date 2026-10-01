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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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
 * 弹层打开计数（P0-2）：弹层改用 Dialog 独立窗口后，主窗口会短暂"整树无焦点"
 * （焦点在弹层窗口）。MainActivity 的 200ms 焦点观察器必须靠这个计数停手，
 * 否则会把焦点从弹层抢回底页/顶栏页签——即"弹框开着 OK 却作用在背后"的根因。
 */
object ModalWindows {
    private val count = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastOpenedAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** 是否有弹层处于打开状态。 */
    val anyOpen: Boolean get() = count.get() > 0

    /** 最近一次弹层打开的时间戳（毫秒）。给"等弹层都落定再动作"的逻辑用。 */
    fun lastOpenedAtMillis(): Long = lastOpenedAt.get()

    internal fun acquire() {
        lastOpenedAt.set(System.currentTimeMillis())
        count.incrementAndGet()
    }

    internal fun release() {
        count.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}

/**
 * 居中弹层通用骨架：半透明遮罩 + 标题/副标题 + 内容插槽 + 底栏插槽（默认「关闭」按钮）。
 * 全应用弹层统一经此渲染，宽度/遮罩色不再各自为政。
 *
 * P0-2：整个弹层跑在 Compose [Dialog] 的**独立窗口**里。原来只是一个铺满屏的 Box
 * 画层遮罩，遮罩不吃键、不拦焦点，D-pad 焦点能从弹层按钮逃到顶栏页签或底页列表
 * （冷启动「继续播放」弹框按 UP 就切页，按 DOWN 就在弹框背后播歌）。窗口级焦点
 * 隔离由系统保证，比在单窗口里手写焦点围栏可靠，也不用给底页每个可聚焦节点加锁。
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
    // 至少有一个可关闭/操作按钮时才有可聚焦子节点，可安全接管焦点
    val focusSafe = onDismiss != null || bottomBar != null
    val containerRequester = remember { FocusRequester() }
    // 弹层窗口登记：观察器据此判断"整树无焦点"只是焦点去了弹层窗口（P0-2）
    DisposableEffect(Unit) {
        ModalWindows.acquire()
        onDispose { ModalWindows.release() }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = androidx.compose.ui.window.DialogProperties(
            // 返回键/点击外部都不交给平台：交给系统默认行为会绕过调用方的状态流转
            // （平台会把窗口直接关掉，调用方的 showXxx 状态还开着，下次重组又弹回来）。
            // 返回键改由下面的窗口 KeyListener 统一处理——见那里的说明。
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            // 去掉平台默认的窗口宽度限制（对话框默认 WRAP_CONTENT，遮罩铺不满屏）
            usePlatformDefaultWidth = false
        )
    ) {
        if (onDismiss != null) {
            androidx.activity.compose.BackHandler { onDismiss() }
        }
        // 弹层窗口铺满全屏 + 关掉系统 dim：Dialog 默认把窗口包在内容尺寸里、并在背后
        // 叠一层 dim，与我们自己画的 ModalScrim 叠加会又暗又漏边。
        val dialogView = LocalView.current
        val dismissState = rememberUpdatedState(onDismiss)
        DisposableEffect(dialogView) {
            val win = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            win?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            win?.setDimAmount(0f)
            // 返回键必须在**窗口层**拦，不能靠 Compose 的 BackHandler：弹层是独立窗口，BACK
            // 只进这个窗口的 View 树，而弹层内容里的 BackHandler 注册在 Activity 的
            // OnBackPressedDispatcher 上（拿不到窗口自己的），于是 BACK 被窗口吞掉、什么
            // 都不发生——实测所有弹层返回键集体失效（「继续播放」「退出应用」「崩溃提示」
            // 全按 BACK 关不掉，焦点还被甩回容器）。注意不能直接调 win.setOnKeyListener：
            // PhoneWindow 会覆盖它（PhoneWindow.installDecor 每次都重新装自己的），
            // 必须经 decorView.setOnKeyListener 落到 DecorView 上才留得住。
            win?.decorView?.setOnKeyListener(android.view.View.OnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    dismissState.value?.invoke()
                    true
                } else {
                    false
                }
            })
            onDispose { win?.decorView?.setOnKeyListener(null) }
        }
        LaunchedEffect(initialFocus, focusSafe) {
            if (!focusSafe) return@LaunchedEffect
            val target = initialFocus ?: containerRequester
            // 弹层跑在独立 Dialog 窗口里，平台会在"窗口获得焦点"那一刻把初始焦点给
            // **第一个可聚焦节点**（=下面的容器 Column）。这跟这里的 requestFocus 是竞争
            // 关系：请求"成功"并不代表焦点留得住，平台那次默认赋值会紧接着把它抢回容器
            // ——实测冷启动「继续播放」弹框焦点停在容器上、按 OK 无反应。所以不能只请求
            // 一次，要在窗口拿到焦点后的几拍里重复送同一目标，直到稳为止。
            // 节奏取 0/90/220/420ms：覆盖首帧未挂载（抛异常）与平台默认初始焦点两段竞争，
            // 又短到用户不可能在这段时间里主动挪走焦点。
            var attached = false
            for (d in longArrayOf(0L, 90L, 220L, 420L)) {
                if (d > 0L) kotlinx.coroutines.delay(d)
                try {
                    target.requestFocus()
                    attached = true
                } catch (_: Throwable) {
                    // 目标节点尚未挂载（首帧），下一拍再试
                }
            }
            // 目标始终挂不上（如空列表的首行不存在）：退回容器，保证焦点至少留在弹层内
            if (!attached && initialFocus != null) {
                try { containerRequester.requestFocus() } catch (_: Throwable) {}
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ModalScrim)
                // 返回键：Compose 的 BackHandler 在弹层里是死键——它注册到的是 Activity 的
                // OnBackPressedDispatcher（Dialog 内容拿不到窗口自己的 dispatcher，
                // LocalOnBackPressedDispatcherOwner 沿 view 树解析会落到 Activity），
                // 而 BACK 事件只进弹层这个窗口，永远派发不到那里。实测所有弹层按 BACK
                // 都关不掉，焦点还被甩回容器。所以直接在 Compose 的按键管线里拦：
                // onPreviewKeyEvent 走的是"从根到焦点节点"的预扫描，不依赖 dispatcher。
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyUp && ev.key == Key.Back) {
                        dismissState.value?.invoke()
                        true
                    } else {
                        false
                    }
                },
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
}
