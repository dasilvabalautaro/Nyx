package chat.neto.nyx.core

import kotlinx.coroutines.flow.Flow

/**
 * Descubrimiento de peers. En v1 el descubrimiento principal es por DHT + rendezvous
 * (WAN); mDNS queda como implementación opcional en LAN. Se mantiene la interfaz para
 * preservar la inyección de dependencias de la spec.
 */
interface IDiscoveryService {
    /** Emite los peerId encontrados para un rendezvous dado. */
    fun discover(rendezvous: ByteArray): Flow<String>

    /** Se anuncia bajo el rendezvous dado. */
    suspend fun advertise(rendezvous: ByteArray)
}
