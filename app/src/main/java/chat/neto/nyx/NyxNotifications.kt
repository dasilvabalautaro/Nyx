package chat.neto.nyx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Centraliza los canales y las notificaciones de Nyx, para que tanto el
 * [IncomingNotifier] (que postea) como la UI (que limpia al leer) usen la misma lógica y los
 * mismos ids.
 *
 * Tres canales: **servicio** (persistente, IMPORTANCE_LOW, sin badge — no debe sumar al
 * conteo del icono), **mensajes** (IMPORTANCE_HIGH → heads-up + sonido + vibración, con
 * badge) y **llamadas** (IMPORTANCE_HIGH sin sonido de canal: el timbre en bucle lo pone
 * [IncomingNotifier]).
 *
 * La notificación de mensaje usa [Notification.MessagingStyle], así que varios mensajes
 * seguidos del mismo contacto se **acumulan** en una sola notificación (como en cualquier
 * mensajería conocida) en vez de que el último borre al anterior. Lleva un **deep-link** a su
 * conversación, se autocancela al tocarla, y todas se limpian al pasar la app a primer plano
 * ([cancelAllMessages]) — los no leídos por contacto siguen viviendo en Room, así que el
 * badge de la lista de conversaciones no se ve afectado.
 */
object NyxNotifications {

    // v2: los canales son inmutables tras crearse, así que para cambiar showBadge/vibración
    // hay que crear ids nuevos y borrar los viejos.
    const val CHANNEL_SERVICE = "nyx_service_v2"
    const val CHANNEL_MESSAGES = "nyx_messages_v2"
    const val CHANNEL_CALLS = "nyx_calls_v1"
    const val ONGOING_ID = 1
    const val CALL_ID = 2

    /** Extra del Intent de MainActivity: id del contacto cuya conversación abrir. */
    const val EXTRA_OPEN_CONTACT = "nyx.open_contact"

    /**
     * Historial reciente por contacto para el [Notification.MessagingStyle]. Vive en memoria
     * a propósito: es solo el contenido *ya visible* en la bandeja, y se descarta al cancelar
     * la notificación. El texto plano no se persiste en ningún sitio.
     */
    private val recent = mutableMapOf<String, MutableList<Pair<Long, String>>>()

    private const val MAX_LINES = 6

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Limpia los canales v1 (importance/badge ya no configurables en ellos).
        nm.deleteNotificationChannel("nyx_service")
        nm.deleteNotificationChannel("nyx_messages")
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Servicio en segundo plano", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene Nyx conectada para recibir mensajes"
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
                // Sin sonido de canal: el timbre en bucle lo pone IncomingNotifier
                // (RingtoneManager), que también vibra en bucle mientras suena.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /** Notificación persistente del servicio (obligatoria para un FG service). */
    fun serviceNotification(context: Context): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_nyx)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Conectado — recibiendo mensajes cifrados")
            .setContentIntent(openAppIntent(context, contactId = null, requestCode = 0))
            .setOngoing(true)
            .build()

    /**
     * Postea (o actualiza) la notificación de [contactId] añadiendo [text] a su hilo. Con
     * varios mensajes sin leer se ven las últimas [MAX_LINES] líneas y el conteo, y **cada
     * mensaje nuevo vuelve a sonar** (sin `setOnlyAlertOnce`), que es el comportamiento
     * esperado en una mensajería.
     */
    fun notifyMessage(
        context: Context,
        contactId: String,
        contactName: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val lines = synchronized(recent) {
            val list = recent.getOrPut(contactId) { mutableListOf() }
            list.add(timestamp to text)
            while (list.size > MAX_LINES) list.removeAt(0)
            list.toList()
        }
        val sender = Person.Builder().setName(contactName).setImportant(true).build()
        val style = Notification.MessagingStyle(Person.Builder().setName("Tú").build())
            .setConversationTitle(contactName)
        for ((ts, line) in lines) style.addMessage(line, ts, sender)

        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_nyx)
            .setStyle(style)
            .setContentTitle(contactName)
            .setContentText(text)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setNumber(lines.size)
            .setContentIntent(openAppIntent(context, contactId, requestCode = contactId.hashCode()))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(contactId.hashCode(), notification)
    }

    /** Cancela la notificación de [contactId] (al abrir su chat) y olvida su hilo. */
    fun cancel(context: Context, contactId: String) {
        synchronized(recent) { recent.remove(contactId) }
        context.getSystemService(NotificationManager::class.java).cancel(contactId.hashCode())
    }

    /**
     * Retira **todas** las notificaciones de mensaje (al pasar la app a primer plano): la
     * bandeja y el conteo del icono quedan limpios de un golpe, sin tener que entrar en cada
     * chat. Los **no leídos siguen intactos** — viven en Room y solo los borra abrir la
     * conversación (`markIncomingRead`), así que la lista de contactos conserva su badge.
     *
     * Filtra por canal en vez de usar `cancelAll()` para no tirar la notificación permanente
     * del servicio en primer plano (que lo mataría) ni la de una llamada entrante sonando.
     *
     * Va en **dos pasadas, hijas primero**: cuando hay 4+ avisos el sistema los agrupa bajo
     * una cabecera automática suya (`ranker_group`), y si se retira antes que sus hijas el
     * sistema la vuelve a crear — se quedaba una cabecera vacía en la bandeja, contando en el
     * icono. Por eso la segunda pasada relee las activas y barre lo que quede en el canal.
     */
    fun cancelAllMessages(context: Context) {
        synchronized(recent) { recent.clear() }
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching {
            val onMessagesChannel = { sbn: android.service.notification.StatusBarNotification ->
                sbn.notification.channelId == CHANNEL_MESSAGES
            }
            nm.activeNotifications
                .filter { onMessagesChannel(it) && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .forEach { nm.cancel(it.tag, it.id) }
            nm.activeNotifications
                .filter(onMessagesChannel)
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    /**
     * Notificación de **llamada entrante**. Desde API 31 usa [Notification.CallStyle] con
     * acciones de contestar/rechazar y, en ambas versiones, un **full-screen intent**: sin él
     * (era el caso) una llamada con la pantalla apagada o bloqueada solo dejaba un aviso
     * discreto en la bandeja en vez de tomar la pantalla como cualquier teléfono.
     */
    fun notifyIncomingCall(context: Context, contactName: String) {
        val fullScreen = openAppIntent(context, contactId = null, requestCode = CALL_ID)
        val answer = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_ANSWER)
        val decline = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_DECLINE)
        val builder = Notification.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_nyx)
            .setContentTitle("📞 Llamada entrante")
            .setContentText(contactName)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder().setName(contactName).setImportant(true).build()
            builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_nyx)
            builder
                .addAction(Notification.Action.Builder(icon, "Rechazar", decline).build())
                .addAction(Notification.Action.Builder(icon, "Contestar", answer).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(CALL_ID, builder.build())
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
