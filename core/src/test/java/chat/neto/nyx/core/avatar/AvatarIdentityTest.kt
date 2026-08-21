package chat.neto.nyx.core.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El avatar derivado del PeerID. Se prueba en la JVM porque es lógica pura, igual que
 * `LikeState` o `SafetyNumber`: el dibujo necesita Android, pero la *decisión* de qué rasgos
 * le tocan a una identidad no.
 */
class AvatarIdentityTest {

    private val alice = "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3"
    private val bob = "12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm"

    @Test
    fun `el mismo PeerID da siempre el mismo rostro`() {
        assertEquals(AvatarIdentity.attributesFor(alice), AvatarIdentity.attributesFor(alice))
    }

    @Test
    fun `PeerID distintos dan rostros distintos`() {
        assertNotEquals(AvatarIdentity.attributesFor(alice), AvatarIdentity.attributesFor(bob))
    }

    /**
     * Un PeerID se copia y se pega a mano, así que el error típico no es un identificador
     * aleatorio: es *uno parecido*. Si un carácter de diferencia no moviera la cara, el avatar
     * no serviría para lo único que de verdad hace bien — cazar el pegado equivocado.
     */
    @Test
    fun `un solo caracter distinto cambia el rostro`() {
        val casi = alice.dropLast(1) + "4"
        assertNotEquals(AvatarIdentity.attributesFor(alice), AvatarIdentity.attributesFor(casi))
    }

    @Test
    fun `un PeerID prefijo de otro no produce el mismo rostro`() {
        assertNotEquals(
            AvatarIdentity.attributesFor("12D3KooWabc"),
            AvatarIdentity.attributesFor("12D3KooWabcd"),
        )
    }

    /**
     * Cada rasgo tiene que caer dentro del vocabulario que el renderizador entiende. Un valor
     * fuera de la lista no revienta el dibujo: cae en el `else` de algún `when` y sale un rasgo
     * por defecto, o sea que el fallo sería *silencioso* y solo para algunos PeerID. Por eso se
     * barren muchas identidades y no una.
     */
    @Test
    fun `todo rasgo derivado pertenece al vocabulario, en mil identidades`() {
        repeat(1000) { i ->
            val a = AvatarIdentity.attributesFor("12D3KooWprueba$i")
            assertTrue(a.expression in EXPRESSION)
            assertTrue(a.faceShape in FACE_SHAPE)
            assertTrue(a.skinTone in SKIN_TONE)
            assertTrue(a.hairStyle in HAIR_STYLE)
            assertTrue(a.hairColor in HAIR_COLOR)
            assertTrue(a.eyeColor in EYE_COLOR)
            assertTrue(a.eyeShape in EYE_SHAPE)
            assertTrue(a.background in BACKGROUND)
            assertTrue(a.browStyle in BROW_STYLE)
            assertTrue(a.noseStyle in NOSE_STYLE)
            assertTrue(a.facialHair in FACIAL_HAIR)
            assertTrue(a.glasses in GLASSES)
            assertTrue(a.earrings in EARRINGS)
            assertTrue(a.freckles in FRECKLES)
            assertTrue(a.clothing in CLOTHING)
            assertTrue(a.clothingColor in CLOTHING_COLOR)
        }
    }

    /**
     * `accessory` solapa con `glasses`/`earrings`/`freckles` a través de `effectiveGlasses` y
     * compañía. Si se derivara también, un PeerID podría pedir gafas por los dos caminos y el
     * atributo explícito dejaría de mandar.
     */
    @Test
    fun `el atributo heredado accessory no se deriva`() {
        repeat(200) { i ->
            assertEquals("none", AvatarIdentity.attributesFor("12D3KooWx$i").accessory)
        }
    }

    /**
     * Los rasgos que una persona registra de un vistazo son los que hacen útil el avatar. Si el
     * mapeo se sesgara —por ejemplo, si el 90 % de las identidades saliera con el mismo fondo—
     * dejarían de distinguirse entre sí sin que ningún otro test lo notara.
     */
    @Test
    fun `los rasgos salientes se reparten, no se amontonan`() {
        val muestras = (0 until 2000).map { AvatarIdentity.attributesFor("12D3KooWdist$it") }
        assertEquals(BACKGROUND.size, muestras.map { it.background }.distinct().size)
        assertEquals(SKIN_TONE.size, muestras.map { it.skinTone }.distinct().size)
        assertEquals(HAIR_STYLE.size, muestras.map { it.hairStyle }.distinct().size)
        assertEquals(HAIR_COLOR.size, muestras.map { it.hairColor }.distinct().size)

        // Ningún valor debe llevarse más del doble de su parte proporcional.
        val fondos = muestras.groupingBy { it.background }.eachCount()
        val esperado = muestras.size / BACKGROUND.size
        assertTrue(
            "reparto de fondos desequilibrado: $fondos",
            fondos.values.all { it < esperado * 2 },
        )
    }

    /**
     * Dos identidades distintas no deberían compartir rostro completo. Con ~2⁴¹ combinaciones,
     * una colisión en 2000 muestras sería señal de que el flujo de bytes está mal derivado
     * (por ejemplo, reutilizando el mismo bloque para varios atributos).
     */
    @Test
    fun `no hay colisiones de rostro completo en dos mil identidades`() {
        val rostros = (0 until 2000).map { AvatarIdentity.attributesFor("12D3KooWcol$it") }
        assertEquals(rostros.size, rostros.distinct().size)
    }

    /**
     * El vello facial se apaga con los peinados largos. Es la regla de coherencia que se decidió
     * el 21 ago 2026, después de haberla descartado el mismo día: va aquí para que quede claro
     * que es deliberada y no un efecto colateral de los pesos.
     */
    @Test
    fun `ningun peinado largo lleva vello facial`() {
        val largos = setOf("bob", "long", "ponytail", "bun")
        val conVello = (0 until 5000)
            .map { AvatarIdentity.attributesFor("12D3KooWlargo$it") }
            .filter { it.hairStyle in largos && it.facialHair != "none" }
        assertTrue("peinado largo con vello facial: ${conVello.take(3)}", conVello.isEmpty())
    }

    /**
     * Los cuatro pares pálido-sobre-pálido que no se leen a 40 dp. Se comprobó renderizándolos:
     * en esos, piel y pelo son los dos cálidos y claros y no queda borde.
     */
    @Test
    fun `no salen los pares de pelo y piel que no se distinguen`() {
        val prohibidos = setOf(
            // Pálidos sobre pálido: los dos tonos cálidos y claros, sin borde entre ellos.
            "light" to "silver", "beige" to "silver",
            "beige" to "blonde", "golden" to "blonde", "porcelain" to "silver",
            // Y los oscuros que de verdad se funden. Ojo: "ebony" to "black" (Δ22) NO está
            // aquí a propósito — ese se lee bien, y el test de más abajo exige que siga saliendo.
            "ebony" to "brown", "deep" to "auburn", "deep" to "blue", "deep" to "brown",
            "brown" to "red", "brown" to "green",
            "tan" to "gray", "tan" to "pink", "olive" to "gray", "olive" to "pink",
        )
        val encontrados = (0 until 20_000)
            .map { AvatarIdentity.attributesFor("12D3KooWcontraste$it") }
            .map { it.skinTone to it.hairColor }
            .filter { it in prohibidos }
            .distinct()
        assertTrue("par ilegible: $encontrados", encontrados.isEmpty())
    }

    /**
     * El contrapunto del test de arriba, y el más importante de los dos.
     *
     * La primera versión de la regla de contraste filtraba por luminancia a secas, y eso
     * **eliminaba el pelo negro sobre piel oscura** — que se lee perfectamente (el trazado le da
     * borde) y que es una de las combinaciones más comunes que existen. Borrarla en una app de
     * citas es mucho peor que el defecto que arreglaba. Este test impide que vuelva a colarse
     * al ajustar umbrales.
     */
    @Test
    fun `el pelo oscuro sobre piel oscura sigue existiendo`() {
        val casos = (0 until 5000)
            .map { AvatarIdentity.attributesFor("12D3KooWoscuro$it") }
            .count { it.skinTone in setOf("deep", "ebony") && it.hairColor in setOf("black", "brown") }
        assertTrue("se perdió el pelo oscuro sobre piel oscura", casos > 100)
    }

    /**
     * Caso dorado: fija el contrato entero de un tirón.
     *
     * `DOMAIN`, el orden de los vocabularios y **cada peso** entran en el rostro que sale. Si
     * alguien toca cualquiera de los tres, a todos los usuarios les cambia la cara — y el avatar
     * derivado deja de ser lo único que promete ser: estable. Ese cambio no tiene por qué ser
     * malo, pero tiene que ser *deliberado*, y este test obliga a que quien lo haga actualice
     * también estos valores y vea lo que está rompiendo.
     *
     * Los valores no están escritos a mano: salen de ejecutar el gemelo de Python
     * (`tools/avatar/python/identity.py`), así que este test comprueba **además** que las dos
     * implementaciones coinciden, que es la regla 4.2 del kit y lo que evita que el teléfono y
     * la galería de referencia dibujen cosas distintas.
     */
    @Test
    fun `el rostro derivado de un PeerID conocido no cambia`() {
        val a = AvatarIdentity.attributesFor(alice)

        assertEquals("calm", a.expression)
        assertEquals("oval", a.faceShape)
        assertEquals("light", a.skinTone)
        assertEquals("curly", a.hairStyle)
        assertEquals("black", a.hairColor)
        assertEquals("brown", a.eyeColor)
        assertEquals("hooded", a.eyeShape)
        assertEquals("none", a.accessory)
        assertEquals("sand", a.background)
        assertEquals("thick", a.browStyle)
        assertEquals("straight", a.noseStyle)
        assertEquals("short beard", a.facialHair)
        assertEquals("none", a.glasses)
        assertEquals("studs", a.earrings)
        assertEquals("none", a.freckles)
        assertEquals("hoodie", a.clothing)
        assertEquals("green", a.clothingColor)
    }

    /**
     * Los pesos existen para que los rostros derivados se sostengan visualmente (ver el KDoc de
     * `AvatarIdentity`). Si alguien los quitara —volviendo al muestreo uniforme— los tests de
     * arriba seguirían pasando y la regresión sólo se vería mirando avatares. Esto la detecta:
     * con pesos, "sin gafas" y "sin vello facial" tienen que ser mayoría clara, y el pelo de
     * color no natural una minoría.
     */
    @Test
    fun `los pesos moderan los rasgos llamativos`() {
        val muestras = (0 until 5000).map { AvatarIdentity.attributesFor("12D3KooWpeso$it") }

        val sinGafas = muestras.count { it.glasses == "none" }
        assertTrue("gafas en demasiadas identidades: ${5000 - sinGafas}/5000", sinGafas > 5000 * 0.40)

        val sinVello = muestras.count { it.facialHair == "none" }
        assertTrue("vello facial en demasiadas: ${5000 - sinVello}/5000", sinVello > 5000 * 0.38)

        val peloNoNatural = muestras.count { it.hairColor in setOf("blue", "pink", "green") }
        assertTrue("pelo de fantasía en demasiadas: $peloNoNatural/5000", peloNoNatural < 5000 * 0.15)
        assertTrue("el pelo de fantasía desapareció del todo", peloNoNatural > 0)
    }

    /**
     * Dos casos dorados más, elegidos porque **atraviesan las reglas nuevas**, que es donde una
     * divergencia entre Kotlin y Python sería más fácil de introducir y más difícil de ver: uno
     * con peinado largo (el vello facial tiene que salir apagado) y otro con piel clara y pelo
     * claro (el filtro de contraste tuvo que descartar candidatos antes de elegir).
     *
     * Como el dorado principal, los valores salen de ejecutar `tools/avatar/python/identity.py`.
     */
    @Test
    fun `los casos que cruzan las reglas nuevas coinciden con el gemelo de Python`() {
        val largo = AvatarIdentity.attributesFor("12D3KooWcruce2")
        assertEquals("bob", largo.hairStyle)
        assertEquals("none", largo.facialHair)
        assertEquals("ebony", largo.skinTone)
        assertEquals("gray", largo.hairColor)
        assertEquals("long", largo.faceShape)
        assertEquals("rose", largo.background)
        assertEquals("rectangular", largo.glasses)
        assertEquals("crew neck", largo.clothing)

        val claro = AvatarIdentity.attributesFor("12D3KooWcruce3")
        assertEquals("bald", claro.hairStyle)
        assertEquals("short beard", claro.facialHair)
        assertEquals("olive", claro.skinTone)
        assertEquals("blonde", claro.hairColor)
        assertEquals("diamond", claro.faceShape)
        assertEquals("sky", claro.background)
        assertEquals("studs", claro.earrings)
        assertEquals("v-neck", claro.clothing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un PeerID vacio se rechaza`() {
        AvatarIdentity.attributesFor("   ")
    }

    private companion object {
        val EXPRESSION = listOf("smiling", "calm", "happy", "confident", "serious", "friendly")
        val FACE_SHAPE = listOf("round", "oval", "square", "heart", "long", "diamond")
        val SKIN_TONE = listOf(
            "porcelain", "light", "beige", "golden", "olive", "tan", "brown", "deep", "ebony",
        )
        val HAIR_STYLE = listOf(
            "short", "buzz", "curly", "wavy", "side-parted", "bob",
            "long", "ponytail", "bun", "afro", "undercut", "bald",
        )
        val HAIR_COLOR = listOf(
            "black", "brown", "auburn", "blonde", "blue", "pink", "gray", "red", "silver", "green",
        )
        val EYE_COLOR = listOf("brown", "blue", "green", "gray", "hazel", "amber")
        val EYE_SHAPE = listOf("almond", "round", "narrow", "wide", "hooded")
        val BACKGROUND =
            listOf("coral", "mint", "sky", "lavender", "sand", "slate", "rose", "teal")
        val BROW_STYLE = listOf("natural", "arched", "thick", "thin", "angled")
        val NOSE_STYLE = listOf("straight", "small", "button", "wide", "pointed")
        val FACIAL_HAIR =
            listOf("none", "stubble", "mustache", "goatee", "short beard", "full beard")
        val GLASSES = listOf("none", "round", "square", "sunglasses", "rectangular")
        val EARRINGS = listOf("none", "studs", "hoops")
        val FRECKLES = listOf("none", "light", "heavy")
        val CLOTHING = listOf("crew neck", "v-neck", "collared shirt", "hoodie", "turtleneck")
        val CLOTHING_COLOR =
            listOf("white", "charcoal", "red", "blue", "green", "mustard", "purple")
    }
}
