package chat.neto.krypta.ui

/**
 * Ayuda in-app: un resumen corto y orientado a tareas de lo que el manual explica en largo.
 * Es **texto plano, sin Compose**, para poder cubrirlo con tests JVM (ver `HelpContentTest`)
 * y para que la lista sea la única fuente de verdad del FAQ que pinta [HelpScreen].
 *
 * Criterio de contenido: pocas preguntas, las que de verdad generan dudas o soporte (recepción
 * en segundo plano, PeerID, verificación, privacidad); cada respuesta de 2–4 frases. El manual
 * completo ([docs/MANUAL.md]) queda como referencia extensa.
 */
data class HelpItem(
    val category: String,
    val question: String,
    val answer: String,
)

object HelpContent {

    const val INTRO: String =
        "Un resumen de las dudas más frecuentes. Toca una pregunta para ver la respuesta."

    val items: List<HelpItem> = listOf(
        HelpItem(
            category = "Primeros pasos",
            question = "¿Necesito un número de teléfono o registrarme?",
            answer = "No. Krypta no pide teléfono, correo ni ninguna cuenta. Tu identidad se " +
                "crea sola en este móvil la primera vez que abres la app y vive únicamente en " +
                "tu dispositivo.",
        ),
        HelpItem(
            category = "Primeros pasos",
            question = "¿Qué es un PeerID y cómo lo comparto?",
            answer = "Tu PeerID es tu única seña de contacto en Krypta: es tu clave pública, no " +
                "un teléfono ni un correo. Compártelo desde Ajustes → Copiar o Compartir con " +
                "quien quiera escribirte. Quien lo tenga puede añadirte, pero no revela ningún " +
                "otro dato personal tuyo.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Quién puede leer mis mensajes y llamadas?",
            answer = "Solo tú y tu contacto. Todo va cifrado de extremo a extremo con una clave " +
                "que solo tenéis vosotros dos. Los nodos de Krypta y cualquier intermediario " +
                "ven únicamente datos cifrados: nunca el texto, las fotos ni el audio.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Cómo sé que hablo con la persona correcta y no con un impostor?",
            answer = "Abre el chat y entra en “Verificar identidad”. Verás un número de " +
                "seguridad de 60 dígitos: compáralo con el de la otra persona (en persona o por " +
                "llamada) o escanea su código QR. Si coincide en ambos móviles, nadie está en " +
                "medio; el contacto verificado muestra un escudo.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Por qué cada contacto tiene una forma y un color distintos?",
            answer = "El color y la forma del avatar (círculo, hexágono, pentágono…) se generan " +
                "a partir del PeerID del contacto, no del nombre que le pusiste tú. Sirven para " +
                "reconocerlo de un vistazo y como pista de seguridad: si un contacto conocido " +
                "cambiara de forma o color, podría ser señal de que su identidad cambió. La " +
                "letra es la inicial del nombre que tú elegiste.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Puedo recuperar mi cuenta si pierdo o cambio de teléfono?",
            answer = "Solo si hiciste una copia de seguridad (Ajustes → Copia de seguridad). Sin " +
                "ella, perder el móvil significa perder tu PeerID, y tus contactos tendrían que " +
                "volver a añadirte y verificarte. Guarda la copia y su frase-clave en un lugar " +
                "seguro.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Qué pasa si le escribo a alguien que está desconectado?",
            answer = "El mensaje se guarda cifrado en el buzón del nodo y se entrega en cuanto " +
                "esa persona vuelve a conectarse, a menudo en segundos. El nodo solo guarda " +
                "datos cifrados: no puede leer el contenido.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Cómo hago una llamada de voz o de vídeo?",
            answer = "Abre el chat y pulsa el icono de teléfono; durante la llamada puedes " +
                "activar la cámara con el icono de vídeo. Las llamadas también van cifradas de " +
                "extremo a extremo y necesitan que ambos estéis conectados a la vez.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "No me llegan los mensajes con la app cerrada, ¿qué hago?",
            answer = "Muchos móviles “congelan” las apps para ahorrar batería y eso corta la " +
                "recepción. Ve a Ajustes → Recepción en segundo plano, pulsa “Ajustes del " +
                "sistema” y permite a Krypta: batería sin restricciones, inicio automático y " +
                "notificaciones. Con eso los avisos llegan aunque no tengas la app abierta.",
        ),
    )

    /** Categorías en el orden en que deben mostrarse, preservando el de [items]. */
    val categoriesInOrder: List<String> = items.map { it.category }.distinct()
}
