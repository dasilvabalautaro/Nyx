package chat.neto.nyx.p2p

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deriva el tema de **descubrimiento público** de una categoría del tablón:
 *
 *     tema = HKDF(ikm = "nyx-discover-v1", info = categoría)
 *
 * Es hermano de [RendezvousService], no una extensión suya, y la diferencia no es de estilo
 * sino de **modelo de confianza**:
 *
 *  - El rendezvous se deriva de un **secreto compartido** entre dos personas. Nadie más puede
 *    calcularlo, y por eso la DHT nunca ve identificadores reales ni se puede enumerar quién
 *    habla con quién.
 *  - Este tema, en cambio, es **público a propósito**: cualquiera que conozca la categoría
 *    puede derivarlo, porque de eso se trata — es el punto de encuentro de quien quiere ser
 *    descubierto. No aporta secreto y no debe usarse como si lo aportara.
 *
 * Entonces, ¿para qué el HKDF si no hay secreto? Para dos cosas concretas, ninguna de ellas
 * confidencialidad: (a) da una clave de tamaño fijo y bien distribuida a partir de un texto
 * arbitrario, que es lo que la DHT espera; y (b) el prefijo `nyx-discover-v1` separa el
 * espacio de nombres, así que un tema del tablón nunca puede colisionar con un rendezvous
 * privado ni con el de otra versión del protocolo si alguna vez cambia el formato.
 */
@Singleton
class DiscoveryTopic @Inject constructor() {

    /**
     * Tema (32 bytes) de [category]. La categoría se normaliza igual que la valida el nodo
     * (`^[a-z0-9_-]{1,32}$`), para que "Citas" y "citas" no acaben siendo dos tablones
     * distintos según cómo lo escriba cada cliente.
     */
    fun topicFor(category: String): ByteArray {
        val normalized = normalize(category)
        require(normalized != null) { "categoría inválida: $category" }
        return Hkdf.derive(
            ikm = DOMAIN.toByteArray(Charsets.UTF_8),
            salt = ByteArray(0),
            info = normalized.toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    companion object {
        private const val DOMAIN = "nyx-discover-v1"

        /** Mismo alfabeto que acepta el nodo en `board.go`; mantener los dos en sintonía. */
        private val VALID = Regex("^[a-z0-9_-]{1,32}$")

        /**
         * Normaliza a minúsculas y recorta. Devuelve `null` si tras eso sigue sin ser una
         * categoría válida — el nodo la rechazaría igual, así que es mejor enterarse aquí.
         */
        fun normalize(category: String): String? =
            category.trim().lowercase().takeIf { VALID.matches(it) }
    }
}
