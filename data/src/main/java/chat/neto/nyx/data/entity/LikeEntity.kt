package chat.neto.nyx.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fila de "me gusta" por peer. El PeerID es la clave: hay como mucho un estado de like con
 * cada peer, igual que hay como mucho una conversación.
 *
 * [source] se guarda como `String` (el nombre del enum) y no como ordinal, para que reordenar
 * `LikeSource` no reinterprete filas ya escritas.
 *
 * Índice sobre `matchedAt` porque la consulta caliente es "dame los matches" — la lista que
 * se pinta en pantalla — y sin él sería un scan de la tabla entera.
 */
@Entity(
    tableName = "likes",
    indices = [Index("matchedAt")],
)
data class LikeEntity(
    @PrimaryKey val peerId: String,
    val sentAt: Long?,
    val receivedAt: Long?,
    val matchedAt: Long?,
    val source: String,
)
