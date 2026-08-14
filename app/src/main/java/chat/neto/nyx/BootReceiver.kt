package chat.neto.nyx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Rearma [NyxForegroundService] tras un reinicio del móvil, para que Nyx vuelva a
 * recibir (buzón + wake) sin que el usuario tenga que abrir la app. Requiere el tipo de
 * FG service `specialUse`: `dataSync` no puede arrancarse desde BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { NyxForegroundService.start(context) }
        }
    }
}
