package chat.neto.krypta.core.model

/**
 * Mensaje E2EE. El contenido viaja siempre cifrado (`ciphertext`); el dominio nunca
 * maneja texto plano. La infraestructura (relay/buzón) no puede descifrarlo.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val ciphertext: ByteArray,
    val timestamp: Long,
    val status: MessageStatus,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            senderId == other.senderId &&
            ciphertext.contentEquals(other.ciphertext) &&
            timestamp == other.timestamp &&
            status == other.status
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}
