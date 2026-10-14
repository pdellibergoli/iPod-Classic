package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.ui.theme.IPodTheme

/**
 * Schermata "Informazioni": versione e crediti.
 *
 * È statica — la ruota non seleziona niente — quindi è pensata per essere
 * letta con lo scroll verticale del dito. MENU torna alle Impostazioni.
 */
@Composable
fun AboutScreen(
    appVersion: String,
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "iPod Classic",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = colors.screenText
        )
        Text(
            "Emulatore",
            fontSize = 12.sp,
            color = colors.screenText.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Versione $appVersion",
            fontSize = 13.sp,
            color = colors.screenText
        )

        Spacer(modifier = Modifier.height(16.dp))

        Credit(label = "Riproduzione", value = "Spotify App Remote SDK")
        Credit(label = "Brani, playlist, album", value = "Spotify Web API")
        Credit(label = "Lyrics sincronizzate", value = "LRCLIB · lrclib.net")
        Credit(label = "Interfaccia", value = "Jetpack Compose")

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Progetto indipendente, non affiliato a Spotify AB.\n" +
                "Il marchio iPod è di Apple Inc.",
            fontSize = 11.sp,
            color = colors.screenText.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "MENU: torna alle Impostazioni",
            fontSize = 10.sp,
            color = colors.screenText.copy(alpha = 0.6f)
        )
    }
}

/** Una riga di credito: etichetta in grassetto + fonte sotto. */
@Composable
private fun Credit(label: String, value: String) {
    val colors = IPodTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.screenText
        )
        Text(
            value,
            fontSize = 12.sp,
            color = colors.screenText.copy(alpha = 0.65f)
        )
    }
}
