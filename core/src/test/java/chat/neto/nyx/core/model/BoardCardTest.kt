package chat.neto.nyx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El formato de la tarjeta del tablón.
 *
 * Se prueba con saña porque estos bytes llegan **de un desconocido a través de un nodo**: es la
 * única entrada de la app que no viene ni de un contacto verificado ni de nuestro propio disco.
 * Todo lo que aquí se acepte mal acaba dibujándose en la pantalla de descubrimiento.
 */
class BoardCardTest {

    private val completa = BoardCard(
        nickname = "Ana",
        ageMin = 28,
        ageMax = 34,
        interests = listOf("senderismo", "cine"),
        bio = "Me gusta el mar",
        tipAddress = "bc1qejemplo",
        avatar = ByteArray(120) { (it % 251).toByte() },
    )

    @Test
    fun `ida y vuelta conserva todos los campos`() {
        assertEquals(completa, BoardCard.decode(BoardCard.encode(completa)))
    }

    @Test
    fun `una tarjeta minima tambien viaja`() {
        val minima = BoardCard(nickname = "B", ageMin = 18, ageMax = 99)
        assertEquals(minima, BoardCard.decode(BoardCard.encode(minima)))
    }

    /**
     * El avatar son bytes crudos, no base64: hay que asegurarse de que sobrevive **exacto**,
     * incluidos los que coinciden con el separador de líneas. Un `\n` dentro del avatar
     * partiría cualquier formato posicional, y por eso el avatar va con longitud declarada y al
     * final.
     */
    @Test
    fun `el avatar sobrevive intacto aunque contenga saltos de linea`() {
        val conSaltos = completa.copy(avatar = byteArrayOf(1, 10, 2, 10, 10, 3, 0, -1))
        val vuelta = BoardCard.decode(BoardCard.encode(conSaltos))!!
        assertTrue(conSaltos.avatar.contentEquals(vuelta.avatar))
    }

    /** Lo mismo para la bio: es texto libre del usuario y puede llevar saltos. */
    @Test
    fun `una bio con saltos de linea no rompe el formato`() {
        val conSaltos = completa.copy(bio = "primera línea\nsegunda\n\ntercera")
        assertEquals("primera línea\nsegunda\n\ntercera", BoardCard.decode(BoardCard.encode(conSaltos))?.bio)
    }

    /**
     * El apodo sí va en una línea de la cabecera, así que un salto ahí desplazaría todos los
     * campos siguientes. Se sanea al serializar **además** de donde se guarda: no puede depender
     * de que otra capa se acordara, porque el emisor podría no ser nuestro cliente.
     */
    @Test
    fun `un apodo con saltos de linea no desplaza los campos`() {
        val sucio = completa.copy(nickname = "Ana\nFalsa\n99")
        val vuelta = BoardCard.decode(BoardCard.encode(sucio))!!

        assertEquals("Ana Falsa 99", vuelta.nickname)
        assertEquals(28, vuelta.ageMin) // no lo pisó el "99" inyectado
        assertEquals("Me gusta el mar", vuelta.bio)
    }

    @Test
    fun `un interes con tabulador no inventa intereses nuevos`() {
        val sucio = completa.copy(interests = listOf("cine\tteatro", "mar"))
        assertEquals(listOf("cine teatro", "mar"), BoardCard.decode(BoardCard.encode(sucio))?.interests)
    }

    // --- Entradas hostiles ---------------------------------------------------------------

    @Test
    fun `basura, vacio y version desconocida se descartan sin lanzar`() {
        assertNull(BoardCard.decode(ByteArray(0)))
        assertNull(BoardCard.decode("no soy una tarjeta".toByteArray()))
        assertNull(BoardCard.decode("P9\nAna\n28\n34\n\n\n0\n0\n".toByteArray()))
    }

    /**
     * El caso que de verdad importa: longitudes que no cuadran con los bytes que hay. Sin la
     * comprobación, una tarjeta manipulada provocaría una lectura fuera de rango — desde un
     * desconocido, en el hilo que procesa la consulta.
     */
    @Test
    fun `longitudes mentirosas se rechazan en vez de leer fuera de rango`() {
        val mentira = "P1\nAna\n28\n34\n\n\n9999\n0\n".toByteArray()
        assertNull(BoardCard.decode(mentira))

        val avatarLargo = "P1\nAna\n28\n34\n\n\n0\n5000\nabc".toByteArray()
        assertNull(BoardCard.decode(avatarLargo))

        val negativa = "P1\nAna\n28\n34\n\n\n-1\n0\n".toByteArray()
        assertNull(BoardCard.decode(negativa))
    }

    @Test
    fun `una cabecera truncada se descarta`() {
        val entera = BoardCard.encode(completa)
        for (corte in listOf(2, 5, 12, 20)) {
            assertNull("truncada en $corte debería descartarse", BoardCard.decode(entera.copyOf(corte)))
        }
    }

    @Test
    fun `edades no numericas se descartan`() {
        assertNull(BoardCard.decode("P1\nAna\nveintiocho\n34\n\n\n0\n0\n".toByteArray()))
    }

    /** Con el avatar al máximo, la tarjeta tiene que seguir cabiendo en el tope del nodo. */
    @Test
    fun `una tarjeta con el avatar maximo cabe en el tope del nodo`() {
        val grande = completa.copy(
            avatar = ByteArray(58 * 1024),
            bio = "x".repeat(300),
            interests = List(10) { "interes$it" },
            nickname = "n".repeat(32),
        )
        assertTrue(
            "la tarjeta llena se pasa del tope: ${BoardCard.encode(grande).size}",
            BoardCard.encode(grande).size <= BoardCard.MAX_BYTES,
        )
    }
}
