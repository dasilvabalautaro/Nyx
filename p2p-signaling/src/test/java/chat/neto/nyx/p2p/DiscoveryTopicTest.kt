package chat.neto.nyx.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTopicTest {

    private val topics = DiscoveryTopic()

    @Test
    fun `el tema es determinista y de 32 bytes`() {
        val a = topics.topicFor("citas")
        val b = topics.topicFor("citas")
        assertEquals(32, a.size)
        assertArrayEquals("dos clientes deben derivar el mismo tema", a, b)
    }

    @Test
    fun `categorias distintas dan temas distintos`() {
        assertFalse(topics.topicFor("citas").contentEquals(topics.topicFor("amistad")))
    }

    /** "Citas" y "citas" tienen que ser el mismo tablón, no dos según cómo lo escriba cada cliente. */
    @Test
    fun `la categoria se normaliza antes de derivar`() {
        assertArrayEquals(topics.topicFor("citas"), topics.topicFor("  CITAS  "))
        assertEquals("citas", DiscoveryTopic.normalize(" Citas "))
    }

    /** Mismo alfabeto que valida el nodo: lo que aquí pase, allí también. */
    @Test
    fun `rechaza lo que el nodo rechazaria`() {
        for (bad in listOf("", "   ", "con espacio", "../../etc", "a/b", ".", "x".repeat(33))) {
            assertNull("«$bad» debería ser inválida", DiscoveryTopic.normalize(bad))
            assertTrue(
                "«$bad» debería lanzar",
                runCatching { topics.topicFor(bad) }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    /**
     * El prefijo de dominio separa espacios de nombres: un tema del tablón —público, derivable
     * por cualquiera— nunca puede coincidir con un rendezvous privado, que sale de un secreto
     * compartido. Si colisionaran, el tablón filtraría con quién hablas.
     */
    @Test
    fun `un tema publico nunca coincide con un rendezvous privado`() {
        val secret = "citas".toByteArray()
        val rdv = RendezvousService().rendezvousFor(secret)
        assertFalse(
            "los dominios HKDF deben mantener los espacios separados",
            topics.topicFor("citas").contentEquals(rdv),
        )
    }
}
