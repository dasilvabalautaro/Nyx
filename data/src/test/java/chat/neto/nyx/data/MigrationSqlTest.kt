package chat.neto.nyx.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Comprueba que [MIGRATION_4_5] crea **exactamente** el esquema que Room genera para las
 * entidades nuevas, comparándolo con `schemas/…/5.json` (el esquema exportado, que es la
 * referencia contra la que Room valida en el primer arranque).
 *
 * Por qué este test y no solo el de `MigrationTest` (instrumentado): el modo de fallo real de
 * una migración escrita a mano es que el SQL se desvíe una coma del que genera Room —una
 * columna sin `NOT NULL`, un índice que falta— y eso **no se ve al compilar**: aparece en
 * runtime, en el móvil, al arrancar la primera build con la versión nueva. Este test corre en
 * la JVM con `./gradlew testDebugUnitTest`, sin dispositivo, así que la desviación se detecta
 * en el mismo commit que la introduce.
 *
 * El truco para leer el SQL: `Migration` no lo expone, solo lo ejecuta. Se le pasa un
 * `SupportSQLiteDatabase` de mentira (un proxy dinámico) que en vez de ejecutar apunta cada
 * `execSQL`.
 */
class MigrationSqlTest {

    // Los esquemas exportados viven en los assets de androidTest; ver el comentario del
    // `room.schemaLocation` en data/build.gradle.kts.
    private val schemaDir = File("src/androidTest/assets/chat.neto.nyx.data.NyxDatabase")

    @Test
    fun `la migracion 4-5 crea el esquema exacto que Room espera para las tablas nuevas`() {
        val executed = recordSql(MIGRATION_4_5::migrate).map(::normalize).toSet()
        val expected = expectedSqlFor("likes", "blocked_peers").map(::normalize).toSet()

        assertEquals(
            "el SQL de MIGRATION_4_5 no coincide con schemas/5.json; Room lanzará al validar " +
                "en el primer arranque",
            expected,
            executed,
        )
    }

    /** El esquema exportado tiene que estar commiteado, o no hay contra qué validar. */
    @Test
    fun `el esquema v5 esta exportado`() {
        assertTrue(
            "falta ${schemaDir.resolve("5.json")}: commitea el esquema exportado por Room",
            schemaDir.resolve("5.json").isFile,
        )
        assertEquals(5, schemaJson().getJSONObject("database").getInt("version"))
    }

    /**
     * La migración es aditiva: no puede tocar `messages` ni `contacts`. Si alguna vez hay que
     * hacerlo, será con un `ALTER`/recreación deliberada y este test tendrá que cambiar a
     * propósito — no por accidente.
     */
    @Test
    fun `la migracion 4-5 no toca las tablas existentes`() {
        val touched = recordSql(MIGRATION_4_5::migrate)
            .filter { it.contains("messages") || it.contains("contacts") }
        assertTrue("MIGRATION_4_5 toca tablas preexistentes: $touched", touched.isEmpty())
    }

    /**
     * v5→v6 recrea `contacts` sin `sharedSecret` (porte del 9322a82 de Krypta). Aquí la
     * migración no es aditiva: construye `contacts_new`, copia y renombra, así que lo que tiene
     * que coincidir con `6.json` es esa tabla nueva —con el nombre final— y el índice que se
     * recrea al final. Una columna de más o un `NOT NULL` de menos haría que Room rechazara la
     * base en el primer arranque del móvil, con todo el historial dentro.
     */
    @Test
    fun `la migracion 5-6 recrea contacts con el esquema exacto de la v6`() {
        val executed = recordSql(MIGRATION_5_6::migrate)
        val creates = executed
            .filter { it.startsWith("CREATE") }
            .map { normalize(it.replace("contacts_new", "contacts")) }
            .toSet()
        val expected = expectedSqlFor("contacts", version = 6).map(::normalize).toSet()

        assertEquals("el SQL de MIGRATION_5_6 no coincide con schemas/6.json", expected, creates)
    }

    /** Lo que justifica la migración: el secreto no puede sobrevivir en la tabla nueva. */
    @Test
    fun `la migracion 5-6 no copia el secreto compartido`() {
        val executed = recordSql(MIGRATION_5_6::migrate)
        assertTrue(
            "MIGRATION_5_6 sigue mencionando sharedSecret: $executed",
            executed.none { it.contains("sharedSecret") },
        )
        assertTrue(
            "la tabla vieja tiene que borrarse, no quedarse al lado",
            executed.any { normalize(it) == "DROP TABLE contacts" },
        )
    }

    @Test
    fun `el esquema v6 esta exportado y sin secreto en contacts`() {
        assertTrue(schemaDir.resolve("6.json").isFile)
        assertEquals(6, schemaJson(6).getJSONObject("database").getInt("version"))
        val contacts = expectedSqlFor("contacts", version = 6).first()
        assertTrue("6.json aún tiene sharedSecret: $contacts", "sharedSecret" !in contacts)
    }

    // --- utilidades ---

    private fun schemaJson(version: Int = 5) = JSONObject(schemaDir.resolve("$version.json").readText())

    /** `createSql` de las tablas pedidas + el de sus índices, tal y como los generó Room. */
    private fun expectedSqlFor(vararg tables: String, version: Int = 5): List<String> {
        val entities = schemaJson(version).getJSONObject("database").getJSONArray("entities")
        val wanted = tables.toSet()
        val out = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val name = e.getString("tableName")
            if (name !in wanted) continue
            // Room escribe `${TABLE_NAME}` como marcador en el esquema exportado.
            out += e.getString("createSql").replace("\${TABLE_NAME}", "`$name`").replace("``", "`")
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                out += indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", "`$name`").replace("``", "`")
            }
        }
        check(out.isNotEmpty()) { "no se encontró ninguna de las tablas $wanted en $version.json" }
        return out
    }

    /** Ejecuta la migración contra una BD de mentira y devuelve el SQL que intentó ejecutar. */
    private fun recordSql(migrate: (SupportSQLiteDatabase) -> Unit): List<String> {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args[0] as String
            null
        } as SupportSQLiteDatabase
        migrate(db)
        return statements
    }

    /** Comillas invertidas y espacios sobrantes no cambian el esquema; el texto exacto sí varía. */
    private fun normalize(sql: String) =
        sql.replace("`", "").replace(Regex("\\s+"), " ").trim()
}
