package com.seyal.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ink {
    val green = Color(0xFF2E6B5E)
    val greenDeep = Color(0xFF1F4F45)
    val greenSoft = Color(0xFFE3EFEA)
    val amber = Color(0xFF9A6B14)
    val amberSoft = Color(0xFFFBF1DC)
    val amberText = Color(0xFF6E4A08)
    val red = Color(0xFFB4443A)
    val redSoft = Color(0xFFFCE9E6)
    val redText = Color(0xFF8A2E26)
}

private val light = lightColorScheme(
    primary = Ink.green,
    onPrimary = Color.White,
    primaryContainer = Ink.greenSoft,
    onPrimaryContainer = Ink.greenDeep,
    secondary = Ink.amber,
    secondaryContainer = Ink.amberSoft,
    onSecondaryContainer = Ink.amberText,
    error = Ink.red,
    errorContainer = Ink.redSoft,
    onErrorContainer = Ink.redText,
    background = Color(0xFFF7F5EF),
    onBackground = Color(0xFF1C1B18),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B18),
    surfaceVariant = Color(0xFFEDEAE2),
    onSurfaceVariant = Color(0xFF6F6B62),
    outline = Color(0xFFE4E0D6),
    outlineVariant = Color(0xFFEEEAE1),
)

private val dark = darkColorScheme(
    primary = Color(0xFF5DCAA5),
    onPrimary = Color(0xFF04342C),
    primaryContainer = Color(0xFF0F3A32),
    onPrimaryContainer = Color(0xFF9FE1CB),
    secondary = Color(0xFFEF9F27),
    secondaryContainer = Color(0xFF3B2A08),
    onSecondaryContainer = Color(0xFFFAC775),
    error = Color(0xFFF09595),
    errorContainer = Color(0xFF4A1B1B),
    onErrorContainer = Color(0xFFF7C1C1),
    background = Color(0xFF151412),
    onBackground = Color(0xFFECE9E1),
    surface = Color(0xFF1F1E1B),
    onSurface = Color(0xFFECE9E1),
    surfaceVariant = Color(0xFF2A2926),
    onSurfaceVariant = Color(0xFFA8A49A),
    outline = Color(0xFF3A3834),
    outlineVariant = Color(0xFF2C2B28),
)

val SeyalType = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun SeyalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = SeyalType, content = content)
}
