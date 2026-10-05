package com.nshd.geminifreellm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

data class AppColors(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val userBubble: Color,
    val error: Color,
    val scrim: Color
)

private val DarkColors = AppColors(
    background = Color(0xFF212121),
    surface = Color(0xFF171717),
    elevated = Color(0xFF2F2F2F),
    text = Color(0xFFECECEC),
    muted = Color(0xFFB4B4B4),
    border = Color(0xFF454545),
    accent = Color(0xFF10A37F),
    accentSoft = Color(0xFF1E3B34),
    userBubble = Color(0xFF2F2F2F),
    error = Color(0xFFF87171),
    scrim = Color(0x99000000)
)

private val AmoledColors = DarkColors.copy(
    background = Color.Black,
    surface = Color(0xFF0B0B0B),
    elevated = Color(0xFF191919),
    userBubble = Color(0xFF1A1A1A)
)

private val LightColors = AppColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F7F8),
    elevated = Color(0xFFFFFFFF),
    text = Color(0xFF202123),
    muted = Color(0xFF6B6B6B),
    border = Color(0xFFD9D9E0),
    accent = Color(0xFF0D8F72),
    accentSoft = Color(0xFFE3F4EF),
    userBubble = Color(0xFFF1F1F1),
    error = Color(0xFFB42318),
    scrim = Color(0x66000000)
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

@Composable
fun AiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val colors = when {
        mode == ThemeMode.AMOLED -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalAppColors provides colors, content = content)
}
