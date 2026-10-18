package com.nshd.geminifreellm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Shared design tokens. Keep layout and interaction dimensions centralized so
 * chat, settings, dialogs and assistant surfaces stay visually consistent.
 */
object Design {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val page = 20.dp
    val large = 24.dp
    val touch = 48.dp
    val icon = 24.dp
    val readingWidth = 760.dp
    val card = RoundedCornerShape(18.dp)
    val composer = RoundedCornerShape(28.dp)
    const val fastMotion = 150
    const val normalMotion = 220
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED") }

/** Calm, high-contrast light palette with a cool blue/cyan identity. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF176B87), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F2FA), onPrimaryContainer = Color(0xFF073B4C),
    secondary = Color(0xFF526A73), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F0F3), onSecondaryContainer = Color(0xFF1B343B),
    tertiary = Color(0xFF6258A5), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAE6FF), onTertiaryContainer = Color(0xFF29215F),
    background = Color(0xFFF8FBFC), onBackground = Color(0xFF172126),
    surface = Color(0xFFFCFEFF), onSurface = Color(0xFF172126),
    surfaceVariant = Color(0xFFE9F0F3), onSurfaceVariant = Color(0xFF4D6068),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F8FA),
    surfaceContainer = Color(0xFFECF3F6),
    surfaceContainerHigh = Color(0xFFE4EDF1),
    surfaceContainerHighest = Color(0xFFDCE7EC),
    outline = Color(0xFF71858D), outlineVariant = Color(0xFFD2E0E5),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD8F0), onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF164E60), onPrimaryContainer = Color(0xFFC4F0FF),
    secondary = Color(0xFFB7CBD2), onSecondary = Color(0xFF22343A),
    secondaryContainer = Color(0xFF354950), onSecondaryContainer = Color(0xFFD3E7ED),
    tertiary = Color(0xFFC8BEFF), onTertiary = Color(0xFF33296C),
    tertiaryContainer = Color(0xFF4A4184), onTertiaryContainer = Color(0xFFE7E0FF),
    background = Color(0xFF10191D), onBackground = Color(0xFFE0E8EB),
    surface = Color(0xFF10191D), onSurface = Color(0xFFE0E8EB),
    surfaceVariant = Color(0xFF39494F), onSurfaceVariant = Color(0xFFB9C9CE),
    surfaceContainerLowest = Color(0xFF0B1215),
    surfaceContainerLow = Color(0xFF172226),
    surfaceContainer = Color(0xFF1B282D),
    surfaceContainerHigh = Color(0xFF25343A),
    surfaceContainerHighest = Color(0xFF304148),
    outline = Color(0xFF84969C), outlineVariant = Color(0xFF3E5056),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun GeminiTheme(mode: ThemeMode, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.AMOLED || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val scheme = when {
        mode == ThemeMode.AMOLED -> DarkColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080D10),
            surfaceContainer = Color(0xFF11191D)
        )
        dark -> DarkColors
        else -> LightColors
    }
    val activity = LocalActivity.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
                headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
                titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
                titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            ),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(10.dp),
                medium = Design.card,
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(32.dp)
            ),
            content = content
        )
    }
}
