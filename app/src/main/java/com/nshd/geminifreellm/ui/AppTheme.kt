package com.nshd.geminifreellm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    val auroraBubble = RoundedCornerShape(22.dp)
    val auroraComposer = RoundedCornerShape(30.dp)
    const val fastMotion = 150
    const val normalMotion = 220
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Blue Light"), DARK("Blue Dark"), AMOLED("AMOLED"),
    VIOLET("Classic Violet"), CHATGPT("ChatGPT")
}

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

/** Original violet palette retained as a first-class, switchable appearance. */
private val VioletColors = lightColorScheme(
    primary = Color(0xFF783EC8), onPrimary = Color.White,
    primaryContainer = Color(0xFFEFE4FF), onPrimaryContainer = Color(0xFF3F166F),
    secondary = Color(0xFF635A70), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0EAF5), onSecondaryContainer = Color(0xFF342C40),
    tertiary = Color(0xFF7662A8), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE5FF), onTertiaryContainer = Color(0xFF2C2058),
    background = Color(0xFFFCFBFD), onBackground = Color(0xFF242126),
    surface = Color(0xFFFCFBFD), onSurface = Color(0xFF242126),
    surfaceVariant = Color(0xFFEFEAF3), onSurfaceVariant = Color(0xFF635D69),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F4F7),
    surfaceContainer = Color(0xFFF1EFF3), surfaceContainerHigh = Color(0xFFEAE7ED),
    surfaceContainerHighest = Color(0xFFE3DFE7),
    outline = Color(0xFF7A7280), outlineVariant = Color(0xFFDED9E3),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

/** Familiar ChatGPT-inspired green accent with restrained neutral surfaces. */
private val ChatGptColors = lightColorScheme(
    primary = Color(0xFF16856B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F3E8), onPrimaryContainer = Color(0xFF00382B),
    secondary = Color(0xFF53645D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0ECE5), onSecondaryContainer = Color(0xFF172F25),
    tertiary = Color(0xFF426B81), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9EDF8), onTertiaryContainer = Color(0xFF0B3447),
    background = Color(0xFFF7F8F6), onBackground = Color(0xFF1D2420),
    surface = Color(0xFFFBFCFA), onSurface = Color(0xFF1D2420),
    surfaceVariant = Color(0xFFE7ECE8), onSurfaceVariant = Color(0xFF53615A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F1),
    surfaceContainer = Color(0xFFEAEFEB), surfaceContainerHigh = Color(0xFFE1E8E2),
    surfaceContainerHighest = Color(0xFFD8E1DA),
    outline = Color(0xFF74837A), outlineVariant = Color(0xFFD2DDD4),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

@Composable
fun GeminiTheme(mode: ThemeMode, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.AMOLED || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val scheme = when (mode) {
        ThemeMode.VIOLET -> VioletColors
        ThemeMode.CHATGPT -> ChatGptColors
        ThemeMode.AMOLED -> DarkColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080D10),
            surfaceContainer = Color(0xFF11191D)
        )
        ThemeMode.DARK -> DarkColors
        ThemeMode.SYSTEM -> if (dark) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
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

/** Aurora chat-style tokens, resolved against the active color scheme (all 6 themes). */
class AuroraTokens(
    val glassFill: Color,
    val glassBorder: Brush,
    val userGrad: Brush,
    val sparkleColors: List<Color>,
    val sparkle: Brush,
    val composerGlow: Color,
)

@Composable
fun rememberAuroraTokens(): AuroraTokens {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        val dark = scheme.background.luminance() < 0.5f
        val sparkleColors = if (dark) listOf(Color(0xFFB9E9FF), Color(0xFFD9C6FF))
            else listOf(Color(0xFF3D9BD1), Color(0xFF7A68D8))
        AuroraTokens(
            glassFill = if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFF0B2530).copy(alpha = 0.05f),
            glassBorder = Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.35f), scheme.tertiary.copy(alpha = 0.35f))),
            userGrad = Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.55f), if (dark) Color(0xFF2B7A94) else Color(0xFF0E6E8C))),
            sparkleColors = sparkleColors,
            sparkle = Brush.linearGradient(sparkleColors),
            composerGlow = scheme.primary.copy(alpha = 0.25f),
        )
    }
}
