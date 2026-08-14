package chat.neto.krypta.p2p

import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deriva el punto de encuentro (rendezvous) diario entre dos contactos a partir del
 * secreto compartido que ya comparten tras intercambiar claves públicas:
 *
 *     rendezvous = HKDF(shared_secret, info = "krypta-rdv:" + fecha)
 *
 * Propiedades (ver docs/PLAN-senalizacion-descentralizada.md, Riesgo 1 / Fase 3):
 *  - Rotativo por día → no correlacionable a largo plazo.
 *  - No enumerable sin el secreto → la DHT nunca ve identificadores reales.
 */
@Singleton
class RendezvousService @Inject constructor() {

    /** Clave de rendezvous (32 bytes) para [sharedSecret] en la [date] dada (UTC por defecto). */
    fun rendezvousFor(
        sharedSecret: ByteArray,
        date: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): ByteArray {
        val info = "krypta-rdv:$date".toByteArray(Charsets.UTF_8)
        return Hkdf.derive(ikm = sharedSecret, salt = ByteArray(0), info = info, length = 32)
    }
}
