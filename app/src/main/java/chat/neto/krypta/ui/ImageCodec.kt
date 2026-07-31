package chat.neto.krypta.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Compresión de imágenes para envío **en línea** (v1): reduce la resolución y baja la calidad
 * JPEG hasta que quepa bajo el límite del buzón (~60 KiB, dejando margen para el sobre + el
 * cifrado). Para resolución completa hará falta troceado (chunking) — v2. Solo aquí (usa las
 * APIs Bitmap de Android); el sobre/cifrado son agnósticos del formato.
 */
object ImageCodec {

    /** Límite del payload de imagen: el buzón admite 64 KiB de blob; dejamos aire. */
    private const val MAX_BYTES = 58 * 1024
    private const val MAX_DIMENSION = 1280

    /** Lee [uri], reduce y comprime a JPEG bajo [MAX_BYTES]. Devuelve null si no se puede. */
    fun compress(context: Context, uri: Uri): ByteArray? {
        val original = decodeScaled(context, uri, MAX_DIMENSION) ?: return null
        try {
            var quality = 85
            var out = jpeg(original, quality)
            // Baja calidad mientras no quepa (suelo de 40 para no destrozar la imagen).
            while (out.size > MAX_BYTES && quality > 40) {
                quality -= 10
                out = jpeg(original, quality)
            }
            // Si aún no cabe, reduce a la mitad y reintenta una vez.
            if (out.size > MAX_BYTES) {
                val half = Bitmap.createScaledBitmap(
                    original, max(1, original.width / 2), max(1, original.height / 2), true,
                )
                out = jpeg(half, 80)
                half.recycle()
            }
            return if (out.size <= MAX_BYTES) out else null
        } finally {
            original.recycle()
        }
    }

    /** Decodifica un JPEG (recibido) a [ImageBitmap] para pintarlo. */
    fun decode(jpeg: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }.getOrNull()

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { bos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bos.toByteArray()
        }

    /** Carga la imagen submuestreada para que su lado mayor no supere [maxDim] (ahorra RAM). */
    private fun decodeScaled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }
}
