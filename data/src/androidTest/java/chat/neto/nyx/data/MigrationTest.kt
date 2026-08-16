package chat.neto.nyx.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migración de verdad, sobre SQLite real: crea una base en v4 con datos dentro, la migra y
 * comprueba que los datos siguen ahí y que las tablas nuevas funcionan.
 *
 * Complementa a `MigrationSqlTest` (JVM), que compara el SQL con el esquema exportado sin
 * ejecutarlo. Este ejecuta; aquel corre en cada `testDebugUnitTest` sin dispositivo.
 *
 * **No es destructivo para la app del autor.** El aviso de `CLAUDE.md` sobre tests
 * instrumentados se refiere a `:app:connectedDebugAndroidTest`, que desinstala
 * `chat.neto.nyx` y con ello borra la identidad Ed25519. Este vive en `:data`, se instala
 * como `chat.neto.nyx.data.test` y solo se desinstala a sí mismo:
 *
 *     ./gradlew :data:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NyxDatabase::class.java,
    )

    /**
     * El escenario que motiva escribir esta migración: un móvil de desarrollo con la base en
     * v4 (builds de la Fase 1) que instala la primera build con v5. Sus contactos y mensajes
     * tienen que sobrevivir — si no, habría que desinstalar, y desinstalar borra la identidad.
     */
    @Test
    fun migrate4To5_conservaContactosYMensajes() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO contacts (id, displayName, peerId, publicKey, sharedSecret, verified) " +
                    "VALUES ('12D3KooWpeer', 'Jimena', '12D3KooWpeer', X'01', X'02', 1)",
            )
            // `ciphertext`, no `content`: los mensajes se guardan cifrados en reposo.
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderId, ciphertext, timestamp, status) " +
                    "VALUES ('m1', '12D3KooWpeer', 'yo', X'deadbeef', 1000, 'SENT')",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT displayName, verified FROM contacts WHERE id = '12D3KooWpeer'").use { c ->
            assertTrue("se perdió el contacto al migrar", c.moveToFirst())
            assertEquals("Jimena", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        db.query("SELECT hex(ciphertext) FROM messages WHERE id = 'm1'").use { c ->
            assertTrue("se perdió el mensaje al migrar", c.moveToFirst())
            assertEquals("DEADBEEF", c.getString(0))
        }
    }

    /** Las tablas nuevas existen y aceptan escritura tras migrar (no solo tras crear en v5). */
    @Test
    fun migrate4To5_dejaLasTablasNuevasUsables() {
        helper.createDatabase(TEST_DB, 4).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.execSQL(
            "INSERT INTO likes (peerId, sentAt, receivedAt, matchedAt, source) " +
                "VALUES ('p1', 10, 20, 20, 'BOARD')",
        )
        db.execSQL("INSERT INTO blocked_peers (peerId, blockedAt, reason) VALUES ('p2', 30, NULL)")

        db.query("SELECT matchedAt FROM likes WHERE peerId = 'p1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(20, c.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM blocked_peers WHERE peerId = 'p2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    // Nota sobre lo que NO se puede probar aquí: no hay test de la cadena v2→v5. `createDatabase`
    // necesita el esquema exportado de la versión de partida, y `exportSchema` se activó en su
    // día ya en la v4, así que el histórico commiteado empieza en `4.json` — no existen 2.json ni
    // 3.json contra los que construir la base antigua. MIGRATION_2_3 y MIGRATION_3_4 se quedan
    // sin cobertura por ese motivo, no por olvido; a partir de la v4 (esta migración incluida)
    // sí la hay, y la habrá para todas las siguientes.
}
