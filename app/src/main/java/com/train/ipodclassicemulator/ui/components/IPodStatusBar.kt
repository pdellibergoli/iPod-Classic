package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.ui.theme.MontserratFontFamily

/**
 * Barra di stato stile iPod Classic:
 * - Sinistra: titolo sezione
 * - Destra (da sx verso dx): ▶ (se in play) → ⚡ (se in carica) → batteria
 *
 * Il ▶ è IMMEDIATAMENTE a sinistra della batteria nel gruppo destra,
 * non al centro della barra.
 */
@Composable
fun IPodStatusBar(
    title: String,
    batteryPercent: Int,
    isCharging: Boolean,
    isMusicPlaying: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(
                Brush.verticalGradient(colors = listOf(Color(0xFFF2F2F2), Color(0xFFDCDCDC)))
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // SINISTRA: titolo sezione
        Text(
            text = title,
            fontFamily = MontserratFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF333333)
        )

        // DESTRA: ▶ · ⚡ · batteria (tutti in fila, senza spazi vuoti in mezzo)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp) // gap uniforme tra gli elementi
        ) {
            // ▶ play — compare solo se la musica è in riproduzione
            if (isMusicPlaying) {
                Text(
                    text = "▶",
                    color = Color(0xFF007AFF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ⚡ ricarica
            if (isCharging) {
                Text(
                    text = "⚡",
                    color = Color(0xFF34C759),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Icona batteria (corpo + polo positivo)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(25.dp)
                        .height(12.dp)
                        .border(1.dp, Color(0xFF8E8E93), RoundedCornerShape(2.dp))
                        .padding(1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = (batteryPercent / 100f).coerceIn(0f, 1f))
                            .background(
                                color = when {
                                    isCharging         -> Color(0xFF34C759)
                                    batteryPercent <= 20 -> Color(0xFFFF3B30)
                                    else               -> Color(0xFF34C759)
                                },
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
                // Polo positivo della batteria
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(5.dp)
                        .background(Color(0xFF8E8E93))
                )
            }
        }
    }
}