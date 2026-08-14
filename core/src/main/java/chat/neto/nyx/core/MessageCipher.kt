package chat.neto.krypta.core

/**
 * Cifrado autenticado extremo a extremo de los payloads. La clave de sesión se deriva del
 * `sharedSecret` del contacto (ver [model.Contact]); la infraestructura (relay, buzón,
 * wake) nunca ve texto plano. La implementación concreta se inyecta vía Hilt.
 */
interface MessageCipher {

    /** Cifra [plaintext] para el contacto cuyo secreto compartido es [sharedSecret]. */
    fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Descifra [ciphertext]. Lanza si la autenticación falla (manipulación o clave
     * incorrecta) — el dato no debe usarse en ese caso.
     */
    fun decrypt(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray
}
