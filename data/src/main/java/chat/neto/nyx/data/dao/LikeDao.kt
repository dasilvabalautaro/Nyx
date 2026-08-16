package chat.neto.nyx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import chat.neto.nyx.data.entity.LikeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LikeDao {

    @Query("SELECT * FROM likes ORDER BY COALESCE(matchedAt, receivedAt, sentAt) DESC")
    fun observeAll(): Flow<List<LikeEntity>>

    @Query("SELECT * FROM likes WHERE matchedAt IS NOT NULL ORDER BY matchedAt DESC")
    fun observeMatches(): Flow<List<LikeEntity>>

    @Query("SELECT * FROM likes WHERE peerId = :peerId")
    suspend fun find(peerId: String): LikeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(like: LikeEntity)

    @Query("DELETE FROM likes WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}
