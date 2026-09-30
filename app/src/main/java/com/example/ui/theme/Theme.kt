package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkBlue,
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = DarkTextLight,
    background = DarkBackgroundSlate,
    onBackground = DarkTextLight,
    surface = DarkSurface,
    onSurface = DarkTextLight,
    outline = DarkBorder,
    surfaceVariant = Color(0xFF262A30),
    onSurfaceVariant = Color(0xFFC3C6CF)
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
    background = BackgroundSlate,
    onBackground = TextDark,
    surface = Color.White,
    onSurface = TextDark,
    outline = BorderMedium,
    surfaceVariant = InnerNeutralLight,
    onSurfaceVariant = TextDark,
    error = ActionRed,
    onError = Color.White,
    errorContainer = ActionRedContainer,
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // We disable dynamicColor by default to guarantee our clinical brand colors are strictly applied
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
