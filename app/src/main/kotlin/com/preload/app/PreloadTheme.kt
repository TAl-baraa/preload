package com.preload.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TechBlueLightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF172554),
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF172033),
    surface = Color(0xFFFAFCFF),
    onSurface = Color(0xFF172033),
    surfaceDim = Color(0xFFDCE3EC),
    surfaceBright = Color(0xFFFAFCFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF0F4F8),
    surfaceContainerHigh = Color(0xFFE9EEF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF7B8798),
    outlineVariant = Color(0xFFC7D0DC),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF293241),
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = Color(0xFF8AB4FF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val TechBlueDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF123B78),
    onPrimaryContainer = Color(0xFFD8E7FF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF0D121A),
    onBackground = Color(0xFFE5EAF2),
    surface = Color(0xFF151B24),
    onSurface = Color(0xFFE5EAF2),
    surfaceDim = Color(0xFF0D121A),
    surfaceBright = Color(0xFF29313D),
    surfaceContainerLowest = Color(0xFF090E15),
    surfaceContainerLow = Color(0xFF121821),
    surfaceContainer = Color(0xFF171E28),
    surfaceContainerHigh = Color(0xFF1D2530),
    surfaceContainerHighest = Color(0xFF252E3A),
    surfaceVariant = Color(0xFF202936),
    onSurfaceVariant = Color(0xFFB8C2D1),
    outline = Color(0xFF8995A6),
    outlineVariant = Color(0xFF394454),
    surfaceTint = Color(0xFF8AB4FF),
    inverseSurface = Color(0xFFE5EAF2),
    inverseOnSurface = Color(0xFF29313D),
    inversePrimary = Color(0xFF2563EB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PreloadTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TechBlueDarkColors else TechBlueLightColors,
        content = content,
    )
}
