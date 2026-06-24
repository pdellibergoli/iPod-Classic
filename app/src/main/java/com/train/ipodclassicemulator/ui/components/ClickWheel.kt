package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.atan2

@Composable
fun ClickWheel(
    modifier: Modifier = Modifier,
    onScrollNext: () -> Unit,
    onScrollPrevious: () -> Unit,
    onSelectClick: () -> Unit,
    onMenuClick: () -> Unit = {},
    onMenuLongClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onPreviousClick: () -> Unit = {}
) {
    val colors = IPodTheme.colors
    var previousAngle by remember { mutableStateOf(0.0) }
    var isCenterPressed by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Fix #5 — soglia di scroll adattata alla densità dello schermo:
    // 15 dp convertiti in gradi equivalenti su un cerchio di 280 dp di diametro.
    // Su schermi densi/grandi risulta più sensibile; su schermi piccoli rimane fluido.
    val density = LocalDensity.current
    val scrollThresholdDeg = remember(density) {
        val wheelRadiusPx = with(density) { 140.dp.toPx() }
        // arco in gradi corrispondente a 15 dp di spostamento tangenziale
        Math.toDegrees(15.0 / wheelRadiusPx * (density.density))
            .coerceIn(6.0, 20.0) // mai troppo rigido né troppo sensibile
    }

    Box(
        modifier = modifier
            .size(280.dp)
            .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(colors.wheelHighlight, colors.wheelBase, colors.wheelBase),
                    radius = 340f
                ),
                shape = CircleShape
            )
            .pointerInput(Unit) {
                // Utilizziamo awaitPointerEventScope per gestire tutto insieme
                detectTapGestures(
                    onTap = { offset ->
                        // 🟢 TAP: Vibrazione feedback
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)

                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val x = offset.x - centerX
                        val y = offset.y - centerY
                        val angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble()))
                        val normalizedAngle = if (angle < 0) angle + 360 else angle

                        when {
                            normalizedAngle in 225.0..315.0 -> onMenuClick()
                            normalizedAngle in 45.0..135.0 -> onPlayPauseClick()
                            normalizedAngle >= 315.0 || normalizedAngle <= 45.0 -> onNextClick()
                            normalizedAngle in 135.0..225.0 -> onPreviousClick()
                        }
                    },
                    onLongPress = { offset ->
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val x = offset.x - centerX
                        val y = offset.y - centerY
                        val angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble()))
                        val normalizedAngle = if (angle < 0) angle + 360 else angle

                        if (normalizedAngle in 225.0..315.0) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onMenuLongClick()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // Il drag resta separato ma assicurati che sia il SECONDO pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        previousAngle = Math.toDegrees(atan2((offset.y - centerY).toDouble(), (offset.x - centerX).toDouble()))
                    },
                    onDrag = { change, _ ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val currentAngle = Math.toDegrees(atan2((change.position.y - centerY).toDouble(), (change.position.x - centerX).toDouble()))

                        var deltaAngle = currentAngle - previousAngle
                        if (deltaAngle > 180) deltaAngle -= 360
                        if (deltaAngle < -180) deltaAngle += 360

                        if (Math.abs(deltaAngle) >= scrollThresholdDeg) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            if (deltaAngle > 0) onScrollNext() else onScrollPrevious()
                            previousAngle = currentAngle
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(280.dp)) {
            Text(
                "MENU",
                color = colors.wheelText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
            )
            Text(
                "▶ ❙❙",
                color = colors.wheelText,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
            )
            Text(
                "◀◀",
                color = colors.wheelText,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
            )
            Text(
                "▶▶",
                color = colors.wheelText,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            )
        }

        // Anello leggermente incassato attorno al tasto centrale, per dare profondità
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colors.bodyEdge.copy(alpha = 0.25f))
        )

        // Tasto centrale (Select) con gradiente e leggero "schiacciamento" al tocco
        Box(
            modifier = Modifier
                .size(88.dp)
                .shadow(elevation = if (isCenterPressed) 1.dp else 5.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.centerButton,
                            if (isCenterPressed) colors.centerButtonPressed else colors.centerButton
                        )
                    )
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isCenterPressed = true
                            tryAwaitRelease()
                            isCenterPressed = false
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        },
                        onTap = {
                            onSelectClick()
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
        )
    }
}