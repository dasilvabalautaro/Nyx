package chat.neto.nyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.neto.nyx.core.model.BlockedPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Peers bloqueados (plan 4.2): la lista y el botón de desbloquear.
 *
 * Existe porque el bloqueo tiene que ser **reversible y visible**. Sin esta pantalla, bloquear
 * sería una acción sin vuelta atrás desde la app: `ChatService.addContact` rechaza a un PeerID
 * bloqueado a propósito, así que alguien que se equivoque de contacto se quedaría sin forma de
 * arreglarlo.
 *
 * Se muestra el PeerID y no un nombre porque un bloqueo sobrevive al contacto: se puede bloquear
 * a alguien del tablón con quien nunca se llegó a hablar, y también borrar el contacto después.
 * El PeerID es lo único que siempre existe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedPeersScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val blocked by viewModel.blockedPeers.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("Perfiles bloqueados") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NyxBackIcon, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { padding ->
        if (blocked.isEmpty()) {
            EmptyBlocked(Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(blocked, key = { it.peerId }) { peer ->
                    BlockedRow(peer, onUnblock = { viewModel.unblock(peer.peerId) })
                }
            }
        }
    }
}

@Composable
private fun BlockedRow(peer: BlockedPeer, onUnblock: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    NyxBlockIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Bloqueado el ${fechaLegible(peer.blockedAt)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                peer.peerId,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            peer.reason?.takeIf { it.isNotBlank() }?.let { motivo ->
                Spacer(Modifier.height(4.dp))
                Text(
                    motivo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onUnblock) { Text("Desbloquear") }
            }
        }
    }
}

@Composable
private fun EmptyBlocked(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            NyxBlockIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("No has bloqueado a nadie", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cuando bloqueas a alguien deja de poder escribirte y de poder llamarte, y no se " +
                "entera: para esa persona todo sigue igual. Aparecerá aquí para que puedas " +
                "deshacerlo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun fechaLegible(timestamp: Long): String =
    SimpleDateFormat("d/M/yy", Locale.getDefault()).format(Date(timestamp))
