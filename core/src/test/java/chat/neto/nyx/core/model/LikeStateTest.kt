package chat.neto.nyx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La máquina de estados del "me gusta". Es la regla que decide si se desbloquea la mensajería,
 * así que se prueba aparte de Room, en la JVM (mismo criterio que `ThemePreference.resolveDark`
 * o `AppLock.shouldRelock`).
 */
class LikeStateTest {

    private val peer = "12D3KooWpeer"

    @Test
    fun `un like enviado sin recibir no es match y no habilita mensajeria`() {
        val like = LikeState.applySent(null, peer, LikeSource.BOARD, now = 100)

        assertEquals(100L, like.sentAt)
        assertNull(like.receivedAt)
        assertNull(like.matchedAt)
        assertFalse(like.isMatch)
        assertFalse(like.canMessage)
    }

    /**
     * El freno anti-acoso: que alguien me dé like **no** le abre un canal para escribirme.
     * Si esto se rompe, cualquiera puede iniciar conversación con quien no le ha correspondido.
     */
    @Test
    fun `un like SOLO recibido nunca habilita mensajeria`() {
        val like = LikeState.applyReceived(null, peer, LikeSource.BOARD, now = 100)

        assertEquals(100L, like.receivedAt)
        assertNull(like.sentAt)
        assertFalse("un like recibido no puede desbloquear el chat por sí solo", like.canMessage)
    }

    @Test
    fun `enviar despues de recibir cierra el match`() {
        val recibido = LikeState.applyReceived(null, peer, LikeSource.BOARD, now = 100)
        val match = LikeState.applySent(recibido, peer, LikeSource.BOARD, now = 250)

        assertEquals(250L, match.matchedAt)
        assertTrue(match.isMatch)
        assertTrue(match.canMessage)
    }

    @Test
    fun `recibir despues de enviar cierra el match igual`() {
        val enviado = LikeState.applySent(null, peer, LikeSource.BOARD, now = 100)
        val match = LikeState.applyReceived(enviado, peer, LikeSource.BOARD, now = 250)

        assertEquals(250L, match.matchedAt)
        assertTrue(match.canMessage)
    }

    /**
     * Los dos dispositivos llegan a la misma conclusión por su cuenta, con los dos likes
     * unidireccionales y sin protocolo de coordinación. Lo que tiene que coincidir es el
     * **hecho** del match; la fecha es local a cada lado y no se negocia.
     */
    @Test
    fun `ambos lados detectan el match de forma independiente`() {
        // A: primero da like, luego le llega el de B.
        val ladoA = LikeState.applyReceived(
            LikeState.applySent(null, "B", LikeSource.BOARD, now = 10),
            "B", LikeSource.BOARD, now = 40,
        )
        // B: primero le llega el de A, luego da el suyo.
        val ladoB = LikeState.applySent(
            LikeState.applyReceived(null, "A", LikeSource.BOARD, now = 20),
            "A", LikeSource.BOARD, now = 30,
        )

        assertTrue(ladoA.isMatch)
        assertTrue(ladoB.isMatch)
    }

    @Test
    fun `repetir el like es idempotente y no reescribe fechas`() {
        val primero = LikeState.applySent(null, peer, LikeSource.BOARD, now = 100)
        val repetido = LikeState.applySent(primero, peer, LikeSource.MANUAL, now = 900)

        assertEquals("la fecha del primer like no debe moverse", 100L, repetido.sentAt)
        assertEquals("el origen lo fija el primer evento", LikeSource.BOARD, repetido.source)
    }

    /** La fecha del match es el instante en que se detectó; eventos posteriores no la mueven. */
    @Test
    fun `matchedAt se fija una sola vez`() {
        val match = LikeState.applyReceived(
            LikeState.applySent(null, peer, LikeSource.BOARD, now = 100),
            peer, LikeSource.BOARD, now = 200,
        )
        val despues = LikeState.applyReceived(match, peer, LikeSource.BOARD, now = 999)

        assertEquals(200L, despues.matchedAt)
    }

    @Test
    fun `el origen de un like tolera valores desconocidos o ausentes`() {
        assertEquals(LikeSource.BOARD, LikeSource.read("BOARD"))
        assertEquals(LikeSource.UNKNOWN, LikeSource.read(null))
        assertEquals(
            "una fila escrita por una versión posterior no puede tumbar la lectura",
            LikeSource.UNKNOWN,
            LikeSource.read("ALGO_QUE_AUN_NO_EXISTE"),
        )
    }
}
