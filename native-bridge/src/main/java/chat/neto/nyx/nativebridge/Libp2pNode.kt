package chat.neto.nyx.nativebridge

import android.content.Context
import android.net.wifi.WifiManager
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
 * Wrapper Kotlin sobre el nodo go-libp2p empaquetado con gomobile (nyx-p2p.aar): arranque
 * y parada del host, identidad persistente, DHT + rendezvous, relay v2, buzón, wake y streams
 * de llamada/vídeo. Todo lo que cruza a Go pasa por aquí, y los eventos vuelven como [events].
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

    /**
     * Almacén de la identidad: la envuelve con una clave del Android Keystore (no exportable)
     * y migra sola la copia en claro que dejaron versiones anteriores. Ver [IdentityStore],
     * que es donde vive la lógica —y sus tests— sin depender de Android.
     */
    private val identityStore: IdentityStore by lazy {
        IdentityStore(
            prefs = SharedIdentityPrefs(
                context.getSharedPreferences("nyx_identity", Context.MODE_PRIVATE),
            ),
            // Si el Keystore no está disponible en este móvil, IdentityStore sigue con el
            // almacenamiento privado: quedarse sin identidad sería mucho peor.
            wrapper = runCatching { KeystoreKeyWrapper() }.getOrNull(),
            generate = { Bridge.generateIdentity() },
            log = { android.util.Log.i("NyxIdentity", it) },
        )
    }

    // Identidad libp2p persistente: estable entre arranques para que el PeerID y los
    // secretos compartidos derivados de él no cambien.
    private val identity: ByteArray by lazy { identityStore.load() }

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

    /**
     * ¿Descubrimiento en la red local (mDNS) activado? **Por defecto NO** (10 sep 2026).
     *
     * Antes se arrancaba siempre, y eso anuncia el PeerID y la dirección local a **toda** la
     * WiFi: en un café o una oficina, cualquiera que conozca tu PeerID sabe que estás ahí, y el
     * resto ve un identificador estable. Como el descubrimiento real de Nyx es WAN (DHT +
     * rendezvous) y esto es un atajo de pruebas en LAN, sale de serie apagado y se enciende a
     * mano cuando hace falta. Ver `docs/security-model.md` §5.1.
     */
    fun lanDiscoveryEnabled(): Boolean = settings.getBoolean("lan_discovery", false)

    fun setLanDiscovery(enabled: Boolean) {
        settings.edit().putBoolean("lan_discovery", enabled).apply()
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
        identityStore.save(bytes)
        return peerId
    }

    // --- Fase 0: pipeline gomobile -> AAR -> JNI ---------------------------------
    // Llamadas reales al AAR de Go (nyx-p2p.aar). Cargan libgojni.so y prueban que
    // el bridge nativo funciona en el dispositivo antes de meter go-libp2p.

    /** Saludo desde el lado Go. Prueba la llamada JNI. */
    fun nativePing(): String = Bridge.ping()

    /**
     * **Commit del que se compiló el puente nativo**: 40 hexadecimales, con "-modificado" si el
     * módulo Go tenía cambios sin confirmar, o "desconocido" si el AAR no salió de `build-aar.sh`.
     * Es lo que liga el binario que se ejecuta con su código fuente.
     */
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
    var mailboxProcessor: ((id: String, from: String, label: String, ts: Long, data: ByteArray) -> Boolean)? = null

    // El callback llega en un hilo de Go durante MailboxFetch; procesar aquí (bloqueando)
    // es lo que retrasa el ack hasta que la capa de dominio persistió. Sin procesador
    // registrado no se confirma nada (el buzón lo reentrega cuando ya haya quien persista).
    private val mailboxHandler = MailboxHandler { id, from, label, ts, data ->
        mailboxProcessor?.let { runCatching { it(id, from, label, ts, data) }.getOrDefault(false) } ?: false
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

    /**
     * Deja de anunciarse en la red local y **suelta el `MulticastLock`**, que si no se queda
     * tomado mientras viva el proceso (consumo de radio para nada).
     */
    suspend fun stopMdns() = withContext(Dispatchers.IO) {
        runCatching { node?.stopMdns() }
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
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
    /**
     * Quién puede **abrirnos** conexión (uno por línea): contactos + nodos. Vacío = abierto.
     * El filtro vive en Go (`gater.go`) y corta en `InterceptSecured`, o sea en cuanto el
     * handshake revela el PeerID y **antes** de que exista conexión: así un extraño no llega a
     * identify ni provoca el hole punching que le entregaría nuestra IP.
     */
    suspend fun setAllowedPeers(peers: String) = withContext(Dispatchers.IO) {
        node?.setAllowedPeers(peers)
        Unit
    }

    /**
     * Estado del filtro para el panel de Diagnóstico: `permitidos=N entrantes=N rechazadas=N`.
     * `permitidos=0` significa **filtro abierto**, que es precisamente lo que hay que poder ver
     * sin adivinar (el 10 sep 2026 se perdió un rato con esa duda).
     */
    suspend fun gaterStats(): String = withContext(Dispatchers.IO) {
        node?.gaterStats().orEmpty()
    }

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
    /**
     * Mantiene el stream de aviso. Con [labels] se suscribe **por etiquetas** (v2), que es lo
     * único que despierta ante un depósito ciego: el nodo ya no sabe a qué PeerID avisar. Si el
     * conjunto de etiquetas cambia (rotan cada semana, o se añade un contacto), el puente
     * rehace la suscripción.
     */
    suspend fun startWake(labels: String = "") = withContext(Dispatchers.IO) {
        node?.startWake(savedBootstrap().orEmpty(), labels, wakeHandler)
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
    /**
     * Deposita [data] en el buzón para [to]. Con [label] no vacía se usa el **depósito ciego**
     * (el nodo guarda bajo esa etiqueta y no llega a saber para quién es); el puente cae al
     * camino de siempre solo contra los nodos que aún no entiendan v2.
     */
    suspend fun mailboxPut(to: String, data: ByteArray, label: String = "") = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.mailboxPut(savedBootstrap().orEmpty(), to, label, data)
    }

    /**
     * Retira los mensajes pendientes del buzón propio; cada uno se entrega en el sitio al
     * [mailboxProcessor] y solo los que este confirma se ack'ean (borran) en el nodo.
     * Devuelve cuántos se confirmaron.
     *
     * [labels] (una etiqueta por línea) añade la retirada **a ciegas**; el puente consulta
     * además el buzón v1 por el PeerID propio, porque durante la transición un contacto sin
     * actualizar sigue depositando por ahí.
     */
    suspend fun mailboxFetch(labels: String = ""): Long = withContext(Dispatchers.IO) {
        node?.mailboxFetch(savedBootstrap().orEmpty(), labels) ?: 0L
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

    /**
     * Cifra una denuncia para el operador (X25519 efímero + AES-GCM, ver `report.go` del
     * puente). Se hace en Go porque `KeyAgreement("XDH")` llegó a Android en API 33 y el
     * `minSdk` es 30.
     *
     * No necesita nodo arrancado: es criptografía pura, sin red.
     */
    suspend fun sealReport(operatorPubHex: String, plaintext: ByteArray): ByteArray =
        withContext(Dispatchers.IO) { Bridge.sealReport(operatorPubHex, plaintext) }

    /** Entrega un sobre de denuncia ya cifrado al primer nodo que lo acepte. */
    suspend fun sendReport(sealed: ByteArray) = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.sendReport(savedBootstrap().orEmpty(), sealed)
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
            if (res.isEmpty()) emptyList() else res.split("\n").distinct()
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
         * libp2p aprende esas direcciones por identify tras el primer dial.
         *
         * **Segundo nodo desde el 2 sep 2026** (`nyx-node-secaucus`, InterServer, Secaucus
         * NJ, tarea 1.18): con una sola caja, su caída dejaba sin buzón, sin wake y sin relay
         * a todo el parque. Va en **otro proveedor y otro continente** a propósito — compartir
         * sala con el primario cubre la caída del proceso, no la del centro de datos, que es
         * el fallo que importa.
         *
         * **El orden de las líneas es la política de reparto, no una preferencia estética**:
         * `MailboxPut`, `LikePut`, `PublishCard` y `SendReport` depositan en el **primero que
         * acepte**, mientras que `MailboxFetch` drena **todos** y `StartWake` mantiene un
         * stream por nodo. Por eso São Paulo va primero: está a ~105 ms de La Paz frente a los
         * ~135 ms de Secaucus, así que en marcha normal el tráfico se queda en el más cercano
         * y el segundo entra cuando el primero no está. Medido, no supuesto: `p50 135 ms /
         * p95 142 ms` con 0 pérdidas — mejor de lo que cabía esperar de Nueva Jersey, hasta el
         * punto de que una llamada que caiga en ese relay se nota poco.
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
         * `TestWakeAgainstLiveNode`. El segundo nodo pasó las cuatro sondas (esas tres más
         * `TestPingAgainstLiveNode`) **por IP y por nombre** el 2 sep 2026, antes de aparecer
         * en esta constante.
         *
         * Aviso mientras dure: las dos cajas **no corren el mismo binario**. El primario
         * arrancó el 21 ago y no lleva `report.go`, así que hoy las denuncias las recoge
         * siempre Secaucus (el cliente prueba São Paulo, le rechaza el protocolo y pasa al
         * siguiente). Funciona, pero es asimetría: en cuanto se redespliegue el primario,
         * ambas atienden lo mismo.
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
            "/dns4/nyx.neto.chat/tcp/4001/p2p/12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3\n" +
                "/dns4/nyx2.neto.chat/tcp/4001/p2p/12D3KooWBCxhFMH5HjSWArXNXkhbWkD2JXkv1U1L4pVGgJWGYBpk"
    }
}

/** Adaptador de `SharedPreferences` al puerto que usa [IdentityStore]. */
private class SharedIdentityPrefs(
    private val prefs: android.content.SharedPreferences,
) : IdentityPrefs {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
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
