package chat.neto.krypta.core

/** Metadatos de un archivo entrante (llegan en el mensaje "meta", antes de los trozos). */
data class IncomingFileMeta(
    val name: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
)

/** Archivo ya reensamblado y escrito en disco. */
data class AssembledFile(
    val name: String,
    val mime: String,
    val size: Long,
    val path: String,
)

/**
 * Reensambla archivos recibidos por trozos (chunking). La meta y los trozos llegan como
 * mensajes independientes y en cualquier orden; esta interfaz los junta y, cuando están todos
 * (y hay meta), escribe el archivo en disco y devuelve su descriptor. La implementación
 * (con acceso a almacenamiento) vive en la capa de app; se inyecta vía Hilt.
 */
interface FileStore {
    /** Registra la meta; devuelve el archivo si los trozos ya estaban todos, o null. */
    suspend fun onMeta(fileId: String, meta: IncomingFileMeta): AssembledFile?

    /** Guarda un trozo; devuelve el archivo si con este se completó (y hay meta), o null. */
    suspend fun onChunk(fileId: String, index: Int, bytes: ByteArray): AssembledFile?

    /**
     * Borra los restos locales de un archivo al vaciar su chat: el staging pendiente y el
     * ensamblado de [fileId], y la copia local en [path] (p. ej. una nota de voz enviada)
     * si pertenece al almacén. No lanza si no existen.
     */
    suspend fun deleteLocal(fileId: String, path: String?)
}
