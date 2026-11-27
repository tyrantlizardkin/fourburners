package app.fourburners.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutrals (§11)
val Background = Color(0xFF1E2529)
val Surface  = Color(0xFF283136)
val Hairline = Color(0xFF3A474F)
val Foreground = Color(0xFFE9EDEA)
val Muted    = Color(0xFF9FB0AE)

// Accents — solid / tint (20% over Surface)
val Coral  = Color(0xFFF0A08C); val CoralTint  = Color(0xFF504747)
val Periwinkle = Color(0xFFA3B8F0); val PeriwinkleTint = Color(0xFF414C5B)
val Sage   = Color(0xFFA6D4A0); val SageTint   = Color(0xFF41524B)
val Orchid = Color(0xFFD0A6E6); val OrchidTint = Color(0xFF4A4859)
val Aqua   = Color(0xFF8FD0CF); val AquaTint   = Color(0xFF3D5155)
val Amber  = Color(0xFFEBC38A); val AmberTint  = Color(0xFF4F4E47)
val Rose   = Color(0xFFEFA3BF); val RoseTint   = Color(0xFF504851)

// Recipe colours by index
val RecipeColors = listOf(Coral, Periwinkle, Sage, Orchid)

private val DarkColorPalette = darkColorScheme(
    background = Background,
    surface = Surface,
    primary = Aqua,
    onPrimary = Background,
    secondary = Aqua,
    onSecondary = Background,
    error = Rose,
    onError = Background,
)

@Composable
fun FourBurnersTheme(
    dark: Boolean = true, // v1 is dark-only
    content: @Composable () -> Unit
) {
    val colors = DarkColorPalette

    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
