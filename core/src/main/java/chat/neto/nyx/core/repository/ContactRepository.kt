package chat.neto.krypta.core.repository

import chat.neto.krypta.core.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeAll(): Flow<List<Contact>>

    suspend fun upsert(contact: Contact)

    suspend fun findById(id: String): Contact?

    /** Resuelve un contacto por su PeerID libp2p (para mapear mensajes entrantes). */
    suspend fun findByPeerId(peerId: String): Contact?

    /** Elimina el contacto (sus mensajes se borran aparte, vía [MessageRepository]). */
    suspend fun delete(id: String)
}
