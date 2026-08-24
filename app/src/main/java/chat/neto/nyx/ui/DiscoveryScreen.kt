package chat.neto.nyx.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.neto.nyx.avatar.AvatarRenderer
import chat.neto.nyx.core.avatar.AvatarIdentity
import chat.neto.nyx.core.model.DiscoveredCard

/**
 * Tablón de descubrimiento (plan 4.6).
 *
 * Las acciones sobre cada tarjeta —"me interesa", bloquear, denunciar— son 4.8; aquí está la
 * pantalla, la carga y los cuatro estados en que puede quedarse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("Descubrir") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NyxBackIcon, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenProfile) {
                        Icon(NyxPersonIcon, contentDescription = "Mi perfil")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(NyxRefreshIcon, contentDescription = "Actualizar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is DiscoveryState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is DiscoveryState.Cards -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.cards, key = { it.peerId }) { DiscoveryCardView(it) }
                }

                // Vacío y error se distinguen a propósito: en uno no hay nadie publicando, en el
                // otro no llegamos a saberlo. La respuesta correcta a cada uno es distinta —
                // esperar frente a reintentar.
                is DiscoveryState.Empty -> CenteredMessage(
                    title = "Todavía no hay nadie por aquí",
                    body = "Cuando alguien publique su perfil en esta categoría, aparecerá en " +
                        "esta lista. Publica el tuyo para que te encuentren.",
                    action = "Crear mi perfil" to onOpenProfile,
                )

                is DiscoveryState.Error -> CenteredMessage(
                    title = "No se pudo consultar el tablón",
                    body = s.message,
                    onRetry = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCardView(discovered: DiscoveredCard) {
    val card = discovered.card
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
        Row(Modifier.padding(16.dp)) {
            CardAvatar(discovered)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.nickname.ifBlank { "Sin apodo" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${card.ageMin}–${card.ageMax} años",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.interests.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        card.interests.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (card.bio.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(card.bio, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                }
            }
        }
    }
}

/**
 * El rostro de la tarjeta.
 *
 * Si la persona publicó un avatar, se dibuja ese. Si no, se **deriva del PeerID** con
 * `AvatarIdentity`: así ninguna tarjeta sale con un hueco gris, y el rostro por defecto es
 * estable y propio de esa identidad en vez de un icono genérico igual para todos.
 *
 * Es justo para lo que se construyó el avatar derivado, y hace que el tablón sea usable **antes**
 * de que exista el editor de perfil (4.7).
 */
@Composable
private fun CardAvatar(discovered: DiscoveredCard, size: androidx.compose.ui.unit.Dp = 72.dp) {
    val bytes = discovered.card.avatar
    val bitmap = remember(discovered.peerId, bytes.size) {
        if (bytes.isNotEmpty()) {
            // Los bytes vienen de un desconocido: si no decodifican, se cae al derivado en vez
            // de dejar el hueco.
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        } else {
            null
        } ?: runCatching {
            AvatarRenderer(256).render(AvatarIdentity.attributesFor(discovered.peerId))
        }.getOrNull()
    }

    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    onRetry: (() -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        onRetry?.let {
            Spacer(Modifier.height(20.dp))
            Button(onClick = it) { Text("Reintentar") }
        }
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(20.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}
