package chat.neto.nyx

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprueba en el dispositivo real que las notificaciones **se construyen y se postean**.
 * Vale la pena instrumentarlo porque los estilos ricos fallan en tiempo de ejecución, no de
 * compilación: `Notification.CallStyle` la rechaza el sistema (excepción en `notify`) si le
 * falta el intent a pantalla completa o el servicio en primer plano, y un fallo así dejaría
 * **todas** las llamadas entrantes sin aviso sin que nada lo delatara en el build.
 *
 * También fija la regla de limpieza: al volver a la app se van los avisos de mensaje y se
 * queda todo lo demás (servicio en primer plano, llamada sonando).
 */
@RunWith(AndroidJUnit4::class)
class NyxNotificationsTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = context.getSystemService(NotificationManager::class.java)

    private fun active(id: Int) = nm.activeNotifications.firstOrNull { it.id == id }

    @Before
    fun setUp() {
        NyxNotifications.ensureChannels(context)
        cleanUp()
    }

    @After
    fun cleanUp() {
        NyxNotifications.cancelAllMessages(context)
        NyxNotifications.cancelCall(context)
    }

    @Test
    fun messageNotificationsAccumulatePerContact() {
        NyxNotifications.notifyMessage(context, "peer-A", "Ana", "hola", 1_000L)
        NyxNotifications.notifyMessage(context, "peer-A", "Ana", "¿estás?", 2_000L)
        NyxNotifications.notifyMessage(context, "peer-B", "Beto", "📷 Foto", 3_000L)

        val ana = active("peer-A".hashCode())
        assertNotNull("el aviso de Ana debería estar en la bandeja", ana)
        assertEquals(NyxNotifications.CHANNEL_MESSAGES, ana!!.notification.channelId)
        // Los dos mensajes se acumulan en UNA notificación (MessagingStyle), no se pisan —
        // que es lo que pasaba antes: cada mensaje nuevo borraba el texto del anterior.
        val style = androidx.core.app.NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(ana.notification)
        assertEquals(listOf("hola", "¿estás?"), style?.messages?.map { it.text.toString() })
        assertEquals(2, ana.notification.number)
        assertNotNull("cada contacto tiene su propio aviso", active("peer-B".hashCode()))
    }

    /** Abrir un chat retira solo su aviso; el del otro contacto sigue. */
    @Test
    fun cancelRemovesOnlyThatContact() {
        NyxNotifications.notifyMessage(context, "peer-A", "Ana", "hola")
        NyxNotifications.notifyMessage(context, "peer-B", "Beto", "hey")

        NyxNotifications.cancel(context, "peer-A")

        assertNull(active("peer-A".hashCode()))
        assertNotNull(active("peer-B".hashCode()))
    }

    /**
     * Volver a la app limpia TODOS los avisos de mensaje (incluida la cabecera de grupo que
     * el sistema añade sola a partir de 4) sin tocar otros canales — el permanente del
     * servicio en primer plano no debe caer nunca: cancelarlo mataría el servicio.
     */
    @Test
    fun cancelAllMessagesClearsTheChannelAndNothingElse() {
        repeat(5) { i -> NyxNotifications.notifyMessage(context, "peer-$i", "C$i", "m$i") }
        nm.notify(NyxNotifications.ONGOING_ID, NyxNotifications.serviceNotification(context))

        NyxNotifications.cancelAllMessages(context)

        val visibles = nm.activeNotifications.filter {
            it.notification.channelId == NyxNotifications.CHANNEL_MESSAGES &&
                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        assertTrue("no debe quedar ningún aviso de mensaje: $visibles", visibles.isEmpty())
        assertNotNull(
            "el permanente del servicio debe sobrevivir",
            active(NyxNotifications.ONGOING_ID),
        )
    }

    /**
     * La de llamada entrante debe postearse de verdad: es donde `CallStyle` + el intent a
     * pantalla completa pueden hacer que el sistema rechace la notificación en caliente.
     */
    @Test
    fun incomingCallNotificationIsAccepted() {
        NyxNotifications.notifyIncomingCall(context, "Ana")

        val call = active(NyxNotifications.CALL_ID)
        assertNotNull("la llamada entrante no llegó a la bandeja", call)
        assertEquals(Notification.CATEGORY_CALL, call!!.notification.category)
        assertNotNull(
            "sin fullScreenIntent no toma la pantalla con el móvil bloqueado",
            call.notification.fullScreenIntent,
        )

        NyxNotifications.cancelCall(context)
        assertNull(active(NyxNotifications.CALL_ID))
    }
}
