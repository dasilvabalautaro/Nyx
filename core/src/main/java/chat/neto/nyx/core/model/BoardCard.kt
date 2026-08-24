package chat.neto.nyx.core.model

/**
 * Tarjeta del tablón: lo que una persona publica para ser descubierta.
 *
 * # Lo primero que hay que entender: esto va EN CLARO
 *
 * Todo lo demás en Nyx viaja cifrado extremo a extremo. Esto no. El nodo lo almacena sin poder
 * leerlo *sólo* en el sentido de que no lo interpreta — pero los bytes son texto plano, y
 * cualquiera que consulte la categoría los ve, que es exactamente el punto: ser descubrible.
 *
 * De ahí la regla que gobierna este archivo: **aquí no entra nada que no deba ser público**.
 * Ni el PeerID (lo fija el nodo desde la identidad del stream, y ponerlo aquí sólo daría un
 * segundo valor que puede mentir), ni nada derivado de conversaciones, ni la dirección de
 * propinas de nadie más que su dueño.
 *
 * # Por qué no es JSON
 *
 * Dos razones concretas, y ninguna es gusto:
 *
 *  - **El avatar.** Son hasta 58 KiB, y en base64 dentro de un JSON se convierten en ~78 KiB.
 *    Con el tope del nodo en 96 KiB queda muy justo, y una consulta que devuelve 50 tarjetas
 *    movería casi 1 MiB de relleno por el móvil de alguien. Aquí los bytes van crudos.
 *  - **`org.json` no existe en un test JVM**: en `:core` es el stub de `android.jar` y devuelve
 *    nulos, así que un formato basado en él no se podría probar donde toca.
 *
 * El formato sigue el idioma de `MessageEnvelope`: cabecera por líneas y cuerpo binario detrás.
 *
 *     "P1\n" <apodo> "\n" <edadMin> "\n" <edadMax> "\n" <intereses con TAB> "\n"
 *            <dirPropinas> "\n" <bytesBio> "\n" <bytesAvatar> "\n" ++ <bio> ++ <avatar>
 *
 * Bio y avatar van **con longitud declarada** y al final porque son los dos campos que pueden
 * contener cualquier byte: una bio con un salto de línea rompería un formato posicional, y
 * confiar en que el emisor la sanee es confiar en un cliente que puede no ser el nuestro.
 */
data class BoardCard(
    val nickname: String,
    val ageMin: Int,
    val ageMax: Int,
    val interests: List<String> = emptyList(),
    val bio: String = "",
    val tipAddress: String = "",
    val avatar: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardCard) return false
        return nickname == other.nickname && ageMin == other.ageMin && ageMax == other.ageMax &&
            interests == other.interests && bio == other.bio && tipAddress == other.tipAddress &&
            avatar.contentEquals(other.avatar)
    }

    override fun hashCode(): Int {
        var r = nickname.hashCode()
        r = 31 * r + ageMin
        r = 31 * r + ageMax
        r = 31 * r + interests.hashCode()
        r = 31 * r + bio.hashCode()
        r = 31 * r + tipAddress.hashCode()
        r = 31 * r + avatar.contentHashCode()
        return r
    }

    companion object {
        private const val VERSION = "P1"
        private const val NL = '\n'
        private const val TAB = '\t'

        /** Tope del nodo (`boardDefaultMaxCard`). Publicar más grande lo rechaza la caja. */
        const val MAX_BYTES = 96 * 1024

        /**
         * Serializa la tarjeta. Los campos de una línea se sanean aquí **además** de donde se
         * guardan: un salto de línea metido en el apodo partiría la cabecera y desplazaría todos
         * los campos siguientes, así que no puede depender de que otra capa se acordara.
         */
        fun encode(card: BoardCard): ByteArray {
            val bio = card.bio.toByteArray(Charsets.UTF_8)
            val header = buildString {
                append(VERSION).append(NL)
                append(oneLine(card.nickname)).append(NL)
                append(card.ageMin).append(NL)
                append(card.ageMax).append(NL)
                append(card.interests.joinToString(TAB.toString()) { oneLine(it) }).append(NL)
                append(oneLine(card.tipAddress)).append(NL)
                append(bio.size).append(NL)
                append(card.avatar.size).append(NL)
            }.toByteArray(Charsets.UTF_8)
            return header + bio + card.avatar
        }

        /**
         * Lee una tarjeta ajena. Devuelve `null` ante cualquier cosa que no cuadre en vez de
         * lanzar: estos bytes vienen de **un desconocido a través de un nodo**, así que una
         * tarjeta corrupta o de una versión futura tiene que poder ignorarse sin tumbar la
         * consulta entera ni el hilo que la procesa.
         */
        fun decode(raw: ByteArray): BoardCard? {
            var offset = 0
            fun line(): String? {
                val end = raw.indexOfFrom(NL.code.toByte(), offset)
                if (end < 0) return null
                val s = String(raw, offset, end - offset, Charsets.UTF_8)
                offset = end + 1
                return s
            }

            if (line() != VERSION) return null
            val nickname = line() ?: return null
            val ageMin = line()?.toIntOrNull() ?: return null
            val ageMax = line()?.toIntOrNull() ?: return null
            val interests = (line() ?: return null)
                .split(TAB).map { it.trim() }.filter { it.isNotEmpty() }
            val tipAddress = line() ?: return null
            val bioLen = line()?.toIntOrNull() ?: return null
            val avatarLen = line()?.toIntOrNull() ?: return null

            // Las longitudes vienen de fuera: comprobarlas contra lo que hay de verdad evita
            // que una tarjeta manipulada provoque una lectura fuera de rango.
            if (bioLen < 0 || avatarLen < 0) return null
            if (offset + bioLen + avatarLen != raw.size) return null

            val bio = String(raw, offset, bioLen, Charsets.UTF_8)
            val avatar = raw.copyOfRange(offset + bioLen, offset + bioLen + avatarLen)
            return BoardCard(nickname, ageMin, ageMax, interests, bio, tipAddress, avatar)
        }

        private fun oneLine(s: String) = s.replace("\n", " ").replace("\r", " ").replace("\t", " ")

        private fun ByteArray.indexOfFrom(byte: Byte, from: Int): Int {
            for (i in from until size) if (this[i] == byte) return i
            return -1
        }
    }
}

/** Una tarjeta ajena, ya leída, con su autor y su fecha tal como los dio el nodo. */
data class DiscoveredCard(
    /** PeerID del autor. Lo fija el **nodo** desde la identidad del stream: no es suplantable. */
    val peerId: String,
    val publishedAt: Long,
    val card: BoardCard,
)
