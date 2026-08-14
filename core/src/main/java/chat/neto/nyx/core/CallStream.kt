package chat.neto.krypta.core

/**
 * Stream de llamada full-duplex sobre libp2p (`/krypta/call/1.0.0`): frames binarios opacos
 * (cifrados E2EE por la capa superior), transporte fiable y ordenado (yamux/QUIC), directo
 * (DCUtR) o relayed. La implementación concreta envuelve el stream del puente Go.
 */
interface CallStream {
    /** Envía un frame (≤ 64 KiB). Lanza si el stream está caído. */
    suspend fun sendFrame(frame: ByteArray)

    /** Espera el siguiente frame; null cuando el otro extremo cierra (fin de llamada). */
    suspend fun receiveFrame(): ByteArray?

    /** Cierra el stream (el otro extremo ve el fin). Idempotente. */
    suspend fun close()
}
