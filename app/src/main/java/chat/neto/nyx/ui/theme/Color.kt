package chat.neto.nyx.ui.theme

import androidx.compose.ui.graphics.Color

// Identidad Nyx: paleta Material 3 completa generada desde el verde-teal semilla
// #006A60 ("privacidad"). Tonos según el sistema de color M3 (Material Theme Builder);
// si se cambia la semilla, regenerar TODOS los roles, no solo primary.

// Claro
val md_light_primary = Color(0xFF006A60)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFF74F8E5)
val md_light_onPrimaryContainer = Color(0xFF00201C)
val md_light_secondary = Color(0xFF4A635F)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFCCE8E2)
val md_light_onSecondaryContainer = Color(0xFF051F1C)
val md_light_tertiary = Color(0xFF456179)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFFCCE5FF)
val md_light_onTertiaryContainer = Color(0xFF001E31)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)
val md_light_background = Color(0xFFF4FBF8)
val md_light_onBackground = Color(0xFF161D1C)
val md_light_surface = Color(0xFFF4FBF8)
val md_light_onSurface = Color(0xFF161D1C)
val md_light_surfaceVariant = Color(0xFFDAE5E1)
val md_light_onSurfaceVariant = Color(0xFF3F4947)
val md_light_outline = Color(0xFF6F7977)
val md_light_outlineVariant = Color(0xFFBEC9C6)
val md_light_inverseSurface = Color(0xFF2B3230)
val md_light_inverseOnSurface = Color(0xFFECF2EF)
val md_light_inversePrimary = Color(0xFF53DBC9)
val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_light_surfaceContainerLow = Color(0xFFEFF5F2)
val md_light_surfaceContainer = Color(0xFFE9EFEC)
val md_light_surfaceContainerHigh = Color(0xFFE3EAE7)
val md_light_surfaceContainerHighest = Color(0xFFDDE4E1)

// Oscuro
val md_dark_primary = Color(0xFF53DBC9)
val md_dark_onPrimary = Color(0xFF003731)
val md_dark_primaryContainer = Color(0xFF005048)
val md_dark_onPrimaryContainer = Color(0xFF74F8E5)
val md_dark_secondary = Color(0xFFB1CCC6)
val md_dark_onSecondary = Color(0xFF1C3531)
val md_dark_secondaryContainer = Color(0xFF334B47)
val md_dark_onSecondaryContainer = Color(0xFFCCE8E2)
val md_dark_tertiary = Color(0xFFADCAE6)
val md_dark_onTertiary = Color(0xFF153349)
val md_dark_tertiaryContainer = Color(0xFF2D4961)
val md_dark_onTertiaryContainer = Color(0xFFCCE5FF)
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = Color(0xFF0E1513)
val md_dark_onBackground = Color(0xFFDDE4E1)
val md_dark_surface = Color(0xFF0E1513)
val md_dark_onSurface = Color(0xFFDDE4E1)
val md_dark_surfaceVariant = Color(0xFF3F4947)
val md_dark_onSurfaceVariant = Color(0xFFBEC9C6)
val md_dark_outline = Color(0xFF899390)
val md_dark_outlineVariant = Color(0xFF3F4947)
val md_dark_inverseSurface = Color(0xFFDDE4E1)
val md_dark_inverseOnSurface = Color(0xFF2B3230)
val md_dark_inversePrimary = Color(0xFF006A60)
val md_dark_surfaceContainerLowest = Color(0xFF090F0E)
val md_dark_surfaceContainerLow = Color(0xFF161D1C)
val md_dark_surfaceContainer = Color(0xFF1A2120)
val md_dark_surfaceContainerHigh = Color(0xFF252B2A)
val md_dark_surfaceContainerHighest = Color(0xFF303635)

/**
 * Colores de avatar por contacto (inicial sobre círculo). Saturados y con contraste
 * suficiente para texto blanco tanto en tema claro como oscuro; se elige uno estable
 * por hash del PeerID.
 */
val AvatarColors = listOf(
    Color(0xFF00897B), // teal (hermano de la marca)
    Color(0xFF43A047), // verde
    Color(0xFF039BE5), // azul claro
    Color(0xFF3949AB), // índigo
    Color(0xFF8E24AA), // púrpura
    Color(0xFFD81B60), // rosa
    Color(0xFFF4511E), // naranja
    Color(0xFF6D4C41), // marrón
)
