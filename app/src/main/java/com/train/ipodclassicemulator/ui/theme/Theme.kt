package com.train.ipodclassicemulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalIPodColors = compositionLocalOf { IPodPalettes.ClassicWhite }

/**
 * Accesso comodo ai colori del tema corrente da qualsiasi Composable:
 * val colors = IPodTheme.colors
 */
object IPodTheme {
    val colors: IPodColorPalette
        @Composable get() = LocalIPodColors.current
}

@Composable
fun IPodClassicEmulatorTheme(
    themeType: IPodThemeType = IPodThemeType.CLASSIC_WHITE,
    content: @Composable () -> Unit
) {
    val palette = themeType.palette()
    CompositionLocalProvider(LocalIPodColors provides palette) {
        MaterialTheme(
            typography = Typography,
            content = content
        )
    }
}