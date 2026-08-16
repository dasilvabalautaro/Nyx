package chat.neto.nyx.data.repository

import chat.neto.nyx.core.model.BlockedPeer
import chat.neto.nyx.core.repository.BlockRepository
import chat.neto.nyx.data.dao.BlockedPeerDao
import chat.neto.nyx.data.entity.BlockedPeerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomBlockRepository @Inject constructor(
    private val dao: BlockedPeerDao,
) : BlockRepository {

    override fun observeAll(): Flow<List<BlockedPeer>> =
        dao.observeAll().map { list -> list.map(::toDomain) }

    /**
     * Idempotente por diseño (INSERT OR IGNORE): re-bloquear conserva la fecha del primer
     * bloqueo. Un motivo nuevo sobre un bloqueo existente sí se guarda — es información que
     * el usuario acaba de escribir a propósito, y perderla en silencio sería peor.
     */
    override suspend fun block(peerId: String, reason: String?, now: Long) {
        val existing = dao.find(peerId)
        if (existing == null) {
            dao.insertIfAbsent(BlockedPeerEntity(peerId, now, reason))
        } else if (reason != null && reason != existing.reason) {
            dao.upsert(existing.copy(reason = reason))
        }
    }

    override suspend fun unblock(peerId: String) = dao.delete(peerId)

    override suspend fun isBlocked(peerId: String): Boolean = dao.isBlocked(peerId)

    private fun toDomain(e: BlockedPeerEntity) = BlockedPeer(
        peerId = e.peerId,
        blockedAt = e.blockedAt,
        reason = e.reason,
    )
}
