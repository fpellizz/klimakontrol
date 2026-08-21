package net.klimakontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalQuad = staticCompositionLocalOf { DarkQuad }

/** Accesso ai token del sistema: `Klima.colors.accent` ecc. */
object Klima {
    val colors: QuadColors
        @Composable @ReadOnlyComposable get() = LocalQuad.current
}

@Composable
fun KlimaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkQuad else LightQuad
    // ColorScheme minimale di Material3 (per ripple, selezione testo, default coerenti).
    val scheme = if (dark) {
        darkColorScheme(
            background = colors.bg, surface = colors.surface1,
            onBackground = colors.ink, onSurface = colors.ink,
            primary = colors.mode(net.klimakontrol.data.Mode.FREDDO).accent,
        )
    } else {
        lightColorScheme(
            background = colors.bg, surface = colors.surface1,
            onBackground = colors.ink, onSurface = colors.ink,
            primary = colors.mode(net.klimakontrol.data.Mode.FREDDO).accent,
        )
    }
    CompositionLocalProvider(LocalQuad provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
