package chat.neto.krypta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Centraliza los canales y las notificaciones de Krypta, para que tanto el
 * [KryptaForegroundService] (que postea) como la UI (que cancela al leer) usen la misma
 * lógica y los mismos ids.
 *
 * Dos canales: **servicio** (persistente, IMPORTANCE_LOW, sin badge — no debe sumar al
 * conteo del icono) y **mensajes** (IMPORTANCE_HIGH → heads-up + sonido + vibración, con
 * badge). La notificación de mensaje lleva un **deep-link** a su conversación y se
 * autocancela al tocarla; además la UI la cancela al abrir el chat, para que el conteo del
 * icono se limpie aunque entres por el icono en vez de por la notificación.
 */
object KryptaNotifications {

    // v2: los canales son inmutables tras crearse, así que para cambiar showBadge/vibración
    // hay que crear ids nuevos y borrar los viejos.
    const val CHANNEL_SERVICE = "krypta_service_v2"
    const val CHANNEL_MESSAGES = "krypta_messages_v2"
    const val CHANNEL_CALLS = "krypta_calls_v1"
    const val ONGOING_ID = 1
    const val CALL_ID = 2

    /** Extra del Intent de MainActivity: id del contacto cuya conversación abrir. */
    const val EXTRA_OPEN_CONTACT = "krypta.open_contact"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Limpia los canales v1 (importance/badge ya no configurables en ellos).
        nm.deleteNotificationChannel("krypta_service")
        nm.deleteNotificationChannel("krypta_messages")
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Servicio en segundo plano", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene Krypta conectada para recibir mensajes"
                setShowBadge(false) // el ongoing no debe contar en el icono
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES, "Mensajes", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Mensajes nuevos de tus contactos"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS, "Llamadas", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Llamadas entrantes"
                // Sin sonido de canal: el timbre en bucle lo pone el servicio (RingtoneManager).
                setSound(null, null)
                enableVibration(true)
                setShowBadge(false)
            }
        )
    }

    /** Notificación persistente del servicio (obligatoria para un FG service). */
    fun serviceNotification(context: Context): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_krypta)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Conectado — recibiendo mensajes cifrados")
            .setContentIntent(openAppIntent(context, contactId = null, requestCode = 0))
            .setOngoing(true)
            .build()

    /** Postea la notificación de un mensaje nuevo (con deep-link a su conversación). */
    fun notifyMessage(context: Context, contactId: String, title: String, text: String) {
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_krypta)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context, contactId, requestCode = contactId.hashCode()))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(contactId.hashCode(), notification)
    }

    /** Cancela la notificación de [contactId] (al leer su chat → limpia el conteo del icono). */
    fun cancel(context: Context, contactId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(contactId.hashCode())
    }

    /** Notificación de llamada entrante: tocarla abre la app (la pantalla de llamada ya
     * está en RINGING). El timbre lo pone el servicio, no el canal. */
    fun notifyIncomingCall(context: Context, contactName: String) {
        val notification = Notification.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_krypta)
            .setContentTitle("📞 Llamada entrante")
            .setContentText(contactName)
            .setContentIntent(openAppIntent(context, contactId = null, requestCode = CALL_ID))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(CALL_ID, notification)
    }

    /** Retira la notificación de llamada (al contestar/rechazar/perderse). */
    fun cancelCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CALL_ID)
    }

    private fun openAppIntent(context: Context, contactId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (contactId != null) putExtra(EXTRA_OPEN_CONTACT, contactId)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
