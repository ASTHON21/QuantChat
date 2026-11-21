package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val QuantDarkColorScheme = darkColorScheme(
    primary = QuantPrimary,
    onPrimary = QuantOnPrimary,
    primaryContainer = QuantPrimaryContainer,
    onPrimaryContainer = QuantOutline,
    secondary = QuantEmerald,
    onSecondary = QuantOnEmerald,
    secondaryContainer = QuantEmeraldContainer,
    onSecondaryContainer = QuantOnEmeraldContainer,
    tertiary = QuantRose,
    onTertiary = QuantOnError,
    tertiaryContainer = QuantRoseContainer,
    onTertiaryContainer = QuantOnRoseContainer,
    background = QuantSurface,
    onBackground = QuantOnSurface,
    surface = QuantSurface,
    onSurface = QuantOnSurface,
    surfaceVariant = QuantSurfaceHighest,
    onSurfaceVariant = QuantOnSurfaceVariant,
    surfaceContainerLowest = QuantSurfaceLowest,
    surfaceContainerLow = QuantSurfaceLow,
    surfaceContainer = QuantSurfaceContainer,
    surfaceContainerHigh = QuantSurfaceHigh,
    surfaceContainerHighest = QuantSurfaceHighest,
    outline = QuantOutline,
    outlineVariant = QuantOutlineVariant,
    error = QuantError,
    onError = QuantOnError,
    errorContainer = QuantErrorContainer
)

private val QuantShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

@Composable
fun QuantChatTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = QuantDarkColorScheme,
        typography = QuantTypography,
        shapes = QuantShapes,
        content = content
    )
}
