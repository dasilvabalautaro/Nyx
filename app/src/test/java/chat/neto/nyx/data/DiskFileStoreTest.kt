package chat.neto.nyx.data

import chat.neto.nyx.core.IncomingFileMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * El contrato v2 del staging en disco: los trozos sobreviven a la muerte del proceso (se
 * simula creando OTRA instancia sobre el mismo directorio), las reentregas son inocuas y
 * al completarse el archivo el staging se limpia. Es la mitad local de la garantía
 * "ack-tras-persistir" (la otra mitad, no confirmar sobres fallidos, está en ChatServiceTest).
 */
class DiskFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = DiskFileStore(File(tmp.root, "nyx_files"))

    private val meta = IncomingFileMeta("doc.pdf", "application/pdf", 10L, 3)
    private val chunks = listOf("0123".toByteArray(), "4567".toByteArray(), "89".toByteArray())

    @Test
    fun `assembles when meta and all chunks arrive in any order`() = runBlocking {
        val s = store()
        assertNull(s.onChunk("f1", 2, chunks[2]))
        assertNull(s.onMeta("f1", meta))
        assertNull(s.onChunk("f1", 0, chunks[0]))
        val f = s.onChunk("f1", 1, chunks[1])!!

        assertEquals("doc.pdf", f.name)
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
        // staging limpiado al completar
        assertFalse(File(tmp.root, "nyx_files/staging/f1").exists())
    }

    @Test
    fun `partial transfer survives process death and completes on redelivery`() = runBlocking {
        val first = store()
        assertNull(first.onMeta("f2", meta))
        assertNull(first.onChunk("f2", 0, chunks[0]))
        assertNull(first.onChunk("f2", 1, chunks[1]))

        // "Muere el proceso": una instancia nueva sobre el mismo directorio (v1 perdía todo
        // aquí porque los trozos vivían en memoria y los sobres ya estaban ack'd/borrados).
        val second = store()
        val f = second.onChunk("f2", 2, chunks[2])!!
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `redelivered chunk is idempotent`() = runBlocking {
        val s = store()
        assertNull(s.onMeta("f3", meta))
        assertNull(s.onChunk("f3", 0, chunks[0]))
        assertNull(s.onChunk("f3", 0, chunks[0])) // reentrega (ack perdido): inocua
        assertNull(s.onChunk("f3", 1, chunks[1]))
        val f = s.onChunk("f3", 2, chunks[2])!!
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `sanitizes hostile file names and ids`() = runBlocking {
        val s = store()
        val evil = IncomingFileMeta("../../evil.sh", "text/plain", 4L, 1)
        assertNull(s.onMeta("../f4", evil))
        val f = s.onChunk("../f4", 0, "data".toByteArray())!!

        val base = File(tmp.root, "nyx_files").canonicalFile
        assertTrue(File(f.path).canonicalPath.startsWith(base.path)) // no escapa del dir
        assertArrayEquals("data".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `meta with single chunk assembles immediately after chunk`() = runBlocking {
        val s = store()
        val one = IncomingFileMeta("nota.m4a", "audio/mp4", 3L, 1)
        assertNull(s.onChunk("f5", 0, "abc".toByteArray()))
        val f = s.onMeta("f5", one)!!
        assertEquals("audio/mp4", f.mime)
        assertArrayEquals("abc".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `deleteLocal removes assembled file, pending staging and own copy inside the store`() = runBlocking {
        val s = store()
        // Archivo completo (ensamblado) + otro a medias (solo staging).
        assertNull(s.onMeta("f6", meta))
        assertNull(s.onChunk("f6", 0, chunks[0]))
        assertNull(s.onChunk("f6", 1, chunks[1]))
        val done = s.onChunk("f6", 2, chunks[2])!!
        assertNull(s.onMeta("f7", meta))
        assertNull(s.onChunk("f7", 0, chunks[0]))
        // Copia propia (p. ej. nota de voz enviada) dentro del almacén.
        val sent = File(tmp.root, "nyx_files/sent/nota.m4a").apply {
            parentFile!!.mkdirs(); writeBytes("audio".toByteArray())
        }

        s.deleteLocal("f6", done.path)
        s.deleteLocal("f7", null)
        s.deleteLocal("nota-id", sent.absolutePath)

        assertFalse(File(done.path).exists())
        assertFalse(File(tmp.root, "nyx_files/staging/f7").exists())
        assertFalse(sent.exists())
        // Idempotente: repetir un borrado no lanza.
        s.deleteLocal("f6", done.path)
    }

    @Test
    fun `deleteLocal never touches files outside the store`() = runBlocking {
        val s = store()
        val outside = File(tmp.root, "fuera.txt").apply { writeBytes("intocable".toByteArray()) }

        // Un path malicioso/corrupto en un descriptor no puede borrar fuera del almacén.
        s.deleteLocal("fx", outside.absolutePath)

        assertTrue(outside.exists())
    }
}
