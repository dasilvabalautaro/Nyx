package chat.neto.nyx.core

/**
 * Motor de audio full-duplex de una llamada: captura y codifica el micro local (los paquetes
 * salen por el callback de [start], listos para cifrar y enviar) y decodifica/reproduce los
 * frames remotos ([onRemoteFrame]). Los frames son **auto-descriptivos**: el primer frame de
 * cada sentido anuncia el códec ('H'), así que cada lado usa el mejor codificador que tenga
 * (Opus → AMR-WB) sin ronda de negociación — el receptor solo necesita el decodificador, y
 * Android exige ambos decodificadores desde hace años. La implementación (MediaCodec +
 * AudioRecord/AudioTrack) vive en :app; esta interfaz permite un fake en tests JVM.
 */
interface AudioEngine {
    /**
     * Arranca micro y reproducción; cada paquete codificado local se entrega a [onFrame]
     * (en un hilo del motor — el consumidor debe ser rápido o encolar). Idempotente-safe:
     * llamar dos veces sin [stop] es un error de programación.
     */
    fun start(onFrame: (ByteArray) -> Unit)

    /** Frame remoto ya descifrado: decodificar y reproducir. Ignora frames malformados. */
    fun onRemoteFrame(frame: ByteArray)

    /** Para captura/reproducción y libera micro, códecs y modo de audio. Idempotente. */
    fun stop()

    /** Silencia el micro (se sigue enviando silencio para mantener el ritmo). */
    fun setMuted(muted: Boolean)

    /** Altavoz manos-libres on/off. */
    fun setSpeakerphone(on: Boolean)
}
