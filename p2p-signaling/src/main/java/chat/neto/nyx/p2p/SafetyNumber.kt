package chat.neto.nyx.p2p

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Número de seguridad anti-MITM (estilo Signal). En Nyx el PeerID **es** la clave pública
 * (Ed25519 embebida), así que el intercambio de claves no es vulnerable a un intermediario en
 * la matemática: el riesgo es que alguien **sustituya el PeerID** en el canal por el que se
 * comparte. Este número deriva de *ambos* PeerIDs, es **simétrico** (los dos contactos obtienen
 * el mismo, sin importar el orden) y **determinista**. Si los dos ven el mismo número —cotejado
 * en persona o por una llamada de confianza— nadie sustituyó ningún PeerID.
 *
 * Deriva 60 dígitos decimales (~199 bits) de `SHA-256(dominio || peerId_menor || peerId_mayor)`,
 * agrupados de 5 en 5 para leerlos en voz alta, igual que Signal.
 */
object SafetyNumber {

    private const val DOMAIN = "nyx-safety-number-v1"
    private const val DIGITS = 60
    private const val GROUP = 5

    /** Número de seguridad entre [peerIdA] y [peerIdB] (el orden es indiferente). */
    fun compute(peerIdA: String, peerIdB: String): String {
        // Orden canónico → simetría: ambos lados producen el mismo valor.
        val (lo, hi) = if (peerIdA <= peerIdB) peerIdA to peerIdB else peerIdB to peerIdA
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(DOMAIN.toByteArray())
            update(lo.toByteArray())
            update(hi.toByteArray())
        }.digest()

        // Entero positivo → sus DIGITS dígitos decimales menos significativos, con ceros.
        val value = BigInteger(1, digest).mod(BigInteger.TEN.pow(DIGITS))
        val raw = value.toString().padStart(DIGITS, '0')
        return raw.chunked(GROUP).joinToString(" ")
    }
}
