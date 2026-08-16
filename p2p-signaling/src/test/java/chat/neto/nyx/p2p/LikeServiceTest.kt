package chat.neto.nyx.p2p

import chat.neto.nyx.core.KeyExchange
import chat.neto.nyx.core.model.LikeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LikeService` es el único punto de la app que acepta tráfico de peers **desconocidos**, así
 * que aquí se prueban las dos cosas que eso obliga a sostener: que la regla anti-acoso no se
 * puede saltar, y que la superficie de CPU que abre está acotada.
 */
class LikeServiceTest {

    private val cipher = AesGcmMessageCipher()

    /** Cuenta cuántas veces se deriva un secreto: es la operación cara (X25519). */
    private class CountingKeyExchange(private val self: String = "12D3KooWSelf") : KeyExchange {
        var derivations = 0
        override fun localPeerId() = self
        override fun sharedSecretWith(peerId: String): ByteArray {
            derivations++
            // Simétrico como el ECDH real: A↔B derivan lo mismo mirando al otro.
            return ByteArray(32) { (peerId.hashCode() + self.hashCode()).toByte() }
        }
    }

    /** Señalización que solo captura los likes enviados. */
    private class LikeSignaling(peerId: String = "12D3KooWSelf") : FakeSignalingBase(peerId) {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        var failOnSend = false
        override suspend fun sendLike(toPeerId: String, ciphertext: ByteArray) {
            if (failOnSend) error("nodo inalcanzable")
            sent += toPeerId to ciphertext
        }
    }

    private fun service(
        signaling: LikeSignaling = LikeSignaling(),
        keys: CountingKeyExchange = CountingKeyExchange(),
        likes: FakeLikes = FakeLikes(),
        blocks: FakeBlocks = FakeBlocks(),
    ) = LikeService(signaling, cipher, keys, likes, blocks)

    /** Sobre `L` cifrado tal y como lo mandaría el otro lado. */
    private fun likeEnvelope(keys: CountingKeyExchange, fromPeerId: String, ts: Long = 1000L) =
        cipher.encrypt(keys.sharedSecretWith(fromPeerId), MessageEnvelope.encodeLike(ts))

    @Test
    fun `enviar un like lo persiste y lo deposita cifrado`() = runTest {
        val signaling = LikeSignaling()
        val likes = FakeLikes()
        val svc = service(signaling = signaling, likes = likes)

        val state = svc.sendLike("12D3KooWOtro")

        assertNotNull("debe quedar registrado que yo lo di", state.sentAt)
        assertFalse("un like solo enviado no es match", state.isMatch)
        assertEquals(1, signaling.sent.size)
        assertEquals("12D3KooWOtro", signaling.sent[0].first)
        assertFalse(
            "el like debe viajar cifrado",
            signaling.sent[0].second.contentEquals(MessageEnvelope.encodeLike(0)),
        )
    }

    /**
     * La regla que sostiene todo el anti-acoso: recibir un like **no** desbloquea nada. Se
     * prueba aquí además de en `LikeStateTest` porque este es el camino por el que entra de
     * verdad, desde un peer que no es contacto.
     */
    @Test
    fun `un like recibido de un desconocido no habilita mensajeria`() = runTest {
        val keys = CountingKeyExchange()
        val likes = FakeLikes()
        val svc = service(keys = keys, likes = likes)

        val acked = svc.onLikeReceived("12D3KooWDesconocido", likeEnvelope(keys, "12D3KooWDesconocido"), 5_000)

        assertTrue("debe persistirse y confirmarse", acked)
        val state = likes.find("12D3KooWDesconocido")!!
        assertNotNull(state.receivedAt)
        assertNull(state.sentAt)
        assertFalse("recibir un like NO puede abrir el chat", state.canMessage)
    }

    @Test
    fun `like mutuo cierra el match y lo emite`() = runTest {
        val keys = CountingKeyExchange()
        val likes = FakeLikes()
        val svc = service(keys = keys, likes = likes)
        val other = "12D3KooWOtro"

        svc.sendLike(other)
        val acked = svc.onLikeReceived(other, likeEnvelope(keys, other), 9_000)

        assertTrue(acked)
        val state = likes.find(other)!!
        assertTrue("con los dos sentidos debe haber match", state.isMatch)
        assertTrue(state.canMessage)
    }

    /**
     * Un sobre que no descifra es ruido —o alguien probando suerte— y **se confirma igual**:
     * reentregarlo no lo va a arreglar, y dejarlo sin ack lo convertiría en un bucle
     * envenenado que el nodo repite en cada fetch.
     */
    @Test
    fun `un sobre que no descifra se descarta pero se confirma`() = runTest {
        val likes = FakeLikes()
        val svc = service(likes = likes)

        val acked = svc.onLikeReceived("12D3KooWRuido", ByteArray(64) { 9 }, 1_000)

        assertTrue("hay que ack'earlo para que el nodo lo borre", acked)
        assertNull("no puede quedar estado de un sobre inválido", likes.find("12D3KooWRuido"))
    }

    /** Un peer bloqueado no existe: ni se descifra su like, ni se guarda, ni se le nota. */
    @Test
    fun `un like de un peer bloqueado se ignora`() = runTest {
        val keys = CountingKeyExchange()
        val likes = FakeLikes()
        val svc = service(keys = keys, likes = likes, blocks = FakeBlocks(setOf("12D3KooWAcosador")))

        val acked = svc.onLikeReceived("12D3KooWAcosador", likeEnvelope(keys, "12D3KooWAcosador"), 1_000)

        assertTrue(acked)
        assertNull(likes.find("12D3KooWAcosador"))
    }

    /** Y tampoco se le puede dar like a quien has bloqueado. */
    @Test
    fun `no se puede dar like a un peer bloqueado ni a uno mismo`() = runTest {
        val svc = service(blocks = FakeBlocks(setOf("12D3KooWAcosador")))

        assertTrue(
            runCatching { svc.sendLike("12D3KooWAcosador") }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { svc.sendLike("12D3KooWSelf") }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    /**
     * El rate-limit corta al insistente. Lo importante no es solo que se rechace, sino
     * **dónde**: los intentos por encima del tope no deben llegar a derivar el secreto, que es
     * el X25519 y la parte cara. Por eso se cuentan las derivaciones.
     */
    @Test
    fun `el rate-limit corta antes de gastar el X25519`() = runTest {
        val keys = CountingKeyExchange()
        val likes = FakeLikes()
        val svc = service(keys = keys, likes = likes)
        val spammer = "12D3KooWSpammer"
        val sobre = likeEnvelope(keys, spammer)
        val derivacionesDePreparacion = keys.derivations

        repeat(40) { i -> svc.onLikeReceived(spammer, sobre, 1_000L + i) }

        val gastadas = keys.derivations - derivacionesDePreparacion
        assertTrue(
            "solo debía derivarse mientras el rate-limit dejaba pasar (≤${LikeService.MAX_ATTEMPTS_PER_HOUR}), fueron $gastadas",
            gastadas <= LikeService.MAX_ATTEMPTS_PER_HOUR,
        )
        // Y el estado sigue siendo un único like recibido: insistir no multiplica nada.
        assertNotNull(likes.find(spammer))
    }

    /** El límite es por emisor: que uno abuse no puede dejar sin likes a los demás. */
    @Test
    fun `el rate-limit de un abusador no afecta a otros peers`() = runTest {
        val keys = CountingKeyExchange()
        val likes = FakeLikes()
        val svc = service(keys = keys, likes = likes)

        repeat(40) { i -> svc.onLikeReceived("12D3KooWSpammer", likeEnvelope(keys, "12D3KooWSpammer"), 1_000L + i) }
        svc.onLikeReceived("12D3KooWLegitimo", likeEnvelope(keys, "12D3KooWLegitimo"), 2_000)

        assertNotNull("el peer legítimo debe pasar igualmente", likes.find("12D3KooWLegitimo"))
    }

    /** El secreto se deriva una vez por peer: reaparecer (reentrega, like mutuo) sale gratis. */
    @Test
    fun `el secreto derivado se cachea por peer`() = runTest {
        val keys = CountingKeyExchange()
        val svc = service(keys = keys)
        val other = "12D3KooWOtro"
        val sobre = likeEnvelope(keys, other)
        val base = keys.derivations

        svc.sendLike(other)
        svc.onLikeReceived(other, sobre, 1_000)
        svc.onLikeReceived(other, sobre, 1_001)

        assertEquals("solo debía derivarse una vez para ese peer", 1, keys.derivations - base)
    }

    /**
     * Un fallo **transitorio** (no poder escribir en Room) es el único que debe devolver
     * `false`: así el nodo lo reentrega en la siguiente pasada en vez de darlo por entregado.
     */
    @Test
    fun `si la persistencia falla no se confirma, para que el nodo lo reentregue`() = runTest {
        val keys = CountingKeyExchange()
        val likes = FakeLikes().apply { failOnWrite = true }
        val svc = service(keys = keys, likes = likes)

        val acked = svc.onLikeReceived("12D3KooWOtro", likeEnvelope(keys, "12D3KooWOtro"), 1_000)

        assertFalse("sin persistir no se puede ack'ear", acked)
    }

    /** Si el nodo no acepta el depósito, el like queda registrado en local y el error se propaga. */
    @Test
    fun `un envio fallido deja el like local y propaga el error`() = runTest {
        val signaling = LikeSignaling().apply { failOnSend = true }
        val likes = FakeLikes()
        val svc = service(signaling = signaling, likes = likes)

        val failed = runCatching { svc.sendLike("12D3KooWOtro", LikeSource.MANUAL) }

        assertTrue("la UI tiene que poder ofrecer reintentar", failed.isFailure)
        assertNotNull("pero lo que el usuario hizo no se pierde", likes.find("12D3KooWOtro")?.sentAt)
    }
}

/** Ventana deslizante, probada aparte porque el reloj entra por parámetro. */
class RateLimiterTest {

    @Test
    fun `permite hasta el tope y luego corta`() {
        val limiter = RateLimiter(maxPerWindow = 3, windowMs = 1_000)
        assertTrue(limiter.allow("a", 0))
        assertTrue(limiter.allow("a", 1))
        assertTrue(limiter.allow("a", 2))
        assertFalse(limiter.allow("a", 3))
    }

    @Test
    fun `la ventana expira`() {
        val limiter = RateLimiter(maxPerWindow = 2, windowMs = 1_000)
        assertTrue(limiter.allow("a", 0))
        assertTrue(limiter.allow("a", 10))
        assertFalse(limiter.allow("a", 20))
        assertTrue("pasada la ventana vuelve a permitir", limiter.allow("a", 1_500))
    }

    @Test
    fun `las claves son independientes`() {
        val limiter = RateLimiter(maxPerWindow = 1, windowMs = 1_000)
        assertTrue(limiter.allow("a", 0))
        assertFalse(limiter.allow("a", 1))
        assertTrue(limiter.allow("b", 1))
    }

    /**
     * El propio anti-abuso no puede ser la fuga de memoria: con el mapa lleno, una clave nueva
     * se rechaza en vez de hacerlo crecer. Es la decisión conservadora — bajo un ataque masivo
     * se pierden likes legítimos, pero la app no se queda sin memoria.
     */
    @Test
    fun `el mapa de claves tiene techo`() {
        val limiter = RateLimiter(maxPerWindow = 5, windowMs = 1_000, maxKeys = 2)
        assertTrue(limiter.allow("a", 0))
        assertTrue(limiter.allow("b", 0))
        assertFalse(limiter.allow("c", 0))
        // Y al expirar la ventana, las claves viejas se liberan y vuelve a haber sitio.
        assertTrue(limiter.allow("c", 5_000))
    }
}
