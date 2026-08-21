package chat.neto.nyx.core.avatar

/**
 * Atributos del avatar, portados de `src/avatar_face/domain/attributes.py`.
 *
 * Los nueve primeros son los del vocabulario heredado y el resto se añadió con
 * el ADR 0012; los valores por defecto coinciden con `DEFAULT_ATTRIBUTES`.
 *
 * Se le quitó el `fromJson(JSONObject)` que traía el kit: no lo usaba nadie y ataba
 * `:core` a `org.json`, que en un test JVM es el stub de `android.jar` y devuelve
 * nulos. Si el test de comparación de píxeles necesita leer `gallery-specs.json`,
 * ese mapeo va en el *source set* de test, no en el modelo de dominio.
 */
data class AvatarAttributes(
    val expression: String = "calm",
    val faceShape: String = "oval",
    val skinTone: String = "light",
    val hairStyle: String = "short",
    val hairColor: String = "brown",
    val eyeColor: String = "brown",
    val eyeShape: String = "almond",
    val accessory: String = "none",
    val background: String = "sky",
    val browStyle: String = "natural",
    val noseStyle: String = "straight",
    val facialHair: String = "none",
    val glasses: String = "none",
    val earrings: String = "none",
    val freckles: String = "none",
    val clothing: String = "crew neck",
    val clothingColor: String = "blue",
) {
    /** Gafas pedidas de forma explícita o a través del atributo heredado. */
    val effectiveGlasses: String
        get() = when {
            glasses != "none" -> glasses
            accessory == "round glasses" -> "round"
            accessory == "square glasses" -> "square"
            accessory == "sunglasses" -> "sunglasses"
            else -> "none"
        }

    val effectiveEarrings: String
        get() = when {
            earrings != "none" -> earrings
            accessory == "earrings" -> "studs"
            else -> "none"
        }

    val effectiveFreckles: String
        get() = when {
            freckles != "none" -> freckles
            accessory == "freckles" -> "light"
            else -> "none"
        }
}
