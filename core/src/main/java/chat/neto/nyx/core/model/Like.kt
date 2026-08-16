package chat.neto.nyx.core.model

/**
 * De dónde salió un "me gusta". Se persiste por nombre, no por ordinal, para que reordenar
 * el enum no reinterprete filas ya guardadas.
 */
enum class LikeSource {
    /** Desde una tarjeta del tablón (el camino normal, ver Fase 3 del plan). */
    BOARD,

    /** Añadido a mano por PeerID, sin pasar por el tablón. */
    MANUAL,

    /** Valor no reconocido en la fila persistida (build más nueva que escribió otra cosa). */
    UNKNOWN,

    ;

    companion object {
        /**
         * Tolera un valor ausente o desconocido cayendo a [UNKNOWN] en vez de lanzar. Una fila
         * escrita por una versión posterior no debe poder tumbar la lectura de la tabla entera;
         * el origen del like es metadato, nunca el dato que decide si hay match.
         */
        fun read(stored: String?): LikeSource =
            runCatching { valueOf(stored ?: UNKNOWN.name) }.getOrDefault(UNKNOWN)
    }
}

/**
 * Estado del "me gusta" con un peer, desde la perspectiva de **este** dispositivo.
 *
 * El match se detecta localmente y sin protocolo de coordinación: cada lado sabe que hay
 * mutualidad en cuanto tiene a la vez un [sentAt] (yo le di like) y un [receivedAt] (me lo
 * dio), y ambos llegan a la misma conclusión con los dos "like" unidireccionales que ya se
 * intercambiaron. No hay un tercero que declare el match ni un handshake que pueda quedarse
 * a medias.
 *
 * **La regla que sostiene el anti-acoso**: un like solo-recibido ([receivedAt] sin [sentAt])
 * no habilita nada — ni mensajería, ni contacto. Sin ella, cualquiera podría abrir un chat
 * con quien no le ha correspondido con solo pulsar un botón, que es exactamente el problema
 * que las apps de citas tienen que resolver. Está expresada en [canMessage] y probada.
 */
data class Like(
    val peerId: String,
    /** Cuándo di yo el like. `null` = no se lo he dado. */
    val sentAt: Long? = null,
    /** Cuándo me lo dio el peer. `null` = no me lo ha dado. */
    val receivedAt: Long? = null,
    /** Cuándo se detectó la mutualidad. Se fija una vez y no se vuelve a tocar. */
    val matchedAt: Long? = null,
    val source: LikeSource = LikeSource.UNKNOWN,
) {
    /** Hay match: los dos lados se dieron like. */
    val isMatch: Boolean get() = matchedAt != null

    /**
     * ¿Se puede escribir a este peer? Solo con match. Recibir un like **no** basta: ver la
     * nota de la clase.
     */
    val canMessage: Boolean get() = isMatch
}

/**
 * Transiciones del estado de like. Puras y sin Room a propósito (mismo patrón que
 * `ThemePreference.resolveDark` y `AppLock.shouldRelock`): la regla que decide si hay match
 * —y por tanto si se desbloquea la mensajería— se puede probar en la JVM, sin base de datos
 * ni dispositivo.
 */
object LikeState {

    /**
     * Registra que **yo** di like a [peerId]. Idempotente: repetirlo no reescribe la fecha
     * original ni el origen — la fila la estrena el primer evento y los siguientes solo
     * pueden añadir el sentido que faltaba.
     */
    fun applySent(current: Like?, peerId: String, source: LikeSource, now: Long): Like =
        settle((current ?: Like(peerId, source = source)).let { it.copy(sentAt = it.sentAt ?: now) }, now)

    /** Registra que [peerId] me dio like a **mí**. Idempotente igual que [applySent]. */
    fun applyReceived(current: Like?, peerId: String, source: LikeSource, now: Long): Like =
        settle((current ?: Like(peerId, source = source)).let { it.copy(receivedAt = it.receivedAt ?: now) }, now)

    /**
     * Fija [Like.matchedAt] en cuanto los dos sentidos están presentes, y **solo la primera
     * vez**: la fecha del match es el instante en que se detectó, no la del último evento que
     * lo tocó, así que no se sobrescribe.
     */
    private fun settle(like: Like, now: Long): Like =
        if (like.sentAt != null && like.receivedAt != null && like.matchedAt == null) {
            like.copy(matchedAt = now)
        } else {
            like
        }
}
