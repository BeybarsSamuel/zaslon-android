package com.zaslon.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ZaslonBgDeep = Color(0xFF08101F)
val ZaslonBgCard = Color(0xFF0D182E)
val ZaslonBgInput = Color(0xFF12203D)
val ZaslonBorder = Color(0xFF1D2F54)
val ZaslonTextPrimary = Color(0xFFE6ECFA)
val ZaslonTextMuted = Color(0xFF8CA0C6)
val ZaslonAccentBlue = Color(0xFF60A5FA)
val ZaslonEmerald = Color(0xFF34D399)
val ZaslonAmber = Color(0xFFFBBF24)
val ZaslonDangerRed = Color(0xFFF87171)
val ZaslonPurpleTor = Color(0xFFC084FC)

private val ZaslonDarkColorScheme = darkColorScheme(
    primary = ZaslonAccentBlue,
    onPrimary = Color(0xFF040914),
    secondary = ZaslonEmerald,
    onSecondary = Color(0xFF040914),
    tertiary = ZaslonPurpleTor,
    background = ZaslonBgDeep,
    onBackground = ZaslonTextPrimary,
    surface = ZaslonBgCard,
    onSurface = ZaslonTextPrimary,
    surfaceVariant = ZaslonBgInput,
    onSurfaceVariant = ZaslonTextMuted,
    error = ZaslonDangerRed
)

@Composable
fun ZaslonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZaslonDarkColorScheme,
        content = content
    )
}
