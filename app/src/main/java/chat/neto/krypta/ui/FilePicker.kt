package chat.neto.krypta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/** Datos de un archivo elegido, listos para enviar. */
data class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/** Lectura del file picker y apertura de archivos recibidos. */
object FilePicker {

    /** Lee [uri]: nombre, mime y bytes. Devuelve null si supera [maxBytes] o no se puede leer. */
    fun read(context: Context, uri: Uri, maxBytes: Int): PickedFile? {
        val resolver = context.contentResolver
        var name = "archivo"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size in (maxBytes.toLong() + 1)..Long.MAX_VALUE) return null // demasiado grande
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            // Lee acotado: si excede el límite, aborta (evita OOM con archivos enormes).
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        return PickedFile(name, mime, bytes)
    }

    /** Abre un archivo recibido con otra app (visor), vía FileProvider. */
    fun open(context: Context, path: String, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
