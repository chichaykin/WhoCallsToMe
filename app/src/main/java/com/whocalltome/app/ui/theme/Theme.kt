package com.whocalltome.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.whocalltome.app.data.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    secondary = Color(0xFF53688C),
    tertiary = Color(0xFF735572),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    secondary = Color(0xFFBAC6E6),
    tertiary = Color(0xFFE2BBDD),
)

@Composable
fun WhoCallToMeTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        DarkColors
    } else {
        LightColors
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        window.statusBarColor = colors.background.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = colors.background.toArgb()
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
    }
    MaterialTheme(colorScheme = colors, content = content)
}
