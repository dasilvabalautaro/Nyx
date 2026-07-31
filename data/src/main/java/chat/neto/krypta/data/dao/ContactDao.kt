package chat.neto.krypta.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import chat.neto.krypta.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName")
    fun observeAll(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun findById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE peerId = :peerId LIMIT 1")
    suspend fun findByPeerId(peerId: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)
}
