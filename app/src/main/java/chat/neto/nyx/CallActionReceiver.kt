package chat.neto.krypta

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import chat.neto.krypta.p2p.CallService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Acciones de **contestar / rechazar** desde la notificación de llamada entrante, para no
 * obligar a desbloquear y entrar en la app (que es lo que había: la notificación solo abría
 * MainActivity). Contestar además intenta traer la pantalla de llamada al frente — al tocar
 * una acción de notificación el sistema concede una ventana breve de arranque de Activity en
 * segundo plano, así que normalmente funciona; si no, el audio ya está en marcha y la
 * `CallScreen` aparece al abrir la app.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var calls: CallService

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val answering = intent.action == ACTION_ANSWER
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (answering) calls.accept() else calls.reject()
            } finally {
                pending.finish()
            }
        }
        if (answering) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "chat.neto.krypta.CALL_ANSWER"
        const val ACTION_DECLINE = "chat.neto.krypta.CALL_DECLINE"

        fun pendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, CallActionReceiver::class.java).setAction(action)
                    .setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
