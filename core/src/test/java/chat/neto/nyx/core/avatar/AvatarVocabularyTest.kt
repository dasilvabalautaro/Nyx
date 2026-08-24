package chat.neto.nyx.core.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La garantía que sostiene la ayuda del avatar: **todo lo que se le ofrece al usuario funciona**.
 *
 * Una sugerencia que el parser ignore es peor que no ofrecerla — se toca, no pasa nada, y la
 * conclusión razonable es que la función está rota. Como el parser sólo entiende inglés y exige
 * contexto (`brown` suelto no vale, hace falta `brown hair`), es fácil escribir una entrada mal
 * y no enterarse: el fallo es **silencioso**. Por eso se recorre la tabla entera.
 */
class AvatarVocabularyTest {

    private val defaults = AvatarAttributes()

    /**
     * Los términos que **coinciden con el valor de fábrica**: parsearlos no cambia nada porque
     * el atributo ya valía eso. Van declarados uno a uno, con el atributo que fijan, y no como
     * una lista de nombres a secas: así "no cambia nada" queda distinguido de "el parser no lo
     * entendió", que es justo la confusión que este archivo existe para evitar.
     *
     * La primera versión de esta lista estaba **adivinada** y le faltaban cuatro; los encontró
     * el test de abajo al fallar.
     */
    private val coincidenConElDefecto: Map<String, (AvatarAttributes) -> String> = mapOf(
        "calm" to { it.expression },
        "oval face" to { it.faceShape },
        "light skin" to { it.skinTone },
        "short hair" to { it.hairStyle },
        "brown hair" to { it.hairColor },
        "brown eyes" to { it.eyeColor },
        "almond eyes" to { it.eyeShape },
        "sky background" to { it.background },
        "crew neck" to { it.clothing },
    )

    @Test
    fun `todo termino ofrecido lo entiende el parser`() {
        val ignorados = AvatarVocabulary.allOptions
            .filterNot { it.term in coincidenConElDefecto }
            .filter { AttributeParser.parse(it.term) == defaults }

        assertTrue(
            "el parser ignora estos términos, así que tocarlos no haría nada: " +
                ignorados.map { "${it.label} → '${it.term}'" },
            ignorados.isEmpty(),
        )
    }

    /**
     * Y los excluidos lo están **por la razón correcta**: el parser sí los lee, y lo que leen
     * resulta ser el valor de fábrica. Sin esta comprobación, la lista de exclusiones sería una
     * puerta trasera para colar términos rotos.
     */
    @Test
    fun `los excluidos coinciden con el defecto, no es que se ignoren`() {
        coincidenConElDefecto.forEach { (term, leer) ->
            assertEquals(
                "'$term' debería fijar el mismo valor que el de fábrica",
                leer(defaults),
                leer(AttributeParser.parse(term)),
            )
            assertTrue(
                "'$term' está en la lista de exclusiones pero sí cambia algo",
                AttributeParser.parse(term) == defaults,
            )
        }
    }

    /** Ningún grupo vacío ni etiqueta repetida dentro de un grupo: sería UI confusa. */
    @Test
    fun `los grupos estan bien formados`() {
        AvatarVocabulary.groups.forEach { g ->
            assertTrue("grupo vacío: ${g.title}", g.options.isNotEmpty())
            assertEquals(
                "etiquetas repetidas en ${g.title}",
                g.options.size,
                g.options.map { it.label }.distinct().size,
            )
        }
    }

    /**
     * Un par de rasgos concretos, para que el test no se limite a "cambió algo": comprueba que
     * cambió **lo que decía la etiqueta**. Si "Rizado" acabara poniendo gafas, el test de arriba
     * pasaría igual.
     */
    @Test
    fun `las etiquetas producen el rasgo que prometen`() {
        assertEquals("curly", AttributeParser.parse("curly hair").hairStyle)
        assertEquals("pink", AttributeParser.parse("pink hair").hairColor)
        assertEquals("deep", AttributeParser.parse("deep skin").skinTone)
        assertEquals("full beard", AttributeParser.parse("full beard").facialHair)
        assertEquals("round", AttributeParser.parse("round glasses").effectiveGlasses)
        assertEquals("hoodie", AttributeParser.parse("hoodie").clothing)
        assertEquals("mint", AttributeParser.parse("mint background").background)
    }

    // --- Composición del texto ----------------------------------------------------------

    @Test
    fun `anadir compone una lista separada por comas`() {
        var texto = ""
        texto = AvatarVocabulary.append(texto, "curly hair")
        texto = AvatarVocabulary.append(texto, "round glasses")
        assertEquals("curly hair, round glasses", texto)
    }

    /** Tocar dos veces la misma opción no la duplica: el parser leería lo mismo dos veces. */
    @Test
    fun `anadir dos veces el mismo termino no lo repite`() {
        val una = AvatarVocabulary.append("", "curly hair")
        assertEquals(una, AvatarVocabulary.append(una, "curly hair"))
        assertEquals(una, AvatarVocabulary.append(una, "CURLY HAIR"))
    }

    /** Y el resultado de componer varias tiene que seguir siendo parseable entero. */
    @Test
    fun `una frase compuesta produce todos sus rasgos a la vez`() {
        var texto = ""
        listOf("curly hair", "pink hair", "deep skin", "round glasses", "mint background")
            .forEach { texto = AvatarVocabulary.append(texto, it) }

        val a = AttributeParser.parse(texto)
        assertEquals("curly", a.hairStyle)
        assertEquals("pink", a.hairColor)
        assertEquals("deep", a.skinTone)
        assertEquals("round", a.effectiveGlasses)
        assertEquals("mint", a.background)
        assertNotEquals(defaults, a)
    }
}
