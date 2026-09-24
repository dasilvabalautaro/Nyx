package chat.neto.nyx.ui

/**
 * Ayuda in-app: un resumen corto y orientado a tareas de lo que el manual explica en largo.
 * Es **texto plano, sin Compose**, para poder cubrirlo con tests JVM (ver `HelpContentTest`)
 * y para que la lista sea la única fuente de verdad del FAQ que pinta [HelpScreen].
 *
 * Criterio de contenido: pocas preguntas, las que de verdad generan dudas o soporte (cómo se
 * conoce gente, qué es público y qué no, bloquear/denunciar, recepción en segundo plano);
 * cada respuesta de 2–4 frases. El manual completo ([docs/MANUAL.md]) queda como referencia
 * extensa.
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
            answer = "No. Nyx no pide teléfono, correo ni ninguna cuenta. Tu identidad se " +
                "crea sola en este móvil la primera vez que abres la app y vive únicamente en " +
                "tu dispositivo.",
        ),
        HelpItem(
            category = "Primeros pasos",
            question = "¿Por qué me pregunta si soy mayor de edad?",
            answer = "Nyx es una app de citas solo para adultos: declararte mayor de 18 años " +
                "es condición para usarla, y aceptar los Términos de uso es condición para " +
                "publicar en el tablón. Es una declaración tuya — Nyx no tiene cuentas ni " +
                "puede verificar identidades, y te lo dice así en vez de fingir lo contrario.",
        ),
        HelpItem(
            category = "Conocer gente",
            question = "¿Cómo conozco gente en Nyx?",
            answer = "En el tablón (la brújula de la pantalla principal): publicas tu tarjeta " +
                "si quieres que te encuentren, miras las tarjetas de otras personas y tocas " +
                "“Me interesa” en las que te gusten. Nada se publica sin que pulses tú el " +
                "botón de publicar, y puedes usar el tablón solo para mirar.",
        ),
        HelpItem(
            category = "Conocer gente",
            question = "¿Por qué no puedo escribirle a alguien directamente desde el tablón?",
            answer = "Porque en Nyx nadie puede escribirte sin que tú también hayas mostrado " +
                "interés: el chat se abre solo cuando el interés es mutuo (un match). Que " +
                "alguien te dé “me interesa” no le abre tu bandeja — solo te lo enseña, y " +
                "decides tú si corresponder. Es la regla anti-acoso central de la app.",
        ),
        HelpItem(
            category = "Conocer gente",
            question = "¿Qué es público cuando publico mi tarjeta?",
            answer = "Todo lo que pone en ella: apodo, franja de edad, intereses, la " +
                "presentación y el avatar, junto con tu PeerID. La tarjeta viaja sin cifrar — " +
                "ser encontrable es su propósito — y el nodo y cualquier usuario pueden " +
                "verla mientras esté publicada. Caduca sola a las 48 horas y puedes retirarla " +
                "al instante desde tu perfil; lo que escribas en los chats sigue cifrado de " +
                "extremo a extremo, eso no cambia.",
        ),
        HelpItem(
            category = "Conocer gente",
            question = "¿Por qué mi tarjeta lleva un avatar dibujado y no una foto?",
            answer = "Porque una foto real es el dato más identificable que existe y el tablón " +
                "es público. En Nyx la única imagen posible es un rostro dibujado por la app: " +
                "por defecto se genera a partir de tu PeerID (nadie más tiene esa cara) y, si " +
                "prefieres, puedes describir otro con “Elegir rasgos”. Un avatar nunca " +
                "acredita a nadie: para saber con quién hablas está la verificación de " +
                "identidad, no el dibujo.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Quién puede leer mis mensajes y llamadas?",
            answer = "Solo tú y tu contacto. Todo va cifrado de extremo a extremo con una clave " +
                "que solo tenéis vosotros dos. Los nodos de Nyx y cualquier intermediario " +
                "ven únicamente datos cifrados: nunca el texto, las fotos ni el audio.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Qué sabe de mí el nodo que ayuda a entregar los mensajes?",
            answer = "El contenido, nada: solo maneja bloques cifrados que no puede abrir. " +
                "Metadatos sí. Como tu contacto y tú publicáis el mismo punto de cita para " +
                "poder encontraros, el nodo puede saber que sois pareja aunque vuestros " +
                "mensajes viajen directos y no pasen por él; también sabe cuándo estás " +
                "conectado y ve tu IP. Y si escribes a alguien desconectado, el mensaje espera " +
                "en su buzón, donde el nodo ve qué PeerID deposita para cuál y a qué hora, " +
                "hasta que se recoge.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Cómo bloqueo a alguien?",
            answer = "Desde el chat (⋮ → Bloquear), manteniendo pulsada la conversación en la " +
                "lista, o desde su tarjeta del tablón (⋮). El bloqueo es inmediato y " +
                "silencioso: esa persona no recibe ningún aviso, y sus mensajes, llamadas y " +
                "“me interesa” dejan de llegarte. Puedes revisar y deshacer bloqueos en " +
                "Ajustes → Privacidad.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Cómo denuncio a alguien y quién lee la denuncia?",
            answer = "Desde el chat (⋮ → Denunciar, o manteniendo pulsado un mensaje recibido) " +
                "o desde su tarjeta del tablón. Denunciar bloquea a esa persona al momento, " +
                "pase lo que pase con el envío. La denuncia viaja cifrada y solo puede leerla " +
                "el operador de Nyx, que puede expulsar la tarjeta del tablón; tu conversación " +
                "solo se adjunta si tú marcas la casilla y ves antes exactamente qué se envía.",
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
            question = "¿Se pueden hacer capturas de pantalla de mis chats?",
            answer = "Mientras tienes un chat abierto, no: Nyx bloquea la captura y la " +
                "grabación de pantalla, sale en negro si alguien graba, y el chat tampoco " +
                "aparece en la vista de apps recientes. El bloqueo es solo de la pantalla de " +
                "chat; en el resto de la app (lista, ajustes, ayuda) puedes capturar como " +
                "siempre. Si quieres guardar una conversación, usa ⋮ → Capturar pantalla " +
                "dentro del chat; se guarda en Galería › Nyx, ya fuera del cifrado, así " +
                "que trátala como cualquier foto de tu móvil.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Puedo recuperar mi cuenta si pierdo o cambio de teléfono?",
            answer = "Solo si hiciste una copia de seguridad (Ajustes → Copia de seguridad), y " +
                "recuperas tu PeerID y tus contactos, no las conversaciones: la copia no " +
                "incluye mensajes ni archivos, y no existe ninguna otra copia en ningún sitio. " +
                "Sin ella, perder el móvil significa perder tu PeerID, y tus contactos tendrían " +
                "que volver a añadirte y verificarte. Guarda la copia y su frase-clave en un " +
                "lugar seguro.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Qué es un PeerID y para qué sirve?",
            answer = "Tu PeerID es tu única seña en Nyx: es tu clave pública, no un teléfono ni " +
                "un correo. Normalmente no hace falta tocarlo — los chats se abren con un " +
                "match —, pero si ya conoces a alguien fuera de la app podéis añadiros " +
                "directamente compartiéndolo desde Ajustes. Quien lo tenga puede añadirte, " +
                "pero no revela ningún otro dato personal tuyo.",
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
            category = "Mensajes y llamadas",
            question = "¿Cómo respondo a un mensaje concreto y no al último?",
            answer = "Desliza el mensaje hacia la derecha, o mantenlo pulsado y elige " +
                "Responder. Encima del cuadro de escribir verás a quién estás respondiendo, " +
                "con una ✕ para descartarlo. Vale para cualquier mensaje —texto, foto, nota de " +
                "voz o archivo— y puedes responder con lo que quieras, no solo con texto. En el " +
                "chat, tocar la cita te lleva al mensaje original. Por la red solo viaja una " +
                "referencia interna al mensaje citado, nunca una copia de su contenido: por eso, " +
                "si esa persona ya había vaciado el chat, verá “Mensaje no disponible” en la cita.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Puedo enviar GIF, stickers o emoji grandes desde el teclado?",
            answer = "Sí. Abre la pestaña de GIF o de stickers de tu teclado y toca el que " +
                "quieras: los GIF llegan animados y los stickers conservan su fondo " +
                "transparente. Un GIF puede pesar bastante, así que se envía por partes y " +
                "puede tardar unos segundos más que un mensaje de texto; el límite es de 4 MB.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "¿Puedo quitar el aviso fijo de “Conectado — recibiendo mensajes”?",
            answer = "Ese aviso es lo que mantiene a Nyx conectada con la app cerrada: sin él, " +
                "Android detendría el servicio y dejarías de recibir mensajes y llamadas al " +
                "instante (Nyx no usa los servidores de notificaciones de Google). El sistema " +
                "obliga a mostrarlo mientras el servicio funciona. Sí puedes ocultarlo tú: " +
                "deslízalo para descartarlo, o mantenlo pulsado y desactiva el canal “Servicio en " +
                "segundo plano”. Nyx seguirá funcionando igual; solo dejarás de ver el aviso.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "No me llegan los mensajes con la app cerrada, ¿qué hago?",
            answer = "Muchos móviles “congelan” las apps para ahorrar batería y eso corta la " +
                "recepción. Ve a Ajustes → Recepción en segundo plano, pulsa “Ajustes del " +
                "sistema” y permite a Nyx: batería sin restricciones, inicio automático y " +
                "notificaciones. Con eso los avisos llegan aunque no tengas la app abierta.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Puedo copiar un mensaje? ¿Es seguro?",
            answer = "Sí: mantén pulsado el mensaje y elige “Copiar”. Ten en cuenta que al " +
                "copiarlo el texto sale del cifrado de extremo a extremo y pasa a manos del " +
                "portapapeles del móvil, igual que ocurre al guardar una captura en la galería: " +
                "otras apps podrían leerlo al pegarlo. Nyx lo marca como contenido sensible " +
                "para que Android no lo muestre en la vista previa del portapapeles, pero si el " +
                "mensaje es delicado, cópialo solo cuando de verdad lo necesites.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "Uso “Cerrar todo” en aplicaciones recientes, ¿afecta a Nyx?",
            answer = "Sí, y es distinto de ocultar el aviso: “Cerrar todo” cierra Nyx de " +
                "verdad y deja de recibir hasta que vuelve a levantarse sola unos segundos " +
                "después. Para evitarlo, abre recientes, mantén pulsada la tarjeta de Nyx y " +
                "usa el candado: así queda fuera de “Cerrar todo”. Otras apps de mensajería no " +
                "lo necesitan porque usan los servidores de Google; Nyx no los usa, y por " +
                "eso depende de seguir viva en tu móvil.",
        ),
    )

    /** Categorías en el orden en que deben mostrarse, preservando el de [items]. */
    val categoriesInOrder: List<String> = items.map { it.category }.distinct()
}
