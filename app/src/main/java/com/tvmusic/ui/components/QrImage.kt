package com.tvmusic.ui.components

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Hashtable

/**
 * 将文本（如远程管理地址）渲染为二维码图片。
 * 手机相机/浏览器扫码即可直达该地址，无需 TV 端摄像头。
 * 使用 Dispatchers.Default 生成，避免阻塞主线程。
 */
@Composable
fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 300
) {
    var qrBitmap by remember(text, size) { mutableStateOf<ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(text, size) {
        if (text.isBlank()) { qrBitmap = null; return@LaunchedEffect }
        qrBitmap = withContext(Dispatchers.Default) {
            runCatching {
                val hints = Hashtable<EncodeHintType, Any>()
                hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
                hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
                hints[EncodeHintType.MARGIN] = 1
                val matrix: com.google.zxing.common.BitMatrix =
                    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        canvas.drawPoint(
                            x.toFloat(), y.toFloat(),
                            android.graphics.Paint().apply {
                                color = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                                isAntiAlias = false
                            }
                        )
                    }
                }
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    qrBitmap?.let {
        androidx.compose.foundation.Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
        )
    }
}
