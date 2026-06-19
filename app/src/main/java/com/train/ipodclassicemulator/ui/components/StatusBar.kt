package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Barra di stato in cima allo schermo LCD: titolo a sinistra, orario e
 * indicatore batteria a destra — proprio come sull'iPod Classic originale.
 */
@Composable
fun IPodStatusBar(title: String, batteryPercent: Int = 100) {
    val colors = IPodTheme.colors
    var currentTime by remember { mutableStateOf(timeNow()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = timeNow()
            delay(30_000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = colors.screenText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currentTime, color = colors.screenText, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(6.dp))
            BatteryIcon(percent = batteryPercent, color = colors.screenText)
        }
    }
}

@Composable
private fun BatteryIcon(percent: Int, color: Color) {
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(10.dp)
            .border(width = 1.dp, color = color)
            .padding(1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .background(color)
        )
    }
}

private fun timeNow(): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date())
}