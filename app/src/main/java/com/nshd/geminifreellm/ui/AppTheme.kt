package com.nshd.geminifreellm.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

private val LightColors=lightColorScheme(primary=Color(0xFF315FA8),onPrimary=Color.White,primaryContainer=Color(0xFFD9E5FF),onPrimaryContainer=Color(0xFF001A41),secondary=Color(0xFF585F71),secondaryContainer=Color(0xFFDDE2F9),background=Color(0xFFF9F9FF),surface=Color(0xFFF9F9FF),surfaceContainer=Color(0xFFEDEDF5))
private val DarkColors=darkColorScheme(primary=Color(0xFFAFC8FF),onPrimary=Color(0xFF062F68),primaryContainer=Color(0xFF1D477F),onPrimaryContainer=Color(0xFFD9E5FF),secondary=Color(0xFFC1C6DD),secondaryContainer=Color(0xFF404659),background=Color(0xFF101114),surface=Color(0xFF101114),surfaceContainer=Color(0xFF1D1E22))
enum class ThemeMode{SYSTEM,LIGHT,DARK}
@Composable fun GeminiTheme(mode:ThemeMode,content:@Composable()->Unit){val systemDark=isSystemInDarkTheme();val dark=when(mode){ThemeMode.SYSTEM->systemDark;ThemeMode.LIGHT->false;ThemeMode.DARK->true};val context=LocalContext.current;val dynamic=Build.VERSION.SDK_INT>=Build.VERSION_CODES.S;val scheme=when{dynamic&&dark->dynamicDarkColorScheme(context);dynamic&&!dark->dynamicLightColorScheme(context);dark->DarkColors;else->LightColors};(context as? Activity)?.let{a->a.window.statusBarColor=scheme.background.toArgbCompat();a.window.navigationBarColor=scheme.background.toArgbCompat();val c=WindowCompat.getInsetsController(a.window,a.window.decorView);c.isAppearanceLightStatusBars=!dark;c.isAppearanceLightNavigationBars=!dark};MaterialTheme(colorScheme=scheme,typography=Typography(),shapes=Shapes(small=androidx.compose.foundation.shape.RoundedCornerShape(14),medium=androidx.compose.foundation.shape.RoundedCornerShape(20),large=androidx.compose.foundation.shape.RoundedCornerShape(28)),content=content)}
private fun Color.toArgbCompat():Int=android.graphics.Color.argb((alpha*255).toInt(),(red*255).toInt(),(green*255).toInt(),(blue*255).toInt())
