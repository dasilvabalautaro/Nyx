package chat.neto.nyx.ui

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
 * hasta que quepa bajo el límite del buzón (~60 KiB, dejando margen para el sobre + el
 * cifrado). Para resolución completa hará falta troceado (chunking) — v2. Solo aquí (usa las
 * APIs Bitmap de Android); el sobre/cifrado son agnósticos del formato.
 *
 * **Formato según transparencia**: JPEG para fotos, **WEBP_LOSSY para lo que tenga canal
 * alfa**. Los stickers y los emoji grandes del teclado son PNG/WebP con fondo transparente, y
 * el JPEG no tiene alfa: llegaban con el fondo en **negro**. WebP con pérdida sí conserva el
 * alfa, comprime igual o mejor, y el receptor lo abre con el mismo `BitmapFactory` — así que
 * no hace falta tocar el protocolo ni añadir dependencias.
 */
object ImageCodec {

    /** Límite del payload de imagen: el buzón admite 64 KiB de blob; dejamos aire. */
    private const val MAX_BYTES = 58 * 1024
    private const val MAX_DIMENSION = 1280

    /** Lee [uri], reduce y comprime bajo [MAX_BYTES]. Devuelve null si no se puede. */
    fun compress(context: Context, uri: Uri): ByteArray? {
        val original = decodeScaled(context, uri, MAX_DIMENSION) ?: return null
        return try {
            squeeze(original)
        } finally {
            original.recycle()
        }
    }

    /**
     * Comprime un [Bitmap] que ya está en memoria — el avatar que dibuja `AvatarRenderer`
     * (plan 3b.5). Mismo presupuesto que una imagen del chat, que no es casualidad: el tope de
     * 58 KiB del avatar sale de que la tarjeta del tablón tiene que caber en los 96 KiB del
     * nodo, y reutilizar este bucle evita que los dos límites se separen sin querer.
     *
     * **No recicla** el bitmap: lo trajo quien llama y puede seguir pintándolo en la vista
     * previa. La versión de [Uri] sí lo hace porque el bitmap lo creó ella.
     */
    fun compress(bitmap: Bitmap): ByteArray? = squeeze(bitmap)

    /** El regateo de calidad y tamaño, común a las dos entradas. */
    private fun squeeze(original: Bitmap): ByteArray? {
        val format = formatFor(original)
        var quality = 85
        var out = encode(original, format, quality)
        // Baja calidad mientras no quepa (suelo de 40 para no destrozar la imagen).
        while (out.size > MAX_BYTES && quality > 40) {
            quality -= 10
            out = encode(original, format, quality)
        }
        // Si aún no cabe, reduce a la mitad y reintenta una vez.
        if (out.size > MAX_BYTES) {
            val half = Bitmap.createScaledBitmap(
                original, max(1, original.width / 2), max(1, original.height / 2), true,
            )
            out = encode(half, format, 80)
            half.recycle()
        }
        return if (out.size <= MAX_BYTES) out else null
    }

    /** Decodifica una imagen recibida (JPEG o WebP) a [ImageBitmap] para pintarla. */
    fun decode(jpeg: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }.getOrNull()

    /** WebP si hay transparencia que preservar (sticker/emoji), JPEG si no (foto). */
    private fun formatFor(bitmap: Bitmap): Bitmap.CompressFormat =
        if (bitmap.hasAlpha()) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { bos ->
            bitmap.compress(format, quality, bos)
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
