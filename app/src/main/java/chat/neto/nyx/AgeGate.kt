package chat.neto.nyx

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Puerta de edad 18+ y aceptación de los Términos de uso.
 *
 * # Por qué son dos puertas y no una
 *
 * Es tentador juntarlas en una sola pantalla de bienvenida y quitárselas de encima. No se hace,
 * y el motivo no es burocrático:
 *
 *  - La **declaración de edad** cubre **toda la app** y va al primer arranque, antes de nada.
 *    Play exige el age-gating *antes* de las funciones de emparejamiento; ponerlo antes de todo
 *    es más simple de razonar y no deja ninguna rendija.
 *  - Los **Términos** se aceptan **antes de publicar la primera tarjeta**, que es el momento en
 *    que el usuario pasa de leer a *publicar contenido*. Aceptar unos términos en un splash del
 *    primer día, tres semanas antes de publicar nada, es una casilla que nadie lee. Pegada al
 *    acto que gobierna, al menos significa algo.
 *
 * # La limitación, dicha en voz alta
 *
 * Es **autodeclaración**. Sin backend de identidad no hay verificación posible, y fingir lo
 * contrario sería peor: queda documentado así para el cuestionario de clasificación de contenido
 * de Play, y en la ayuda de la app.
 *
 * Mismo patrón que [AppLock] y [ThemePreference]: estado en el proceso, preferencia en
 * `nyx_settings`, y la decisión pura extraída a funciones probables en la JVM.
 */
object AgeGate {

    private const val KEY_AGE_CONFIRMED = "age_gate_confirmed_18"
    private const val KEY_TERMS_ACCEPTED_VERSION = "terms_accepted_version"

    /**
     * Versión de los Términos aceptada. Subirla obliga a **volver a aceptar**, que es la única
     * forma de que un cambio de fondo (por ejemplo, en qué se modera o qué se publica) no se
     * aplique a espaldas de quien aceptó otra cosa. Empieza en 1.
     */
    const val TERMS_VERSION = 1

    private val _ageConfirmed = MutableStateFlow(false)
    val ageConfirmed: StateFlow<Boolean> = _ageConfirmed

    private val _termsAccepted = MutableStateFlow(false)

    /** Términos aceptados en su versión vigente. */
    val termsAccepted: StateFlow<Boolean> = _termsAccepted

    private var initialized = false

    private fun settings(context: Context) =
        context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = settings(context)
        _ageConfirmed.value = prefs.getBoolean(KEY_AGE_CONFIRMED, false)
        _termsAccepted.value =
            termsUpToDate(prefs.getInt(KEY_TERMS_ACCEPTED_VERSION, 0), TERMS_VERSION)
    }

    /** El usuario declara ser mayor de 18. Irreversible desde la UI, a propósito. */
    fun confirmAge(context: Context) {
        settings(context).edit().putBoolean(KEY_AGE_CONFIRMED, true).apply()
        _ageConfirmed.value = true
    }

    fun acceptTerms(context: Context) {
        settings(context).edit().putInt(KEY_TERMS_ACCEPTED_VERSION, TERMS_VERSION).apply()
        _termsAccepted.value = true
    }

    /** Solo para pruebas manuales en el móvil: olvida ambas decisiones. */
    fun resetForTesting(context: Context) {
        settings(context).edit()
            .remove(KEY_AGE_CONFIRMED)
            .remove(KEY_TERMS_ACCEPTED_VERSION)
            .apply()
        _ageConfirmed.value = false
        _termsAccepted.value = false
    }

    // --- Decisiones puras (probadas en la JVM) -------------------------------------------

    /**
     * Si hay que enseñar la puerta de edad. Trivial hoy, y aun así vive aquí y no incrustada en
     * un `if` de Compose: es la condición que decide si alguien entra en una app 18+, y quiero
     * que un cambio en ella rompa un test en vez de pasar en una revisión de UI.
     */
    fun shouldAskAge(confirmed: Boolean): Boolean = !confirmed

    /**
     * Los Términos están al día si se aceptó **esta** versión o una posterior. Un `>=` y no un
     * `==` para que rebajar el número por error no invalide aceptaciones buenas de todo el
     * parque instalado.
     */
    fun termsUpToDate(acceptedVersion: Int, currentVersion: Int): Boolean =
        acceptedVersion >= currentVersion

    fun shouldAskTerms(acceptedVersion: Int, currentVersion: Int): Boolean =
        !termsUpToDate(acceptedVersion, currentVersion)
}
