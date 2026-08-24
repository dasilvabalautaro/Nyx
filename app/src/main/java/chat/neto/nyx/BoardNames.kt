package chat.neto.nyx

import android.content.Context

/**
 * Apodos vistos en el tablón, guardados al dar "me interesa".
 *
 * # Por qué hace falta
 *
 * Un match llega como un **PeerID y nada más**: el sobre `L` no lleva nombre a propósito —
 * cuanto menos viaje en el único mensaje que se acepta de un desconocido, mejor. Pero cuando se
 * cierra el match hay que crear el contacto, y un contacto llamado `12D3KooWAy…` no le dice nada
 * a nadie.
 *
 * El apodo se conoce siempre en el momento del like, porque para dar "me interesa" hay que estar
 * viendo la tarjeta. Y **un match exige que tú hayas dado el tuyo**, en cualquiera de los dos
 * órdenes, así que guardarlo aquí cubre los dos casos: que el suyo llegara antes o después.
 *
 * # Por qué no va en la tabla `likes`
 *
 * Sería una columna nueva y una migración a la v6 para un dato que no es del dominio del like:
 * el estado del match no depende de cómo se llame nadie, y `LikeState` es puro justamente para
 * poder razonarlo sin esto. Además el apodo es **texto ajeno sin verificar** — es lo que esa
 * persona escribió en su tarjeta— y tenerlo aparte deja claro que no es una identidad.
 */
object BoardNames {

    private const val PREFS = "nyx_board_names"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Recuerda cómo se llamaba quien publicó [peerId]. Sanea a una línea y recorta. */
    fun remember(context: Context, peerId: String, nickname: String) {
        val clean = nickname.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_CHARS)
        if (peerId.isBlank() || clean.isEmpty()) return
        prefs(context).edit().putString(peerId, clean).apply()
    }

    /**
     * Apodo recordado, o un identificador corto si no lo hay. Nunca devuelve vacío: un contacto
     * sin nombre es peor que uno con un nombre feo pero reconocible.
     */
    fun nameFor(context: Context, peerId: String): String =
        prefs(context).getString(peerId, null)?.takeIf { it.isNotBlank() }
            ?: fallbackName(peerId)

    fun forget(context: Context, peerId: String) {
        prefs(context).edit().remove(peerId).apply()
    }

    /**
     * Nombre de emergencia a partir del PeerID. Se toman los **últimos** caracteres y no los
     * primeros porque todos los PeerID de libp2p empiezan igual (`12D3KooW`): con el prefijo,
     * dos contactos distintos se llamarían igual.
     */
    fun fallbackName(peerId: String): String = when {
        // No debería llegar vacío (`addContact` exige PeerID), pero devolver "" aquí dejaría un
        // contacto literalmente sin nombre en la lista, que es un fallo mudo y molesto de
        // diagnosticar. Lo cazó su test.
        peerId.isBlank() -> "Sin nombre"
        peerId.length <= 8 -> peerId
        else -> "…" + peerId.takeLast(6)
    }

    private const val MAX_CHARS = 32
}
