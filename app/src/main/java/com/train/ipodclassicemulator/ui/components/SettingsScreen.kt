package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.SettingsRow
import com.train.ipodclassicemulator.SettingsRowKind
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.IPodThemeType

/** Colore della riga "disconnetti" quando non è selezionata. */
private val DangerColor = Color(0xFFD32F2F)

/**
 * Schermata "Impostazioni", navigabile con la click wheel.
 *
 * La rotazione sposta la selezione, il tasto centrale:
 * - su un tema, lo applica;
 * - su "Risposta ruota" / "Suono ruota", cicla al livello successivo;
 * - su "Disconnetti Spotify", chiede conferma e poi disconnette.
 *
 * MENU torna indietro.
 *
 * @param rows righe divise in sezioni (Temi / Feedback / Account)
 * @param confirmLogout true quando la disconnessione è selezionata e va confermata
 */
@Composable
fun SettingsScreen(
    selectedIndex: Int,
    currentTheme: IPodThemeType,
    listState: LazyListState,
    rows: List<SettingsRow>,
    confirmLogout: Boolean
) {
    val colors = IPodTheme.colors

    Column(modifier = Modifier.fillMaxSize()) {
        // 🟢 RIMOSSA LA STATUSBAR DUPLICATA DA QUI - CI PENSA LA MAINACTIVITY!

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows) { index, row ->
                val isSelected = index == selectedIndex
                val isLogout = row.kind == SettingsRowKind.LOGOUT
                val isActive = row.kind == SettingsRowKind.THEME && row.theme == currentTheme
                val text =
                    if (isLogout && isSelected && confirmLogout) "Conferma disconnessione"
                    else row.label
                // Titolo e separatore compaiono solo sulla prima riga di ogni sezione
                val isFirstOfSection = index == 0 || rows[index - 1].section != row.section

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isFirstOfSection) {
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.screenText.copy(alpha = 0.15f))
                            )
                        }
                        Text(
                            text = row.section.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.screenText.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) colors.screenSelectedBg else colors.screenBackground
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text,
                            color = when {
                                isLogout && !isSelected -> DangerColor
                                isSelected -> colors.screenSelectedText
                                else -> colors.screenText
                            },
                            fontSize = 15.sp
                        )
                        // Valore corrente (livelli di feedback); i temi usano la spunta
                        if (row.value != null) {
                            Text(
                                row.value,
                                color = if (isSelected) colors.screenSelectedText
                                       else colors.screenText.copy(alpha = 0.6f),
                                fontSize = 15.sp
                            )
                        } else if (isActive) {
                            Text(
                                "✓",
                                color = if (isSelected) colors.screenSelectedText
                                       else colors.screenAccent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Ruota: seleziona ∙ Centro: cambia ∙ MENU: indietro",
            fontSize = 10.sp,
            color = colors.screenText.copy(alpha = 0.6f),
            modifier = Modifier.padding(8.dp)
        )
    }
}
