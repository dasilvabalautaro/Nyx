package chat.neto.nyx.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import chat.neto.nyx.core.AudioEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Motor de audio de llamadas (Fase 7b): captura `AudioRecord` (VOICE_COMMUNICATION →
 * AEC/NS de plataforma) → codifica 20 ms por paquete con MediaCodec (**Opus** 48 kHz si el
 * chip tiene codificador; si no **AMR-WB** 16 kHz, obligatorio en todo Android) → frames
 * auto-descriptivos; y a la inversa decodifica los frames remotos a un `AudioTrack` de voz.
 *
 * Formato de frame (texto plano; el cifrado E2EE lo pone CallService):
 *   'H' ‖ códec ('O' Opus / 'W' AMR-WB)   — anuncio de códec de ese sentido
 *   'A' ‖ paquete codificado              — audio
 * Cada sentido anuncia su códec: no hay negociación, el receptor solo necesita el
 * DEcodificador (Opus y AMR-WB son obligatorios de fábrica desde hace años). El anuncio se
 * **repite cada [HELLO_REPEAT_FRAMES] paquetes** (~2 s): sin eso el 'H' viajaba una sola vez
 * al principio de la llamada y perderlo dejaba ese sentido mudo *toda* la llamada, porque el
 * hilo de reproducción no sabe qué decodificador crear (ver [playLoop]).
 */
@Singleton
class MediaCodecAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioEngine {

    @Volatile private var muted = false
    @Volatile private var running = false
    @Volatile private var diagSink: ((String) -> Unit)? = null
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    // Cola de frames remotos con tope: bajo congestión se tira el más viejo (latencia acotada).
    private val remote = ArrayBlockingQueue<ByteArray>(64)
    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun setDiagnostics(sink: (String) -> Unit) {
        diagSink = sink
    }

    /** Traza al panel de Diagnóstico. Nunca puede tumbar un hilo de medios. */
    private fun diag(msg: String) {
        runCatching { diagSink?.invoke(msg) }
    }

    override fun start(onFrame: (ByteArray) -> Unit) {
        check(!running) { "motor ya arrancado" }
        running = true
        // OJO: aquí NO se vacía `remote`. CallService arranca el bombeo RX y este motor casi a
        // la vez, así que el 'H' del otro extremo puede llegar unos ms antes que el arranque;
        // descartarlo dejaba este sentido mudo hasta el siguiente anuncio. La cola se vacía en
        // stop(), que es donde de verdad sobra lo que quede dentro.
        runCatching {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            // Algunos OEM (Transsion/HiOS entre ellos) ignoran el cambio de modo: sin
            // MODE_IN_COMMUNICATION la pista de voz puede no encaminarse a ningún sitio, que
            // es exactamente el síntoma "no oigo nada y el otro me oye bien".
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                diag("⚠️ audio: el sistema rechazó el modo llamada (modo=${audioManager.mode})")
            }
        }.onFailure { diag("⚠️ audio: no se pudo fijar el modo llamada (${it.reason()})") }
        requestFocus()
        captureThread = thread(name = "nyx-call-tx") {
            runCatching { captureLoop(onFrame) }
                .onFailure { diag("⚠️ micrófono caído: ${it.reason()}") }
        }
        playThread = thread(name = "nyx-call-rx") {
            runCatching { playLoop() }
                .onFailure { diag("⚠️ reproducción caída: ${it.reason()}") }
        }
    }

    override fun onRemoteFrame(frame: ByteArray) {
        // Se encola aunque el motor aún no haya arrancado: ver el comentario de start().
        if (frame.isEmpty()) return
        if (!remote.offer(frame)) {
            remote.poll()
            remote.offer(frame)
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        captureThread?.join(1_500)
        playThread?.join(1_500)
        captureThread = null
        playThread = null
        remote.clear()
        abandonFocus()
        clearRoute()
        runCatching { audioManager.mode = previousMode }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun setSpeakerphone(on: Boolean) {
        runCatching { route(on) }
            .onFailure { diag("⚠️ audio: no se pudo cambiar el altavoz (${it.reason()})") }
    }

    // --- Enfoque y encaminamiento de audio ---------------------------------------------------

    /**
     * Pide el foco de audio de llamada. Sin él, la política de audio de algunos OEM baja o
     * silencia la pista de voz de una app que no es la telefonía del sistema, y otra app
     * sonando (música, vídeo) se superpone en vez de apartarse.
     */
    private fun requestFocus() {
        runCatching {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            val granted = audioManager.requestAudioFocus(req)
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                diag("⚠️ audio: el sistema no dio el foco de llamada (código $granted)")
            }
        }.onFailure { diag("⚠️ audio: fallo pidiendo el foco (${it.reason()})") }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(req) }
    }

    /**
     * Manos libres on/off. En API 31+ se usa `setCommunicationDevice`, que es la API viva:
     * `isSpeakerphoneOn` está deprecada y en varios OEM es un no-op silencioso dentro de
     * MODE_IN_COMMUNICATION. Apagar el altavoz **limpia** el dispositivo en vez de forzar el
     * auricular, para no robarle la salida a unos cascos o a un manos libres Bluetooth.
     */
    private fun route(speaker: Boolean) {
        val am = audioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!speaker) {
                am.clearCommunicationDevice()
                return
            }
            val dev = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (dev != null && am.setCommunicationDevice(dev)) return
            diag("⚠️ audio: sin dispositivo de altavoz disponible")
        }
        @Suppress("DEPRECATION") // Respaldo para API 30 y para OEM donde la API nueva falla.
        am.isSpeakerphoneOn = speaker
    }

    private fun clearRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    // --- Captura + codificación -----------------------------------------------------------

    private fun captureLoop(onFrame: (ByteArray) -> Unit) {
        val useOpus = hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val mime = if (useOpus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AMR_WB
        val rate = if (useOpus) 48_000 else 16_000
        val hello = byteArrayOf(TYPE_HELLO, if (useOpus) CODEC_OPUS else CODEC_AMR_WB)
        onFrame(hello)
        diag("🎤 enviando audio en ${if (useOpus) "Opus" else "AMR-WB"}")

        val encoder = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createAudioFormat(mime, rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (useOpus) 24_000 else 23_850)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val pcm = ByteArray(rate / 50 * 2) // 20 ms mono PCM16
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission") // RECORD_AUDIO se pide en la UI antes de llamar
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, pcm.size * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "micrófono no disponible" }
        // Refuerzo del AEC/NS por software cuando el chip los expone por sesión.
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.enabled = true }
        record.startRecording()

        val info = MediaCodec.BufferInfo()
        var pts = 0L
        var sent = 0L
        try {
            while (running) {
                var off = 0
                while (off < pcm.size && running) {
                    val n = record.read(pcm, off, pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off <= 0) continue
                if (muted) pcm.fill(0) // silencio: mantiene el ritmo sin abrir el micro al peer
                val inIdx = encoder.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    encoder.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, off) }
                    encoder.queueInputBuffer(inIdx, 0, off, pts, 0)
                    pts += 20_000
                }
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        val out = ByteArray(info.size + 1)
                        out[0] = TYPE_AUDIO
                        encoder.getOutputBuffer(outIdx)!!.get(out, 1, info.size)
                        onFrame(out)
                        // Reanuncio periódico del códec: 2 bytes cada ~2 s que convierten un
                        // 'H' perdido en un mudo de 2 s en vez de una llamada entera muda.
                        sent++
                        if (sent % HELLO_REPEAT_FRAMES == 0L) onFrame(hello)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    // --- Decodificación + reproducción ------------------------------------------------------

    private fun playLoop() {
        // Primer frame útil del remoto = 'H' con su códec. Los 'A' que lleguen antes se
        // descartan (no sabemos aún qué decodificador crear), pero el emisor reanuncia.
        var codec: Byte = 0
        var discarded = 0
        while (running && codec.toInt() == 0) {
            val f = remote.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (f[0] == TYPE_HELLO && f.size >= 2) codec = f[1] else discarded++
        }
        if (!running) return
        if (codec.toInt() == 0) {
            diag("⚠️ audio: el otro lado nunca anunció su códec")
            return
        }
        if (discarded > 0) diag("audio: $discarded paquetes antes del anuncio de códec")

        val opus = codec == CODEC_OPUS
        val mime = if (opus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AMR_WB
        val rate = if (opus) 48_000 else 16_000
        diag("🔊 recibiendo audio en ${if (opus) "Opus" else "AMR-WB"}")
        val format = MediaFormat.createAudioFormat(mime, rate, 1)
        if (opus) {
            // El decodificador Opus de Android exige csd-0 (OpusHead) + pre-skip/pre-roll en ns.
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            val ns80 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).array()
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ns80))
            format.setByteBuffer("csd-2", ByteBuffer.wrap(ns80))
        }
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // ~120 ms de colchón: absorbe el jitter de red sin acumular latencia.
            .setBufferSizeInBytes(maxOf(minBuf, rate / 50 * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            diag("⚠️ audio: no se pudo abrir la salida de voz")
            runCatching { track.release() }
            runCatching { decoder.stop() }
            decoder.release()
            return
        }
        track.play()

        val info = MediaCodec.BufferInfo()
        val pcmOut = ByteArray(16 * 1024)
        var pts = 0L
        var fed = 0L
        var played = 0L
        try {
            while (running) {
                val f = remote.poll(100, TimeUnit.MILLISECONDS)
                if (f != null && f.size > 1 && f[0] == TYPE_AUDIO) {
                    val inIdx = decoder.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        decoder.getInputBuffer(inIdx)!!.apply { clear(); put(f, 1, f.size - 1) }
                        decoder.queueInputBuffer(inIdx, 0, f.size - 1, pts, 0)
                        pts += 20_000
                        fed++
                    }
                }
                while (true) {
                    val outIdx = decoder.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    if (info.size > 0) {
                        val bb = decoder.getOutputBuffer(outIdx)!!
                        val n = minOf(info.size, pcmOut.size)
                        bb.get(pcmOut, 0, n)
                        track.write(pcmOut, 0, n) // bloqueante: el AudioTrack marca el ritmo
                        played++
                        if (played == 1L) diag("🔊 sonando la voz del otro lado")
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                }
                // Diagnóstico del caso mudo: entran paquetes pero el decodificador no suelta
                // PCM (decodificador del chip que no traga el flujo del otro extremo).
                if (fed == MUTE_ALARM_FRAMES && played == 0L) {
                    diag("⚠️ audio: ${fed} paquetes recibidos y ninguno decodificado")
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { decoder.stop() }
            decoder.release()
            if (played == 0L && fed > 0L) diag("⚠️ audio: no se reprodujo nada ($fed paquetes)")
        }
    }

    // --- Utilidades ---------------------------------------------------------------------------

    private fun Throwable.reason(): String = message ?: this::class.java.simpleName

    private fun hasEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    /** OpusHead RFC 7845 para mono 48 kHz (pre-skip 3840 muestras = 80 ms), constante. */
    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII)) // magia
        put(1)                  // versión
        put(1)                  // canales
        putShort(3840)          // pre-skip (muestras a 48 kHz)
        putInt(48_000)          // sample rate de entrada
        putShort(0)             // gain
        put(0)                  // mapping family
    }.array()

    private companion object {
        const val TYPE_HELLO = 'H'.code.toByte()
        const val TYPE_AUDIO = 'A'.code.toByte()
        const val CODEC_OPUS = 'O'.code.toByte()
        const val CODEC_AMR_WB = 'W'.code.toByte()
        /** Cada cuántos paquetes de 20 ms se reanuncia el códec (~2 s). */
        const val HELLO_REPEAT_FRAMES = 100L
        /** Paquetes recibidos sin haber sonado nada tras los que se avisa en Diagnóstico. */
        const val MUTE_ALARM_FRAMES = 50L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioEngineModule {
    @Binds
    abstract fun bindAudioEngine(impl: MediaCodecAudioEngine): AudioEngine
}
