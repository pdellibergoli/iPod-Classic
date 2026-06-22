package com.train.ipodclassicemulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color

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
    // 🟢 Recuperiamo la palette selezionata
    val basePalette = themeType.palette()

    // 🟢 Forziamo i parametri dello schermo su Bianco e i testi non selezionati su Nero
    // in modo da eliminare il verdino residuo a livello di engine del tema.
    val overriddenPalette = basePalette.copy(
        screenBackground = Color.White,
        screenText = Color.Black
    )

    CompositionLocalProvider(LocalIPodColors provides overriddenPalette) {
        MaterialTheme(
            typography = IpodTypography,
            content = content
        )
    }
}