package chat.neto.nyx.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Contacto **tal como se guarda**. Ojo a lo que NO está: el secreto compartido. Se guardaba
 * aquí en claro, y como la clave de cada mensaje sale de él por HKDF, el fichero `nyx.db`
 * bastaba por sí solo para descifrar el historial entero —sin la identidad y sin ejecutar nada
 * dentro de la app—. Como es una función pura de la identidad y del PeerID del contacto
 * (`KeyExchange.sharedSecretWith`, ECDH X25519), no hay ninguna razón para persistirlo: se
 * deriva al vuelo al leer. Así la clave vuelve a depender de la identidad, que vive envuelta
 * en el Android Keystore.
 */
@Entity(
    tableName = "contacts",
    indices = [Index("peerId")],
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val peerId: String,
    val publicKey: ByteArray,
    val verified: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return id == other.id &&
            displayName == other.displayName &&
            peerId == other.peerId &&
            publicKey.contentEquals(other.publicKey) &&
            verified == other.verified
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + verified.hashCode()
        return result
    }
}
