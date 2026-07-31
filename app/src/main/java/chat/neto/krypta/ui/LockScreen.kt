package chat.neto.krypta.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Pantalla que cubre toda la app mientras [chat.neto.krypta.AppLock] esté bloqueado. No
 * muestra ningún contenido del usuario. Lanza el diálogo de autenticación nada más aparecer
 * (para no exigir un toque extra) y deja el botón por si el usuario lo cancela.
 */
@Composable
fun LockScreen(onRequestUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onRequestUnlock() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                KryptaLockIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Krypta está bloqueada",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                "Desbloquea con tu huella, cara o el PIN del móvil.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onRequestUnlock) { Text("Desbloquear") }
        }
    }
}

/**
 * Desenvuelve el [Context] de Compose hasta la [Activity] (el `BiometricPrompt` necesita una
 * para anclar su diálogo). En `setContent` el contexto ya ES la Activity, pero el bucle cubre
 * los `ContextWrapper` intermedios que algunos temas/inspecciones interponen.
 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
