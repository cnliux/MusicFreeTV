package com.tvmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 页面状态视图：加载中 / 错误重试 / 空态 / 加载更多页脚。
 * 四个页面级状态在 TV 上表现一致，收口为一份避免各页自绘导致视觉漂移。
 */

/** 全屏加载中。 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** 错误提示条 + 可选「重试」按钮（直角纯文字按钮，与全应用按钮规范一致）。 */
@Composable
fun ErrorBox(message: String?, onRetry: (() -> Unit)? = null) {
    if (message == null) return
    Box(Modifier.fillMaxWidth().padding(28.dp)) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp
        )
        if (onRetry != null) {
            FocusTextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.TopEnd),
                shape = RoundedCornerShape(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("重试", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
        }
    }
}

/** 居中空态：统一图标?/文案 + 可选操作按钮。 */
@Composable
fun EmptyState(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        if (actionLabel != null && onAction != null) {
            GlassButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) { _ -> Text(actionLabel, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp) }
        }
    }
}

/** 列表底部加载态：加载中 / 错误重试 / 可加载更多 / 已到底，四态统一。 */
@Composable
fun LoadMoreFooter(
    loading: Boolean,
    hasMore: Boolean,
    error: String?,
    allLoadedText: String?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Text(
                "加载中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                DialogTextButton("重试", onLoadMore, MaterialTheme.colorScheme.primary)
            }
            hasMore -> GlassButton(
                onClick = onLoadMore,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) { _ -> Text("加载更多", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp) }
            allLoadedText != null -> Text(
                allLoadedText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
