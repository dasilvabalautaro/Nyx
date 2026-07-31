package chat.neto.krypta

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.p2p.CallPhase
import chat.neto.krypta.p2p.CallService
import chat.neto.krypta.p2p.CallState
import chat.neto.krypta.p2p.ChatService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Servicio en primer plano que mantiene Krypta recibiendo **con la app cerrada** (Fase 5):
 * sostiene el nodo libp2p (host + bucle WAN + stream de wake) y muestra una notificación
 * por cada mensaje entrante. Sobrevive al swipe de la UI (`START_STICKY`); junto con el
 * wake integrado del nodo, un depósito en el buzón llega al móvil en segundos.
 *
 * El texto plano solo existe al construir la notificación ([ChatService.decrypt]); si el
 * descifrado falla se notifica sin contenido.
 */
@AndroidEntryPoint
class KryptaForegroundService : Service() {

    @Inject
    lateinit var chat: ChatService

    // Inyectarlo aquí garantiza que CallService (y sus colectores de señales) viven desde
    // que arranca el servicio: un invite puede llegar con la UI cerrada.
    @Inject
    lateinit var calls: CallService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Timbre en bucle de la llamada entrante (el canal de notificación va sin sonido).
    private var ringtone: android.media.Ringtone? = null

    // Visibilidad de la UI observada en el hilo PRINCIPAL (fiable). Antes se leía el estado
    // del ciclo de vida desde un hilo de fondo, que en algunos estados (p. ej. la app "casi en
    // primer plano" al estar conectada por USB) daba un valor equivocado y suprimía el aviso.
    @Volatile
    private var uiVisible = false
    private val visibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { uiVisible = true }
        override fun onStop(owner: LifecycleOwner) { uiVisible = false }
    }

    // Mantiene el WiFi despierto en segundo plano: muchos OEM (Transsion/TECNO, Xiaomi…)
    // apagan el WiFi al apagar la pantalla estando a batería, lo que mata el stream de wake y
    // la recepción. El WifiLock lo contrarresta mientras el servicio vive.
    private var wifiLock: WifiManager.WifiLock? = null

    // Al cambiar la red (WiFi↔datos, recuperar cobertura) adelanta el ciclo WAN: reconecta
    // DHT + relay + wake y retira el buzón al instante en vez de esperar los 30 s.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            chat.kickWan()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // onCreate del servicio corre en el hilo principal → seguro para observar el ciclo de vida.
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibilityObserver)
        KryptaNotifications.ensureChannels(this)
        // specialUse (no dataSync): Android 15 corta los FGS dataSync a las 6 h — fatal
        // para una conexión de mensajería persistente.
        startForeground(
            KryptaNotifications.ONGOING_ID,
            KryptaNotifications.serviceNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch { runCatching { chat.start() } }
        scope.launch {
            chat.incoming.collect { (contact, message) -> notifyMessage(contact, message) }
        }
        scope.launch {
            calls.state.collect { st -> runCatching { onCallState(st) } }
        }
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }
        runCatching {
            @Suppress("DEPRECATION") // FULL_HIGH_PERF: el único modo que mantiene el radio
            // despierto con la pantalla apagada (LOW_LATENCY solo aplica en primer plano).
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "krypta:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
        // Latido de entrega: red de seguridad para OEM que suspenden la red en 2.º plano.
        HeartbeatReceiver.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibilityObserver)
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        HeartbeatReceiver.cancel(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Notificación de mensaje nuevo — omitida si la UI está en pantalla (ya lo ves en el chat). */
    private fun notifyMessage(contact: Contact, message: Message) {
        if (uiVisible) return
        val text = runCatching { chat.notificationText(contact, message) }.getOrDefault("Mensaje nuevo")
        KryptaNotifications.notifyMessage(this, contact.id, contact.displayName, text)
    }

    // 7d: en llamada el FGS declara también el tipo **microphone**: Android/OEM saben que
    // hay captura de audio activa (el micro sigue vivo con la pantalla apagada por el
    // sensor de proximidad) y el proceso gana prioridad frente a los limpiadores. El tipo
    // `phoneCall` de verdad exige Telecom (ConnectionService) — aplazado a esa integración.
    // El upgrade ocurre al aceptar/conectar (la UI está en primer plano, así que el tipo
    // while-in-use está permitido); si el sistema lo rechaza, seguimos en specialUse.
    private var inCallTypes = false
    private fun updateForegroundType(inCall: Boolean) {
        if (inCall == inCallTypes) return
        val types = if (inCall) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        val ok = runCatching {
            startForeground(
                KryptaNotifications.ONGOING_ID,
                KryptaNotifications.serviceNotification(this),
                types,
            )
        }.isSuccess
        if (ok) inCallTypes = inCall
    }

    /** Timbre + notificación mientras la llamada entrante suena; se retiran al salir de RINGING. */
    private fun onCallState(state: CallState) {
        updateForegroundType(state.phase == CallPhase.CONNECTING || state.phase == CallPhase.ACTIVE)
        if (state.phase == CallPhase.RINGING) {
            if (ringtone == null) {
                ringtone = android.media.RingtoneManager.getRingtone(
                    this,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                )?.apply {
                    isLooping = true
                    runCatching { play() }
                }
            }
            if (!uiVisible) {
                KryptaNotifications.notifyIncomingCall(this, state.contact?.displayName ?: "Contacto")
            }
        } else {
            ringtone?.let { runCatching { it.stop() } }
            ringtone = null
            KryptaNotifications.cancelCall(this)
        }
    }

    companion object {
        /** Arranca el servicio (idempotente: si ya corre, Android reutiliza la instancia). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, KryptaForegroundService::class.java))
        }
    }
}
