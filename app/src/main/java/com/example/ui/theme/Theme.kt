package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Stored in logimind_prefs under "theme_mode"; SYSTEM follows the phone's dark mode.
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val DarkColorScheme = darkColorScheme(
    primary = DarkBlue,
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = DarkTextMuted,
    onSecondary = Color(0xFF223040),
    secondaryContainer = Color(0xFF2C3A4B),
    onSecondaryContainer = Color(0xFFD6E3F4),
    tertiary = DarkTeal,
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFF7EF8E2),
    background = DarkBackgroundSlate,
    onBackground = DarkTextLight,
    surface = DarkSurface,
    onSurface = DarkTextLight,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF181C22),
    surfaceContainer = Color(0xFF1C2026),
    surfaceContainerHigh = Color(0xFF232830),
    surfaceContainerHighest = Color(0xFF2A2F37),
    outline = DarkOutline,
    outlineVariant = DarkBorder,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = ProfessionalBlue,
    onPrimary = Color.White,
    primaryContainer = ProfessionalBlueContainer,
    onPrimaryContainer = OnProfessionalBlueContainer,
    secondary = TextGray,
    onSecondary = Color.White,
    secondaryContainer = InnerNeutralLight,
    onSecondaryContainer = TextDark,
    tertiary = MedicalTeal,
    onTertiary = Color.White,
    tertiaryContainer = MedicalTealContainer,
    onTertiaryContainer = OnMedicalTealContainer,
    background = BackgroundSlate,
    onBackground = TextDark,
    surface = Color.White,
    onSurface = TextDark,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FBFE),
    surfaceContainer = Color(0xFFF3F6FA),
    surfaceContainerHigh = Color(0xFFEEF2F7),
    surfaceContainerHighest = Color(0xFFE8ECF2),
    outline = BorderMedium,
    outlineVariant = BorderLight,
    surfaceVariant = InnerNeutralLight,
    onSurfaceVariant = TextDark,
    error = ActionRed,
    onError = Color.White,
    errorContainer = ActionRedContainer,
    onErrorContainer = Color(0xFF410002)
)

// Brand colours outside the Material scheme, with light and dark variants.
@Immutable
data class BrandColors(
    val premiumContainer: Color,
    val onPremiumContainer: Color,
    val tilePatients: Color,
    val tileNewEntry: Color,
    val tileTemplates: Color,
    val tileSync: Color,
    val tileProcedures: Color,
    val headerDivider: Color
)

private val LightBrandColors = BrandColors(
    premiumContainer = Color(0xFFFFF3E0),
    onPremiumContainer = Color(0xFFB45309),
    tilePatients = Color(0xFF1E88E5),
    tileNewEntry = Color(0xFF2E9D4F),
    tileTemplates = Color(0xFFF08C00),
    tileSync = Color(0xFF8E44AD),
    tileProcedures = Color(0xFFD6336C),
    headerDivider = Color(0xFFE4E8EF)
)

private val DarkBrandColors = BrandColors(
    premiumContainer = Color(0xFF3A2A12),
    onPremiumContainer = Color(0xFFFFC46B),
    tilePatients = Color(0xFF7CB9F4),
    tileNewEntry = Color(0xFF7BD19A),
    tileTemplates = Color(0xFFFFBE5C),
    tileSync = Color(0xFFCFA3E6),
    tileProcedures = Color(0xFFF58FB3),
    headerDivider = Color(0xFF262B33)
)

private val LocalBrandColors = staticCompositionLocalOf { LightBrandColors }

object BrandTheme {
    val colors: BrandColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBrandColors.current
}

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // We disable dynamicColor by default to guarantee our clinical brand colors are strictly applied
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalBrandColors provides if (darkTheme) DarkBrandColors else LightBrandColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

fun isDarkFor(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
