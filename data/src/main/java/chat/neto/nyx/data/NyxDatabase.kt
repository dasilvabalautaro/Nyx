package chat.neto.nyx.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import chat.neto.nyx.data.dao.ContactDao
import chat.neto.nyx.data.dao.MessageDao
import chat.neto.nyx.data.entity.ContactEntity
import chat.neto.nyx.data.entity.MessageEntity

@Database(
    entities = [MessageEntity::class, ContactEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
}
