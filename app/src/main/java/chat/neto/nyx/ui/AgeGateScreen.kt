package chat.neto.nyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Puerta de edad al primer arranque (plan 4.5).
 *
 * No es saltable y no tiene "atrás": es lo primero que se ve, antes de la lista, de los ajustes
 * y de cualquier otra cosa. Declinar cierra la app, que es la única respuesta coherente — una
 * versión "solo mirar" de una app de citas 18+ no existiría.
 *
 * Se dice en claro que es una **autodeclaración**. Sin backend de identidad no hay verificación
 * posible, y adornarlo sería mentir en la pantalla más regulada de la app.
 */
@Composable
fun AgeGateScreen(
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                NyxLockIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Nyx es solo para mayores de 18 años",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Es una app de citas y relaciones. Para usarla tienes que confirmar que eres " +
                    "mayor de edad.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Es una declaración tuya: Nyx no verifica la edad de nadie, porque no pide " +
                    "datos personales ni tiene servidores donde comprobarlos.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Tengo 18 años o más")
            }
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text("Soy menor de 18 — salir")
            }
        }
    }
}

/**
 * Aceptación de los Términos de uso (plan 4.5b).
 *
 * Va **antes de publicar la primera tarjeta**, no en un splash del primer día: aceptar unos
 * términos tres semanas antes de publicar nada es una casilla que nadie lee. Pegada al acto que
 * gobierna, al menos significa algo.
 *
 * El texto es corto a propósito. Unos términos que nadie termina de leer protegen menos que
 * cinco reglas que sí se leen, y las cinco que hay aquí son las que de verdad rigen lo que puede
 * pasar en el tablón.
 */
@Composable
fun TermsScreen(
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Términos de uso del tablón", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Vas a publicar un perfil visible para otras personas. Al publicarlo aceptas " +
                    "estas reglas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            listOf(
                "Tienes 18 años o más" to
                    "Publicar un perfil siendo menor de edad, o hacerse pasar por menor, está " +
                        "prohibido y se retira sin aviso.",
                "Nada de contenido sexual explícito" to
                    "Ni en el avatar ni en el texto del perfil. El tablón es público para " +
                        "quien use la app.",
                "No suplantes a nadie" to
                    "Ni uses el nombre, la imagen o los datos de otra persona.",
                "No acoses" to
                    "Un \"me interesa\" no correspondido termina ahí. Quien te bloquee deja de " +
                        "recibirte, y crear otra identidad para saltárselo es motivo de expulsión.",
                "Se modera el tablón, no tus conversaciones" to
                    "Quien opera Nyx puede retirar tu perfil del tablón si lo denuncian. No " +
                        "puede leer tus mensajes: son cifrados y no tiene las claves.",
            ).forEachIndexed { i, (titulo, cuerpo) ->
                Text("${i + 1}. $titulo", style = MaterialTheme.typography.titleSmall)
                Text(
                    cuerpo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            Text(
                "Si incumples estas reglas, tu perfil puede retirarse del tablón. Tus " +
                    "conversaciones y tu identidad siguen siendo tuyas y siguen en tu " +
                    "dispositivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text("Acepto y publico mi perfil")
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Ahora no")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
