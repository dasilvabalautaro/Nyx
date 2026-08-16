package chat.neto.nyx.nativebridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import chat.neto.nyx.bridge.Bridge
import chat.neto.nyx.bridge.LikeHandler
import chat.neto.nyx.bridge.MailboxHandler
import chat.neto.nyx.bridge.MessageHandler
import chat.neto.nyx.bridge.Node
import chat.neto.nyx.bridge.PeerHandler
import chat.neto.nyx.bridge.WakeHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper Kotlin sobre el nodo go-libp2p empaquetado con gomobile (nyx-p2p.aar).
 *
 * STUB: la Fase 0 (spike) y la Fase 2 del plan sustituirán los TODO por llamadas reales
 * al AAR (Noise, DHT client, relay client v2, DCUtR, AutoNAT). Se mantiene como clase
 * inyectable @Singleton para poder cablear ya el grafo de dependencias.
 */
@Singleton
class Libp2pNode @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // Canal SIN límite: una ráfaga de trozos de archivo (un fetch de buzón entrega decenas
    // de sobres seguidos) desbordaba el SharedFlow(64) y tryEmit DESCARTABA eventos en
    // silencio — trozos perdidos e irrecuperables. Con Channel.UNLIMITED nada se descarta.
    private val _events = Channel<NodeEvent>(Channel.UNLIMITED)
    val events: Flow<NodeEvent> = _events.receiveAsFlow()

    // Identidad libp2p persistente: estable entre arranques para que el PeerID y los
    // secretos compartidos derivados de él no cambien. (TODO: cifrar en reposo.)
    private val identity: ByteArray by lazy {
        val prefs = context.getSharedPreferences("nyx_identity", Context.MODE_PRIVATE)
        prefs.getString("ed25519", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: Bridge.generateIdentity().also { fresh ->
                prefs.edit().putString("ed25519", Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
            }
    }

    private val settings get() =
        context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)

    /**
     * Multiaddr del nodo bootstrap WAN. Por defecto el nodo de infraestructura de Nyx
     * ([DEFAULT_BOOTSTRAP]), de modo que la app se une a la WAN sola en el primer arranque
     * sin que el usuario pegue nada. Un valor vacío guardado (`""`) significa "solo LAN/mDNS"
     * y se respeta — solo la *ausencia* de la pref cae al default.
     */
    fun savedBootstrap(): String? = settings.getString("bootstrap", DEFAULT_BOOTSTRAP)

    fun setBootstrap(addr: String) {
        settings.edit().putString("bootstrap", addr).apply()
    }

    /** PeerID de este dispositivo (derivado de la identidad persistente). */
    fun localPeerId(): String = Bridge.peerIDForIdentity(identity)

    /** Secreto compartido (ECDH X25519) con [peerId], a partir de nuestra identidad. */
    fun sharedSecretWith(peerId: String): ByteArray = Bridge.sharedSecretFor(identity, peerId)

    // --- Respaldo de identidad ---------------------------------------------------

    /** Bytes de la identidad Ed25519 (¡material sensible!) para el respaldo cifrado. */
    fun exportIdentityBytes(): ByteArray = identity.copyOf()

    /** Secreto ECDH con [peerId] usando una identidad EXPLÍCITA (p. ej. recién importada). */
    fun sharedSecretFor(identityBytes: ByteArray, peerId: String): ByteArray =
        Bridge.sharedSecretFor(identityBytes, peerId)

    /**
     * Valida y persiste una identidad importada; devuelve su PeerID. La identidad en uso
     * es un `lazy` y el host ya puede estar corriendo con la anterior, así que el cambio
     * es efectivo **al reiniciar el proceso** — el llamador debe forzar/indicar el reinicio.
     */
    fun importIdentityBytes(bytes: ByteArray): String {
        val peerId = Bridge.peerIDForIdentity(bytes) // lanza si no es una identidad válida
        context.getSharedPreferences("nyx_identity", Context.MODE_PRIVATE)
            .edit().putString("ed25519", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return peerId
    }

    // --- Fase 0: pipeline gomobile -> AAR -> JNI ---------------------------------
    // Llamadas reales al AAR de Go (nyx-p2p.aar). Cargan libgojni.so y prueban que
    // el bridge nativo funciona en el dispositivo antes de meter go-libp2p.

    /** Saludo desde el lado Go. Prueba la llamada JNI. */
    fun nativePing(): String = Bridge.ping()

    /** Versión de la API del bridge nativo. */
    fun nativeVersion(): String = Bridge.version()

    /** Suma cruzando la frontera JNI (Go int -> Java long). */
    fun nativeSum(a: Long, b: Long): Long = Bridge.sum(a, b)

    // --- Host go-libp2p real -----------------------------------------------------

    @Volatile
    private var node: Node? = null

    // Reenvía los mensajes entrantes (stream libp2p) al Flow de eventos del nodo.
    private val messageHandler = MessageHandler { from, data ->
        _events.trySend(NodeEvent.StreamData(from, data))
    }

    // Notifica conexiones de peers (p. ej. descubiertos por mDNS en LAN).
    private val peerHandler = PeerHandler { peerId ->
        _events.trySend(NodeEvent.Connected(peerId))
    }

    /**
     * Procesador SÍNCRONO de los sobres retirados del buzón (lo registra la capa de
     * señalización). Debe persistir el mensaje y devolver `true`; solo entonces el bridge
     * Go ack'ea el sobre y el nodo lo borra — si devuelve `false` (o lanza), el sobre se
     * reentrega en el próximo fetch y el cliente deduplica por `id`. Así un trozo de
     * archivo nunca se pierde entre el fetch y su persistencia.
     */
    @Volatile
    var mailboxProcessor: ((id: String, from: String, ts: Long, data: ByteArray) -> Boolean)? = null

    // El callback llega en un hilo de Go durante MailboxFetch; procesar aquí (bloqueando)
    // es lo que retrasa el ack hasta que la capa de dominio persistió. Sin procesador
    // registrado no se confirma nada (el buzón lo reentrega cuando ya haya quien persista).
    private val mailboxHandler = MailboxHandler { id, from, ts, data ->
        mailboxProcessor?.let { runCatching { it(id, from, ts, data) }.getOrDefault(false) } ?: false
    }

    // Aviso de buzón: el nodo señala que hay correo (o el stream de wake se reconectó).
    private val wakeHandler = WakeHandler {
        _events.trySend(NodeEvent.WakePing)
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Crea y arranca el host libp2p (identidad persistente, transportes TCP + QUIC + relay).
     * El nodo bootstrap guardado se pasa como **relay estático**: el host activa AutoRelay
     * contra él (Circuit Relay v2), de modo que obtiene una reserva y una dirección
     * `/p2p-circuit` para ser alcanzable tras NAT/CGNAT (+ DCUtR para intentar conexión directa).
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (node == null) {
            node = Bridge.newNodeWithIdentity(identity, savedBootstrap().orEmpty()).also {
                it.setMessageHandler(messageHandler)
                it.setPeerHandler(peerHandler)
                it.setMailboxHandler(mailboxHandler)
                it.setLikeHandler(likeHandler)
                // Streams de llamada entrantes → NodeEvent (los valida CallService con el hello).
                it.setCallHandler { s ->
                    _events.trySend(NodeEvent.IncomingCall(s.remotePeer(), GoCallStream(s)))
                }
                // Streams de vídeo entrantes (Fase 7c): canal aparte del audio.
                it.setVideoHandler { s ->
                    _events.trySend(NodeEvent.IncomingVideo(s.remotePeer(), GoVideoStream(s)))
                }
            }
        }
        Unit
    }

    /**
     * Activa el descubrimiento en LAN por mDNS (SOLO para pruebas en la misma WiFi). El
     * descubrimiento real de Nyx es WAN por DHT + rendezvous; esto es un atajo de test.
     * Requiere un MulticastLock o Android ignora el multicast de mDNS.
     */
    suspend fun startMdns(serviceTag: String = "nyx-lan") = withContext(Dispatchers.IO) {
        if (multicastLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("nyx-mdns").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        node?.startMdns(serviceTag)
        Unit
    }

    /** Envía un blob a [peerId] abriendo un stream libp2p (lo descubre/conecta antes). */
    suspend fun sendMessage(peerId: String, data: ByteArray) = withContext(Dispatchers.IO) {
        node?.sendMessage(peerId, data)
        Unit
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        node?.close()
        node = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    /** PeerID del host activo, o cadena vacía si no se ha arrancado. */
    fun peerId(): String = node?.peerID().orEmpty()

    /** Multiaddrs de escucha del host activo (diagnóstico). */
    fun listenAddrs(): String = node?.listenAddrs().orEmpty()

    /**
     * Inicializa la DHT Kademlia y conecta a los bootstrap dados (multiaddrs separados por
     * salto de línea). En el móvil [server] = false (modo client).
     */
    suspend fun startDht(bootstrap: String, server: Boolean = false) = withContext(Dispatchers.IO) {
        node?.startDHT(bootstrap, server)
        Unit
    }

    /** Reserva explícita de Circuit Relay v2 en [addrs]; "" (OK) o texto de error. */
    suspend fun reserveRelay(addrs: String): String = withContext(Dispatchers.IO) {
        node?.reserveRelay(addrs) ?: "nodo no iniciado"
    }

    /**
     * Sonda de latencia (Fase 7a, llamadas): mide el RTT contra el nodo bootstrap con el
     * ping estándar de libp2p ([count] pings cada [intervalMs] ms) y devuelve
     * "n=… min=… p50=… p95=… max=…". Lanza si el nodo no es alcanzable.
     */
    suspend fun pingProbe(count: Int = 50, intervalMs: Int = 20): String = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }
            .pingProbe(savedBootstrap().orEmpty(), count.toLong(), intervalMs.toLong())
    }

    /**
     * Mantiene el stream ligero de wake al nodo bootstrap (con reconexión automática en
     * Go). Cada aviso —o reconexión— emite [NodeEvent.WakePing]. Idempotente.
     */
    suspend fun startWake() = withContext(Dispatchers.IO) {
        node?.startWake(savedBootstrap().orEmpty(), wakeHandler)
        Unit
    }

    /** Corta el stream de wake (p. ej. al desactivar la WAN). */
    suspend fun stopWake() = withContext(Dispatchers.IO) {
        node?.stopWake()
        Unit
    }

    /**
     * ¿Está el stream de wake abierto ahora mismo? Si lo está, los depósitos llegan al
     * instante y el bucle WAN puede espaciarse (ahorro de batería). False durante el backoff
     * de reconexión o si el wake no corre.
     */
    suspend fun wakeOnline(): Boolean = withContext(Dispatchers.IO) {
        node?.wakeOnline() ?: false
    }

    /**
     * Deposita [data] (ciphertext) en el buzón del nodo bootstrap para entrega offline
     * a [to]. Lanza si el nodo no es alcanzable o rechaza el depósito (cuota/tamaño).
     */
    suspend fun mailboxPut(to: String, data: ByteArray) = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.mailboxPut(savedBootstrap().orEmpty(), to, data)
    }

    /**
     * Retira los mensajes pendientes del buzón propio; cada uno se entrega en el sitio al
     * [mailboxProcessor] y solo los que este confirma se ack'ean (borran) en el nodo.
     * Devuelve cuántos se confirmaron.
     */
    suspend fun mailboxFetch(): Long = withContext(Dispatchers.IO) {
        node?.mailboxFetch(savedBootstrap().orEmpty()) ?: 0L
    }

    // --- Tablón de perfiles y "me gusta" (Fase 3) ---------------------------------------

    /**
     * Publica (o actualiza) mi tarjeta en [category]. `card` va **en claro**: al revés que el
     * buzón, aquí ser descubrible es el punto. El autor lo fija el nodo desde la identidad del
     * stream, así que no viaja en la petición.
     */
    suspend fun publishCard(category: String, card: ByteArray) = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.publishCard(savedBootstrap().orEmpty(), category, card)
    }

    /**
     * Tarjetas de [category] como JSON (`[{"peer","ts","card"},…]`, `card` en base64). El
     * puente consulta todos los nodos y fusiona por autor; el JSON lo parsea la capa de
     * dominio, que es quien conoce el formato de la tarjeta.
     */
    suspend fun queryBoard(category: String, limit: Int): String = withContext(Dispatchers.IO) {
        node?.queryBoard(savedBootstrap().orEmpty(), category, limit.toLong()).orEmpty()
    }

    /**
     * Quita mi tarjeta de [category] (vacía = de todas). **Lanza si falla en algún nodo**: a
     * diferencia de publicar, un éxito parcial deja el perfil visible donde falló, y quien
     * llama tiene que poder decírselo al usuario y reintentar.
     */
    suspend fun deleteCard(category: String) = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.deleteCard(savedBootstrap().orEmpty(), category)
    }

    /** Deposita un "me gusta" ya cifrado para [to], por el camino de likes (cuota propia). */
    suspend fun likePut(to: String, data: ByteArray) = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.likePut(savedBootstrap().orEmpty(), to, data)
    }

    /**
     * Retira los "me gusta" pendientes; cada uno va en el sitio al [likeProcessor] y solo los
     * confirmados se ack'ean. Devuelve cuántos se confirmaron.
     */
    suspend fun likeFetch(): Long = withContext(Dispatchers.IO) {
        node?.likeFetch(savedBootstrap().orEmpty()) ?: 0L
    }

    /**
     * Procesador de "me gusta" entrantes. Mismo contrato que [mailboxProcessor] y por el mismo
     * motivo: devuelve true solo si quedó persistido, y solo entonces se borra en el nodo —
     * un like perdido es un match que nunca ocurre.
     */
    @Volatile
    var likeProcessor: ((from: String, ts: Long, data: ByteArray) -> Boolean)? = null

    private val likeHandler = LikeHandler { from, ts, data ->
        likeProcessor?.let { runCatching { it(from, ts, data) }.getOrDefault(false) } ?: false
    }

    /** Anuncia este nodo bajo el rendezvous diario (bytes HKDF) en la DHT. */
    suspend fun advertise(rendezvous: ByteArray) = withContext(Dispatchers.IO) {
        node?.advertise(rendezvous.toHex())
        Unit
    }

    /** Busca peers anunciados bajo el rendezvous; devuelve sus PeerID. */
    suspend fun findPeers(rendezvous: ByteArray, timeoutSec: Long = 20): List<String> =
        withContext(Dispatchers.IO) {
            val res = node?.findPeers(rendezvous.toHex(), timeoutSec).orEmpty()
            if (res.isEmpty()) emptyList() else res.split("\n")
        }

    /** Intenta conexión directa (DCUtR) y cae a Circuit Relay v2 si falla. */
    suspend fun dial(peerId: String) {
        // TODO Fase 2: Node.dial(peerId) con fallback a relay
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { b -> "%02x".format(b) }

    // --- Llamadas (Fase 7b): streams full-duplex /nyx/call/1.0.0 -------------------

    /**
     * Abre un stream de llamada hacia [peerId] (directo por DCUtR o relayed). Los frames
     * van con framing uint16 en Go; aquí solo se envuelve como [chat.neto.nyx.core.CallStream].
     */
    suspend fun openCallStream(peerId: String): chat.neto.nyx.core.CallStream =
        withContext(Dispatchers.IO) {
            GoCallStream(checkNotNull(node) { "nodo no iniciado" }.openCallStream(peerId))
        }

    /** Abre un stream de vídeo (Fase 7c) hacia [peerId]; framing uint32 (frames ≤ 1 MiB). */
    suspend fun openVideoStream(peerId: String): chat.neto.nyx.core.CallStream =
        withContext(Dispatchers.IO) {
            GoVideoStream(checkNotNull(node) { "nodo no iniciado" }.openVideoStream(peerId))
        }

    /** Adapta el CallStream del AAR (lecturas bloqueantes JNI) a la interfaz de dominio. */
    private class GoCallStream(
        private val s: chat.neto.nyx.bridge.CallStream,
    ) : chat.neto.nyx.core.CallStream {
        override suspend fun sendFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
            s.writeFrame(frame)
        }

        override suspend fun receiveFrame(): ByteArray? = withContext(Dispatchers.IO) {
            // El error (EOF/reset) señala el fin del stream → null; el que llama cuelga.
            runCatching { s.readFrame() }.getOrNull()
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) { runCatching { s.close() } }
            Unit
        }
    }

    /** Ídem para el stream de vídeo del AAR (misma interfaz de dominio, framing uint32). */
    private class GoVideoStream(
        private val s: chat.neto.nyx.bridge.VideoStream,
    ) : chat.neto.nyx.core.CallStream {
        override suspend fun sendFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
            s.writeFrame(frame)
        }

        override suspend fun receiveFrame(): ByteArray? = withContext(Dispatchers.IO) {
            runCatching { s.readFrame() }.getOrNull()
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) { runCatching { s.close() } }
            Unit
        }
    }

    companion object {
        /**
         * Nodos bootstrap WAN por defecto (uno por línea), en orden de preferencia —
         * `MailboxPut` deposita en el primero vivo, así que quien esté primero aquí es el
         * primario. El bridge retira/escucha de TODOS los nodos (`MailboxFetch`,
         * `StartWake`), así que la caída de uno no corta la entrega. Es infraestructura
         * compartida (igual para todos los usuarios) y pública, no identidad de nadie.
         *
         * **Nodo propio de Nyx** (`nyx-node-saopaulo`, Vultr São Paulo, desplegado el
         * 14 ago 2026, tarea 1.13 del plan): una sola línea, **TCP directo sin proxy** — la
         * caja tiene IP pública, así que no hay túnel que recicle WebSockets ni latencia de
         * intermediario (sonda `TestPingAgainstLiveNode` desde La Paz: p50 ~105 ms). El nodo
         * también escucha QUIC en `udp/4001` y `ws` en `8081`; no hacen falta aquí porque
         * libp2p aprende esas direcciones por identify tras el primer dial. Se mantiene el
         * formato de lista para el segundo nodo (aún pendiente: hoy este es punto único de
         * fallo del buzón, el wake y el relay).
         *
         * **Por nombre (`/dns4/`), no por IP literal, a propósito**: el multiaddr va
         * compilado en cada APK instalado, así que con una IP literal un cambio de caja o de
         * proveedor dejaría sin buzón/wake/relay a todo el parque hasta publicar otra versión
         * en Play. Con nombre, mover el nodo es un registro DNS. No debilita nada: la
         * seguridad la da el `/p2p/<PeerID>` (la clave pública del nodo), y un DNS
         * secuestrado hace fallar el handshake Noise — puede tirar el servicio, nunca
         * suplantar al nodo ni leer tráfico. El registro es un **A con proxy desactivado**
         * (nube gris en Cloudflare): el proxy solo entiende HTTP y rompería el TCP+Noise
         * del 4001.
         *
         * Validado antes de fijarlo, por IP y por nombre, con
         * `TestMailboxFetchAgainstLiveNode`, `TestMailboxRoundTripAgainstLiveNode` y
         * `TestWakeAgainstLiveNode`.
         *
         * Aquí estaban los tres nodos de **Krypta**. Se quitaron, no se renombraron: el
         * primario era `/ip4/216.128.169.83/...`, una IP literal que la sustitución de marca
         * no toca, así que habría sobrevivido intacta y los móviles de Nyx se habrían
         * conectado a la infraestructura de Krypta en producción.
         *
         * Un valor vacío aquí significaría "solo LAN/mDNS" — ver [savedBootstrap]; ojo, un
         * móvil que ya guardó preferencia de bootstrap conserva la suya y NO hereda este
         * default.
         */
        const val DEFAULT_BOOTSTRAP =
            "/dns4/nyx.neto.chat/tcp/4001/p2p/12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3"
    }
}

sealed interface NodeEvent {
    data class Connected(val peerId: String) : NodeEvent
    data class StreamData(val peerId: String, val bytes: ByteArray) : NodeEvent

    /** Aviso de buzón del nodo (o reconexión del stream de wake): retirar ahora. */
    data object WakePing : NodeEvent

    /** Stream de llamada entrante: [peerId] autenticado + el stream ya envuelto. */
    data class IncomingCall(val peerId: String, val stream: chat.neto.nyx.core.CallStream) : NodeEvent

    /** Stream de vídeo entrante (Fase 7c): [peerId] autenticado + el stream ya envuelto. */
    data class IncomingVideo(val peerId: String, val stream: chat.neto.nyx.core.CallStream) : NodeEvent
}
