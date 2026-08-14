package chat.neto.nyx.p2p

import chat.neto.nyx.core.KeyExchange
import chat.neto.nyx.nativebridge.Libp2pNode
import javax.inject.Inject
import javax.inject.Singleton

/** [KeyExchange] respaldado por la identidad del nodo libp2p ([Libp2pNode]). */
@Singleton
class Libp2pKeyExchange @Inject constructor(
    private val node: Libp2pNode,
) : KeyExchange {

    override fun localPeerId(): String = node.localPeerId()

    override fun sharedSecretWith(peerId: String): ByteArray = node.sharedSecretWith(peerId)
}
