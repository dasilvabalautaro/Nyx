package chat.neto.nyx.core.model

/**
 * Desambiguación de nombres repetidos en la lista de contactos.
 *
 * # Por qué hace falta y por qué es local
 *
 * Los apodos del tablón **no son únicos y no pueden serlo**: no hay registro central que posea
 * el espacio de nombres, el nodo ni siquiera interpreta las tarjetas —son bytes opacos a
 * propósito, para que el formato evolucione en el cliente— y un "¿está libre este nombre?" sería
 * enumerable, que es justo la fuga que todo el diseño evita.
 *
 * Así que dos personas pueden llamarse "Ana", y después de dos matches aparecen idénticas en la
 * lista. Esto no lo arregla: lo hace **visible**, añadiendo un rabo del PeerID sólo a los que
 * chocan. Es lo que hace Signal, no necesita ningún registro, y deja intacto el caso normal.
 *
 * # Lo que NO es
 *
 * No es una defensa contra la suplantación. Alguien puede copiar el apodo de otro perfil, y esto
 * sólo hará que se vean dos entradas parecidas — no dice cuál es cuál. Contra eso está el
 * **número de seguridad** y el QR, que se comparan fuera de la app. Un nombre nunca es identidad;
 * la identidad es el PeerID.
 */
object DisplayNames {

    /**
     * Devuelve, para cada PeerID, el nombre a enseñar. Los nombres únicos salen tal cual; los
     * repetidos llevan un sufijo con la cola del PeerID.
     *
     * La cola y no la cabeza: **todos** los PeerID de libp2p empiezan por `12D3KooW`, así que un
     * sufijo con el prefijo daría el mismo para los dos y no desambiguaría nada.
     *
     * La comparación ignora mayúsculas y espacios de sobra, porque "Ana" y "ana " son el mismo
     * nombre a ojos de quien mira la lista, que es lo único que importa aquí.
     */
    fun disambiguate(contacts: List<Pair<String, String>>): Map<String, String> {
        val repetidos = contacts
            .groupBy { it.second.trim().lowercase() }
            .filterValues { it.size > 1 }
            .keys

        return contacts.associate { (peerId, name) ->
            val clave = name.trim().lowercase()
            peerId to if (clave in repetidos) "$name (${tail(peerId)})" else name
        }
    }

    /** Cola corta del PeerID, la parte que de verdad distingue. */
    fun tail(peerId: String, chars: Int = 5): String =
        if (peerId.length <= chars) peerId else "…" + peerId.takeLast(chars)
}
