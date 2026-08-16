package chat.neto.nyx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import chat.neto.nyx.data.entity.BlockedPeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedPeerDao {

    @Query("SELECT * FROM blocked_peers ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedPeerEntity>>

    @Query("SELECT * FROM blocked_peers WHERE peerId = :peerId")
    suspend fun find(peerId: String): BlockedPeerEntity?

    /**
     * IGNORE, no REPLACE: bloquear dos veces al mismo peer debe conservar la fecha del primer
     * bloqueo, no reiniciarla. La actualización deliberada (cambiar el motivo) va por [upsert].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(peer: BlockedPeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: BlockedPeerEntity)

    @Query("DELETE FROM blocked_peers WHERE peerId = :peerId")
    suspend fun delete(peerId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_peers WHERE peerId = :peerId)")
    suspend fun isBlocked(peerId: String): Boolean
}
