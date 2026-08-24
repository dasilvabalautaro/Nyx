package chat.neto.nyx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNamesTest {

    private val ana1 = "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3"
    private val ana2 = "12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm"
    private val bruno = "12D3KooWH8AqHT5Z78AGHBKkmC8DNis5NjYqz7Zgqth4BGY46sXR"

    @Test
    fun `un nombre unico se queda tal cual`() {
        val r = DisplayNames.disambiguate(listOf(ana1 to "Ana", bruno to "Bruno"))
        assertEquals("Ana", r[ana1])
        assertEquals("Bruno", r[bruno])
    }

    @Test
    fun `dos nombres iguales se distinguen`() {
        val r = DisplayNames.disambiguate(listOf(ana1 to "Ana", ana2 to "Ana", bruno to "Bruno"))

        assertNotEquals(r[ana1], r[ana2])
        assertTrue(r[ana1]!!.startsWith("Ana ("))
        assertTrue(r[ana2]!!.startsWith("Ana ("))
        assertEquals("Bruno", r[bruno]) // el que no choca no se toca
    }

    /**
     * "Ana" y "ana " son el mismo nombre para quien mira la lista, así que también chocan. Sin
     * esto, la desambiguación fallaría justo en el caso que un suplantador escribiría.
     */
    @Test
    fun `chocan aunque difieran en mayusculas o espacios`() {
        val r = DisplayNames.disambiguate(listOf(ana1 to "Ana", ana2 to " ana "))
        assertNotEquals(r[ana1], r[ana2])
        assertTrue(r[ana1]!!.contains("("))
    }

    /**
     * El test que justifica usar la cola. Todos los PeerID de libp2p empiezan por `12D3KooW`:
     * con un sufijo hecho de la cabeza, los dos "Ana" seguirían llamándose igual.
     */
    @Test
    fun `el sufijo distingue peers que comparten prefijo`() {
        assertNotEquals(DisplayNames.tail(ana1), DisplayNames.tail(ana2))
        assertNotEquals(DisplayNames.tail(ana1), DisplayNames.tail(bruno))
    }

    @Test
    fun `una lista vacia no revienta`() {
        assertTrue(DisplayNames.disambiguate(emptyList()).isEmpty())
    }
}
