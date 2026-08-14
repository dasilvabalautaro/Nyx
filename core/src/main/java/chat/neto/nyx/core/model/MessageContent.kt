package chat.neto.krypta.core.model

/**
 * Contenido de un mensaje ya descifrado, listo para pintar. Distingue el tipo (texto vs
 * imagen…) sin exponer el formato de transporte. Preparado para más tipos (archivo, audio).
 */
sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    /** Imagen JPEG (bytes ya descifrados). */
    data class Image(val jpeg: ByteArray) : MessageContent
    /**
     * Archivo adjunto. [localPath] apunta al fichero ya ensamblado en disco (null si aún no
     * está disponible localmente, p. ej. la copia del emisor). Abrir requiere [localPath].
     */
    data class File(
        val name: String,
        val mime: String,
        val size: Long,
        val localPath: String?,
    ) : MessageContent
}
