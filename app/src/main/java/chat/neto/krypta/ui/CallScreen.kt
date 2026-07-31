package chat.neto.krypta.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import chat.neto.krypta.p2p.CallPhase
import chat.neto.krypta.p2p.CallState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Pantalla de llamada (7b + vídeo 7c, rediseño UI-4): ocupa toda la app mientras hay
 * llamada. Sin vídeo: avatar grande + nombre + estado/cronómetro. Con vídeo: el remoto
 * llena la pantalla, la vista propia va en un **PiP arrastrable**, y un toque en el vídeo
 * **muestra/oculta los controles** (se auto-ocultan a los 4 s). Controles como botones
 * redondos (silenciar, altavoz, vídeo, cambiar cámara, colgar).
 */
@Composable
fun CallScreen(
    state: CallState,
    muted: Boolean,
    speaker: Boolean,
    videoWanted: Boolean,
    remoteRotation: Int,
    localRotation: Int,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onPreviewSurface: (Surface) -> Unit,
    onRemoteSurface: (Surface?) -> Unit,
) {
    val context = LocalContext.current

    // Pantalla encendida mientras dura la llamada: si se apaga, el OEM (TECNO/HiOS)
    // suspende la red en segundos y los streams mueren — visto en vivo (6 jul): "se apaga
    // la pantalla y se corta".
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val showVideoArea = state.videoReceiving || videoWanted

    // 7d: sensor de proximidad. En llamada de voz al oído (sin altavoz y sin vídeo) la
    // pantalla se apaga al acercarla — la mejilla ya no cuelga ni silencia — y se
    // reenciende al alejarla. Convive con keepScreenOn (que solo aplica con el sensor
    // "lejos"). El release espera a que el sensor despeje (WAIT_FOR_NO_PROXIMITY) para no
    // reencender bajo la oreja al cambiar de fase.
    val proximityWanted = !speaker && !showVideoArea && state.phase in setOf(
        CallPhase.CALLING, CallPhase.CONNECTING, CallPhase.ACTIVE,
    )
    DisposableEffect(proximityWanted) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val lock = if (
            proximityWanted &&
            power.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        ) {
            runCatching {
                power.newWakeLock(
                    android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "krypta:proximity",
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // tope de seguridad: 4 h > cualquier llamada
                }
            }.getOrNull()
        } else null
        onDispose {
            lock?.let {
                runCatching { it.release(android.os.PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onToggleVideo() }
    val toggleVideo = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onToggleVideo() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // Con vídeo, un toque muestra/oculta los controles; visibles se auto-ocultan a los 4 s.
    var controlsVisible by remember { mutableStateOf(true) }
    val controlsShown = controlsVisible || !showVideoArea || state.phase != CallPhase.ACTIVE
    LaunchedEffect(controlsVisible, showVideoArea, state.phase) {
        if (controlsVisible && showVideoArea && state.phase == CallPhase.ACTIVE) {
            delay(4_000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (showVideoArea) Color.Black else MaterialTheme.colorScheme.surface)
            .pointerInput(showVideoArea) {
                detectTapGestures { if (showVideoArea) controlsVisible = !controlsVisible }
            },
    ) {
        if (showVideoArea) {
            VideoCallArea(
                state = state,
                videoWanted = videoWanted,
                remoteRotation = remoteRotation,
                localRotation = localRotation,
                onPreviewSurface = onPreviewSurface,
                onRemoteSurface = onRemoteSurface,
            )
        } else {
            VoiceCallHeader(state, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = controlsShown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            CallControls(
                state = state,
                muted = muted,
                speaker = speaker,
                videoWanted = videoWanted,
                onVideoBackdrop = showVideoArea,
                onAccept = onAccept,
                onReject = onReject,
                onHangup = onHangup,
                onToggleMute = onToggleMute,
                onToggleSpeaker = onToggleSpeaker,
                onToggleVideo = toggleVideo,
                onSwitchCamera = onSwitchCamera,
            )
        }
    }
}

/** Cabecera de llamada de voz: avatar grande + nombre + estado/cronómetro. */
@Composable
private fun VoiceCallHeader(state: CallState, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val contact = state.contact
        if (contact != null) {
            ContactAvatar(contact.displayName, contact.peerId, online = false, size = 96.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            contact?.displayName ?: "",
            style = MaterialTheme.typography.headlineMedium,
            // Sin un Surface de por medio, LocalContentColor cae a negro: color explícito.
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            statusLine(state),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Zona de vídeo: remoto a pantalla completa + cabecera compacta + PiP propio arrastrable. */
@Composable
private fun VideoCallArea(
    state: CallState,
    videoWanted: Boolean,
    remoteRotation: Int,
    localRotation: Int,
    onPreviewSurface: (Surface) -> Unit,
    onRemoteSurface: (Surface?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (state.videoReceiving) {
            VideoSurface(
                onSurface = onRemoteSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = remoteRotation.toFloat() },
            )
        } else {
            Text(
                "Enviando tu cámara…",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            "${state.contact?.displayName ?: ""} · ${statusLine(state)}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (videoWanted) {
            // PiP arrastrable: parte anclado arriba-derecha y se mueve con el dedo,
            // acotado a los bordes de la pantalla.
            val density = LocalDensity.current
            val pipWidth = 108.dp
            val pipHeight = 144.dp
            val margin = 12.dp
            val maxX = with(density) { (maxWidth - pipWidth - margin * 2).toPx() }
            val maxY = with(density) { (maxHeight - pipHeight - margin * 2).toPx() }
            var offset by remember { mutableStateOf(Offset.Zero) } // desde TopEnd: x ≤ 0, y ≥ 0
            VideoSurface(
                onSurface = { s -> if (s != null) onPreviewSurface(s) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(margin)
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(width = pipWidth, height = pipHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .graphicsLayer { rotationZ = localRotation.toFloat() }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offset = Offset(
                                (offset.x + drag.x).coerceIn(-maxX, 0f),
                                (offset.y + drag.y).coerceIn(0f, maxY),
                            )
                        }
                    },
            )
        }
    }
}

/** Botonera inferior según la fase: aceptar/rechazar (RINGING) o controles + colgar. */
@Composable
private fun CallControls(
    state: CallState,
    muted: Boolean,
    speaker: Boolean,
    videoWanted: Boolean,
    onVideoBackdrop: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
) {
    // Sobre vídeo (fondo negro) los botones van en blanco translúcido; sobre surface, en
    // los contenedores del tema.
    val idleContainer = if (onVideoBackdrop) Color.White.copy(alpha = 0.18f)
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val idleContent = if (onVideoBackdrop) Color.White else MaterialTheme.colorScheme.onSurface
    val activeContainer = MaterialTheme.colorScheme.primary
    val activeContent = MaterialTheme.colorScheme.onPrimary
    val labelColor = if (onVideoBackdrop) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        when (state.phase) {
            CallPhase.RINGING -> Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                RoundCallButton(
                    icon = KryptaCallEndIcon,
                    label = "Rechazar",
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    labelColor = labelColor,
                    size = 68.dp,
                    onClick = onReject,
                )
                RoundCallButton(
                    icon = KryptaPhoneIcon,
                    label = "Aceptar",
                    container = Color(0xFF43A047),
                    content = Color.White,
                    labelColor = labelColor,
                    size = 68.dp,
                    onClick = onAccept,
                )
            }
            CallPhase.CALLING, CallPhase.CONNECTING, CallPhase.ACTIVE -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.phase == CallPhase.ACTIVE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RoundCallButton(
                            icon = if (muted) KryptaMicOffIcon else KryptaMicIcon,
                            label = if (muted) "Activar" else "Silenciar",
                            container = if (muted) activeContainer else idleContainer,
                            content = if (muted) activeContent else idleContent,
                            labelColor = labelColor,
                            onClick = onToggleMute,
                        )
                        RoundCallButton(
                            icon = KryptaSpeakerIcon,
                            label = "Altavoz",
                            container = if (speaker) activeContainer else idleContainer,
                            content = if (speaker) activeContent else idleContent,
                            labelColor = labelColor,
                            onClick = onToggleSpeaker,
                        )
                        RoundCallButton(
                            icon = KryptaVideocamIcon,
                            label = "Vídeo",
                            container = if (videoWanted) activeContainer else idleContainer,
                            content = if (videoWanted) activeContent else idleContent,
                            labelColor = labelColor,
                            onClick = onToggleVideo,
                        )
                        if (videoWanted) {
                            RoundCallButton(
                                icon = KryptaFlipCameraIcon,
                                label = "Cámara",
                                container = idleContainer,
                                content = idleContent,
                                labelColor = labelColor,
                                onClick = onSwitchCamera,
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                RoundCallButton(
                    icon = KryptaCallEndIcon,
                    label = "Colgar",
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    labelColor = labelColor,
                    size = 68.dp,
                    onClick = onHangup,
                )
            }
            else -> Unit // ENDED: solo el texto del motivo; vuelve a IDLE en unos segundos
        }
    }
}

/** Botón redondo de llamada: círculo de color + icono, con etiqueta pequeña debajo. */
@Composable
private fun RoundCallButton(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    labelColor: Color,
    size: Dp = 56.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
    }
}

/**
 * TextureView que entrega su [Surface] al estar lista y avisa con null al destruirse. La
 * Surface se crea UNA vez por textura (recrearla rompería el render del decoder/cámara).
 */
@Composable
private fun VideoSurface(onSurface: (Surface?) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        onSurface(Surface(st))
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onSurface(null)
                        return true
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

@Composable
private fun statusLine(state: CallState): String = when (state.phase) {
    CallPhase.CALLING -> "Llamando…"
    CallPhase.RINGING -> "Te está llamando"
    CallPhase.CONNECTING -> "Conectando…"
    CallPhase.ACTIVE -> activeTimer(state.startedAt)
    CallPhase.ENDED -> state.endReason ?: "Llamada terminada"
    CallPhase.IDLE -> ""
}

/** Cronómetro mm:ss de la llamada activa (se refresca cada segundo). */
@Composable
private fun activeTimer(startedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
