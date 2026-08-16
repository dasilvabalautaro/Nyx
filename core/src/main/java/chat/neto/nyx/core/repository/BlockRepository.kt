package chat.neto.nyx.core.repository

import chat.neto.nyx.core.model.BlockedPeer
import kotlinx.coroutines.flow.Flow

/** Peers bloqueados. Ver [chat.neto.nyx.core.model.BlockedPeer]: local, unilateral, silencioso. */
interface BlockRepository {

    fun observeAll(): Flow<List<BlockedPeer>>

    /** Idempotente: volver a bloquear a alguien ya bloqueado no cambia la fecha original. */
    suspend fun block(peerId: String, reason: String? = null, now: Long = System.currentTimeMillis())

    suspend fun unblock(peerId: String)

    suspend fun isBlocked(peerId: String): Boolean
}
