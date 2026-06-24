package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.ui.theme.MontserratFontFamily
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size

/**
 * Barra di stato stile iPod Classic (ispirata a Retro Music).
 * Sinistra: titolo sezione
 * Destra: ▶ (gradient blu se in play) · batteria con polo positivo
 */
@Composable
fun IPodStatusBar(
    title: String,
    batteryPercent: Int,
    isCharging: Boolean,
    isMusicPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Gradiente della barra ispirato al retro: top chiaro → bottom grigio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(25.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFB0B0B4), Color(0xFFFFFFFF)),
                )
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // SINISTRA: titolo sezione
        Text(
            text = title,
            fontFamily = MontserratFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.5.sp,
            color = Color.Black
        )

        // DESTRA: ▶ con gradiente blu · [⚡] · batteria
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // ▶ play con gradiente radiale blu (come SFSymbols nel retro)
            if (isMusicPlaying) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .drawWithCache {
                            val gradient = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFADD8FF),
                                    Color(0xFF2D8DDC)
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension / 1.5f
                            )
                            onDrawBehind {
                                // Draw triangle (play icon)
                                val path = androidx.compose.ui.graphics.Path()
                                val w = size.width
                                val h = size.height
                                path.moveTo(w * 0.2f, h * 0.1f)
                                path.lineTo(w * 0.9f, h * 0.5f)
                                path.lineTo(w * 0.2f, h * 0.9f)
                                path.close()
                                drawPath(path, gradient)
                            }
                        }
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
                        .width(28.dp)
                        .height(13.dp)
                        .border(1.dp, Color(0xFF555555), RoundedCornerShape(2.dp))
                        .padding(1.5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = (batteryPercent / 100f).coerceIn(0f, 1f))
                            .background(
                                color = when {
                                    isCharging           -> Color(0xFF34C759)
                                    batteryPercent <= 20 -> Color(0xFFFF3B30)
                                    else                 -> Color(0xFF34C759)
                                },
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
                // Polo positivo
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(6.dp)
                        .background(Color(0xFF555555))
                )
            }
        }
    }
}
