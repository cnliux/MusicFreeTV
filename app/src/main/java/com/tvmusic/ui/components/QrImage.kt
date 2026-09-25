package com.tvmusic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Hashtable

/**
 * 将文本（如远程管理地址）渲染为二维码图片。
 * 手机相机/浏览器扫码即可直达该地址，无需 TV 端摄像头。
 */
@Composable
fun QrImage(
    text: String,
    sizePx: Int = 512,
    modifier: Modifier = Modifier
) {
    // 512×512 逐像素填充耗时，放后台线程生成，避免卡 UI 线程
    val bitmap by produceState<Bitmap?>(null, text, sizePx) {
        value = withContext(Dispatchers.Default) {
            runCatching { generateQr(text, sizePx) }.getOrNull()
        }
    }
    val bmp = bitmap ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "二维码",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(8.dp)
    )
}

private fun generateQr(text: String, size: Int): Bitmap {
    val hints = Hashtable<com.google.zxing.EncodeHintType, Any>()
    hints[com.google.zxing.EncodeHintType.CHARACTER_SET] = "UTF-8"
    hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    hints[com.google.zxing.EncodeHintType.MARGIN] = 1
    val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}
