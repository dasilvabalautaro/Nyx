package chat.neto.nyx

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Modo de tema elegible por el usuario. [SYSTEM] sigue el ajuste claro/oscuro de Android. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Preferencia de tema (claro / oscuro / según el sistema). Por defecto [ThemeMode.SYSTEM], que
 * es el comportamiento previo: la app seguía `isSystemInDarkTheme()` sin opción propia.
 *
 * Mismo patrón que [AppLock]: estado en el proceso (singleton), preferencia en
 * `nyx_settings`. La decisión "¿oscuro?" se resuelve con [resolveDark] (pura, testeable);
 * `MainActivity` combina el modo elegido con el tema del sistema y lo pasa a `NyxTheme`, así
 * "Sistema" sigue cambiando en caliente si el móvil alterna claro/oscuro.
 */
object ThemePreference {

    private const val KEY_MODE = "theme_mode"

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    private var initialized = false

    private fun settings(context: Context) =
        context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)

    /** Carga la preferencia guardada. Idempotente. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _mode.value = read(settings(context).getString(KEY_MODE, null))
    }

    fun setMode(context: Context, value: ThemeMode) {
        settings(context).edit().putString(KEY_MODE, value.name).apply()
        _mode.value = value
    }

    /** Tolera un valor ausente o corrupto en prefs cayendo a [ThemeMode.SYSTEM]. */
    internal fun read(stored: String?): ThemeMode =
        runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    /**
     * ¿Debe pintarse en oscuro? Decisión pura (separada para testear en JVM): [ThemeMode.SYSTEM]
     * delega en [systemDark]; los modos fijos ignoran el sistema.
     */
    fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}
