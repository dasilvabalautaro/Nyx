package chat.neto.nyx.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.neto.nyx.core.model.ReportReason
import chat.neto.nyx.core.model.ReportedLine

/**
 * Diálogo de denuncia (plan 4.4b).
 *
 * # Lo que este diálogo tiene que conseguir
 *
 * Adjuntar la conversación es la **única excepción al cifrado extremo a extremo** de toda la
 * app: si el usuario dice que sí, texto que estaba cifrado sale del dispositivo hacia el
 * operador. Un consentimiento así no se pide con una casilla marcada por defecto y una frase
 * en gris.
 *
 * De ahí tres decisiones:
 *
 *  - La casilla nace **desmarcada**, y denunciar sin adjuntar nada funciona.
 *  - Al marcarla se **enseña** lo que se enviaría. Autorizar a ciegas el envío de una
 *    conversación no es autorizar nada; si el usuario ve las líneas, la decisión es suya de
 *    verdad.
 *  - Se dice en claro **qué puede hacer el operador**: expulsar del tablón, no borrar mensajes
 *    ni leer conversaciones. Prometer moderación que no existe sería peor que la limitación.
 */
@Composable
fun ReportDialog(
    contactName: String,
    excerpt: List<ReportedLine>,
    onDismiss: () -> Unit,
    onConfirm: (ReportReason, String, Boolean) -> Unit,
) {
    var reason by remember { mutableStateOf(ReportReason.HARASSMENT) }
    var note by remember { mutableStateOf("") }
    var adjuntar by remember { mutableStateOf(false) } // desmarcada a propósito
    val scroll = rememberScrollState()

    // Al autorizar, bajar solo hasta la vista previa.
    //
    // Se vio en el móvil: con seis motivos y la nota, la casilla es lo último visible en una
    // pantalla de 1600 px, así que se podía marcar y confirmar **sin haber visto nunca** lo que
    // se autoriza a enviar — que es justo lo que el diálogo existe para evitar. Enseñarlo solo
    // si el usuario decide desplazarse convierte la garantía en un adorno.
    LaunchedEffect(adjuntar) {
        if (adjuntar) scroll.animateScrollTo(scroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Denunciar a $contactName") },
        text = {
            Column(Modifier.verticalScroll(scroll)) {
                Text(
                    "Al denunciar, $contactName queda bloqueado en este dispositivo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))

                Text("Motivo", style = MaterialTheme.typography.titleSmall)
                ReportReason.entries.forEach { opcion ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == opcion, onClick = { reason = opcion })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == opcion, onClick = { reason = opcion })
                        Text(opcion.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Qué ha pasado (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = adjuntar, onClick = { adjuntar = !adjuntar }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = adjuntar, onCheckedChange = { adjuntar = it })
                    Text(
                        "Adjuntar los últimos mensajes como prueba",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Tus mensajes están cifrados y nadie más puede leerlos. Si adjuntas este " +
                        "fragmento, esas líneas —y solo esas— dejarán de estarlo para quien " +
                        "modera. Sin él, la denuncia llega igual, pero sin nada que mirar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Enseñar lo que se envía es parte del consentimiento, no un extra.
                if (adjuntar) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            if (excerpt.isEmpty()) {
                                Text(
                                    "No hay mensajes que adjuntar.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                )
                            } else {
                                excerpt.forEach { linea ->
                                    Text(
                                        "${if (linea.fromMe) "Yo" else contactName}: ${linea.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Qué puede hacer quien modera: retirar del tablón el perfil de esta " +
                        "persona. No puede borrar mensajes ni leer conversaciones — son " +
                        "cifradas, y solo verá lo que tú adjuntes aquí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason, note, adjuntar) }) {
                Text("Denunciar y bloquear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
