package chat.neto.krypta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Rearma [KryptaForegroundService] tras un reinicio del móvil, para que Krypta vuelva a
 * recibir (buzón + wake) sin que el usuario tenga que abrir la app. Requiere el tipo de
 * FG service `specialUse`: `dataSync` no puede arrancarse desde BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { KryptaForegroundService.start(context) }
        }
    }
}
