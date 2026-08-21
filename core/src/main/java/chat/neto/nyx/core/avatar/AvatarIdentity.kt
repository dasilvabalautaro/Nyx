package chat.neto.nyx.core.avatar

import java.security.MessageDigest

/**
 * Deriva un avatar **del PeerID**, de forma determinista y sin que intervenga nada que el
 * usuario escriba.
 *
 * ## Por qué existe
 *
 * El kit de AvatarFace genera el rostro a partir de texto libre: el usuario describe la cara
 * que quiere. Eso es *presentación*, y para la tarjeta del tablón es exactamente lo correcto.
 * Pero un avatar elegido no **identifica** a nadie: cualquiera puede escribir la misma
 * descripción y obtener la misma cara. Esta función cubre el otro papel — el avatar como seña
 * de identidad — y por eso su entrada es el PeerID y **solo** el PeerID.
 *
 * Dos propiedades salen de ahí, y las dos importan:
 *
 * 1. **Es tuyo sin hacer nada.** Un perfil recién creado ya tiene un rostro propio y estable,
 *    en vez de un hueco o un avatar por defecto igual para todos.
 * 2. **No se puede falsificar cambiando el texto**, porque no hay texto. Si alguien sustituye
 *    un PeerID, la cara derivada cambia entera.
 *
 * ## Lo que NO es: no sustituye al número de seguridad
 *
 * Es tentador leer la propiedad 2 como "entonces basta con mirar la cara para verificar a un
 * contacto". **No basta, y conviene no construir UI que lo insinúe.** Las cuentas:
 *
 * - Entropía total de lo que aquí se deriva: **~39,5 bits** (~2³⁹). Los pesos de más abajo la
 *   bajan desde los ~41 bits del muestreo uniforme.
 * - Entropía **perceptiva** viendo el avatar en grande, que es la que decide si una persona
 *   nota la diferencia: fondo, piel, peinado, color de pelo, vello facial y gafas suman
 *   **~17 bits**.
 * - Y en la lista de conversaciones, a ~40 dp, se pierde el detalle de la cara y sólo quedan
 *   fondo, piel y color de pelo: **~9 bits**. Comprobado renderizando a ese tamaño.
 *
 * Generar una identidad Ed25519 cuesta microsegundos, así que encontrar un PeerID cuyo rostro
 * derivado se *parezca* al de una víctima son unos miles de intentos: menos de un segundo en un
 * portátil. Contra un atacante decidido, esto no defiende nada, y la cifra de 9 bits deja claro
 * que en la lista aún menos.
 *
 * Donde sí sirve, y mucho, es contra el **error**: pegar el PeerID equivocado, añadir dos veces
 * a la misma persona con nombres distintos, o abrir el chat que no era. Ahí el fallo salta a la
 * vista sin leer sesenta dígitos. La verificación de verdad sigue siendo [SafetyNumber] y el QR:
 * 199 bits, que es el orden de magnitud que hace falta y el motivo de que ese número sea largo
 * y aburrido de comparar.
 *
 * ## Cumplimiento (RF-09)
 *
 * Un rostro derivado de un hash no puede pedir una persona menor de edad, ni parecerse a
 * alguien real a propósito: no hay descripción que dirigir. Por eso el avatar derivado es
 * también la opción con **menos superficie de abuso** de las dos, y el motivo de que valga la
 * pena que sea el punto de partida por defecto y no un extra.
 */
object AvatarIdentity {

    /**
     * Separación de dominio, mismo patrón que [SafetyNumber] y que `DiscoveryTopic`: la etiqueta
     * lleva versión para poder cambiar el mapeo algún día sin que un avatar viejo y uno nuevo
     * se confundan, y garantiza que este flujo de bytes no coincida con el de ningún otro uso
     * del mismo PeerID.
     */
    private const val DOMAIN = "nyx-avatar-v1"

    /**
     * Bytes por atributo. Con 4 bytes (2³²) sobre vocabularios de ≤12 valores, el sesgo del
     * módulo queda por debajo de 2⁻²⁸ — irrelevante. Con 1 byte llegaría al 1,6 % en el
     * vocabulario de 12, que es entropía tirada a la basura sin ninguna ganancia.
     */
    private const val BYTES_PER_ATTRIBUTE = 4

    /**
     * `accessory` es el atributo heredado que solapa con `glasses`/`earrings`/`freckles`
     * (ver `AvatarAttributes.effectiveGlasses`). Derivar los dos aplicaría el rasgo dos veces
     * y haría que el vocabulario explícito no mandara, así que aquí se fija en "none" y se
     * derivan los explícitos, que son los que el renderizador trata como autoritativos.
     */
    private const val ACCESSORY_FIXED = "none"

    /**
     * Un valor del vocabulario con su peso relativo.
     *
     * Hizo falta al ver la primera galería de rostros derivados: muestreando **uniforme** salían
     * 8 de cada 10 con gafas, pelo azul o verde por todas partes y barbas de colores. No era un
     * error del mapeo — era que un catálogo curado **no es una distribución uniforme**. Las doce
     * personas de referencia del kit las eligió una persona; sortear entre todas las
     * combinaciones saca justo las que nadie elegiría.
     *
     * Se paga en entropía, y se paga a gusto: el análisis de arriba ya establece que esto no
     * defiende contra un atacante decidido a ninguna escala alcanzable, así que cambiar entropía
     * que no compra seguridad por rostros que se sostienen es el intercambio correcto. Todos los
     * valores siguen siendo alcanzables; sólo dejan de ser equiprobables.
     */
    private data class Choice(val value: String, val weight: Int)

    private fun uniform(vararg values: String) = values.map { Choice(it, 1) }

    private val EXPRESSION = uniform("smiling", "calm", "happy", "confident", "serious", "friendly")
    private val FACE_SHAPE = uniform("round", "oval", "square", "heart", "long", "diamond")
    private val SKIN_TONE =
        uniform("porcelain", "light", "beige", "golden", "olive", "tan", "brown", "deep", "ebony")
    private val HAIR_STYLE = uniform(
        "short", "buzz", "curly", "wavy", "side-parted", "bob",
        "long", "ponytail", "bun", "afro", "undercut", "bald",
    )

    /** Azul, rosa y verde existen —alguien los querrá— pero no en un tercio de la población. */
    private val HAIR_COLOR = listOf(
        Choice("black", 6), Choice("brown", 6), Choice("auburn", 4), Choice("blonde", 5),
        Choice("gray", 3), Choice("red", 3), Choice("silver", 3),
        Choice("blue", 1), Choice("pink", 1), Choice("green", 1),
    )

    private val EYE_COLOR = uniform("brown", "blue", "green", "gray", "hazel", "amber")
    private val EYE_SHAPE = uniform("almond", "round", "narrow", "wide", "hooded")
    private val BACKGROUND =
        uniform("coral", "mint", "sky", "lavender", "sand", "slate", "rose", "teal")
    private val BROW_STYLE = uniform("natural", "arched", "thick", "thin", "angled")
    private val NOSE_STYLE = uniform("straight", "small", "button", "wide", "pointed")

    /**
     * Deliberadamente **no** se correlaciona el vello facial con el peinado. Sería fácil quitar
     * la barba a los peinados largos y saldrían rostros más "coherentes", pero eso es codificar
     * una norma de género en el avatar por defecto de una app de citas, y no es una decisión que
     * corresponda tomar aquí. Se baja la frecuencia y quien quiera otra cosa la escribe.
     */
    private val FACIAL_HAIR = listOf(
        Choice("none", 10), Choice("stubble", 3), Choice("mustache", 1),
        Choice("goatee", 2), Choice("short beard", 3), Choice("full beard", 2),
    )

    private val GLASSES = listOf(
        Choice("none", 10), Choice("round", 3), Choice("square", 3),
        Choice("sunglasses", 1), Choice("rectangular", 3),
    )
    private val EARRINGS = listOf(Choice("none", 6), Choice("studs", 3), Choice("hoops", 2))
    private val FRECKLES = listOf(Choice("none", 7), Choice("light", 3), Choice("heavy", 1))
    private val CLOTHING = uniform("crew neck", "v-neck", "collared shirt", "hoodie", "turtleneck")
    private val CLOTHING_COLOR =
        uniform("white", "charcoal", "red", "blue", "green", "mustard", "purple")

    /**
     * Los 16 vocabularios derivados, **en orden fijo**. El orden es parte del contrato: cambiarlo
     * le cambia el rostro a todo el mundo, igual que cambiar [DOMAIN] o cualquier peso. Si algún
     * día hay que tocarlo, se sube la versión de la etiqueta de dominio y se asume que los
     * avatares derivados anteriores dejan de reproducirse.
     */
    private val VOCABULARIES = listOf(
        EXPRESSION, FACE_SHAPE, SKIN_TONE, HAIR_STYLE, HAIR_COLOR, EYE_COLOR, EYE_SHAPE,
        BACKGROUND, BROW_STYLE, NOSE_STYLE, FACIAL_HAIR, GLASSES, EARRINGS, FRECKLES,
        CLOTHING, CLOTHING_COLOR,
    )

    /** Atributos del avatar que le corresponden a [peerId]. Determinista y sin estado. */
    fun attributesFor(peerId: String): AvatarAttributes {
        require(peerId.isNotBlank()) { "el PeerID no puede estar vacío" }
        val picks = pick(peerId)
        return AvatarAttributes(
            expression = picks[0],
            faceShape = picks[1],
            skinTone = picks[2],
            hairStyle = picks[3],
            hairColor = picks[4],
            eyeColor = picks[5],
            eyeShape = picks[6],
            accessory = ACCESSORY_FIXED,
            background = picks[7],
            browStyle = picks[8],
            noseStyle = picks[9],
            facialHair = picks[10],
            glasses = picks[11],
            earrings = picks[12],
            freckles = picks[13],
            clothing = picks[14],
            clothingColor = picks[15],
        )
    }

    private fun pick(peerId: String): List<String> {
        val bytes = stream(peerId, VOCABULARIES.size * BYTES_PER_ATTRIBUTE)
        return VOCABULARIES.mapIndexed { index, vocabulary ->
            val offset = index * BYTES_PER_ATTRIBUTE
            var value = 0L
            for (i in 0 until BYTES_PER_ATTRIBUTE) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            choose(vocabulary, value)
        }
    }

    /** Selección por peso acumulado. Con todos los pesos a 1 equivale a un módulo normal. */
    private fun choose(vocabulary: List<Choice>, value: Long): String {
        val total = vocabulary.sumOf { it.weight }
        var remaining = (value % total).toInt()
        for (choice in vocabulary) {
            remaining -= choice.weight
            if (remaining < 0) return choice.value
        }
        // Inalcanzable: el módulo deja `remaining` por debajo de la suma de los pesos.
        return vocabulary.last().value
    }

    /**
     * Flujo de [length] bytes derivado del PeerID: `SHA-256(DOMAIN ‖ contador ‖ peerId)`
     * concatenado por bloques.
     *
     * El contador va **antes** del PeerID, y no después, para que dos PeerID en los que uno sea
     * prefijo del otro no puedan producir bloques solapados: con la longitud variable al final,
     * `SHA-256(D‖0‖"abc")` y `SHA-256(D‖0‖"abcd")` son distintos, pero mezclar posiciones sería
     * pedir problemas gratis. Un digest solo (32 B) daría de sobra para los 64 B que se piden
     * hoy, pero por bloques esto no se rompe si mañana se derivan más atributos.
     */
    private fun stream(peerId: String, length: Int): ByteArray {
        val out = ByteArray(length)
        var written = 0
        var counter = 0
        while (written < length) {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(DOMAIN.toByteArray())
                update(counter.toByte())
                update(peerId.toByteArray())
            }.digest()
            val take = minOf(digest.size, length - written)
            digest.copyInto(out, written, 0, take)
            written += take
            counter++
        }
        return out
    }
}
