package chat.neto.krypta.core

/**
 * Tipos de frame del canal de vídeo (Fase 7c): 1 byte de cabecera antes del payload
 * (todo viaja cifrado E2EE). Los conocen el motor de vídeo (que los produce/consume) y
 * `CallService` (que descarta con criterio bajo congestión: los `DELTA` dependen del
 * último `KEY`, así que descartar uno suelto corrompe la imagen — se descarta el grupo
 * entero hasta el próximo `KEY`, que refresca la imagen completa).
 */
object VideoFrame {
    /** Rotación del sensor (payload 1 byte = grados/90). Se envía una vez, al principio. */
    const val ROTATION: Byte = 'R'.code.toByte()

    /** Configuración del códec (SPS/PPS). Imprescindible para arrancar el decoder. */
    const val CONFIG: Byte = 'C'.code.toByte()

    /** Frame delta (depende de frames anteriores). */
    const val DELTA: Byte = 'F'.code.toByte()

    /** Keyframe (imagen completa; punto de recuperación tras descartes). */
    const val KEY: Byte = 'K'.code.toByte()
}
