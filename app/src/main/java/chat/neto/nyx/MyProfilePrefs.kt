package chat.neto.nyx

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Borrador de "mi perfil": lo que el usuario publicará en el tablón cuando lo pida
 * explícitamente (Fase 3/4 del plan). **Nada de esto sale del dispositivo por sí solo.**
 *
 * No es una tabla Room a propósito: es una única fila por dispositivo, sin nada relacional
 * que consultar. Mismo patrón que [ThemePreference] y [AppLock] — singleton de proceso,
 * preferencia en `nyx_settings`.
 *
 * **El avatar es la excepción y va a fichero, no a prefs**, aunque el plan lo llamara
 * `avatarBytes`: la API pública se mantiene ([avatarBytes] / [setAvatar]), pero los bytes
 * viven en `filesDir/nyx_profile/avatar.bin`. `SharedPreferences` carga el fichero entero en
 * memoria al arrancar y lo **reescribe completo en cada `apply()` de cualquier clave**, así
 * que meter ahí ~58 KiB (78 KB en base64) haría que cambiar el tema o el bloqueo reescribiera
 * el avatar entero cada vez. En prefs queda solo si hay avatar o no.
 *
 * Todo lo que el usuario escribe se **sanea al guardar**, no al publicar ([sanitizeNickname] y
 * compañía, puras y testeadas): estos campos acaban en una tarjeta pública, y saltos de línea
 * o textos kilométricos serían tanto un problema de formato como una superficie de abuso.
 */
object MyProfilePrefs {

    // Topes. Son de producto, no técnicos —el texto entero ocupa menos de 1 KiB de los 96 KiB
    // de la tarjeta— y se bajaron el 24 ago 2026 después de **mirar** una tarjeta llena en el
    // móvil: con los valores viejos, diez intereses ocupaban diez líneas (media tarjeta, más
    // que el nombre y la bio juntos) y un apodo de 32 caracteres se comía tres renglones,
    // dejando el avatar perdido al lado de un bloque de texto.
    //
    // El criterio de producto que los fija: la tarjeta es el **anzuelo**, no la biografía. Para
    // contar quién eres está la conversación, que es donde además va cifrada.
    //
    // Bajarlos **recorta en silencio** el texto ya guardado (`sanitize` corre al cargar), así
    // que sale gratis ahora y no después de publicar en Play.
    const val MAX_NICKNAME_CHARS = 20
    const val MAX_BIO_CHARS = 250
    const val MAX_INTERESTS = 5
    const val MAX_INTEREST_CHARS = 24
    const val MAX_TIP_ADDRESS_CHARS = 128

    /** Nyx es 18+ (ver docs/NYX-POLITICA-CONTENIDO.md): la franja no puede empezar por debajo. */
    const val MIN_AGE = 18
    const val MAX_AGE = 99

    /** Mismo tope que las imágenes en línea del chat, para que el avatar quepa en la tarjeta. */
    const val MAX_AVATAR_BYTES = 58 * 1024

    private const val KEY_NICKNAME = "profile_nickname"
    private const val KEY_AGE_MIN = "profile_age_min"
    private const val KEY_AGE_MAX = "profile_age_max"
    private const val KEY_INTERESTS = "profile_interests"
    private const val KEY_BIO = "profile_bio"
    private const val KEY_TIP_ADDRESS = "profile_tip_address"
    private const val KEY_HAS_AVATAR = "profile_has_avatar"

    /** Separador de intereses. Los intereses se sanean quitando saltos, así que no puede chocar. */
    private const val INTEREST_SEPARATOR = "\n"

    private val _profile = MutableStateFlow(MyProfile())
    val profile: StateFlow<MyProfile> = _profile

    private var initialized = false

    private fun settings(context: Context) =
        context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)

    private fun avatarFile(context: Context) =
        File(File(context.filesDir, "nyx_profile").apply { mkdirs() }, "avatar.bin")

    /** Carga el perfil guardado. Idempotente. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val p = settings(context)
        _profile.value = MyProfile(
            nickname = p.getString(KEY_NICKNAME, "").orEmpty(),
            ageMin = p.getInt(KEY_AGE_MIN, MIN_AGE),
            ageMax = p.getInt(KEY_AGE_MAX, MAX_AGE),
            interests = readInterests(p.getString(KEY_INTERESTS, null)),
            bio = p.getString(KEY_BIO, "").orEmpty(),
            tipAddress = p.getString(KEY_TIP_ADDRESS, "").orEmpty(),
            hasAvatar = p.getBoolean(KEY_HAS_AVATAR, false),
        ).let(::sanitize) // tolera prefs escritas por una versión con otros topes
    }

    fun setNickname(context: Context, value: String) = update(context) { it.copy(nickname = value) }

    fun setBio(context: Context, value: String) = update(context) { it.copy(bio = value) }

    fun setTipAddress(context: Context, value: String) = update(context) { it.copy(tipAddress = value) }

    fun setInterests(context: Context, value: List<String>) = update(context) { it.copy(interests = value) }

    fun setAgeRange(context: Context, min: Int, max: Int) =
        update(context) { it.copy(ageMin = min, ageMax = max) }

    /** Bytes del avatar, o `null` si no hay. Se leen del fichero, no de prefs. */
    fun avatarBytes(context: Context): ByteArray? {
        init(context)
        if (!_profile.value.hasAvatar) return null
        val f = avatarFile(context)
        return runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()
    }

    /**
     * Guarda (o borra, con `null`) el avatar. Rechaza lo que pase de [MAX_AVATAR_BYTES]
     * devolviendo `false` en vez de lanzar: quien llama es la UI, y el caso normal de fallo es
     * "esta imagen no comprime lo bastante", no un error de programación.
     */
    fun setAvatar(context: Context, bytes: ByteArray?): Boolean {
        val f = avatarFile(context)
        if (bytes == null) {
            f.delete()
            settings(context).edit().putBoolean(KEY_HAS_AVATAR, false).apply()
            _profile.value = _profile.value.copy(hasAvatar = false)
            return true
        }
        if (bytes.size > MAX_AVATAR_BYTES) return false
        // tmp + rename: un fallo a media escritura no deja un avatar truncado (mismo criterio
        // que DiskFileStore).
        val tmp = File(f.parentFile, "avatar.tmp")
        return runCatching {
            tmp.writeBytes(bytes)
            check(tmp.renameTo(f)) { "no se pudo renombrar el avatar" }
            settings(context).edit().putBoolean(KEY_HAS_AVATAR, true).apply()
            _profile.value = _profile.value.copy(hasAvatar = true)
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    /** Borra el perfil entero, avatar incluido (al restablecer o cerrar sesión). */
    fun clear(context: Context) {
        avatarFile(context).delete()
        settings(context).edit()
            .remove(KEY_NICKNAME).remove(KEY_AGE_MIN).remove(KEY_AGE_MAX)
            .remove(KEY_INTERESTS).remove(KEY_BIO).remove(KEY_TIP_ADDRESS)
            .remove(KEY_HAS_AVATAR)
            .apply()
        _profile.value = MyProfile()
    }

    private fun update(context: Context, edit: (MyProfile) -> MyProfile) {
        // Cargar antes de escribir, siempre. Esta línea arregla una pérdida de datos real y
        // silenciosa: `update` reescribe **todos** los campos desde `_profile.value`, así que
        // si nadie había llamado a [init] —y durante un tiempo nadie lo hacía— el estado en
        // memoria era el perfil vacío y editar un solo campo tras reiniciar la app borraba del
        // disco todos los demás.
        //
        // Va aquí y no sólo en el arranque a propósito: depender de que alguien acuerde llamar
        // a `init` es exactamente lo que falló. Es idempotente, así que no cuesta nada.
        init(context)
        val next = sanitize(edit(_profile.value))
        settings(context).edit()
            .putString(KEY_NICKNAME, next.nickname)
            .putInt(KEY_AGE_MIN, next.ageMin)
            .putInt(KEY_AGE_MAX, next.ageMax)
            .putString(KEY_INTERESTS, next.interests.joinToString(INTEREST_SEPARATOR))
            .putString(KEY_BIO, next.bio)
            .putString(KEY_TIP_ADDRESS, next.tipAddress)
            .apply()
        _profile.value = next
    }

    private fun readInterests(stored: String?): List<String> =
        stored?.split(INTEREST_SEPARATOR).orEmpty()

    // --- saneado (puro y testeable: nada de Context aquí abajo) ---

    /** Aplica todos los topes de una vez. */
    fun sanitize(p: MyProfile): MyProfile {
        val (min, max) = sanitizeAgeRange(p.ageMin, p.ageMax)
        return p.copy(
            nickname = sanitizeNickname(p.nickname),
            ageMin = min,
            ageMax = max,
            interests = sanitizeInterests(p.interests),
            bio = sanitizeBio(p.bio),
            tipAddress = sanitizeTipAddress(p.tipAddress),
        )
    }

    /** Una sola línea: un apodo con saltos rompería la maquetación de la tarjeta. */
    fun sanitizeNickname(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_NICKNAME_CHARS)

    /** La bio sí admite saltos (es un párrafo), pero no una ristra de líneas en blanco. */
    fun sanitizeBio(value: String): String =
        value.replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_BIO_CHARS)

    fun sanitizeTipAddress(value: String): String =
        value.replace(Regex("\\s+"), "").take(MAX_TIP_ADDRESS_CHARS)

    /** Sin vacíos, sin duplicados (ignorando mayúsculas), una línea cada uno y con tope. */
    fun sanitizeInterests(values: List<String>): List<String> =
        values.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim().take(MAX_INTEREST_CHARS) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_INTERESTS)
            .toList()

    /**
     * Franja de edad. [MIN_AGE] es el suelo duro de la app (18+), y un rango invertido se
     * corrige en vez de rechazarse — llega de dos sliders, y cruzarlos es un accidente normal
     * de manejo, no un intento de saltarse nada.
     */
    fun sanitizeAgeRange(min: Int, max: Int): Pair<Int, Int> {
        val lo = min.coerceIn(MIN_AGE, MAX_AGE)
        val hi = max.coerceIn(MIN_AGE, MAX_AGE)
        return if (lo <= hi) lo to hi else hi to lo
    }
}

/**
 * Instantánea del perfil. [hasAvatar] en vez de los bytes: el `StateFlow` lo observa la UI en
 * cada recomposición, y arrastrar ~58 KiB ahí no aporta nada. Los bytes se piden con
 * [MyProfilePrefs.avatarBytes] cuando de verdad hacen falta.
 */
data class MyProfile(
    val nickname: String = "",
    val ageMin: Int = MyProfilePrefs.MIN_AGE,
    val ageMax: Int = MyProfilePrefs.MAX_AGE,
    val interests: List<String> = emptyList(),
    val bio: String = "",
    val tipAddress: String = "",
    val hasAvatar: Boolean = false,
)
