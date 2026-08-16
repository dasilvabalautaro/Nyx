package chat.neto.nyx.p2p

import chat.neto.nyx.core.model.BlockedPeer
import chat.neto.nyx.core.model.Like
import chat.neto.nyx.core.model.LikeSource
import chat.neto.nyx.core.model.LikeState
import chat.neto.nyx.core.repository.BlockRepository
import chat.neto.nyx.core.repository.LikeRepository
import kotlinx.coroutines.flow.flowOf

/**
 * Repositorios en memoria compartidos por los tests de `:p2p-signaling`. Están aquí y no
 * duplicados en cada test porque los usan tanto `ChatServiceTest` como `CallServiceTest`.
 */

internal class FakeBlocks(blocked: Set<String> = emptySet()) : BlockRepository {
    val store = blocked.associateWith { BlockedPeer(it, 0L) }.toMutableMap()
    override fun observeAll() = flowOf(store.values.toList())
    override suspend fun block(peerId: String, reason: String?, now: Long) {
        store.getOrPut(peerId) { BlockedPeer(peerId, now, reason) }
    }
    override suspend fun unblock(peerId: String) { store.remove(peerId) }
    override suspend fun isBlocked(peerId: String) = store.containsKey(peerId)
}

/** Usa la máquina de estados real, para que los tests ejerciten la lógica de match de verdad. */
internal class FakeLikes : LikeRepository {
    val store = mutableMapOf<String, Like>()
    /** Simula un fallo transitorio de Room al persistir (disco lleno, etc.). */
    var failOnWrite = false
    override fun observeAll() = flowOf(store.values.toList())
    override fun observeMatches() = flowOf(store.values.filter { it.isMatch })
    override suspend fun find(peerId: String) = store[peerId]
    override suspend fun recordSent(peerId: String, source: LikeSource, now: Long): Like {
        if (failOnWrite) error("Room: disco lleno")
        return LikeState.applySent(store[peerId], peerId, source, now).also { store[peerId] = it }
    }
    override suspend fun recordReceived(peerId: String, source: LikeSource, now: Long): Like {
        if (failOnWrite) error("Room: disco lleno")
        return LikeState.applyReceived(store[peerId], peerId, source, now).also { store[peerId] = it }
    }
    override suspend fun delete(peerId: String) { store.remove(peerId) }
}
