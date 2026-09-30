package com.tvmusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 歌词行块：播放页歌词区每行渲染（主行 + 译文），当前行加粗放大高亮。
 */
@Composable
fun LyricLineBlock(
    line: com.tvmusic.player.LrcLine,
    isCurrent: Boolean,
    lrcColor: Color,
    fontSizeSp: Float
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = line.text,
            color = if (isCurrent) lrcColor else lrcColor.copy(alpha = 0.35f),
            fontSize = if (isCurrent) (fontSizeSp + 4).sp else fontSizeSp.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        line.translation?.takeIf { it.isNotBlank() }?.let { t ->
            Text(
                text = t,
                color = if (isCurrent) lrcColor.copy(alpha = 0.85f) else lrcColor.copy(alpha = 0.28f),
                fontSize = (fontSizeSp - 2f).coerceAtLeast(10f).sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
