package chat.neto.nyx.core

import chat.neto.nyx.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * Abstracción de la capa de señalización P2P descentralizada que reemplaza al
 * descubrimiento mDNS (solo LAN) de la spec v1.0. La implementación concreta vive en
 * el módulo :p2p-signaling y se inyecta vía Hilt.
 */
interface ISignalingService {
    /** Eventos entrantes (peers encontrados, mensajes recibidos, errores). */
    val events: Flow<SignalingEvent>

    suspend fun start()

    suspend fun stop()

    /**
     * Fija quién puede **abrirnos** una conexión: los PeerID de los contactos y de los nodos,
     * separados por saltos de línea. Una cadena vacía deja el filtro abierto.
     *
     * No es una comodidad: sin esto, cualquiera que conociera nuestro PeerID nos marcaba por
     * la dirección de relay —que el propio nodo le entrega— y libp2p, ante una conexión
     * entrante por relay, iniciaba el hole punching por su cuenta y le mandaba nuestras
     * direcciones públicas. El descarte de PeerID desconocido de `onReceived` no llegaba a
     * tiempo porque está una capa por encima.
     *
     * Implementación por defecto vacía: los dobles de test no necesitan saber de esto.
     */
    suspend fun setAllowedPeers(peers: String) {}

    /** Estado del filtro de conexiones, para el diagnóstico. Vacío si no aplica. */
    suspend fun allowedPeersStatus(): String = ""

    /**
     * ¿Descubrimiento en la red local (mDNS) activado? Por defecto **no**: anunciarse en la
     * WiFi delata el PeerID a quien comparta la red, y el descubrimiento real es WAN.
     */
    suspend fun lanDiscovery(): Boolean = false

    /** Activa o desactiva el descubrimiento en la red local. */
    suspend fun setLanDiscovery(enabled: Boolean) {}

    /** Publica el punto de encuentro diario derivado por HKDF para ser descubierto. */
    suspend fun announce(rendezvous: ByteArray)

    /** Busca peers anunciados bajo [rendezvous] (los conecta); devuelve sus PeerID. */
    suspend fun findPeers(rendezvous: ByteArray): List<String>

    /** Envía un blob cifrado a un contacto (stream directo u offline vía buzón). */
    suspend fun send(contact: Contact, ciphertext: ByteArray)

    /**
     * Deposita el blob cifrado en el buzón store-and-forward del nodo para entrega
     * offline (el contacto lo retirará al conectarse). El nodo solo ve ciphertext.
     *
     * [label] es la dirección **ciega** del buzón (ver `MailboxLabel`): con ella el nodo
     * guarda bajo una etiqueta derivada del secreto de la pareja y no llega a saber para
     * quién es el mensaje. Vacía = camino antiguo, direccionado por PeerID.
     */
    suspend fun sendOffline(contact: Contact, ciphertext: ByteArray, label: String = "")

    /**
     * Retira los mensajes pendientes del buzón propio; cada uno se entrega EN EL SITIO al
     * procesador de [setMailboxProcessor] y solo los confirmados se ack'ean (borran) en el
     * nodo — los demás se reentregan en el próximo fetch. Devuelve cuántos se confirmaron.
     */
    suspend fun fetchMailbox(labels: String = ""): Int

    /**
     * Registra el procesador síncrono de sobres del buzón: recibe (PeerID remitente,
     * ciphertext, id de sobre para dedup, hora del depósito) y devuelve `true` solo si el
     * mensaje quedó persistido — la garantía **ack-tras-persistir**: un fallo o una muerte
     * del proceso a mitad de proceso ya no pierde el sobre (el nodo lo reentrega).
     */
    fun setMailboxProcessor(
        processor: suspend (
            fromPeerId: String,
            ciphertext: ByteArray,
            envelopeId: String,
            timestamp: Long,
            label: String,
        ) -> Boolean,
    )

    // --- Tablón de perfiles y "me gusta" (Fase 3) ---------------------------------------
    //
    // El tablón es el opuesto exacto del buzón en cuanto a confidencialidad: la tarjeta va
    // EN CLARO porque ser descubrible es el punto. Los "me gusta", en cambio, sí van cifrados
    // y además por un camino **con cuota propia**, separada de la del buzón — si la
    // compartieran, inundar de likes a alguien le bloquearía la entrega de sus mensajes.

    /** Publica (o actualiza) mi tarjeta en [category]. El nodo fija el autor desde el stream. */
    suspend fun publishCard(category: String, card: ByteArray)

    /** Tarjetas de [category] en JSON (`[{"peer","ts","card"},…]`, `card` en base64). */
    suspend fun queryBoard(category: String, limit: Int): String

    /**
     * Quita mi tarjeta de [category] (vacía = de todas). **Lanza si falla en algún nodo**: un
     * éxito parcial deja el perfil visible donde falló, y eso el usuario tiene que saberlo.
     */
    suspend fun deleteCard(category: String)

    /** Deposita un "me gusta" ya cifrado para [toPeerId] (camino propio, cuota propia). */
    suspend fun sendLike(toPeerId: String, ciphertext: ByteArray)

    /**
     * Cifra una denuncia para el operador. Va por aquí y no por `MessageCipher` porque el
     * destinatario no es un contacto: es una clave X25519 suelta, y el cifrado vive en el
     * puente Go (X25519 en Java exige API 33; el `minSdk` es 30).
     */
    suspend fun sealReport(operatorPubHex: String, plaintext: ByteArray): ByteArray

    /** Entrega un sobre de denuncia ya cifrado al primer nodo que lo acepte. */
    suspend fun sendReport(sealed: ByteArray)

    /** Retira los "me gusta" pendientes. Devuelve cuántos confirmó el procesador. */
    suspend fun fetchLikes(): Int

    /**
     * Procesador síncrono de "me gusta" entrantes: (PeerID emisor, ciphertext, hora) →
     * `true` solo si quedó persistido. Mismo contrato ack-tras-persistir que el buzón, y por
     * el mismo motivo: un like perdido es un match que nunca llega a formarse.
     */
    fun setLikeProcessor(processor: suspend (fromPeerId: String, ciphertext: ByteArray, timestamp: Long) -> Boolean)

    /**
     * Mantiene el stream ligero de wake al nodo: cada aviso de buzón (o reconexión) se
     * emite como [SignalingEvent.WakeReceived]. Idempotente.
     */
    suspend fun startWake(labels: String = "")

    /** Corta el stream de wake (p. ej. al desactivar la WAN). */
    suspend fun stopWake()

    /** ¿El stream de wake está abierto? Si sí, el bucle WAN puede espaciarse (batería). */
    suspend fun wakeConnected(): Boolean

    // --- WAN: unión a la DHT vía un nodo bootstrap (Fase 1) ---

    /** Multiaddr del nodo bootstrap configurado, o null si solo se usa LAN/mDNS. */
    suspend fun bootstrap(): String?

    /** Persiste el multiaddr del nodo bootstrap para futuras conexiones. */
    suspend fun setBootstrap(addr: String)

    /** Se une a la DHT a través del nodo [bootstrap] (modo client). */
    suspend fun connectDht(bootstrap: String)

    /** Multiaddrs propias de escucha/anunciadas (incluye `/p2p-circuit` si hay reserva de relay). */
    suspend fun selfAddrs(): List<String>

    /** Fuerza/renueva la reserva de Circuit Relay v2 en el nodo bootstrap; "" (OK) o error. */
    suspend fun reserveRelay(): String

    /**
     * Sonda de latencia contra el nodo bootstrap (gate de llamadas, Fase 7a): [count]
     * pings libp2p cada [intervalMs] ms → "n=… min=… p50=… p95=… max=…". Lanza si falla.
     */
    suspend fun pingProbe(count: Int, intervalMs: Int): String

    // --- Llamadas (Fase 7b) ---

    /**
     * Streams de llamada entrantes: (PeerID autenticado del otro extremo, stream). El
     * consumidor (CallService) valida el "hello" E2EE antes de aceptar la llamada.
     */
    val incomingCallStreams: Flow<Pair<String, CallStream>>

    /** Abre el stream de llamada hacia [contact] (directo o relayed). Lanza si no hay ruta. */
    suspend fun openCallStream(contact: Contact): CallStream

    /**
     * Streams de **vídeo** entrantes (Fase 7c): canal aparte del audio (`/nyx/video/…`,
     * frames hasta 1 MiB) — si el vídeo se cae, la voz no se ve afectada. Mismo contrato
     * [CallStream]; el consumidor valida el hello E2EE de la llamada activa.
     */
    val incomingVideoStreams: Flow<Pair<String, CallStream>>

    /** Abre el stream de vídeo hacia [contact] (directo o relayed). Lanza si no hay ruta. */
    suspend fun openVideoStream(contact: Contact): CallStream
}

sealed interface SignalingEvent {
    data class PeerFound(val contactId: String) : SignalingEvent

    /**
     * Mensaje entrante por stream directo (en vivo). Los del buzón NO pasan por aquí:
     * van al procesador de [ISignalingService.setMailboxProcessor] (ack-tras-persistir).
     */
    data class MessageReceived(
        val fromContactId: String,
        val ciphertext: ByteArray,
    ) : SignalingEvent

    /** El nodo avisa de que hay correo en el buzón (o el stream de wake se reconectó). */
    data object WakeReceived : SignalingEvent

    data class Failure(val cause: Throwable) : SignalingEvent
}
