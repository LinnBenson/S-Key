package io.secure.keyboard.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ClipboardQr(text: String) {
    val result by produceState<Result<Bitmap>?>(null, text) {
        value = withContext(Dispatchers.Default) {
            try {
                // Even numeric-only QR codes cannot exceed 7089 characters.
                if (text.length > 7089) throw WriterException()
                val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Result.success(Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888))
            } catch (e: WriterException) {
                Result.failure(e)
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val bitmap = result?.getOrNull()
        when {
            bitmap != null -> Image(remember(bitmap) { bitmap.asImageBitmap() }, "剪贴板内容二维码",
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f),
                filterQuality = FilterQuality.None)
            result == null -> Text("正在生成二维码…")
            else -> Text("内容太长，无法放入一个二维码。")
        }
    }
}
