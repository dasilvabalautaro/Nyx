package chat.neto.krypta.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Generación y formato del QR de verificación de identidad (anti-MITM). El QR codifica el
 * **propio** PeerID con un prefijo de esquema; la otra persona lo escanea y su app compara el
 * PeerID escaneado con el que tiene guardado para ese contacto (ver [parseVerifyPayload]): si
 * coinciden, nadie sustituyó el PeerID en el canal por el que se compartió.
 *
 * Solo generación aquí (ZXing puro, sin cámara); el escaneo lo hace `zxing-android-embedded`
 * vía `ScanContract`. Es la contraparte visual de [chat.neto.krypta.p2p] SafetyNumber.
 */
object QrCode {

    private const val SCHEME = "krypta:verify:"

    /** Contenido del QR que muestra este dispositivo: su propio PeerID con esquema. */
    fun verifyPayload(peerId: String): String = SCHEME + peerId

    /** Extrae el PeerID de un QR escaneado, o null si no es un QR de verificación de Krypta. */
    fun parseVerifyPayload(scanned: String): String? =
        scanned.trim().takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.takeIf { it.isNotBlank() }

    /** Codifica [content] como un QR cuadrado de [size] px (negro sobre blanco). */
    fun encode(content: String, size: Int = 512): ImageBitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp.asImageBitmap()
    }
}
