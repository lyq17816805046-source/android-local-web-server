package com.lyq.localwebserver

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * 使用 ZXing 生成二维码位图。
 */
object QRCodeGenerator {
    fun generate(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }
}