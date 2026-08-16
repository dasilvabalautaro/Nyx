package chat.neto.nyx.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Peer bloqueado. Sin índices extra: la única consulta es por PK. */
@Entity(tableName = "blocked_peers")
data class BlockedPeerEntity(
    @PrimaryKey val peerId: String,
    val blockedAt: Long,
    val reason: String?,
)
