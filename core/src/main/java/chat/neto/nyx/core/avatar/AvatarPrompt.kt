package chat.neto.nyx.core.avatar

import java.text.Normalizer

/**
 * Contrato del prompt y filtro de sólo adultos (RF-09), portado de
 * `src/avatar_face/domain/models.py`.
 *
 * El filtro vive en el dispositivo, no en un servidor: la app funciona offline
 * y la comprobación tiene que ocurrir antes de dibujar nada.
 */
object AvatarPrompt {

    /**
     * Términos que solicitan o sugieren un avatar de una persona menor de edad.
     * Se comparan sobre el texto normalizado en minúsculas y sin diacríticos,
     * por lo que la lista se mantiene en ASCII.
     */
    private val MINOR_AGE_TERMS = setOf(
        "nino", "nina", "ninos", "ninas", "bebe", "bebes", "infante", "infantes",
        "infantil", "menor", "menores", "adolescente", "adolescentes",
        "preadolescente", "preadolescentes", "child", "children", "kid", "kids",
        "baby", "babies", "toddler", "toddlers", "infant", "infants", "teen",
        "teens", "teenager", "teenagers", "teenage", "preteen", "preteens",
        "underage", "minor", "minors", "boy", "boys", "girl", "girls", "loli",
        "shota",
    )

    private val AGE_PATTERN = Regex("""\b(\d{1,2})\s*(?:anos|years?|yrs|y/?o)\b""")
    private val WORD_PATTERN = Regex("[a-z]+")

    sealed interface Result {
        data class Valid(val text: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    private fun referencesMinor(normalized: String): Boolean {
        val words = WORD_PATTERN.findAll(normalized).map { it.value }.toSet()
        if (words.any { it in MINOR_AGE_TERMS }) return true
        return AGE_PATTERN.findAll(normalized).any { it.groupValues[1].toInt() < 18 }
    }

    /** Valida el contrato del prompt; el rechazo de menores es RF-09. */
    fun validate(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Escribe cómo quieres tu avatar.")
        if (trimmed.length > 500) {
            return Result.Invalid("La descripción no puede superar 500 caracteres.")
        }
        if (referencesMinor(normalize(trimmed))) {
            return Result.Invalid(
                "La descripción sugiere una persona menor de edad; " +
                    "sólo se generan rostros de adultos.",
            )
        }
        return Result.Valid(trimmed)
    }
}
