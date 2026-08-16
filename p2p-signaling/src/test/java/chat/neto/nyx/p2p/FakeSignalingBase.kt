package chat.neto.nyx.p2p

import chat.neto.nyx.core.CallStream
import chat.neto.nyx.core.ISignalingService
import chat.neto.nyx.core.SignalingEvent
import chat.neto.nyx.core.model.Contact
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Implementación vacía de [ISignalingService] para tests que solo necesitan una o dos de sus
 * operaciones. Cada test sobreescribe lo suyo y se olvida del resto.
 *
 * Existe porque la interfaz ha crecido bastante (mensajes, buzón, wake, llamadas, vídeo,
 * tablón, likes) y arrastrar treinta `override` vacíos a cada test escondía lo que el test de
 * verdad estaba comprobando.
 */
internal open class FakeSignalingBase(private val selfPeerId: String = "12D3KooWSelf") : ISignalingService {

    override val events = MutableSharedFlow<SignalingEvent>()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun announce(rendezvous: ByteArray) = Unit
    override suspend fun findPeers(rendezvous: ByteArray): List<String> = emptyList()
    override suspend fun bootstrap(): String? = null
    override suspend fun setBootstrap(addr: String) = Unit
    override suspend fun connectDht(bootstrap: String) = Unit
    override suspend fun selfAddrs(): List<String> = listOf("/ip4/127.0.0.1/tcp/1/p2p/$selfPeerId")
    override suspend fun reserveRelay(): String = ""
    override suspend fun pingProbe(count: Int, intervalMs: Int): String = ""

    override suspend fun send(contact: Contact, ciphertext: ByteArray) = Unit
    override suspend fun sendOffline(contact: Contact, ciphertext: ByteArray) = Unit
    override suspend fun fetchMailbox(): Int = 0
    override fun setMailboxProcessor(
        processor: suspend (fromPeerId: String, ciphertext: ByteArray, envelopeId: String, timestamp: Long) -> Boolean,
    ) = Unit

    override suspend fun publishCard(category: String, card: ByteArray) = Unit
    override suspend fun queryBoard(category: String, limit: Int): String = "[]"
    override suspend fun deleteCard(category: String) = Unit
    override suspend fun sendLike(toPeerId: String, ciphertext: ByteArray) = Unit
    override suspend fun fetchLikes(): Int = 0
    override fun setLikeProcessor(
        processor: suspend (fromPeerId: String, ciphertext: ByteArray, timestamp: Long) -> Boolean,
    ) = Unit

    override suspend fun startWake() = Unit
    override suspend fun stopWake() = Unit
    override suspend fun wakeConnected(): Boolean = false

    override val incomingCallStreams = MutableSharedFlow<Pair<String, CallStream>>()
    override suspend fun openCallStream(contact: Contact): CallStream = error("sin llamadas en este fake")
    override val incomingVideoStreams = MutableSharedFlow<Pair<String, CallStream>>()
    override suspend fun openVideoStream(contact: Contact): CallStream = error("sin vídeo en este fake")
}
