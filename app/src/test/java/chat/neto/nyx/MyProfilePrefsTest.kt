package chat.neto.nyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saneado del perfil. Se prueba aparte de `SharedPreferences` porque es la parte que importa:
 * estos campos acaban en una tarjeta pública del tablón, así que el tope y la forma se
 * aplican **al guardar**, no al publicar.
 */
class MyProfilePrefsTest {

    @Test
    fun `el apodo queda en una sola linea y con tope`() {
        assertEquals("Ana Maria", MyProfilePrefs.sanitizeNickname("  Ana\n\tMaria  "))
        assertEquals(
            MyProfilePrefs.MAX_NICKNAME_CHARS,
            MyProfilePrefs.sanitizeNickname("x".repeat(200)).length,
        )
    }

    @Test
    fun `la bio conserva parrafos pero no ristras de lineas en blanco`() {
        assertEquals("uno\n\ndos", MyProfilePrefs.sanitizeBio("\n uno\n\n\n\n\ndos \n"))
        assertTrue(MyProfilePrefs.sanitizeBio("y".repeat(999)).length <= MyProfilePrefs.MAX_BIO_CHARS)
    }

    @Test
    fun `los intereses se limpian, se deduplican y se topan`() {
        val out = MyProfilePrefs.sanitizeInterests(
            listOf(" cine ", "CINE", "", "  ", "senderismo", "cine"),
        )
        assertEquals(listOf("cine", "senderismo"), out)

        val muchos = MyProfilePrefs.sanitizeInterests((1..50).map { "interes$it" })
        assertEquals(MyProfilePrefs.MAX_INTERESTS, muchos.size)
    }

    /**
     * Los intereses se persisten separados por saltos de línea, así que ninguno puede contener
     * uno: si se colara, al releer la preferencia un interés se partiría en dos.
     */
    @Test
    fun `ningun interes saneado contiene el separador de persistencia`() {
        val out = MyProfilePrefs.sanitizeInterests(listOf("mú\nsica", "cine\n\nclásico"))
        assertTrue("un salto de línea rompería el round-trip de prefs", out.none { it.contains("\n") })
    }

    /** Nyx es 18+: la franja no puede empezar por debajo, venga de donde venga el valor. */
    @Test
    fun `la franja de edad nunca baja de 18`() {
        assertEquals(18 to 30, MyProfilePrefs.sanitizeAgeRange(13, 30))
        assertEquals(18 to 18, MyProfilePrefs.sanitizeAgeRange(0, 0))
        assertEquals(18 to 99, MyProfilePrefs.sanitizeAgeRange(-5, 500))
    }

    /** Dos sliders cruzados son un accidente de manejo: se corrige, no se rechaza. */
    @Test
    fun `una franja invertida se endereza`() {
        assertEquals(25 to 40, MyProfilePrefs.sanitizeAgeRange(40, 25))
    }

    @Test
    fun `la direccion de propina pierde los espacios`() {
        assertEquals("bc1qabc", MyProfilePrefs.sanitizeTipAddress(" bc1q abc\n"))
    }

    @Test
    fun `sanitize aplica todo a la vez y es idempotente`() {
        val sucio = MyProfile(
            nickname = "  Ana\nM  ",
            ageMin = 90,
            ageMax = 20,
            interests = listOf("cine", "CINE", " "),
            bio = "hola\n\n\n\nadiós",
            tipAddress = "bc1 q",
        )
        val limpio = MyProfilePrefs.sanitize(sucio)

        assertEquals("Ana M", limpio.nickname)
        assertEquals(20, limpio.ageMin)
        assertEquals(90, limpio.ageMax)
        assertEquals(listOf("cine"), limpio.interests)
        assertEquals("hola\n\nadiós", limpio.bio)
        assertEquals("bc1q", limpio.tipAddress)
        assertEquals("volver a sanear no debe cambiar nada", limpio, MyProfilePrefs.sanitize(limpio))
    }
}
