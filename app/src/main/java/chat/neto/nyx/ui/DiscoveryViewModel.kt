package chat.neto.nyx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.neto.nyx.core.model.DiscoveredCard
import chat.neto.nyx.core.model.Like
import chat.neto.nyx.core.repository.LikeRepository
import chat.neto.nyx.p2p.BoardService
import chat.neto.nyx.p2p.ChatService
import chat.neto.nyx.p2p.LikeService
import chat.neto.nyx.p2p.ReportService
import chat.neto.nyx.core.model.ReportDraft
import chat.neto.nyx.core.model.ReportReason
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado de la pantalla de descubrimiento (plan 4.6).
 *
 * # Por qué el estado es un `sealed interface` y no un puñado de booleanos
 *
 * "Vacío" y "no se pudo consultar" se parecen mucho en pantalla y significan cosas opuestas: en
 * uno no hay nadie publicando, en el otro no llegamos a saberlo. Con `cargando`/`error`/lista
 * sueltos es fácil pintar "todavía no hay nadie por aquí" cuando en realidad el nodo está caído
 * — y en un tablón recién estrenado esa confusión es especialmente cara, porque la respuesta
 * correcta a "no hay nadie" es esperar, y a "no se pudo consultar" es reintentar.
 */
sealed interface DiscoveryState {
    data object Loading : DiscoveryState
    data class Cards(val cards: List<DiscoveredCard>) : DiscoveryState
    data object Empty : DiscoveryState
    data class Error(val message: String) : DiscoveryState
}

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val board: BoardService,
    private val chat: ChatService,
    private val likes: LikeService,
    private val likeRepo: LikeRepository,
    private val reports: ReportService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Loading)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    /**
     * Categoría única por ahora. Cuando haya varias, esto pasa a ser estado de la pantalla; hoy
     * inventar un selector sin nada que seleccionar sería UI muerta.
     */
    val category: String = DEFAULT_CATEGORY

    init {
        refresh()
    }

    /**
     * Estado de "me gusta" por PeerID, para que la tarjeta sepa si ya se le dio.
     *
     * Sale del repositorio y no de un estado local de la pantalla porque un like es
     * **permanente y bidireccional**: puede llegar el del otro lado mientras miras el tablón, y
     * entonces la tarjeta tiene que pasar a match sin recargar.
     */
    val likeStates: StateFlow<Map<String, Like>> =
        likeRepo.observeAll()
            .map { list -> list.associateBy { it.peerId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _action = MutableStateFlow<String?>(null)

    /** Aviso de la última acción sobre una tarjeta. */
    val action: StateFlow<String?> = _action.asStateFlow()

    fun clearAction() { _action.value = null }

    /**
     * "Me interesa". No abre ninguna conversación: un like **solo enviado** no desbloquea la
     * mensajería, que es la regla sobre la que se sostiene toda la postura anti-acoso
     * (`Like.canMessage`). Si el otro lado ya te había dado el suyo, esto cierra el match y de
     * eso se encarga `LikeState`.
     */
    fun like(peerId: String, nickname: String) {
        viewModelScope.launch {
            _action.value = runCatching { likes.sendLike(peerId) }.fold(
                onSuccess = { estado ->
                    if (estado.isMatch) "¡Hay match con $nickname! Ya podéis escribiros."
                    else "Le has dicho que te interesa. Si te corresponde, podréis hablar."
                },
                onFailure = { "No se pudo enviar: ${(it.message ?: "$it").take(80)}" },
            )
        }
    }

    /**
     * Bloquea desde la tarjeta — la mitad de 4.3 que esperaba a que existiera el tablón.
     * Se refresca después para que desaparezca de la lista en el sitio, sin recargar a mano:
     * seguir viendo a quien acabas de bloquear es exactamente lo que el filtro evita.
     */
    fun block(peerId: String, nickname: String) {
        viewModelScope.launch {
            runCatching { chat.block(peerId, "bloqueado desde el tablón") }
                .onFailure { _action.value = "No se pudo bloquear"; return@launch }
            _action.value = "$nickname ya no aparecerá en el tablón ni podrá escribirte."
            refresh()
        }
    }

    /**
     * Denuncia desde la tarjeta — la mitad de 4.4b que esperaba al tablón. Nunca lleva
     * fragmento de conversación: aquí no hay ninguna, y `ReportService` bloquea igual, así que
     * la tarjeta desaparece del tablón en el refresco.
     */
    fun report(peerId: String, nickname: String, reason: ReportReason, note: String) {
        viewModelScope.launch {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            val draft = ReportDraft(
                reportedPeerId = peerId,
                reason = reason,
                note = note,
                includeExcerpt = false,
            )
            _action.value = when (reports.report(draft, chat.myPeerId(), version)) {
                is ReportService.Result.Sent -> "Denuncia enviada. $nickname queda bloqueado."
                is ReportService.Result.Blocked ->
                    "$nickname queda bloqueado, pero la denuncia no se pudo enviar."
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = DiscoveryState.Loading
            val myPeerId = runCatching { chat.myPeerId() }.getOrDefault("")
            _state.value = runCatching { board.discover(category, myPeerId) }.fold(
                onSuccess = { if (it.isEmpty()) DiscoveryState.Empty else DiscoveryState.Cards(it) },
                // El mensaje del nodo se enseña recortado en vez de tragárselo: cuando esto
                // falle en producción, lo primero que hará falta saber es si el nodo no habla el
                // protocolo (binario viejo) o si es que no hay red.
                onFailure = { DiscoveryState.Error((it.message ?: it.toString()).take(120)) },
            )
        }
    }

    companion object {
        const val DEFAULT_CATEGORY = "citas"
    }
}
