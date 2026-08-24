package chat.neto.nyx.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.neto.nyx.AgeGate
import chat.neto.nyx.MyProfile
import chat.neto.nyx.MyProfilePrefs
import chat.neto.nyx.avatar.AvatarRenderer
import chat.neto.nyx.core.avatar.AttributeParser
import chat.neto.nyx.core.avatar.AvatarIdentity
import chat.neto.nyx.core.avatar.AvatarPrompt
import chat.neto.nyx.core.model.BoardCard
import chat.neto.nyx.p2p.BoardService
import chat.neto.nyx.p2p.ChatService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Editor del perfil propio y publicación en el tablón (plan 4.7).
 *
 * # Dónde vive cada cosa, y por qué importa
 *
 * El perfil se guarda **en el dispositivo** en cuanto se edita ([MyProfilePrefs]); publicarlo es
 * un acto **aparte y explícito**. Esa separación es intencionada: se puede rellenar el perfil,
 * verlo, cambiarlo y no enseñárselo a nadie. Nada sale del móvil hasta que se pulsa Publicar, y
 * lo que sale es una copia — editar después no actualiza el tablón hasta volver a publicar.
 *
 * # El avatar
 *
 * Arranca **derivado del PeerID** ([AvatarIdentity]), así que un perfil recién creado ya tiene
 * cara sin que nadie escriba nada. Escribir una descripción lo sustituye, pasando siempre por el
 * filtro de sólo adultos ([AvatarPrompt]) — que es lo que impide que el texto libre sea la
 * superficie de abuso que la Fase 0 señalaba.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val board: BoardService,
    private val chat: ChatService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val profile: StateFlow<MyProfile> = MyProfilePrefs.profile

    private val _avatar = MutableStateFlow<ByteArray?>(null)

    /** Avatar vigente ya comprimido; null hasta que se calcula el derivado. */
    val avatar: StateFlow<ByteArray?> = _avatar.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing.asStateFlow()

    fun clearMessage() { _message.value = null }

    init {
        viewModelScope.launch {
            // Si nunca se ha elegido avatar, se calcula el derivado del PeerID. Así el editor
            // nunca se abre con un hueco, y quien no quiera tocarlo ya tiene uno propio.
            _avatar.value = MyProfilePrefs.avatarBytes(context) ?: deriveAvatar()
        }
    }

    // --- Edición (se guarda al vuelo, no hace falta "Guardar") ---------------------------

    fun setNickname(value: String) = MyProfilePrefs.setNickname(context, value)
    fun setBio(value: String) = MyProfilePrefs.setBio(context, value)
    fun setTipAddress(value: String) = MyProfilePrefs.setTipAddress(context, value)
    fun setInterests(values: List<String>) = MyProfilePrefs.setInterests(context, values)
    fun setAgeRange(min: Int, max: Int) = MyProfilePrefs.setAgeRange(context, min, max)

    // --- Avatar -------------------------------------------------------------------------

    /** Vuelve al rostro derivado del PeerID. Siempre disponible: no depende de escribir nada. */
    fun useDerivedAvatar() {
        viewModelScope.launch {
            val bytes = deriveAvatar()
            _avatar.value = bytes
            MyProfilePrefs.setAvatar(context, bytes)
        }
    }

    /**
     * Dibuja el avatar desde una descripción libre. El texto pasa **antes** por [AvatarPrompt]:
     * es el filtro de sólo adultos (RF-09), y tiene que estar delante de cualquier vía de
     * entrada, no sólo de ésta — si algún día hay selectores, también pasan por aquí.
     */
    fun setAvatarFromText(description: String) {
        viewModelScope.launch {
            when (val r = AvatarPrompt.validate(description)) {
                is AvatarPrompt.Result.Invalid -> _message.value = r.reason
                is AvatarPrompt.Result.Valid -> {
                    val bytes = withContext(Dispatchers.Default) {
                        val attrs = AttributeParser.parse(r.text)
                        ImageCodec.compress(AvatarRenderer(AVATAR_PX).render(attrs))
                    }
                    if (bytes == null) {
                        _message.value = "No se pudo generar el avatar"
                    } else {
                        _avatar.value = bytes
                        MyProfilePrefs.setAvatar(context, bytes)
                    }
                }
            }
        }
    }

    private suspend fun deriveAvatar(): ByteArray? = withContext(Dispatchers.Default) {
        runCatching {
            ImageCodec.compress(AvatarRenderer(AVATAR_PX).render(AvatarIdentity.attributesFor(chat.myPeerId())))
        }.getOrNull()
    }

    // --- Publicación --------------------------------------------------------------------

    /** Si hay que enseñar los Términos antes de publicar (plan 4.5b). */
    fun needsTerms(): Boolean = !AgeGate.termsAccepted.value

    fun acceptTerms() = AgeGate.acceptTerms(context)

    /**
     * Publica la tarjeta en el tablón. Exige apodo: una tarjeta sin nombre no es descubrible por
     * nadie y sólo ocupa sitio.
     */
    fun publish() {
        viewModelScope.launch {
            val p = profile.value
            if (p.nickname.isBlank()) {
                _message.value = "Ponte un apodo antes de publicar"
                return@launch
            }
            _publishing.value = true
            val card = BoardCard(
                nickname = p.nickname,
                ageMin = p.ageMin,
                ageMax = p.ageMax,
                interests = p.interests,
                bio = p.bio,
                tipAddress = p.tipAddress,
                avatar = _avatar.value ?: ByteArray(0),
            )
            _message.value = runCatching { board.publish(DiscoveryViewModel.DEFAULT_CATEGORY, card) }.fold(
                onSuccess = { "Perfil publicado. Puedes retirarlo cuando quieras." },
                onFailure = { "No se pudo publicar: ${(it.message ?: "$it").take(90)}" },
            )
            _publishing.value = false
        }
    }

    /**
     * Retira la tarjeta del tablón (plan 3.12). Sin esto, "quitar mi perfil" significaría seguir
     * siendo visible hasta 48 h — además de mal producto, es justo el control que exige el RGPD.
     */
    fun unpublish() {
        viewModelScope.launch {
            _publishing.value = true
            _message.value = runCatching { board.unpublish(DiscoveryViewModel.DEFAULT_CATEGORY) }.fold(
                onSuccess = { "Tu perfil ya no está en el tablón." },
                // El error importa: `deleteCard` lanza si falla en ALGÚN nodo, porque un éxito
                // parcial deja el perfil visible donde falló y el usuario tiene que saberlo.
                onFailure = { "No se pudo retirar de todos los nodos: ${(it.message ?: "$it").take(90)}" },
            )
            _publishing.value = false
        }
    }

    private companion object {
        /** 256 px: el lienzo de referencia del renderizador y de sobra para una tarjeta. */
        const val AVATAR_PX = 256
    }
}
