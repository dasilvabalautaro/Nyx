package chat.neto.nyx

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Engancha [IncomingNotifier] al arrancar el proceso — **no** desde el servicio en primer
 * plano. Un proceso revivido solo por un `BroadcastReceiver` (el latido de entrega, el
 * arranque del móvil) también pasa por aquí, así que a partir de ahora siempre hay alguien
 * que postea el aviso de un mensaje o de una llamada entrante.
 */
@HiltAndroidApp
class NyxApplication : Application() {

    @Inject
    lateinit var notifier: IncomingNotifier

    override fun onCreate() {
        super.onCreate() // Hilt inyecta los campos aquí
        notifier.attach()
    }
}
