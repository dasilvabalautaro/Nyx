package chat.neto.nyx.p2p

import chat.neto.nyx.core.model.ReportDraft
import chat.neto.nyx.core.model.ReportReason
import chat.neto.nyx.core.model.ReportedLine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportServiceTest {

    private val yo = "12D3KooWYo"
    private val acosador = "12D3KooWAcosador"

    private fun draft(incluye: Boolean = false) = ReportDraft(
        reportedPeerId = acosador,
        reason = ReportReason.HARASSMENT,
        note = "no para de escribirme",
        includeExcerpt = incluye,
        excerpt = listOf(ReportedLine(false, 1_756_000_000_000L, "texto privado")),
    )

    @Test
    fun `denunciar bloquea y entrega el sobre cifrado`() = runTest {
        val signaling = FakeSignalingBase()
        val blocks = FakeBlocks()
        val service = ReportService(signaling, blocks)

        val result = service.report(draft(), yo, "1.0")

        assertTrue(result is ReportService.Result.Sent)
        assertTrue("debería quedar bloqueado", blocks.isBlocked(acosador))
        assertEquals(1, signaling.sentReports.size)
        // Lo que sale por la red pasó por el sellado; el texto en claro no viaja.
        assertTrue(String(signaling.sentReports[0]).startsWith("SEALED:"))
    }

    /**
     * El orden es lo que se prueba aquí, y no es cosmético: quien denuncia quiere dejar de
     * recibir a esa persona **ya**. Si el bloqueo dependiera de que el envío funcione, un fallo
     * de red dejaría a la víctima expuesta justo en el momento en que pidió ayuda — y la
     * política de Play pide "bloqueo inmediato" como parte del mecanismo.
     */
    @Test
    fun `si el envio falla el bloqueo se aplica igual`() = runTest {
        val signaling = FakeSignalingBase().apply { failReportSend = true }
        val blocks = FakeBlocks()
        val service = ReportService(signaling, blocks)

        val result = service.report(draft(), yo, "1.0")

        assertTrue("debería reportar el fallo de entrega", result is ReportService.Result.Blocked)
        assertTrue("el bloqueo NO puede depender de la red", blocks.isBlocked(acosador))
        assertEquals(0, signaling.sentReports.size)
    }

    /**
     * El fallo de entrega tiene que devolver el texto en claro: es lo que permite a la UI
     * ofrecer la exportación local como salida, en vez de dejar al usuario creyendo que su
     * denuncia llegó a alguien.
     */
    @Test
    fun `un fallo devuelve el texto para poder exportarlo a mano`() = runTest {
        val service = ReportService(FakeSignalingBase().apply { failReportSend = true }, FakeBlocks())

        val result = service.report(draft(), yo, "1.0") as ReportService.Result.Blocked

        assertTrue(result.plaintext.contains(acosador))
        assertTrue(result.plaintext.contains("no para de escribirme"))
        assertTrue(result.error.isNotBlank())
    }

    /**
     * La garantía de consentimiento, comprobada **de punta a punta** y no solo en el formateo:
     * lo que de verdad sale por la red no contiene la conversación si el usuario no la autorizó.
     */
    @Test
    fun `sin autorizacion la conversacion no sale por la red`() = runTest {
        val signaling = FakeSignalingBase()
        val service = ReportService(signaling, FakeBlocks())

        service.report(draft(incluye = false), yo, "1.0")

        val enviado = String(signaling.sentReports.single())
        assertFalse("el texto privado no debía viajar", enviado.contains("texto privado"))

        // Y con autorización sí, para que el test anterior signifique algo.
        val otro = FakeSignalingBase()
        ReportService(otro, FakeBlocks()).report(draft(incluye = true), yo, "1.0")
        assertTrue(String(otro.sentReports.single()).contains("texto privado"))
    }

    /** Denunciar a alguien ya bloqueado no debe fallar ni mover la fecha del bloqueo. */
    @Test
    fun `denunciar a alguien ya bloqueado funciona igual`() = runTest {
        val blocks = FakeBlocks(setOf(acosador))
        val original = blocks.store[acosador]
        val signaling = FakeSignalingBase()

        val result = ReportService(signaling, blocks).report(draft(), yo, "1.0")

        assertTrue(result is ReportService.Result.Sent)
        assertEquals(original, blocks.store[acosador])
    }
}
