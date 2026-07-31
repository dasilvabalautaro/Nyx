package chat.neto.krypta.video

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import chat.neto.krypta.core.VideoFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Motor de vídeo de llamadas (Fase 7c): cámara frontal (Camera2) → MediaCodec **H.264**
 * (superficie de entrada, 640×480 ~500 kbps, keyframe cada 2 s) → frames tipados que
 * `CallService` cifra y envía; y a la inversa, frames remotos → decoder H.264 → `Surface`
 * del UI. Cada frame lleva 1 byte de tipo: `R` (rotación, una vez), `C` (SPS/PPS del
 * encoder) o `F` (frame codificado). Si un frame se pierde/descarta, la imagen se
 * recompone en el siguiente keyframe. Todo best-effort: un fallo apaga el vídeo, nunca
 * tumba la llamada de voz.
 */
@Singleton
class MediaCodecVideoEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // --- Captura + encode -----------------------------------------------------------------

    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var encoderSurface: Surface? = null
    @Volatile private var capturing = false
    private var drainThread: Thread? = null
    private var cameraThread: HandlerThread? = null

    // 7d: cámara elegida (frontal por defecto) + lo necesario para reiniciar la captura
    // al alternar de cámara a mitad de llamada.
    @Volatile private var facing = CameraCharacteristics.LENS_FACING_FRONT
    @Volatile private var lastPreviewSurface: Surface? = null
    @Volatile private var lastOnFrame: ((ByteArray) -> Unit)? = null

    private val _localRotation = MutableStateFlow(0)
    /** Orientación del sensor local (para girar el PiP de vista propia en el UI). */
    val localRotation: StateFlow<Int> = _localRotation.asStateFlow()

    /**
     * Enciende cámara frontal + encoder. [previewSurface] (opcional) recibe la vista propia.
     * [onFrame] recibe cada frame tipado listo para cifrar/enviar. Lanza si no hay cámara o
     * permiso (la UI pide CAMERA antes). Una sola captura a la vez.
     */
    @SuppressLint("MissingPermission") // CAMERA se pide en la UI antes de llegar aquí
    fun startCapture(previewSurface: Surface?, onFrame: (ByteArray) -> Unit) {
        check(!capturing) { "cámara ya encendida" }
        capturing = true
        lastPreviewSurface = previewSurface
        lastOnFrame = onFrame
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            capturing = false
            error("sin cámara")
        }
        val rotation = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        _localRotation.value = rotation

        val enc = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            // Keyframe cada 1 s: es el punto de recuperación tras un descarte por
            // congestión — cuanto más corto, antes se recompone la imagen.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface()
        enc.start()
        encoder = enc
        encoderSurface = input

        // La rotación viaja una vez, antes que el config: el otro lado gira su render.
        onFrame(byteArrayOf(VideoFrame.ROTATION, (rotation / 90).toByte()))

        // Drena el encoder en su propio hilo (dequeue bloqueante corto). Los keyframes van
        // marcados (K) para que CallService pueda descartar por grupos bajo congestión.
        drainThread = thread(name = "krypta-video-enc") {
            val info = MediaCodec.BufferInfo()
            runCatching {
                while (capturing) {
                    val idx = enc.dequeueOutputBuffer(info, 20_000)
                    if (idx < 0) continue
                    if (info.size > 0) {
                        val type = when {
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> VideoFrame.CONFIG
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> VideoFrame.KEY
                            else -> VideoFrame.DELTA
                        }
                        val out = ByteArray(info.size + 1)
                        out[0] = type
                        enc.getOutputBuffer(idx)!!.get(out, 1, info.size)
                        onFrame(out)
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
        }

        val handlerThread = HandlerThread("krypta-camera").apply { start() }
        cameraThread = handlerThread
        val handler = Handler(handlerThread.looper)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!capturing) { device.close(); return }
                camera = device
                val targets = listOfNotNull(input, previewSurface)
                @Suppress("DEPRECATION") // createCaptureSession(List) sigue operativa; la API
                // nueva (SessionConfiguration) no aporta nada aquí.
                device.createCaptureSession(
                    targets,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (!capturing) { s.close(); return }
                            session = s
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                targets.forEach { addTarget(it) }
                            }
                            runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) { stopCapture() }
                    },
                    handler,
                )
            }

            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close(); stopCapture() }
        }, handler)
    }

    /**
     * 7d: alterna cámara frontal/trasera. Con captura activa reinicia cámara + encoder con
     * la otra lente; la nueva rotación y el nuevo SPS/PPS viajan en banda y el receptor
     * re-crea su decoder al ver un config distinto. Sin captura, solo cambia la elección
     * para el próximo encendido.
     */
    fun switchCamera() {
        facing = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        if (!capturing) return
        val preview = lastPreviewSurface
        val sink = lastOnFrame ?: return
        stopCapture()
        startCapture(preview, sink)
    }

    /** Apaga cámara + encoder. Idempotente. */
    fun stopCapture() {
        if (!capturing && camera == null && encoder == null) return
        capturing = false
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        drainThread?.join(1_000); drainThread = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }; encoder = null
        runCatching { encoderSurface?.release() }; encoderSurface = null
        cameraThread?.quitSafely(); cameraThread = null
    }

    // --- Recepción + decode -----------------------------------------------------------------

    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var remoteSurface: Surface? = null
    private var csd: ByteArray? = null
    private val pendingFrames = ArrayDeque<ByteArray>()
    private val decodeLock = Any()

    private val _remoteRotation = MutableStateFlow(0)
    /** Rotación anunciada por el emisor remoto (grados; el UI gira el render). */
    val remoteRotation: StateFlow<Int> = _remoteRotation.asStateFlow()

    /** El UI entrega (o retira) la superficie donde pintar el vídeo remoto. */
    fun setRemoteSurface(surface: Surface?) = synchronized(decodeLock) {
        remoteSurface = surface
        if (surface == null) {
            releaseDecoderLocked()
        } else {
            maybeStartDecoderLocked()
            drainPendingLocked()
        }
    }

    /** Frame remoto ya descifrado (desde `CallService.remoteVideoFrames`). */
    fun onRemoteFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        synchronized(decodeLock) {
            when (frame[0]) {
                VideoFrame.ROTATION -> if (frame.size >= 2) _remoteRotation.value = frame[1].toInt() * 90
                VideoFrame.CONFIG -> {
                    val newCsd = frame.copyOfRange(1, frame.size)
                    // Un SPS/PPS distinto = el emisor reinició su encoder (p. ej. cambió de
                    // cámara): el decoder actual queda inválido, se re-crea con el nuevo.
                    if (csd?.contentEquals(newCsd) == false) releaseDecoderLocked()
                    csd = newCsd
                    maybeStartDecoderLocked()
                    drainPendingLocked()
                }
                VideoFrame.KEY, VideoFrame.DELTA -> {
                    val d = decoder
                    if (d == null) {
                        // Aún sin superficie/config: retén unos pocos (se recompone en keyframe).
                        if (pendingFrames.size >= PENDING_MAX) pendingFrames.pollFirst()
                        pendingFrames.addLast(frame)
                    } else {
                        decodeLocked(d, frame)
                    }
                }
            }
        }
    }

    /** Apaga todo (fin de llamada). Idempotente. */
    fun stopAll() {
        stopCapture()
        lastPreviewSurface = null
        lastOnFrame = null
        facing = CameraCharacteristics.LENS_FACING_FRONT
        synchronized(decodeLock) {
            releaseDecoderLocked()
            csd = null
            pendingFrames.clear()
            _remoteRotation.value = 0
        }
    }

    private fun maybeStartDecoderLocked() {
        if (decoder != null) return
        val surface = remoteSurface ?: return
        val config = csd ?: return
        runCatching {
            val dec = MediaCodec.createDecoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
            }
            dec.configure(format, surface, null, 0)
            dec.start()
            decoder = dec
        }
    }

    private fun drainPendingLocked() {
        val d = decoder ?: return
        while (pendingFrames.isNotEmpty()) decodeLocked(d, pendingFrames.pollFirst()!!)
    }

    /** Mete un frame al decoder y pinta las salidas listas. Un fallo reinicia el decoder. */
    private fun decodeLocked(d: MediaCodec, frame: ByteArray) {
        runCatching {
            val idx = d.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                d.getInputBuffer(idx)!!.apply { clear(); put(frame, 1, frame.size - 1) }
                d.queueInputBuffer(idx, 0, frame.size - 1, System.nanoTime() / 1_000, 0)
            } // sin hueco: se descarta (recompone en keyframe)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = d.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                d.releaseOutputBuffer(out, info.size > 0) // render directo a la Surface
            }
        }.onFailure {
            releaseDecoderLocked() // el próximo config/keyframe lo re-crea
        }
    }

    private fun releaseDecoderLocked() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        // Calidad conservadora (visto en vivo 6 jul con 640×480@500kbps: la ruta relayed —
        // un solo túnel wss por Cloudflare — se saturaba y congelaba imagen Y VOZ): a
        // 320×240/12fps/250kbps el vídeo pide ~31 KB/s por sentido y el audio respira.
        // Subir resolución/bitrate adaptativo = 7d.
        const val WIDTH = 320
        const val HEIGHT = 240
        const val BIT_RATE = 250_000
        const val FPS = 12
        const val PENDING_MAX = 60
    }
}
