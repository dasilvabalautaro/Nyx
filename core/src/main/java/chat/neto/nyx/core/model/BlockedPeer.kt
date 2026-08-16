package chat.neto.nyx.core.model

/**
 * Peer bloqueado por el usuario. El bloqueo es **local y unilateral**: no viaja por la red y
 * el bloqueado no se entera, que es lo correcto para no darle señal de que ha sido bloqueado
 * (y lo único posible sin backend de moderación).
 *
 * [reason] es opcional y privada — la escribe el usuario para sí mismo. Si algún día el
 * reporte gana un destino real (ver Fase 4 del plan), es el texto que acompañaría al informe
 * exportado, nunca algo que se publique.
 */
data class BlockedPeer(
    val peerId: String,
    val blockedAt: Long,
    val reason: String? = null,
)
