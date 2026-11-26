package media.sovereign.sovemu.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code as its modules: `modules[y][x]` is dark. Drawn by the pairing screen, one square
 * per module, so it stays sharp at any size. Medium error correction, and no quiet zone: the
 * screen draws its own light margin around it.
 */
class QrCode private constructor(val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        fun encode(text: String): QrCode {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            // Size 0 asks for one pixel per module.
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrCode(size, BooleanArray(size * size) { matrix.get(it % size, it / size) })
        }
    }
}
