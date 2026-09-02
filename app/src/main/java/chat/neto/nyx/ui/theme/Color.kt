package chat.neto.nyx.ui.theme

import androidx.compose.ui.graphics.Color

// Identidad Nyx (fase 5 del plan): noche/misterio/intimidad — primario índigo, secundario
// ciruela, terciario dorado rosado, neutros con un velo índigo. La paleta NO está elegida
// valor a valor: se parte de la paleta base M3 de Google (semilla #6750A4, generada con
// HCT, contrastes ya garantizados) y se rota SOLO el matiz en CIELAB LCh (primario y
// neutral-variant −10°, secundario +25°, terciario +25°; neutros al matiz del primario con
// croma mínimo; error intacto), conservando L* y croma — que es lo que sostiene el
// contraste. Si se cambia la dirección, regenerar TODOS los roles con
// el script (rotaciones documentadas aquí), no retocar roles sueltos.

// Claro
val md_light_primary = Color(0xFF4E56AB)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFFE3DFFF)
val md_light_onPrimaryContainer = Color(0xFF001066)
val md_light_secondary = Color(0xFF6C586A)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFF4DBF0)
val md_light_onSecondaryContainer = Color(0xFF261626)
val md_light_tertiary = Color(0xFF7F5352)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFFFFD8D7)
val md_light_onTertiaryContainer = Color(0xFF321213)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)
val md_light_background = Color(0xFFF9F8FF)
val md_light_onBackground = Color(0xFF1C1B1F)
val md_light_surface = Color(0xFFF9F8FF)
val md_light_onSurface = Color(0xFF1C1B1F)
val md_light_surfaceVariant = Color(0xFFE4E1ED)
val md_light_onSurfaceVariant = Color(0xFF474650)
val md_light_outline = Color(0xFF77757F)
val md_light_outlineVariant = Color(0xFFC7C5D1)
val md_light_inverseSurface = Color(0xFF303034)
val md_light_inverseOnSurface = Color(0xFFF1F0F6)
val md_light_inversePrimary = Color(0xFFC0C0FF)
val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_light_surfaceContainerLow = Color(0xFFF4F3FC)
val md_light_surfaceContainer = Color(0xFFEFEEF9)
val md_light_surfaceContainerHigh = Color(0xFFE8E7F2)
val md_light_surfaceContainerHighest = Color(0xFFE2E1EB)

// Oscuro
val md_dark_primary = Color(0xFFC0C0FF)
val md_dark_onPrimary = Color(0xFF13277A)
val md_dark_primaryContainer = Color(0xFF333E93)
val md_dark_onPrimaryContainer = Color(0xFFE3DFFF)
val md_dark_secondary = Color(0xFFD8BFD4)
val md_dark_onSecondary = Color(0xFF3D2A3B)
val md_dark_secondaryContainer = Color(0xFF544152)
val md_dark_onSecondaryContainer = Color(0xFFF4DBF0)
val md_dark_tertiary = Color(0xFFF2B9B7)
val md_dark_onTertiary = Color(0xFF4A2526)
val md_dark_tertiaryContainer = Color(0xFF643C3B)
val md_dark_onTertiaryContainer = Color(0xFFFFD8D7)
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = Color(0xFF131218)
val md_dark_onBackground = Color(0xFFE2E2E9)
val md_dark_surface = Color(0xFF131218)
val md_dark_onSurface = Color(0xFFE2E2E9)
val md_dark_surfaceVariant = Color(0xFF474650)
val md_dark_onSurfaceVariant = Color(0xFFC7C5D1)
val md_dark_outline = Color(0xFF91909A)
val md_dark_outlineVariant = Color(0xFF474650)
val md_dark_inverseSurface = Color(0xFFE2E2E9)
val md_dark_inverseOnSurface = Color(0xFF303036)
val md_dark_inversePrimary = Color(0xFF4E56AB)
val md_dark_surfaceContainerLowest = Color(0xFF0E0D14)
val md_dark_surfaceContainerLow = Color(0xFF1C1B21)
val md_dark_surfaceContainer = Color(0xFF201F26)
val md_dark_surfaceContainerHigh = Color(0xFF2A2930)
val md_dark_surfaceContainerHighest = Color(0xFF35343B)

/**
 * Colores de avatar por contacto (inicial sobre círculo). Saturados y con contraste
 * suficiente para texto blanco tanto en tema claro como oscuro; se elige uno estable
 * por hash del PeerID.
 */
val AvatarColors = listOf(
    Color(0xFF5E35B1), // violeta profundo (hermano de la marca nueva)
    Color(0xFF43A047), // verde
    Color(0xFF039BE5), // azul claro
    Color(0xFF3949AB), // índigo
    Color(0xFF8E24AA), // púrpura
    Color(0xFFD81B60), // rosa
    Color(0xFFF4511E), // naranja
    Color(0xFF6D4C41), // marrón
)
