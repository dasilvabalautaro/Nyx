package chat.neto.nyx.data.repository

import chat.neto.nyx.core.model.Like
import chat.neto.nyx.core.model.LikeSource
import chat.neto.nyx.core.model.LikeState
import chat.neto.nyx.core.repository.LikeRepository
import chat.neto.nyx.data.dao.LikeDao
import chat.neto.nyx.data.entity.LikeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Persiste el estado de like. La decisión de si hay match **no** está aquí: la calcula
 * `LikeState` en `:core`, que es puro y testeable sin base de datos. Este repositorio lee la
 * fila, aplica la transición y la vuelve a escribir.
 */
class RoomLikeRepository @Inject constructor(
    private val dao: LikeDao,
) : LikeRepository {

    override fun observeAll(): Flow<List<Like>> =
        dao.observeAll().map { list -> list.map(::toDomain) }

    override fun observeMatches(): Flow<List<Like>> =
        dao.observeMatches().map { list -> list.map(::toDomain) }

    override suspend fun find(peerId: String): Like? = dao.find(peerId)?.let(::toDomain)

    override suspend fun recordSent(peerId: String, source: LikeSource, now: Long): Like =
        LikeState.applySent(find(peerId), peerId, source, now).also { dao.upsert(it.toEntity()) }

    override suspend fun recordReceived(peerId: String, source: LikeSource, now: Long): Like =
        LikeState.applyReceived(find(peerId), peerId, source, now).also { dao.upsert(it.toEntity()) }

    override suspend fun delete(peerId: String) = dao.delete(peerId)

    private fun toDomain(e: LikeEntity) = Like(
        peerId = e.peerId,
        sentAt = e.sentAt,
        receivedAt = e.receivedAt,
        matchedAt = e.matchedAt,
        source = LikeSource.read(e.source),
    )

    private fun Like.toEntity() = LikeEntity(
        peerId = peerId,
        sentAt = sentAt,
        receivedAt = receivedAt,
        matchedAt = matchedAt,
        source = source.name,
    )
}
