package chat.neto.nyx.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.SuggestionChip
import chat.neto.nyx.core.avatar.AvatarVocabulary
import chat.neto.nyx.MyProfilePrefs
import kotlin.math.roundToInt

/**
 * Editor del perfil propio (plan 4.7).
 *
 * Lo que edites se guarda **en el móvil** al vuelo; publicar es un botón aparte. Esa separación
 * está en la pantalla a propósito y se dice con palabras: se puede rellenar el perfil, mirarlo y
 * no enseñárselo a nadie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
) {
    val profile by viewModel.profile.collectAsState()
    val avatar by viewModel.avatar.collectAsState()
    val publishing by viewModel.publishing.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    var showTerms by remember { mutableStateOf(false) }
    var avatarPrompt by remember { mutableStateOf("") }
    var showVocab by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    // Los Términos, antes de publicar y no antes: ver el KDoc de AgeGate.
    if (showTerms) {
        TermsScreen(
            onAccept = {
                showTerms = false
                viewModel.acceptTerms()
                viewModel.publish()
            },
            onCancel = { showTerms = false },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("Mi perfil") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(NyxBackIcon, contentDescription = "Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // imePadding ANTES del scroll: encoge el área desplazable al abrirse el
                // teclado, en vez de dejar que lo tape. Sin esto, el campo que se está
                // editando quedaba oculto detrás del teclado — mismo fallo que ya se arregló
                // en la pantalla de chat, y por el mismo motivo: esta pantalla es casi toda
                // campos de texto y el usuario escribe con ellos delante.
                //
                // Con el contenedor desplazable consciente del IME, Compose además trae solo
                // el campo enfocado a la vista al recibir el foco.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileCard("Tu rostro") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarPreview(avatar)
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "Nyx te da un rostro propio derivado de tu identidad. Puedes " +
                            "describir otro con \"Elegir rasgos\", que va componiendo la " +
                            "descripción por ti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = avatarPrompt,
                    onValueChange = { avatarPrompt = it },
                    label = { Text("Descripción del rostro") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                TextButton(onClick = { showVocab = !showVocab }) {
                    Text(if (showVocab) "Ocultar opciones" else "Elegir rasgos")
                }
                if (showVocab) {
                    AvatarVocabularyPicker(
                        onPick = { term -> avatarPrompt = AvatarVocabulary.append(avatarPrompt, term) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setAvatarFromText(avatarPrompt) },
                        enabled = avatarPrompt.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Dibujar") }
                    OutlinedButton(
                        onClick = { avatarPrompt = ""; viewModel.useDerivedAvatar() },
                        modifier = Modifier.weight(1f),
                    ) { Text("El mío") }
                }
            }

            ProfileCard("Cómo te presentas") {
                // La recomendación va arriba del todo, antes de los campos: leída después de
                // escribir un párrafo no sirve de nada.
                Text(
                    "Esto es un anzuelo, no tu biografía. Unas pocas cosas concretas funcionan " +
                        "mejor que un párrafo general — y lo que de verdad quieras contar cabe " +
                        "en la conversación, que además va cifrada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                // Los campos guardan estado LOCAL en vez de leer el perfil ya saneado, y eso
                // arregla dos fallos que se vieron escribiendo en el móvil:
                //
                //  - En el apodo, `sanitizeNickname` hace `trim()`, así que el espacio que
                //    acabas de teclear desaparecía antes de poder escribir la letra siguiente:
                //    "Ana Maria" salía "AnaMaria" y era **imposible** poner un espacio.
                //  - En intereses era peor: al reinicializarse el campo con la lista guardada,
                //    el cursor se perdía y el texto salía mezclado — "aa,bb,cc,dd,ee,ff" quedó
                //    en "aa, cbc, ed, ff".
                //
                // El saneado sigue existiendo donde importa, al **persistir**; lo que no puede
                // hacer es reescribir lo que el usuario está tecleando.
                var nickname by rememberSaveable { mutableStateOf(profile.nickname) }
                var bio by rememberSaveable { mutableStateOf(profile.bio) }

                TextField(
                    value = nickname,
                    onValueChange = { nickname = it; viewModel.setNickname(it) },
                    label = { Text("Apodo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Counter(nickname.length, MyProfilePrefs.MAX_NICKNAME_CHARS) },
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = bio,
                    onValueChange = { bio = it; viewModel.setBio(it) },
                    label = { Text("Sobre ti") },
                    placeholder = { Text("Dos o tres cosas concretas: a qué dedicas el tiempo, qué buscas.") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Counter(bio.length, MyProfilePrefs.MAX_BIO_CHARS) },
                )
                Spacer(Modifier.height(8.dp))
                InterestsField(profile.interests, viewModel::setInterests)
            }

            ProfileCard("Franja de edad") {
                Text(
                    "Se publica una franja y no tu edad exacta: basta para encontrarse y da " +
                        "bastante menos información sobre ti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${profile.ageMin} – ${profile.ageMax} años",
                    style = MaterialTheme.typography.titleMedium,
                )
                RangeSlider(
                    value = profile.ageMin.toFloat()..profile.ageMax.toFloat(),
                    onValueChange = {
                        viewModel.setAgeRange(it.start.roundToInt(), it.endInclusive.roundToInt())
                    },
                    valueRange = MyProfilePrefs.MIN_AGE.toFloat()..MyProfilePrefs.MAX_AGE.toFloat(),
                )
            }

            ProfileCard("Publicar en el tablón") {
                Text(
                    "Hasta que publiques, tu perfil no sale de este móvil. Al publicarlo, el " +
                        "apodo, la franja de edad, los intereses, el texto y el rostro pasan a " +
                        "ser visibles para quien use Nyx — esta parte no va cifrada, porque el " +
                        "sentido es que te encuentren.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (publishing) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Button(
                        onClick = { if (viewModel.needsTerms()) showTerms = true else viewModel.publish() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Publicar mi perfil") }
                    TextButton(onClick = viewModel::unpublish, modifier = Modifier.fillMaxWidth()) {
                        Text("Retirarlo del tablón", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
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
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun AvatarPreview(bytes: ByteArray?) {
    val bitmap = remember(bytes?.size, bytes?.firstOrNull()) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "Tu rostro",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
    }
}

/**
 * Intereses en un solo campo separado por comas.
 *
 * Se edita como texto y se guarda como lista: un editor de "chips" con su botón de añadir es más
 * bonito y bastante más código, y aquí lo que hace falta es que se pueda escribir "cine, mar,
 * senderismo" y seguir. `MyProfilePrefs` ya recorta, quita vacíos y limita a diez.
 */
@Composable
private fun InterestsField(interests: List<String>, onChange: (List<String>) -> Unit) {
    // Estado local para no reordenar lo que el usuario escribe mientras lo escribe: si se
    // reconstruyera el texto desde la lista guardada en cada pulsación, la coma desaparecería
    // en cuanto se teclea y no se podría separar nada.
    // Sin `key`: reinicializarse con la lista guardada es lo que mezclaba el texto mientras se
    // escribía (ver el comentario de los campos de arriba). Se toma el valor una vez al entrar
    // en la pantalla y a partir de ahí manda lo que teclea el usuario.
    var text by rememberSaveable { mutableStateOf(interests.joinToString(", ")) }
    // Cuántos se han escrito, no cuántos quedaron guardados: si el usuario escribe seis y solo
    // se guardan cinco, el contador tiene que enseñar el choque (6/5 en rojo), no fingir que
    // todo fue bien. El recorte silencioso era justo el problema.
    val escritos = text.split(",").map(String::trim).filter(String::isNotEmpty).size
    TextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.split(",").map(String::trim).filter(String::isNotEmpty))
        },
        label = { Text("Intereses, separados por comas") },
        placeholder = { Text("cine, senderismo, cocinar") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 2,
        supportingText = {
            Counter(
                escritos,
                MyProfilePrefs.MAX_INTERESTS,
                sufijo = if (escritos > MyProfilePrefs.MAX_INTERESTS) " · solo se guardan los primeros" else "",
            )
        },
    )
}

/**
 * Contador de un campo con tope.
 *
 * Existe porque el recorte era **mudo**: `sanitize` cortaba al guardar y, como el campo lee el
 * valor ya saneado, al llegar al tope los caracteres simplemente dejaban de aparecer. Sin aviso.
 * Se pone en rojo al pasarse en vez de solo al llegar, para que el aviso llegue **mientras** se
 * escribe de más y no cuando ya se perdió texto.
 */
@Composable
private fun Counter(actual: Int, maximo: Int, sufijo: String = "") {
    val pasado = actual > maximo
    Text(
        "$actual/$maximo$sufijo",
        style = MaterialTheme.typography.bodySmall,
        color = if (pasado) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


/**
 * Selector de rasgos: el usuario toca en español y el campo se rellena con lo que el parser
 * entiende.
 *
 * # Por qué existe
 *
 * `AttributeParser` sólo lee **inglés** y exige contexto — «brown» suelto no asigna nada, hace
 * falta «brown hair». Delante de alguien que habla español, un campo de texto libre es un juego
 * de adivinanzas donde casi todo lo que escriba se ignora **en silencio**, que es el peor fallo
 * posible: no distingue "no te he entendido" de "eso ya era el valor por defecto".
 *
 * Traducir el parser habría sido peor: el vocabulario vive por duplicado en Kotlin y Python —
 * tienen que dibujar lo mismo— así que añadir un segundo idioma son cuatro sitios que se
 * desincronizan. Aquí el idioma se resuelve en la interfaz y el contrato con el parser no cambia.
 *
 * Escribir a mano sigue funcionando para quien conozca el vocabulario; esto es un atajo, no un
 * sustituto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarVocabularyPicker(onPick: (String) -> Unit) {
    Column {
        Text(
            "Toca los rasgos que quieras. Se van sumando a la descripción, y lo que no elijas " +
                "queda como está.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        AvatarVocabulary.groups.forEach { grupo ->
            Text(
                grupo.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grupo.options.forEach { opcion ->
                    SuggestionChip(
                        onClick = { onPick(opcion.term) },
                        label = { Text(opcion.label, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }
    }
}
