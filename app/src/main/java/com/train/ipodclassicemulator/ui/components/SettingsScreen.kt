package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.IPodThemeType

/**
 * Schermata "Impostazioni > Temi", navigabile con la click wheel
 * (rotazione = sposta selezione, tasto centrale = applica tema, MENU = indietro).
 */
@Composable
fun SettingsScreen(
    selectedIndex: Int,
    currentTheme: IPodThemeType,
    listState: LazyListState
) {
    val colors = IPodTheme.colors
    val themes = IPodThemeType.values()

    Column(modifier = Modifier.fillMaxSize()) {
        // 🟢 RIMOSSA LA STATUSBAR DUPLICATA DA QUI - CI PENSA LA MAINACTIVITY!

        Text(
            "Scegli un tema",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.screenText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(themes) { index, theme ->
                val isSelected = index == selectedIndex
                val isActive = theme == currentTheme
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        theme.displayName,
                        color = if (isSelected) colors.screenSelectedText else colors.screenText,
                        fontSize = 15.sp
                    )
                    if (isActive) {
                        Text(
                            "✓",
                            color = if (isSelected) colors.screenSelectedText else colors.screenAccent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Text(
            "MENU per tornare indietro",
            fontSize = 10.sp,
            color = colors.screenText.copy(alpha = 0.6f),
            modifier = Modifier.padding(8.dp)
        )
    }
}