package dev.zissha.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Purple80,
    onPrimary = Color(0xFF2A1160),
    primaryContainer = Color(0xFF4527A0),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Cyan80,
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF004C69),
    onSecondaryContainer = Color(0xFFC4E8FF),
    tertiary = PurpleGrey80,
    onTertiary = Color(0xFF332D41),
    background = Color(0xFF101218),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF101218),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF48454E),
    onSurfaceVariant = Color(0xFFC9C5D0),
    outline = Color(0xFF938F99),
    error = DangerDark,
    onError = Color(0xFF3A0A0A),
)

private val LightColors = lightColorScheme(
    primary = Purple40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Cyan40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC4E8FF),
    onSecondaryContainer = Color(0xFF00344A),
    tertiary = PurpleGrey40,
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    error = DangerLight,
    onError = Color(0xFFFFFFFF),
)

/**
 * Product-semantic colors that are not part of the Material role set: the output
 * mode palette (LIVE/FREEZE/PRIVACY), health states and their readable ink colors.
 */
@Immutable
data class SemanticColors(
    val live: Color,
    val onLive: Color,
    val freeze: Color,
    val onFreeze: Color,
    val privacy: Color,
    val onPrivacy: Color,
    val ok: Color,
    val warning: Color,
    val danger: Color,
    /** Ink that stays legible on any of the saturated fills above. */
    val onFill: Color,
)

private val DarkSemantics = SemanticColors(
    live = LiveDark,
    onLive = InkOnLightFill,
    freeze = FreezeDark,
    onFreeze = InkOnDarkFill,
    privacy = PrivacyDark,
    onPrivacy = InkOnLightFill,
    ok = OkDark,
    warning = WarnDark,
    danger = DangerDark,
    onFill = InkOnDarkFill,
)

private val LightSemantics = SemanticColors(
    live = LiveLight,
    onLive = InkOnLightFill,
    freeze = FreezeLight,
    onFreeze = InkOnLightFill,
    privacy = PrivacyLight,
    onPrivacy = InkOnLightFill,
    ok = OkLight,
    warning = WarnLight,
    danger = DangerLight,
    onFill = InkOnLightFill,
)

val LocalSemanticColors = staticCompositionLocalOf { DarkSemantics }

/** Accessor for the product-semantic palette: `ZisshaTheme.semantic`. */
object ZisshaTheme {
    val semantic: SemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
}

/**
 * ZisshaVR design system entry point. Follows the system light/dark setting.
 * Shared by the main app (:app) and the BLE remote (:remote) so both look and
 * behave like one product.
 */
@Composable
fun ZisshaVRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val semantics = if (darkTheme) DarkSemantics else LightSemantics
    CompositionLocalProvider(LocalSemanticColors provides semantics) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
