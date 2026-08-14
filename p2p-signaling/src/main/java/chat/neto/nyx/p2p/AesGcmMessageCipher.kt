package chat.neto.nyx.p2p

import chat.neto.nyx.core.MessageCipher
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AEAD con AES-256-GCM. La clave de sesión (32 bytes) se deriva del secreto compartido con
 * `HKDF(sharedSecret, info="nyx-msg-key-v1")`. Cada mensaje usa un nonce aleatorio de
 * 12 bytes que se antepone al texto cifrado: `salida = nonce(12) || ciphertext+tag(16)`.
 *
 * Nota: v1 deriva la clave directamente del secreto compartido estático (sin ratchet/PFS).
 * Suficiente para el spike; un forward-secrecy real (Noise/double-ratchet) es trabajo futuro.
 */
@Singleton
class AesGcmMessageCipher @Inject constructor() : MessageCipher {

    private val random = SecureRandom()

    override fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, sessionKey(sharedSecret), GCMParameterSpec(TAG_BITS, nonce))
        }
        return nonce + cipher.doFinal(plaintext)
    }

    override fun decrypt(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        val body = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, sessionKey(sharedSecret), GCMParameterSpec(TAG_BITS, nonce))
        }
        return cipher.doFinal(body)
    }

    private fun sessionKey(sharedSecret: ByteArray): SecretKeySpec {
        val key = Hkdf.derive(
            ikm = sharedSecret,
            salt = ByteArray(0),
            info = "nyx-msg-key-v1".toByteArray(Charsets.UTF_8),
            length = KEY_BYTES,
        )
        return SecretKeySpec(key, "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
