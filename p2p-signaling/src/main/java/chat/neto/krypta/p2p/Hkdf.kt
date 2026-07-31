package chat.neto.krypta.p2p

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF (RFC 5869) con HMAC-SHA256. Usado por el rendezvous y por la derivación de claves. */
internal object Hkdf {

    private const val HMAC_SHA256 = "HmacSHA256"

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)

        // Extract
        val saltKey = if (salt.isEmpty()) ByteArray(mac.macLength) else salt
        mac.init(SecretKeySpec(saltKey, HMAC_SHA256))
        val prk = mac.doFinal(ikm)

        // Expand
        mac.init(SecretKeySpec(prk, HMAC_SHA256))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - pos)
            System.arraycopy(previous, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }
}
