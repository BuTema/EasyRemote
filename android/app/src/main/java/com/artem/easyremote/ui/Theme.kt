package com.artem.easyremote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F5FA8),
    secondaryContainer = Color(0xFFDCE7F5),
    onSecondaryContainer = Color(0xFF14283D),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF331E00),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CCBFF),
    secondaryContainer = Color(0xFF2B3F55),
    onSecondaryContainer = Color(0xFFD7E6F8),
    tertiaryContainer = Color(0xFF5B3A00),
    onTertiaryContainer = Color(0xFFFFDDB8),
)

@Composable
fun EasyRemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
