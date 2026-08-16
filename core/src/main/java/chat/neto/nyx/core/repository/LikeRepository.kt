package chat.neto.nyx.core.repository

import chat.neto.nyx.core.model.Like
import chat.neto.nyx.core.model.LikeSource
import kotlinx.coroutines.flow.Flow

/**
 * Estado de "me gusta" / match con cada peer. La detección de mutualidad vive en
 * `LikeState` (pura, en `:core`); esta interfaz solo la persiste.
 */
interface LikeRepository {

    fun observeAll(): Flow<List<Like>>

    /** Solo los matches (mutuos), que son los que habilitan mensajería. */
    fun observeMatches(): Flow<List<Like>>

    suspend fun find(peerId: String): Like?

    /**
     * Registra que **yo** doy like a [peerId] y devuelve el estado resultante — que ya viene
     * con `matchedAt` puesto si el peer me había dado like antes.
     */
    suspend fun recordSent(peerId: String, source: LikeSource, now: Long = System.currentTimeMillis()): Like

    /** Registra que [peerId] me da like a **mí**. Devuelve el estado resultante. */
    suspend fun recordReceived(peerId: String, source: LikeSource, now: Long = System.currentTimeMillis()): Like

    /** Olvida el estado de like con [peerId] (al bloquear o eliminar el peer). */
    suspend fun delete(peerId: String)
}
