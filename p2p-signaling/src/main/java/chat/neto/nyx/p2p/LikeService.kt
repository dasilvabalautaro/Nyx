package chat.neto.nyx.p2p

import chat.neto.nyx.core.ISignalingService
import chat.neto.nyx.core.KeyExchange
import chat.neto.nyx.core.MessageCipher
import chat.neto.nyx.core.model.Like
import chat.neto.nyx.core.model.LikeSource
import chat.neto.nyx.core.repository.BlockRepository
import chat.neto.nyx.core.repository.LikeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Me gusta" del tablón: enviarlos, recibirlos y decidir cuándo hay match.
 *
 * Va aparte de [ChatService] por una razón estructural, no de organización: `onReceived`
 * corta con `contacts.findByPeerId(peerId) ?: return null`, y un Like llega **por definición
 * de alguien que todavía no es contacto**. Aquí el secreto ECDH se deriva al vuelo con
 * [KeyExchange.sharedSecretWith], que ya funciona con PeerIDs arbitrarios sin fila previa.
 *
 * **Qué autentica un Like.** No hay firma explícita ni hace falta: el tag de AES-GCM ya lo es.
 * El secreto sale del ECDH entre mi clave privada y la pública **embebida en el PeerID del
 * emisor**, así que si el sobre descifra con tag válido, solo pudo haberlo cifrado quien posee
 * la privada de ese PeerID. Un tag inválido se descarta en silencio: es ruido, no un error del
 * que informar a nadie.
 *
 * **La superficie que esto abre, y cómo se cierra.** Aceptar tráfico de desconocidos es
 * justo lo que el resto de la app evita. Lo caro no es el AES-GCM (microsegundos) sino el
 * **X25519** de derivar el secreto, así que el rate-limit va **antes** de derivarlo
 * ([RateLimiter]) y el secreto derivado se cachea por peer. Un desconocido que insista
 * consume una entrada de mapa, no criptografía.
 */
@Singleton
class LikeService @Inject constructor(
    private val signaling: ISignalingService,
    private val cipher: MessageCipher,
    private val keyExchange: KeyExchange,
    private val likes: LikeRepository,
    private val blocked: BlockRepository,
) {

    private val _matches = MutableSharedFlow<Like>(extraBufferCapacity = 32)
    /** Matches recién cerrados (los dos lados se dieron like). Los consume la capa de avisos. */
    val matches: Flow<Like> = _matches

    /** Rate-limit de intentos por PeerID **no contacto**, antes de gastar un X25519. */
    private val limiter = RateLimiter(maxPerWindow = MAX_ATTEMPTS_PER_HOUR, windowMs = HOUR_MS)

    /**
     * Caché del secreto ECDH por peer. Deriva una vez por PeerID: el X25519 es lo caro de
     * este camino y un mismo emisor puede reaparecer (reentrega del nodo, like mutuo…).
     */
    private val secrets = LruCache<String, ByteArray>(MAX_CACHED_SECRETS)

    init {
        // Punto de entrada propio para los sobres `L`, con la misma garantía
        // ack-tras-persistir que el buzón: solo se confirma (y el nodo borra) lo que quedó
        // guardado. Un like perdido es un match que nunca ocurre.
        signaling.setLikeProcessor { from, ciphertext, ts ->
            runCatching { onLikeReceived(from, ciphertext, ts) }.getOrDefault(false)
        }
    }

    /**
     * Doy "me gusta" a [peerId]. Persiste primero y envía después: si el envío falla, el like
     * queda registrado localmente y el estado no miente sobre lo que el usuario hizo — pero se
     * propaga el error para que la UI pueda ofrecer reintentar.
     */
    suspend fun sendLike(peerId: String, source: LikeSource = LikeSource.BOARD): Like {
        require(peerId != keyExchange.localPeerId()) { "no puedes darte like a ti mismo" }
        require(!blocked.isBlocked(peerId)) { "has bloqueado a este perfil" }

        val state = likes.recordSent(peerId, source)
        val secret = secretFor(peerId)
        val envelope = MessageEnvelope.encodeLike(System.currentTimeMillis())
        signaling.sendLike(peerId, cipher.encrypt(secret, envelope))
        if (state.isMatch) _matches.tryEmit(state)
        return state
    }

    /** Retira los "me gusta" pendientes del nodo. Devuelve cuántos se persistieron. */
    suspend fun fetchLikes(): Int = signaling.fetchLikes()

    /**
     * Procesa un "me gusta" entrante. Devuelve `true` si quedó persistido — y también si se
     * descartó **de forma definitiva**: un sobre de un peer bloqueado, o con el tag roto, no
     * mejora reentregándolo, así que se confirma para que el nodo lo borre en vez de quedar
     * reentregándolo en bucle. Solo un fallo *transitorio* (no poder escribir en Room) debe
     * devolver `false`.
     */
    internal suspend fun onLikeReceived(fromPeerId: String, ciphertext: ByteArray, ts: Long): Boolean {
        // 1. Bloqueado: se descarta y se confirma. El bloqueado no debe notar nada.
        if (blocked.isBlocked(fromPeerId)) return true

        // 2. Rate-limit ANTES de tocar criptografía asimétrica: es la defensa real, porque lo
        //    caro es el X25519 de más abajo, no el descifrado.
        if (!limiter.allow(fromPeerId, ts)) return true

        // 3. Ahora sí, derivar (o recuperar de caché) y descifrar. El tag de GCM es la prueba
        //    de que el sobre es realmente para mí y realmente de ese PeerID.
        val plain = runCatching { cipher.decrypt(secretFor(fromPeerId), ciphertext) }.getOrNull()
            ?: return true // ruido: descartar y confirmar, reentregarlo no lo arreglaría
        if (MessageEnvelope.decode(plain) !is MessageEnvelope.Decoded.Like) return true

        // 4. Persistir. Este es el único paso cuyo fallo merece reentrega.
        val state = runCatching { likes.recordReceived(fromPeerId, LikeSource.BOARD, ts) }
            .getOrElse { return false }
        if (state.isMatch) _matches.tryEmit(state)
        return true
    }

    private fun secretFor(peerId: String): ByteArray =
        secrets.get(peerId) ?: keyExchange.sharedSecretWith(peerId).also { secrets.put(peerId, it) }

    companion object {
        private const val HOUR_MS = 60 * 60 * 1000L

        /**
         * Intentos por hora y PeerID emisor. Un usuario legítimo manda **un** like a alguien;
         * 5 deja sitio a reintentos y reentregas del nodo sin dar barra libre.
         */
        const val MAX_ATTEMPTS_PER_HOUR = 5

        /** Techo de secretos cacheados: acota la memoria si desfilan muchos desconocidos. */
        private const val MAX_CACHED_SECRETS = 256
    }
}

/**
 * Ventana deslizante por clave, sin dependencias. Puro y testeable: el reloj entra por
 * parámetro, así que no hace falta esperar una hora para probar que la ventana expira.
 *
 * Se limpia sola al insertar (no hay tarea de fondo que pueda quedarse colgada) y tiene techo
 * de claves: si no, el propio anti-abuso sería la fuga de memoria — un atacante con muchas
 * identidades haría crecer el mapa sin fin.
 */
internal class RateLimiter(
    private val maxPerWindow: Int,
    private val windowMs: Long,
    private val maxKeys: Int = 4096,
) {
    private val hits = HashMap<String, MutableList<Long>>()

    /** ¿Se permite un intento de [key] en [now]? Cuenta el intento si lo permite. */
    @Synchronized
    fun allow(key: String, now: Long): Boolean {
        val cutoff = now - windowMs
        hits.values.forEach { it.removeAll { t -> t < cutoff } }
        hits.entries.removeAll { it.value.isEmpty() }

        // Con el mapa lleno, un desconocido nuevo se rechaza en vez de hacerlo crecer. Es la
        // decisión conservadora: bajo un ataque masivo se pierden likes legítimos, pero la app
        // no se queda sin memoria.
        val list = hits[key] ?: if (hits.size >= maxKeys) return false else mutableListOf<Long>().also { hits[key] = it }
        if (list.size >= maxPerWindow) return false
        list += now
        return true
    }
}

/** LRU mínimo (LinkedHashMap en modo acceso), para no arrastrar `android.util.LruCache` a la JVM. */
internal class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) { map[key] = value }
}
