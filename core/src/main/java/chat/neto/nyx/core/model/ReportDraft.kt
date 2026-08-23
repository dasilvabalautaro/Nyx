package chat.neto.nyx.core.model

/** Motivo de la denuncia. Lista corta y cerrada: el detalle va en la nota libre. */
enum class ReportReason(val label: String) {
    HARASSMENT("Acoso o amenazas"),
    SEXUAL_CONTENT("Contenido sexual no solicitado"),
    MINOR("Parece una persona menor de edad"),
    SPAM("Spam o estafa"),
    IMPERSONATION("Suplantación de identidad"),
    OTHER("Otro"),
}

/** Una línea de conversación adjuntada como prueba. */
data class ReportedLine(
    val fromMe: Boolean,
    val timestamp: Long,
    val text: String,
)

/**
 * Denuncia compuesta en el dispositivo, antes de cifrarla para el operador.
 *
 * # La decisión que gobierna este archivo
 *
 * Adjuntar un fragmento de conversación significa que **texto plano que estaba cifrado de
 * extremo a extremo sale del dispositivo** hacia el operador. Es la única excepción al E2EE en
 * toda la app, y existe porque una denuncia sin prueba no le sirve a nadie: el operador no puede
 * leer las conversaciones, así que si el denunciante no aporta el fragmento, no hay nada sobre lo
 * que actuar.
 *
 * Por eso [includeExcerpt] es un campo explícito y no un detalle de implementación: la UI tiene
 * que preguntarlo, en claro, y por defecto va a `false`. Que la promesa se cumpla —que decir "no"
 * signifique que **ni una palabra** de la conversación viaja— es lo que comprueba
 * `ReportDraftTest`, y es la clase de garantía que no puede depender de que nadie se despiste al
 * tocar el formato luego.
 */
data class ReportDraft(
    val reportedPeerId: String,
    val reason: ReportReason,
    val note: String = "",
    val includeExcerpt: Boolean = false,
    val excerpt: List<ReportedLine> = emptyList(),
) {
    init {
        require(reportedPeerId.isNotBlank()) { "hace falta el PeerID denunciado" }
    }

    /**
     * Texto que se cifra y que el operador acaba leyendo. Se genera legible **a propósito**: la
     * herramienta del operador lo imprime tal cual, y quien modere no debería necesitar un
     * parser para entender una denuncia.
     *
     * [reporterPeerId] es el del denunciante. Va dentro aunque el nodo ya lo registre por su
     * cuenta, porque el registro del nodo es un dato operativo que se puede perder o barrer,
     * mientras que esto es parte de la denuncia — y sin ello el operador no puede responder ni
     * detectar a quien denuncia en masa por venganza.
     */
    fun render(reporterPeerId: String, now: Long, appVersion: String): String = buildString {
        appendLine("== DENUNCIA NYX ==")
        appendLine("fecha:       ${isoUtc(now)}")
        appendLine("app:         $appVersion")
        appendLine("denunciante: $reporterPeerId")
        appendLine("denunciado:  $reportedPeerId")
        appendLine("motivo:      ${reason.label}")
        appendLine()

        appendLine("-- nota del denunciante --")
        appendLine(note.trim().ifBlank { "(sin nota)" })
        appendLine()

        appendLine("-- fragmento de conversación --")
        when {
            // El orden de estas dos ramas importa: si el usuario dijo que no, no se mira
            // siquiera `excerpt`. Así, un fragmento que quedara cargado en memoria por la UI
            // no puede colarse por un descuido al reordenar el `when`.
            !includeExcerpt ->
                appendLine("(no adjuntado: el usuario no autorizó incluir la conversación)")
            excerpt.isEmpty() ->
                appendLine("(autorizado, pero no había mensajes que adjuntar)")
            else -> excerpt.forEach { line ->
                appendLine("[${isoUtc(line.timestamp)}] ${if (line.fromMe) "yo" else "él/ella"}: ${line.text}")
            }
        }
    }

    private fun isoUtc(millis: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(millis))
    }
}
