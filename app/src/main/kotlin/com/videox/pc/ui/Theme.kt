package com.videox.pc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = VideoXPurple,
    onPrimary = VideoXTextPrimary,
    secondary = VideoXPurpleSoft,
    onSecondary = VideoXTextPrimary,
    background = VideoXBackground,
    onBackground = VideoXTextPrimary,
    surface = VideoXBackgroundSecondary,
    onSurface = VideoXTextPrimary,
    surfaceVariant = VideoXCard,
    onSurfaceVariant = VideoXTextSecondary,
    error = VideoXError,
    onError = VideoXTextPrimary,
    outline = VideoXBorder
)

private val LightScheme = lightColorScheme(
    primary = VideoXPurple,
    onPrimary = VideoXTextPrimary,
    secondary = VideoXPurpleDeep,
    onSecondary = VideoXTextPrimary,
    background = VideoXLightBackground,
    onBackground = VideoXLightTextPrimary,
    surface = VideoXLightBackgroundSecondary,
    onSurface = VideoXLightTextPrimary,
    surfaceVariant = VideoXLightCard,
    onSurfaceVariant = VideoXLightTextSecondary,
    error = VideoXError,
    onError = VideoXTextPrimary,
    outline = VideoXLightBorder
)

@Composable
fun VideoXTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
