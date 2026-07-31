package chat.neto.krypta.core

/**
 * Acuerdo de claves basado en la identidad libp2p. El secreto compartido con un contacto
 * se deriva por ECDH (X25519) entre nuestra clave privada y la pública del contacto, que
 * va embebida en su PeerID — por eso para dar de alta a alguien basta con su PeerID.
 */
interface KeyExchange {
    /** PeerID de este dispositivo (identidad pública a compartir). */
    fun localPeerId(): String

    /** Secreto compartido (32 B) con el contacto identificado por [peerId]. */
    fun sharedSecretWith(peerId: String): ByteArray
}
