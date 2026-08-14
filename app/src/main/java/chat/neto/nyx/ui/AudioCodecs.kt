package chat.neto.krypta.ui

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Inventario de códecs de audio del dispositivo, para el gate de llamadas (Fase 7a):
 * la Opción A necesita un **codificador** de baja latencia (Opus ideal; AAC como fallback).
 */
object AudioCodecs {

    /** Resumen corto para el panel de diagnóstico, p. ej. "Opus enc: sí · AAC enc: sí". */
    fun summary(): String {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        fun hasEncoder(mime: String) = codecs.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val opus = hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val aac = hasEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)
        return "Opus enc: ${if (opus) "sí" else "NO"} · AAC enc: ${if (aac) "sí" else "NO"}"
    }
}
