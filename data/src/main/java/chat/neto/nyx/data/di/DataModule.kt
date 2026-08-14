package chat.neto.nyx.data.di

import android.content.Context
import androidx.room.Room
import chat.neto.nyx.core.repository.ContactRepository
import chat.neto.nyx.core.repository.MessageRepository
import chat.neto.nyx.data.NyxDatabase
import chat.neto.nyx.data.MIGRATION_2_3
import chat.neto.nyx.data.MIGRATION_3_4
import chat.neto.nyx.data.dao.ContactDao
import chat.neto.nyx.data.dao.MessageDao
import chat.neto.nyx.data.repository.RoomContactRepository
import chat.neto.nyx.data.repository.RoomMessageRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NyxDatabase =
        Room.databaseBuilder(context, NyxDatabase::class.java, "nyx.db")
            // Migraciones reales: preservan contactos + mensajes al subir de versión.
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            // Red de seguridad solo para la v1 antigua (sin migración definida); v2+ migra.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .build()

    @Provides
    fun provideMessageDao(database: NyxDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideContactDao(database: NyxDatabase): ContactDao = database.contactDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindMessageRepository(impl: RoomMessageRepository): MessageRepository

    @Binds
    abstract fun bindContactRepository(impl: RoomContactRepository): ContactRepository
}
