package com.example.bookreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PaperColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = InkSoft,
    background = Paper,
    surface = Paper,
    surfaceVariant = CoverPaper,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = InkSoft,
    outline = Rule,
    primaryContainer = CoverPaper,
    onPrimaryContainer = Ink
)

@Composable
fun BookReaderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PaperColorScheme,
        typography = Typography,
        content = content
    )
}
