package chat.neto.nyx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las decisiones puras de la puerta de edad y de los Términos. Se prueban en la JVM, igual que
 * `AppLock.shouldRelock` y `ThemePreference.resolveDark`: son las condiciones que deciden si
 * alguien entra en una app 18+ y si publica contenido, y un cambio en ellas tiene que romper un
 * test en vez de colarse en una revisión de UI.
 */
class AgeGateTest {

    @Test
    fun `sin confirmar la edad se pregunta, despues no`() {
        assertTrue(AgeGate.shouldAskAge(confirmed = false))
        assertFalse(AgeGate.shouldAskAge(confirmed = true))
    }

    @Test
    fun `quien nunca aceptó los terminos tiene que aceptarlos`() {
        assertTrue(AgeGate.shouldAskTerms(acceptedVersion = 0, currentVersion = 1))
        assertFalse(AgeGate.shouldAskTerms(acceptedVersion = 1, currentVersion = 1))
    }

    /**
     * Subir la versión vuelve a preguntar. Es lo que impide que un cambio de fondo en las reglas
     * —en qué se modera, o en qué se publica— se le aplique a quien aceptó otra cosa.
     */
    @Test
    fun `subir la version de los terminos obliga a aceptar de nuevo`() {
        assertFalse(AgeGate.shouldAskTerms(acceptedVersion = 1, currentVersion = 1))
        assertTrue(AgeGate.shouldAskTerms(acceptedVersion = 1, currentVersion = 2))
    }

    /**
     * `>=` y no `==`: si alguien rebajara [AgeGate.TERMS_VERSION] por error, no debe invalidar
     * las aceptaciones buenas de todo el parque instalado y volver a molestar a todo el mundo.
     */
    @Test
    fun `haber aceptado una version posterior sigue valiendo`() {
        assertFalse(AgeGate.shouldAskTerms(acceptedVersion = 3, currentVersion = 2))
        assertTrue(AgeGate.termsUpToDate(acceptedVersion = 3, currentVersion = 2))
    }

    /** La versión vigente arranca en 1: un 0 guardado significa "nunca aceptó". */
    @Test
    fun `la version vigente distingue de no haber aceptado nunca`() {
        assertTrue(AgeGate.TERMS_VERSION >= 1)
        assertTrue(AgeGate.shouldAskTerms(acceptedVersion = 0, currentVersion = AgeGate.TERMS_VERSION))
    }
}
