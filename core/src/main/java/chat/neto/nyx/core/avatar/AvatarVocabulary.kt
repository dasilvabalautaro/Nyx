package chat.neto.nyx.core.avatar

/**
 * Vocabulario del avatar en español, para construir la descripción **con** el usuario.
 *
 * # El problema que resuelve
 *
 * `AttributeParser` sólo entiende inglés, y además exige **contexto**: «brown» suelto no asigna
 * nada, hace falta «brown hair» o «brown skin». Puesto delante de alguien que habla español, un
 * campo de texto libre es un juego de adivinanzas donde casi todo lo que escriba se ignora en
 * silencio — y el silencio es lo peor que puede hacer, porque no distingue «no te he entendido»
 * de «eso ya era el valor por defecto».
 *
 * Traducir el parser al español no es la salida: el trazado y el parser viven **en dos lenguajes**
 * (Kotlin y Python) y tienen que producir lo mismo, así que duplicar el vocabulario en dos
 * idiomas × dos lenguajes son cuatro sitios que se desincronizan. Aquí el idioma se resuelve
 * **en la interfaz**: el usuario elige en español y lo que entra en el campo es el término que
 * el parser sí lee. Escribir a mano sigue funcionando para quien conozca el vocabulario.
 *
 * # La garantía que lo sostiene
 *
 * Cada término que se ofrece aquí **tiene que producir de verdad el atributo que promete**. Una
 * sugerencia que el parser ignore es peor que no ofrecerla: el usuario la toca, no cambia nada y
 * concluye que la función está rota. Eso lo comprueba `AvatarVocabularyTest` recorriendo la
 * tabla entera contra `AttributeParser`, así que añadir una opción mal escrita rompe un test en
 * vez de llegar al teléfono.
 */
object AvatarVocabulary {

    /** Una opción ofrecible: lo que lee el usuario y lo que se inserta en el campo. */
    data class Option(val label: String, val term: String)

    /** Un grupo de opciones, con el nombre del rasgo en español. */
    data class Group(val title: String, val options: List<Option>)

    private fun opts(vararg pairs: Pair<String, String>) = pairs.map { Option(it.first, it.second) }

    val groups: List<Group> = listOf(
        Group(
            "Expresión",
            opts(
                "Sonriente" to "smiling",
                "Serena" to "calm",
                "Alegre" to "happy",
                "Segura" to "confident",
                "Seria" to "serious",
                "Amable" to "friendly",
            ),
        ),
        Group(
            "Peinado",
            opts(
                "Corto" to "short hair",
                "Rapado" to "buzz hair",
                "Rizado" to "curly hair",
                "Ondulado" to "wavy hair",
                "Con raya" to "side-parted hair",
                "Melena corta" to "bob hair",
                "Largo" to "long hair",
                "Coleta" to "ponytail hair",
                "Moño" to "bun hair",
                "Afro" to "afro hair",
                "Rapado a los lados" to "undercut hair",
                "Sin pelo" to "bald hair",
            ),
        ),
        Group(
            "Color de pelo",
            opts(
                "Negro" to "black hair",
                "Castaño" to "brown hair",
                "Caoba" to "auburn hair",
                "Rubio" to "blonde hair",
                "Gris" to "gray hair",
                "Pelirrojo" to "red hair",
                "Plateado" to "silver hair",
                "Azul" to "blue hair",
                "Rosa" to "pink hair",
                "Verde" to "green hair",
            ),
        ),
        Group(
            "Tono de piel",
            opts(
                "Muy clara" to "porcelain skin",
                "Clara" to "light skin",
                "Beige" to "beige skin",
                "Dorada" to "golden skin",
                "Oliva" to "olive skin",
                "Morena" to "tan skin",
                "Marrón" to "brown skin",
                "Oscura" to "deep skin",
                "Muy oscura" to "ebony skin",
            ),
        ),
        Group(
            "Ojos",
            opts(
                "Marrones" to "brown eyes",
                "Azules" to "blue eyes",
                "Verdes" to "green eyes",
                "Grises" to "gray eyes",
                "Avellana" to "hazel eyes",
                "Ámbar" to "amber eyes",
                "Almendrados" to "almond eyes",
                "Redondos" to "round eyes",
                "Rasgados" to "narrow eyes",
                "Grandes" to "wide eyes",
                "Caídos" to "hooded eyes",
            ),
        ),
        Group(
            "Cara",
            opts(
                "Redonda" to "round face",
                "Ovalada" to "oval face",
                "Cuadrada" to "square face",
                "Corazón" to "heart face",
                "Alargada" to "long face",
                "Diamante" to "diamond face",
            ),
        ),
        Group(
            "Vello facial",
            opts(
                "Barba de días" to "stubble",
                "Bigote" to "mustache",
                "Perilla" to "goatee",
                "Barba corta" to "short beard",
                "Barba cerrada" to "full beard",
            ),
        ),
        Group(
            "Gafas",
            opts(
                "Redondas" to "round glasses",
                "Cuadradas" to "square glasses",
                "Rectangulares" to "rectangular glasses",
                "De sol" to "sunglasses",
            ),
        ),
        Group(
            "Detalles",
            opts(
                "Pecas" to "light freckles",
                "Muchas pecas" to "heavy freckles",
                "Pendientes" to "studs earrings",
                "Aros" to "hoops earrings",
                "Cejas gruesas" to "thick brows",
                "Cejas finas" to "thin brows",
                "Cejas arqueadas" to "arched brows",
            ),
        ),
        Group(
            "Ropa",
            opts(
                "Cuello redondo" to "crew neck",
                "Cuello de pico" to "v-neck",
                "Camisa" to "collared shirt",
                "Sudadera" to "hoodie",
                "Cuello alto" to "turtleneck",
            ),
        ),
        Group(
            "Fondo",
            opts(
                "Coral" to "coral background",
                "Menta" to "mint background",
                "Cielo" to "sky background",
                "Lavanda" to "lavender background",
                "Arena" to "sand background",
                "Pizarra" to "slate background",
                "Rosa" to "rose background",
                "Turquesa" to "teal background",
            ),
        ),
    )

    /** Todos los términos ofrecidos, para el test que los valida contra el parser. */
    val allOptions: List<Option> = groups.flatMap { it.options }

    /**
     * Añade [term] a [current] sin repetir y separando por comas, que es como el parser espera
     * leerlos. Devuelve el texto nuevo.
     */
    fun append(current: String, term: String): String {
        val partes = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (partes.any { it.equals(term, ignoreCase = true) }) return current
        return (partes + term).joinToString(", ")
    }
}
