package chat.neto.nyx.p2p

import chat.neto.nyx.core.model.BoardCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BoardServiceTest {

    private val yo = "12D3KooWYo"
    private val ana = "12D3KooWAna"
    private val bruno = "12D3KooWBruno"

    private fun card(nick: String) = BoardCard(nickname = nick, ageMin = 28, ageMax = 34)

    private fun respuesta(vararg entradas: Pair<String, BoardCard>, ts: Long = 1_000L): String =
        entradas.mapIndexed { i, (peer, c) ->
            val b64 = Base64.getEncoder().encodeToString(BoardCard.encode(c))
            """{"peer":"$peer","ts":${ts + i},"card":"$b64"}"""
        }.joinToString(",", "[", "]")

    private fun service(
        json: String = "[]",
        bloqueados: Set<String> = emptySet(),
    ): Pair<BoardService, FakeSignalingBase> {
        val signaling = object : FakeSignalingBase() {
            override suspend fun queryBoard(category: String, limit: Int): String = json
        }
        return BoardService(signaling, FakeBlocks(bloqueados)) to signaling
    }

    @Test
    fun `descubrir devuelve las tarjetas legibles, de la mas reciente a la mas antigua`() = runTest {
        val (board, _) = service(respuesta(ana to card("Ana"), bruno to card("Bruno")))

        val encontradas = board.discover("citas", yo)

        assertEquals(2, encontradas.size)
        assertEquals(bruno, encontradas[0].peerId) // ts mayor
        assertEquals("Ana", encontradas[1].card.nickname)
    }

    /** Verse a uno mismo en el tablón no aporta nada y hace pensar que el filtro está roto. */
    @Test
    fun `mi propia tarjeta no sale en el descubrimiento`() = runTest {
        val (board, _) = service(respuesta(yo to card("Yo"), ana to card("Ana")))

        val encontradas = board.discover("citas", yo)

        assertEquals(listOf(ana), encontradas.map { it.peerId })
    }

    /**
     * El filtro que de verdad importa. Si alguien a quien bloqueaste reapareciera en el tablón,
     * el bloqueo estaría a medias: dejaría de escribirte pero seguirías viendo su cara. El nodo
     * no puede filtrarlo —no sabe quién pregunta, y contárselo sería darle el grafo social— así
     * que tiene que hacerlo el cliente.
     */
    @Test
    fun `las tarjetas de peers bloqueados no salen`() = runTest {
        val (board, _) = service(
            respuesta(ana to card("Ana"), bruno to card("Bruno")),
            bloqueados = setOf(bruno),
        )

        assertEquals(listOf(ana), board.discover("citas", yo).map { it.peerId })
    }

    /**
     * Una tarjeta corrupta o de una versión futura del formato **no puede vaciar el tablón**.
     * Viene de un desconocido a través de un nodo, así que es el caso normal, no el excepcional.
     */
    @Test
    fun `una tarjeta ilegible se descarta sin tumbar el resto`() = runTest {
        val buena = Base64.getEncoder().encodeToString(BoardCard.encode(card("Ana")))
        val json = """[
            {"peer":"$ana","ts":1,"card":"$buena"},
            {"peer":"$bruno","ts":2,"card":"bm8gc295IHVuYSB0YXJqZXRh"},
            {"peer":"$bruno","ts":3,"card":"no es base64 válido !!"},
            {"peer":"","ts":4,"card":"$buena"},
            {"peer":"$bruno","ts":5}
        ]"""
        val (board, _) = service(json)

        assertEquals(listOf(ana), board.discover("citas", yo).map { it.peerId })
    }

    @Test
    fun `una respuesta que no es JSON da lista vacia en vez de lanzar`() = runTest {
        val (board, _) = service("no soy json")
        assertTrue(board.discover("citas", yo).isEmpty())
    }

    @Test
    fun `publicar serializa la tarjeta y la manda a la categoria`() = runTest {
        val (board, signaling) = service()

        board.publish("citas", card("Ana"))

        assertEquals("citas", signaling.publishedCategory)
        assertEquals("Ana", BoardCard.decode(signaling.publishedCard!!)?.nickname)
    }

    /**
     * El tope se comprueba **antes** de subir: el error del nodo llegaría como un texto genérico
     * después de haber gastado 96 KiB de datos móviles.
     */
    @Test
    fun `una tarjeta que se pasa del tope se rechaza antes de subirla`() = runTest {
        val (board, signaling) = service()
        val enorme = card("Ana").copy(avatar = ByteArray(BoardCard.MAX_BYTES + 1))

        val error = runCatching { board.publish("citas", enorme) }.exceptionOrNull()

        assertTrue("debería rechazarse", error is IllegalArgumentException)
        assertTrue("no debería haber subido nada", signaling.publishedCard == null)
    }
}
