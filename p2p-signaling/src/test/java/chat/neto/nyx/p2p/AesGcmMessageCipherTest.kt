package chat.neto.nyx.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmMessageCipherTest {

    private val cipher = AesGcmMessageCipher()
    private val secret = "shared-secret-between-alice-and-bob".toByteArray()
    private val message = "mensaje secreto de nyx".toByteArray()

    @Test
    fun `encrypt then decrypt recovers the plaintext`() {
        val ct = cipher.encrypt(secret, message)
        assertArrayEquals(message, cipher.decrypt(secret, ct))
    }

    @Test
    fun `ciphertext is not the plaintext`() {
        val ct = cipher.encrypt(secret, message)
        assertFalse(ct.contentEquals(message))
    }

    @Test
    fun `each encryption uses a fresh nonce (different ciphertexts)`() {
        val a = cipher.encrypt(secret, message)
        val b = cipher.encrypt(secret, message)
        assertFalse("same plaintext must not yield identical ciphertext", a.contentEquals(b))
    }

    @Test
    fun `decrypting with the wrong secret fails`() {
        val ct = cipher.encrypt(secret, message)
        assertThrows(Exception::class.java) {
            cipher.decrypt("a-different-secret".toByteArray(), ct)
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val ct = cipher.encrypt(secret, message)
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt(secret, ct) }
    }
}
