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

@Composable
fun IPodStatusBar(
    title: String,
    batteryPercent: Int,
    isCharging: Boolean,
    isMusicPlaying: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF2F2F2), Color(0xFFDCDCDC))
                )
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. TITOLO A SINISTRA
        Text(
            text = title,
            fontFamily = MontserratFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF333333)
        )

        // 2. AREA DESTRA (Play, Orologio, Batteria)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // 🟢 Icona Play spostata qui a destra
            if (isMusicPlaying) {
                Text(
                    text = "▶",
                    color = Color(0xFF007AFF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp) // Spazio tra Play e Batteria
                )
            }

            if (isCharging) {
                Text(
                    text = "⚡ ",
                    color = Color(0xFF34C759),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Contenitore esterno della batteria
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
                        .background(Color(0xFF34C759), shape = RoundedCornerShape(1.dp))
                )
            }
            // Pasticca finale della batteria
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(4.dp)
                    .background(Color(0xFF8E8E93))
            )
        }
    }
}