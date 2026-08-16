package chat.neto.nyx.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones Room explícitas: preservan los datos (contactos + mensajes) al subir de versión,
 * en vez de recrear la BD (lo que hacía `fallbackToDestructiveMigration` en dev y borraba todo).
 * A partir de aquí, **cada cambio de esquema añade una `Migration` nueva + sube la versión**.
 */

/** v2→v3: verificación de identidad anti-MITM (número de seguridad / QR). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3→v4: índice compuesto `(conversationId, timestamp)` en `messages`. La consulta de la
 * conversación filtra por `conversationId` y ordena por `timestamp`; con el compuesto SQLite
 * resuelve WHERE + ORDER BY desde el índice (el antiguo era solo sobre `conversationId`).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_messages_conversationId")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp " +
                "ON messages (conversationId, timestamp)",
        )
    }
}

/**
 * v4→v5: tablas de Nyx que Krypta no tenía — `likes` (estado de me gusta / match) y
 * `blocked_peers`. Aditiva: no toca `messages` ni `contacts`, y las dos tablas son
 * independientes entre sí, así que no hay orden que respetar ni claves foráneas que cuadrar.
 *
 * Nota honesta sobre para quién es esta migración: **para nadie que la vaya a usar en
 * producción**. Nyx se publica ya en v5, así que no existe parque instalado en v4 que migrar.
 * Se escribe por un motivo concreto y no por ritual: los móviles de desarrollo (el TECNO del
 * autor, el de la persona que colabora) tienen builds de la Fase 1 con la base en v4, y sin
 * esta migración el primer build con v5 revienta al arrancar y habría que desinstalar — lo que
 * borraría la identidad Ed25519. Es decir, protege exactamente el escenario de las pruebas en
 * curso.
 *
 * El SQL tiene que reproducir **exactamente** el esquema que genera Room para las entidades,
 * o Room lanza al validar en el primer arranque. Está comprobado contra `schemas/5.json` en
 * `MigrationTest`.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `likes` (" +
                "`peerId` TEXT NOT NULL, " +
                "`sentAt` INTEGER, " +
                "`receivedAt` INTEGER, " +
                "`matchedAt` INTEGER, " +
                "`source` TEXT NOT NULL, " +
                "PRIMARY KEY(`peerId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_likes_matchedAt` ON `likes` (`matchedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `blocked_peers` (" +
                "`peerId` TEXT NOT NULL, " +
                "`blockedAt` INTEGER NOT NULL, " +
                "`reason` TEXT, " +
                "PRIMARY KEY(`peerId`))",
        )
    }
}
