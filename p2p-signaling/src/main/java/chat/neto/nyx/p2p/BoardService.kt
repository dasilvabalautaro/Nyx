package chat.neto.nyx.p2p

import chat.neto.nyx.core.ISignalingService
import chat.neto.nyx.core.model.BoardCard
import chat.neto.nyx.core.model.DiscoveredCard
import chat.neto.nyx.core.repository.BlockRepository
import org.json.JSONArray
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El tablón de descubrimiento visto desde la app (plan 4.6): publicar mi tarjeta, consultar las
 * de una categoría y retirar la mía.
 *
 * # Lo que este servicio filtra, y por qué no lo filtra el nodo
 *
 * El nodo devuelve **todas** las tarjetas vivas de la categoría, y hace bien: no sabe quién
 * pregunta ni a quién ha bloqueado, y darle esa información sería contarle a la infraestructura
 * el grafo social que todo el diseño evita. El filtrado es cosa del cliente:
 *
 *  - **La mía**, porque verse a uno mismo en el tablón no aporta nada y hace pensar que el
 *    filtro está roto.
 *  - **Las de peers bloqueados.** Esta es la que importa: si alguien a quien bloqueaste
 *    reapareciera en el tablón, el bloqueo estaría a medias — dejaría de escribirte pero
 *    seguirías viendo su cara. Es el complemento natural del guard de `ChatService.onReceived`.
 *
 * # Robustez frente a lo que viene de fuera
 *
 * Una consulta trae bytes de **desconocidos**. Una tarjeta ilegible se descarta sin tumbar la
 * consulta entera: con el filtro al revés, una sola tarjeta corrupta —o de una versión futura
 * del formato— dejaría al usuario con el tablón vacío y sin saber por qué.
 */
@Singleton
class BoardService @Inject constructor(
    private val signaling: ISignalingService,
    private val blocked: BlockRepository,
) {

    /**
     * Publica (o reemplaza) mi tarjeta. El nodo guarda **una viva por autor y categoría**, así
     * que republicar es actualizar: no hay que borrar antes.
     */
    suspend fun publish(category: String, card: BoardCard) {
        val bytes = BoardCard.encode(card)
        // Comprobar aquí y no dejar que lo rechace el nodo: el error del nodo llega como un
        // texto genérico después de subir 96 KiB por datos móviles, y el usuario merece saber
        // antes que su avatar no cabe.
        require(bytes.size <= BoardCard.MAX_BYTES) {
            "La tarjeta ocupa ${bytes.size / 1024} KiB y el máximo son ${BoardCard.MAX_BYTES / 1024} KiB"
        }
        signaling.publishCard(category, bytes)
    }

    /** Retira mi tarjeta. Lanza si algún nodo falla: ver `ISignalingService.deleteCard`. */
    suspend fun unpublish(category: String) = signaling.deleteCard(category)

    /**
     * Tarjetas de [category], ya filtradas y ordenadas de la más reciente a la más antigua.
     * [myPeerId] se excluye del resultado.
     */
    suspend fun discover(category: String, myPeerId: String, limit: Int = 50): List<DiscoveredCard> {
        val raw = signaling.queryBoard(category, limit)
        return parse(raw)
            .filter { it.peerId != myPeerId }
            .filterNot { blocked.isBlocked(it.peerId) }
            .sortedByDescending { it.publishedAt }
    }

    /**
     * Convierte la respuesta del puente (`[{"peer","ts","card"},…]`, `card` en base64) en
     * tarjetas. Aislada y `internal` para poder probarla con respuestas hostiles sin montar la
     * pila de red entera.
     */
    internal fun parse(json: String): List<DiscoveredCard> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<DiscoveredCard>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val peer = obj.optString("peer").takeIf { it.isNotBlank() } ?: continue
            val cardB64 = obj.optString("card").takeIf { it.isNotBlank() } ?: continue
            // java.util.Base64 y no android.util.Base64: el de Android es un stub en un test
            // JVM, y con minSdk 30 el de Java está disponible en el dispositivo igual.
            val bytes = runCatching { Base64.getDecoder().decode(cardB64) }.getOrNull() ?: continue
            val card = BoardCard.decode(bytes) ?: continue
            out += DiscoveredCard(peerId = peer, publishedAt = obj.optLong("ts"), card = card)
        }
        return out
    }
}
