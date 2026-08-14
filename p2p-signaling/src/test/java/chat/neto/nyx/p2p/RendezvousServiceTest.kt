package chat.neto.nyx.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RendezvousServiceTest {

    private val service = RendezvousService()
    private val secret = "shared-secret-abc".toByteArray()

    @Test
    fun `mismo secreto y dia produce el mismo rendezvous`() {
        val day = LocalDate.of(2026, 6, 25)
        assertTrue(
            service.rendezvousFor(secret, day)
                .contentEquals(service.rendezvousFor(secret, day))
        )
    }

    @Test
    fun `el rendezvous rota cada dia`() {
        val a = service.rendezvousFor(secret, LocalDate.of(2026, 6, 25))
        val b = service.rendezvousFor(secret, LocalDate.of(2026, 6, 26))
        assertFalse("debe rotar al cambiar de dia", a.contentEquals(b))
    }

    @Test
    fun `secretos distintos producen rendezvous distintos el mismo dia`() {
        val day = LocalDate.of(2026, 6, 25)
        val a = service.rendezvousFor("alice-bob".toByteArray(), day)
        val b = service.rendezvousFor("alice-carol".toByteArray(), day)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `la longitud del rendezvous es de 32 bytes`() {
        assertEquals(32, service.rendezvousFor(secret, LocalDate.of(2026, 6, 25)).size)
    }
}
