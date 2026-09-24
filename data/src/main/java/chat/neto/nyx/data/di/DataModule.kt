package chat.neto.nyx.data.di

import android.content.Context
import androidx.room.Room
import chat.neto.nyx.core.repository.BlockRepository
import chat.neto.nyx.core.RatchetStore
import chat.neto.nyx.core.TransactionRunner
import chat.neto.nyx.core.repository.ContactRepository
import chat.neto.nyx.core.repository.LikeRepository
import chat.neto.nyx.core.repository.MessageRepository
import chat.neto.nyx.data.NyxDatabase
import chat.neto.nyx.data.MIGRATION_2_3
import chat.neto.nyx.data.MIGRATION_3_4
import chat.neto.nyx.data.MIGRATION_4_5
import chat.neto.nyx.data.MIGRATION_5_6
import chat.neto.nyx.data.MIGRATION_6_7
import chat.neto.nyx.data.MIGRATION_7_8
import chat.neto.nyx.data.MIGRATION_8_9
import chat.neto.nyx.data.crypto.DatabaseEncryption
import chat.neto.nyx.data.crypto.DatabaseKey
import chat.neto.nyx.data.crypto.KeyPrefs
import chat.neto.nyx.data.crypto.KeystoreVault
import chat.neto.nyx.data.crypto.SqlCipher
import chat.neto.nyx.data.dao.BlockedPeerDao
import chat.neto.nyx.data.dao.ContactDao
import chat.neto.nyx.data.dao.LikeDao
import chat.neto.nyx.data.dao.MessageDao
import chat.neto.nyx.data.repository.RoomBlockRepository
import chat.neto.nyx.data.dao.RatchetDao
import chat.neto.nyx.data.repository.RoomContactRepository
import chat.neto.nyx.data.repository.RoomLikeRepository
import chat.neto.nyx.data.repository.RoomMessageRepository
import chat.neto.nyx.data.repository.RoomRatchetStore
import chat.neto.nyx.data.repository.RoomTransactionRunner
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
    fun provideDatabase(@ApplicationContext context: Context): NyxDatabase {
        // Base **cifrada** (SQLCipher) con una frase-clave que vive envuelta en el Android
        // Keystore. Lo que protege son los metadatos locales -nombres, PeerID, marcas de
        // tiempo, quien habla con quien-; el contenido ya dependia de la identidad desde que
        // el secreto compartido salio de la base.
        val key = DatabaseKey(
            prefs = SharedKeyPrefs(context.getSharedPreferences("nyx_db", Context.MODE_PRIVATE)),
            vault = KeystoreVault(),
        )
        val passphrase = key.passphrase()
        // Conversion de la base en claro que dejaron las versiones anteriores. Va aqui, antes
        // de que Room la abra, y es idempotente: si ya esta cifrada no hace nada.
        DatabaseEncryption.encryptInPlace(context.getDatabasePath("nyx.db"), passphrase)

        return Room.databaseBuilder(context, NyxDatabase::class.java, "nyx.db")
            .openHelperFactory(SqlCipher.openHelperFactory(passphrase))
            // Migraciones reales: preservan contactos + mensajes al subir de versión.
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            // Red de seguridad solo para la v1 antigua (sin migración definida); v2+ migra.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .build()
    }

    /** Adaptador de `SharedPreferences` al puerto que usa [DatabaseKey]. */
    private class SharedKeyPrefs(
        private val prefs: android.content.SharedPreferences,
    ) : KeyPrefs {
        override fun get(key: String): String? = prefs.getString(key, null)
        // `commit()`, no `apply()`: la frase se crea justo antes de cifrar la base en claro, y
        // si el proceso muriera con la escritura aún en cola la base quedaría cifrada bajo una
        // clave que no llegó al disco — el historial perdido sin remedio. Se escribe una vez.
        override fun put(key: String, value: String) {
            check(prefs.edit().putString(key, value).commit()) { "no se pudo guardar la clave de la base" }
        }
    }

    @Provides
    fun provideMessageDao(database: NyxDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideContactDao(database: NyxDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideLikeDao(database: NyxDatabase): LikeDao = database.likeDao()

    @Provides
    fun provideBlockedPeerDao(database: NyxDatabase): BlockedPeerDao = database.blockedPeerDao()

    @Provides
    fun provideRatchetDao(database: NyxDatabase): RatchetDao = database.ratchetDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindMessageRepository(impl: RoomMessageRepository): MessageRepository

    @Binds
    abstract fun bindContactRepository(impl: RoomContactRepository): ContactRepository

    @Binds
    abstract fun bindLikeRepository(impl: RoomLikeRepository): LikeRepository

    @Binds
    abstract fun bindBlockRepository(impl: RoomBlockRepository): BlockRepository

    @Binds
    abstract fun bindRatchetStore(impl: RoomRatchetStore): RatchetStore

    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner
}
