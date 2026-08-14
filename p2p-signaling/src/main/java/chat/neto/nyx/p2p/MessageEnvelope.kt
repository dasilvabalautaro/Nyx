package chat.neto.nyx.p2p

/**
 * Sobre de aplicación que viaja **dentro** del cifrado E2EE (el nodo/relay nunca lo ve). Lleva
 * el **id del mensaje del emisor**, para que el receptor pueda acusar recibo/lectura citándolo
 * (marca de leído), y un **tipo**, que abre la puerta a futuros contenidos (media). Formato
 * texto plano por líneas (va cifrado, así que su estructura no filtra nada):
 *
 *   TEXT:  "T\n<id>\n" ++ <cuerpo en bytes>     (el cuerpo puede contener cualquier byte)
 *   READ:  "R\n<id1>\n<id2>\n…"                 (acuse de lectura de esos ids)
 *   IMAGE: "I\n<id>\n" ++ <bytes JPEG>          (imagen comprimida en línea)
 *   FMETA: "F\n<fileId>\n<total>\n<size>\n<mime>\n<name>"  (anuncia un archivo troceado)
 *   FCHNK: "K\n<fileId>\n<index>\n" ++ <bytes del trozo>   (un trozo del archivo)
 *   FDESC: "D\n<size>\n<mime>\n<path>\n<name>"  (descriptor LOCAL del archivo; nunca se envía)
 *   CALL:  "C\n<kind>\n<callId>\n<ts>"  (señalización de llamada: invite/accept/reject/
 *          hangup/busy; ts = unix millis del emisor, para descartar invites rancios)
 *
 * Decodificar tolera bytes sin sobre (mensajes previos a esta versión) devolviendo null, para
 * que la capa superior los trate como texto legado.
 */
object MessageEnvelope {

    private const val NL = '\n'.code.toByte()

    sealed interface Decoded {
        /** Mensaje de chat: [id] del emisor + [body] (texto/plano en bytes). */
        data class Text(val id: String, val body: ByteArray) : Decoded
        /** Acuse de lectura de los mensajes [ids]. */
        data class Read(val ids: List<String>) : Decoded
        /** Imagen: [id] del emisor + [bytes] JPEG. */
        data class Image(val id: String, val bytes: ByteArray) : Decoded
        /** Meta de un archivo troceado. */
        data class FileMeta(
            val fileId: String, val name: String, val mime: String,
            val size: Long, val totalChunks: Int,
        ) : Decoded
        /** Un trozo de un archivo. */
        data class FileChunk(val fileId: String, val index: Int, val bytes: ByteArray) : Decoded
        /** Descriptor LOCAL de un archivo ya recibido/enviado (no viaja por la red). */
        data class FileDescriptor(
            val name: String, val mime: String, val size: Long, val path: String?,
        ) : Decoded
        /** Señal de llamada: [kind] ∈ invite/accept/reject/hangup/busy, [ts] = unix millis. */
        data class Call(val kind: String, val callId: String, val ts: Long) : Decoded
    }

    fun encodeText(id: String, body: ByteArray): ByteArray =
        "T\n$id\n".toByteArray(Charsets.UTF_8) + body

    fun encodeRead(ids: List<String>): ByteArray =
        ("R\n" + ids.joinToString("\n")).toByteArray(Charsets.UTF_8)

    fun encodeImage(id: String, jpeg: ByteArray): ByteArray =
        "I\n$id\n".toByteArray(Charsets.UTF_8) + jpeg

    fun encodeFileMeta(fileId: String, name: String, mime: String, size: Long, totalChunks: Int): ByteArray =
        "F\n$fileId\n$totalChunks\n$size\n$mime\n$name".toByteArray(Charsets.UTF_8)

    fun encodeFileChunk(fileId: String, index: Int, bytes: ByteArray): ByteArray =
        "K\n$fileId\n$index\n".toByteArray(Charsets.UTF_8) + bytes

    fun encodeFileDescriptor(name: String, mime: String, size: Long, path: String?): ByteArray =
        "D\n$size\n$mime\n${path ?: ""}\n$name".toByteArray(Charsets.UTF_8)

    fun encodeCall(kind: String, callId: String, ts: Long): ByteArray =
        "C\n$kind\n$callId\n$ts".toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 2 || bytes[1] != NL) return null
        return when (bytes[0].toInt().toChar()) {
            'T' -> decodeIdAndBody(bytes)?.let { (id, body) -> Decoded.Text(id, body) }
            'I' -> decodeIdAndBody(bytes)?.let { (id, body) -> Decoded.Image(id, body) }
            'R' -> {
                val rest = String(bytes, 2, bytes.size - 2, Charsets.UTF_8)
                Decoded.Read(rest.split("\n").filter { it.isNotBlank() })
            }
            'F' -> {
                // F\n<fileId>\n<total>\n<size>\n<mime>\n<name>  (todo texto; name puede llevar '\n')
                val parts = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split("\n", limit = 5)
                if (parts.size < 5) return null
                val total = parts[1].toIntOrNull() ?: return null
                val size = parts[2].toLongOrNull() ?: return null
                Decoded.FileMeta(parts[0], parts[4], parts[3], size, total)
            }
            'K' -> {
                // K\n<fileId>\n<index>\n<bytes>  (fileId/index texto; bytes binarios)
                val idEnd = bytes.indexOf(NL, from = 2)
                if (idEnd < 0) return null
                val idxEnd = bytes.indexOf(NL, from = idEnd + 1)
                if (idxEnd < 0) return null
                val fileId = String(bytes, 2, idEnd - 2, Charsets.UTF_8)
                val index = String(bytes, idEnd + 1, idxEnd - idEnd - 1, Charsets.UTF_8).toIntOrNull() ?: return null
                Decoded.FileChunk(fileId, index, bytes.copyOfRange(idxEnd + 1, bytes.size))
            }
            'C' -> {
                // C\n<kind>\n<callId>\n<ts>
                val parts = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split("\n", limit = 3)
                if (parts.size < 3) return null
                val ts = parts[2].toLongOrNull() ?: return null
                if (parts[0].isBlank() || parts[1].isBlank()) return null
                Decoded.Call(parts[0], parts[1], ts)
            }
            'D' -> {
                // D\n<size>\n<mime>\n<path>\n<name>
                val parts = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split("\n", limit = 4)
                if (parts.size < 4) return null
                val size = parts[0].toLongOrNull() ?: return null
                Decoded.FileDescriptor(parts[3], parts[1], size, parts[2].ifBlank { null })
            }
            else -> null
        }
    }

    /** Parsea el prefijo común `X\n<id>\n<body>` → (id, body). */
    private fun decodeIdAndBody(bytes: ByteArray): Pair<String, ByteArray>? {
        val idEnd = bytes.indexOf(NL, from = 2)
        if (idEnd < 0) return null
        val id = String(bytes, 2, idEnd - 2, Charsets.UTF_8)
        if (id.isBlank()) return null
        return id to bytes.copyOfRange(idEnd + 1, bytes.size)
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }
}
