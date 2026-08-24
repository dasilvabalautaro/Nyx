package chat.neto.nyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El nombre de emergencia cuando no se recordó el apodo. Se prueba en la JVM porque es la parte
 * pura; el guardado en `SharedPreferences` necesita `Context` y no aporta nada probarlo aquí.
 */
class BoardNamesTest {

    /**
     * El test que justifica la función. **Todos** los PeerID de libp2p empiezan por `12D3KooW`,
     * así que un nombre hecho con el prefijo daría el mismo para todo el mundo — dos matches
     * distintos aparecerían en la lista con idéntico nombre y no habría forma de distinguirlos.
     */
    @Test
    fun `el nombre de emergencia distingue peers que comparten prefijo`() {
        val a = "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3"
        val b = "12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm"

        assertNotEquals(BoardNames.fallbackName(a), BoardNames.fallbackName(b))
    }

    @Test
    fun `un peerId corto se devuelve tal cual`() {
        assertEquals("abc", BoardNames.fallbackName("abc"))
        assertEquals("12345678", BoardNames.fallbackName("12345678"))
    }

    /** Nunca vacío: un contacto sin nombre es peor que uno con un nombre feo pero reconocible. */
    @Test
    fun `el nombre de emergencia nunca sale vacio`() {
        listOf("", "x", "12D3KooWAy").forEach {
            assertTrue("vacío para '$it'", BoardNames.fallbackName(it).isNotEmpty())
        }
    }
}
