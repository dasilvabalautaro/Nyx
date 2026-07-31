package chat.neto.krypta

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import chat.neto.krypta.p2p.ChatService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Latido" de entrega: un `AlarmManager` despierta la app cada [INTERVAL_MS] con
 * `setAndAllowWhileIdle` (atraviesa Doze; sin límite al estar exenta de batería) y retira el
 * buzón. Es la **red de seguridad** para móviles que **suspenden la red en segundo plano**
 * (Transsion/TECNO, Xiaomi…), donde el stream de wake queda dormido y los mensajes no llegan
 * hasta abrir la app. El wake sigue siendo el camino rápido cuando la red está viva; esto
 * garantiza la entrega (y la notificación) en ≤ ~2 min aunque no lo esté.
 */
@AndroidEntryPoint
class HeartbeatReceiver : BroadcastReceiver() {

    @Inject
    lateinit var chat: ChatService

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync + wakelock: mantiene CPU y ventana de red mientras se retira el buzón.
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krypta:heartbeat")
            .apply { acquire(WAKELOCK_MS) }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                chat.pollOnce()
            } finally {
                schedule(context) // reprograma el siguiente latido (la alarma es de un disparo)
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
                pending.finish()
            }
        }
    }

    companion object {
        private const val INTERVAL_MS = 2 * 60 * 1000L
        private const val WAKELOCK_MS = 25_000L

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, Intent(context, HeartbeatReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Programa el próximo latido. `setAndAllowWhileIdle` atraviesa Doze. */
        fun schedule(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pendingIntent(context),
                )
            }
        }

        fun cancel(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
            }
        }
    }
}
