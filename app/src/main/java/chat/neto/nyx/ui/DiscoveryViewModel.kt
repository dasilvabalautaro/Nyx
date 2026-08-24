package chat.neto.nyx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.neto.nyx.core.model.DiscoveredCard
import chat.neto.nyx.p2p.BoardService
import chat.neto.nyx.p2p.ChatService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
