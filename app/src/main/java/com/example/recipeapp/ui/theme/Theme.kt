package com.example.recipeapp.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF79CCBF),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFFF7DCA8),
    surface = Color(0xFFF7DCA8),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF79CCBF),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onPrimary = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
)

@Composable
fun RecipeAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val window = (LocalContext.current as? Activity)?.window
    window?.let { safeWindow ->
        WindowCompat.getInsetsController(safeWindow, safeWindow.decorView)?.apply {
            val statusBarColor = Color(0xFF704F3C) // Цвет статусбара после загрузки
            safeWindow.statusBarColor = AndroidColor.argb(
                (statusBarColor.alpha * 255).toInt(),
                (statusBarColor.red * 255).toInt(),
                (statusBarColor.green * 255).toInt(),
                (statusBarColor.blue * 255).toInt()
            )
            // Убираем управление иконками, предоставляем системе
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) { // API 23+
                safeWindow.decorView.systemUiVisibility = 0 // Сброс флагов
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}