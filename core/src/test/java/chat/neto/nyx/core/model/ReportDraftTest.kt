package chat.neto.nyx.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La denuncia es la **única** excepción al E2EE de toda la app: si el usuario lo autoriza, un
 * fragmento de conversación en texto plano viaja al operador. Estos tests cubren la mitad que no
 * puede fallar — que "no autorizo" signifique de verdad que no viaja nada.
 */
class ReportDraftTest {

    private val ahora = 1_756_000_000_000L
    private val denunciante = "12D3KooWDenunciante"
    private val denunciado = "12D3KooWDenunciado"

    private val conversacion = listOf(
        ReportedLine(fromMe = false, timestamp = ahora - 60_000, text = "mensaje muy comprometedor"),
        ReportedLine(fromMe = true, timestamp = ahora - 30_000, text = "respuesta mía privada"),
    )

    private fun draft(incluye: Boolean, lineas: List<ReportedLine> = conversacion) = ReportDraft(
        reportedPeerId = denunciado,
        reason = ReportReason.HARASSMENT,
        note = "me está acosando",
        includeExcerpt = incluye,
        excerpt = lineas,
    )

    /**
     * El test que justifica que este archivo exista. Con el fragmento **no** autorizado, ni una
     * palabra de la conversación puede aparecer en lo que se cifra — ni aunque la UI haya dejado
     * los mensajes cargados en el borrador, que es justo lo que pasa cuando el usuario marca y
     * desmarca la casilla antes de enviar.
     */
    @Test
    fun `sin autorizacion no viaja ni una palabra de la conversacion`() {
        val texto = draft(incluye = false).render(denunciante, ahora, "1.0")

        assertFalse(texto.contains("mensaje muy comprometedor"))
        assertFalse(texto.contains("respuesta mía privada"))
        assertTrue(texto.contains("no autorizó"))
    }

    @Test
    fun `con autorizacion viaja el fragmento, con quien dijo cada cosa`() {
        val texto = draft(incluye = true).render(denunciante, ahora, "1.0")

        assertTrue(texto.contains("mensaje muy comprometedor"))
        assertTrue(texto.contains("respuesta mía privada"))
        assertTrue(texto.contains("él/ella:"))
        assertTrue(texto.contains("yo:"))
    }

    /**
     * Autorizar y no tener nada que adjuntar no es lo mismo que no autorizar, y el operador
     * tiene que poder distinguirlo: en un caso el usuario ocultó la prueba, en el otro no la
     * había.
     */
    @Test
    fun `autorizar sin mensajes se distingue de no autorizar`() {
        val vacio = draft(incluye = true, lineas = emptyList()).render(denunciante, ahora, "1.0")
        val negado = draft(incluye = false).render(denunciante, ahora, "1.0")

        assertTrue(vacio.contains("no había mensajes"))
        assertFalse(vacio.contains("no autorizó"))
        assertTrue(negado.contains("no autorizó"))
    }

    /**
     * Los dos PeerID van siempre. El del denunciante también, aunque el nodo lo registre por su
     * cuenta: ese registro es un dato operativo que se puede barrer, y sin él el operador no
     * puede detectar a quien denuncia en masa por venganza.
     */
    @Test
    fun `la denuncia identifica a los dos lados y el motivo`() {
        val texto = draft(incluye = false).render(denunciante, ahora, "1.0")

        assertTrue(texto.contains(denunciado))
        assertTrue(texto.contains(denunciante))
        assertTrue(texto.contains(ReportReason.HARASSMENT.label))
        assertTrue(texto.contains("me está acosando"))
    }

    @Test
    fun `una nota vacia se marca en vez de dejar un hueco`() {
        val texto = ReportDraft(denunciado, ReportReason.SPAM).render(denunciante, ahora, "1.0")
        assertTrue(texto.contains("(sin nota)"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `no se puede denunciar sin PeerID`() {
        ReportDraft("  ", ReportReason.OTHER)
    }
}
