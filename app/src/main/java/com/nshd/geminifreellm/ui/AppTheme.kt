package com.nshd.geminifreellm.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

object Design {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val page = 20.dp
    val large = 24.dp
    val touch = 48.dp
    val icon = 24.dp
    val readingWidth = 760.dp
    val card = RoundedCornerShape(16.dp)
    val composer = RoundedCornerShape(28.dp)
    const val fastMotion = 150
    const val normalMotion = 220
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED") }

private val LightColors = lightColorScheme(
    primary = Color(0xFF783EC8), onPrimary = Color.White,
    primaryContainer = Color(0xFFEFE4FF), onPrimaryContainer = Color(0xFF3F166F),
    secondary = Color(0xFF635A70), secondaryContainer = Color(0xFFF0EAF5), onSecondaryContainer = Color(0xFF342C40),
    background = Color(0xFFFCFBFD), onBackground = Color(0xFF242126),
    surface = Color(0xFFFCFBFD), onSurface = Color(0xFF242126),
    surfaceContainer = Color(0xFFF1EFF3), surfaceContainerLow = Color(0xFFF6F4F7),
    surfaceContainerHigh = Color(0xFFEAE7ED), surfaceContainerHighest = Color(0xFFE3DFE7),
    onSurfaceVariant = Color(0xFF635D69), outline = Color(0xFF7A7280), outlineVariant = Color(0xFFDED9E3)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD3B5FF), onPrimary = Color(0xFF3E176A),
    primaryContainer = Color(0xFF503074), onPrimaryContainer = Color(0xFFF0E3FF),
    secondary = Color(0xFFCBC1D4), secondaryContainer = Color(0xFF39333F), onSecondaryContainer = Color(0xFFEAE0F2),
    background = Color(0xFF171619), onBackground = Color(0xFFECE9EF),
    surface = Color(0xFF171619), onSurface = Color(0xFFECE9EF),
    surfaceContainer = Color(0xFF242227), surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainerHigh = Color(0xFF2E2B33), surfaceContainerHighest = Color(0xFF38343D),
    onSurfaceVariant = Color(0xFFC4BDC9), outline = Color(0xFF978D9F), outlineVariant = Color(0xFF49424F)
)

@Composable
fun GeminiTheme(mode: ThemeMode, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.AMOLED || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val scheme = when {
        mode == ThemeMode.AMOLED -> DarkColors.copy(background = Color.Black, surface = Color.Black,
            surfaceContainerLow = Color(0xFF0D0C0F), surfaceContainer = Color(0xFF18161B))
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
    MaterialTheme(colorScheme = scheme,
        typography = Typography(
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = Design.card, large = RoundedCornerShape(24.dp)),
        content = content)
    }
}
