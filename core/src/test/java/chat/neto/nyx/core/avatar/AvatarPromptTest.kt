package chat.neto.nyx.core.avatar

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El filtro de sólo adultos (RF-09) del kit de AvatarFace.
 *
 * No es una comprobación cosmética: la tarjeta del tablón es la superficie que Play regula por
 * los Child Safety Standards, y el avatar se publica ahí. La Fase 0 del plan endureció el
 * criterio a que generar un rostro que parezca de menor sea *estructuralmente improbable*, no
 * *estadísticamente raro*. El kit venía sin tests en Kotlin — sólo con la implementación de
 * Python y una captura del rechazo en el móvil — así que esto cubre el lado que se compila
 * dentro de la app.
 */
class AvatarPromptTest {

    private fun rechaza(texto: String) = AvatarPrompt.validate(texto) is AvatarPrompt.Result.Invalid
    private fun acepta(texto: String) = AvatarPrompt.validate(texto) is AvatarPrompt.Result.Valid

    @Test
    fun `rechaza terminos de menor en espanol y en ingles`() {
        listOf(
            "una nina sonriente", "un niño con gafas", "retrato de un bebe",
            "adolescente con pelo rizado", "a smiling kid", "teenage girl with braids",
            "toddler with freckles", "underage person",
        ).forEach { assertTrue("debería rechazar: $it", rechaza(it)) }
    }

    /**
     * El filtro normaliza a ASCII sin diacríticos antes de comparar. Si no lo hiciera, escribir
     * "niña" con tilde esquivaría la lista entera, que está en ASCII — y esa es justo la forma
     * natural de escribirlo en español.
     */
    @Test
    fun `los diacriticos no esquivan el filtro`() {
        assertTrue(rechaza("niña"))
        assertTrue(rechaza("NIÑA"))
        assertTrue(rechaza("Niño pequeño"))
    }

    @Test
    fun `rechaza edades explicitas menores de 18`() {
        listOf("16 años", "17 y/o", "a 15 year old", "12 yrs", "person of 17 years")
            .forEach { assertTrue("debería rechazar: $it", rechaza(it)) }
    }

    @Test
    fun `acepta 18 y por encima`() {
        listOf("18 años", "25 y/o", "a 30 year old with a beard", "40 years")
            .forEach { assertTrue("debería aceptar: $it", acepta(it)) }
    }

    /**
     * La lista compara **palabras completas**, no subcadenas. Si comparara subcadenas, "kidney"
     * llevaría dentro "kid" y "minorca" llevaría "minor": el filtro rechazaría descripciones
     * legítimas y el usuario no entendería por qué.
     */
    @Test
    fun `no rechaza palabras que solo contienen un termino prohibido`() {
        assertTrue(acepta("adult with kidney-shaped glasses"))
        assertTrue(acepta("mujer adulta de Minorca"))
    }

    @Test
    fun `acepta descripciones normales de personas adultas`() {
        listOf(
            "smiling adult with curly pink hair and round glasses",
            "hombre adulto con barba y gafas cuadradas",
            "confident woman with an afro and hoop earrings",
        ).forEach { assertTrue("debería aceptar: $it", acepta(it)) }
    }

    @Test
    fun `rechaza texto vacio y texto demasiado largo`() {
        assertTrue(rechaza(""))
        assertTrue(rechaza("   "))
        assertTrue(rechaza("a".repeat(501)))
        assertTrue(acepta("a".repeat(500)))
    }
}
