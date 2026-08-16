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

    // --- utilidades ---

    private fun schemaJson() = JSONObject(schemaDir.resolve("5.json").readText())

    /** `createSql` de las tablas pedidas + el de sus índices, tal y como los generó Room. */
    private fun expectedSqlFor(vararg tables: String): List<String> {
        val entities = schemaJson().getJSONObject("database").getJSONArray("entities")
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
        check(out.isNotEmpty()) { "no se encontró ninguna de las tablas $wanted en 5.json" }
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
