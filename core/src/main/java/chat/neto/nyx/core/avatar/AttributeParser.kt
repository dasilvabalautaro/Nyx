package chat.neto.nyx.core.avatar

/**
 * Traduce texto libre a atributos, portado de `attributes_from_text` en
 * `src/avatar_face/domain/attributes.py`.
 *
 * Los atributos ambiguos («brown», «round», «light») se resuelven por contexto
 * («brown hair», «round face»); un término suelto sin contexto no asigna nada.
 * La validación de sólo adultos (RF-09) ocurre antes, en [AvatarPrompt].
 */
object AttributeParser {

    private const val HAIR_STYLES =
        "short|buzz|curly|wavy|side-parted|bob|long|ponytail|bun|afro|undercut|bald"
    private const val HAIR_COLORS =
        "black|brown|auburn|blonde|blue|pink|gray|red|silver|green"

    private val FACE = Regex("""\b(round|oval|square|heart|long|diamond)\s+face\b""")
    private val SKIN =
        Regex("""\b(porcelain|light|beige|golden|olive|tan|brown|deep|ebony)\s+skin\b""")
    private val HAIR = Regex("""\b(?:($HAIR_STYLES)\s+)?($HAIR_COLORS)\s+hair\b""")
    private val HAIR_STYLE = Regex("""\b($HAIR_STYLES)\s+hair\b""")
    private val EYES = Regex(
        """\b(?:(brown|blue|green|gray|hazel|amber)\s+)?""" +
            """(?:(almond|round|narrow|wide|hooded)\s+)?eyes\b""",
    )
    private val EXPRESSION =
        Regex("""\b(smiling|calm|happy|confident|serious|friendly)\b""")
    private val BACKGROUND =
        Regex("""\b(coral|mint|sky|lavender|sand|slate|rose|teal)\s+background\b""")
    private val BROWS = Regex("""\b(natural|arched|thick|thin|angled)\s+(?:eye)?brows\b""")
    private val NOSE = Regex("""\b(straight|small|button|wide|pointed)\s+nose\b""")
    private val CLOTHING =
        Regex("""\b(crew neck|v-neck|collared shirt|hoodie|turtleneck)\b""")
    private val CLOTHING_COLOR = Regex(
        """\b(white|charcoal|red|blue|green|mustard|purple)\s+(?:shirt|top|hoodie|sweater)\b""",
    )
    private val GLASSES = Regex("""\b(round|square|rectangular)\s+glasses\b""")

    private val ACCESSORIES = listOf(
        "round glasses", "square glasses", "sunglasses", "earrings", "freckles",
    )

    private fun matchFacialHair(text: String): String? {
        for (value in listOf("full beard", "short beard", "stubble", "mustache", "goatee")) {
            if (text.contains(value)) return value
        }
        if (text.contains("beard")) return "short beard"
        if (text.contains("clean shaven") || text.contains("clean-shaven")) return "none"
        return null
    }

    fun parse(text: String): AvatarAttributes {
        val normalized = text.lowercase()
        var attributes = AvatarAttributes()

        ACCESSORIES.firstOrNull { normalized.contains(it) }?.let {
            attributes = attributes.copy(accessory = it)
        }
        EXPRESSION.find(normalized)?.let {
            attributes = attributes.copy(expression = it.groupValues[1])
        }
        FACE.find(normalized)?.let {
            attributes = attributes.copy(faceShape = it.groupValues[1])
        }
        SKIN.find(normalized)?.let {
            attributes = attributes.copy(skinTone = it.groupValues[1])
        }
        HAIR.find(normalized)?.let { match ->
            if (match.groupValues[1].isNotEmpty()) {
                attributes = attributes.copy(hairStyle = match.groupValues[1])
            }
            attributes = attributes.copy(hairColor = match.groupValues[2])
        }
        HAIR_STYLE.find(normalized)?.let {
            attributes = attributes.copy(hairStyle = it.groupValues[1])
        }
        if (normalized.contains("bald")) attributes = attributes.copy(hairStyle = "bald")
        EYES.find(normalized)?.let { match ->
            if (match.groupValues[1].isNotEmpty()) {
                attributes = attributes.copy(eyeColor = match.groupValues[1])
            }
            if (match.groupValues[2].isNotEmpty()) {
                attributes = attributes.copy(eyeShape = match.groupValues[2])
            }
        }
        BACKGROUND.find(normalized)?.let {
            attributes = attributes.copy(background = it.groupValues[1])
        }
        BROWS.find(normalized)?.let {
            attributes = attributes.copy(browStyle = it.groupValues[1])
        }
        NOSE.find(normalized)?.let {
            attributes = attributes.copy(noseStyle = it.groupValues[1])
        }
        matchFacialHair(normalized)?.let { attributes = attributes.copy(facialHair = it) }

        val glasses = GLASSES.find(normalized)
        attributes = when {
            glasses != null -> attributes.copy(glasses = glasses.groupValues[1])
            normalized.contains("sunglasses") -> attributes.copy(glasses = "sunglasses")
            normalized.contains("glasses") -> attributes.copy(glasses = "round")
            else -> attributes
        }
        attributes = when {
            normalized.contains("hoops") -> attributes.copy(earrings = "hoops")
            normalized.contains("studs") || normalized.contains("earrings") ->
                attributes.copy(earrings = "studs")
            else -> attributes
        }
        if (normalized.contains("freckles")) {
            attributes = attributes.copy(
                freckles = if (normalized.contains("many freckles")) "heavy" else "light",
            )
        }
        val clothing = CLOTHING.find(normalized)
        attributes = when {
            clothing != null -> attributes.copy(clothing = clothing.groupValues[1])
            normalized.contains("hoodie") -> attributes.copy(clothing = "hoodie")
            else -> attributes
        }
        CLOTHING_COLOR.find(normalized)?.let {
            attributes = attributes.copy(clothingColor = it.groupValues[1])
        }
        return attributes
    }
}
