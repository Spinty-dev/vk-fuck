package app.vkturn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Amnezia-inspired palette: near-black background, rectangular "slab" surfaces
 * separated by subtle hairlines, creamy peach accent for primary actions and
 * a saturated green/red for connection state.
 */
object AmneziaColors {
    val Background = Color(0xFF0E0E11)
    val Surface = Color(0xFF1C1D21)
    val SurfaceElevated = Color(0xFF23242A)
    val SurfaceHighest = Color(0xFF2B2C33)
    val Outline = Color(0xFF3A3B41)
    val OutlineSoft = Color(0xFF2A2B30)

    val Primary = Color(0xFFFBCEB1)
    val OnPrimary = Color(0xFF1C1D21)
    val PrimaryContainer = Color(0xFF2A261F)

    val Connected = Color(0xFF4ADE80)
    val Connecting = Color(0xFFFBBF24)
    val Disconnected = Color(0xFF6B6C73)
    val Error = Color(0xFFF87171)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA4A4A8)
    val TextTertiary = Color(0xFF6C6D73)
}

private val amneziaDarkScheme = darkColorScheme(
    primary = AmneziaColors.Primary,
    onPrimary = AmneziaColors.OnPrimary,
    primaryContainer = AmneziaColors.PrimaryContainer,
    onPrimaryContainer = AmneziaColors.Primary,

    secondary = AmneziaColors.Primary,
    onSecondary = AmneziaColors.OnPrimary,

    tertiary = AmneziaColors.Connected,
    onTertiary = AmneziaColors.OnPrimary,

    background = AmneziaColors.Background,
    onBackground = AmneziaColors.TextPrimary,

    surface = AmneziaColors.Surface,
    onSurface = AmneziaColors.TextPrimary,
    surfaceVariant = AmneziaColors.SurfaceElevated,
    onSurfaceVariant = AmneziaColors.TextSecondary,
    surfaceContainerHigh = AmneziaColors.SurfaceHighest,
    surfaceContainerHighest = AmneziaColors.SurfaceHighest,

    outline = AmneziaColors.Outline,
    outlineVariant = AmneziaColors.OutlineSoft,

    error = AmneziaColors.Error,
    onError = AmneziaColors.OnPrimary,
)

@Composable
fun AmneziaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = amneziaDarkScheme,
        typography = AmneziaTypography,
        shapes = AmneziaShapes,
        content = content,
    )
}
