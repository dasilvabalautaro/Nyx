package chat.neto.nyx.data

import androidx.room.TypeConverter
import chat.neto.nyx.core.model.MessageStatus

class Converters {
    @TypeConverter
    fun fromStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
