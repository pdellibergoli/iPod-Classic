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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import kotlin.math.atan2

/** Diametro della ruota usato come riferimento per tutte le proporzioni. */
private const val BASE_WHEEL_SIZE = 280f

@Composable
fun ClickWheel(
    modifier: Modifier = Modifier,
    wheelSize: Dp = 280.dp,
    onScrollNext: () -> Unit,
    onScrollPrevious: () -> Unit,
    onSelectClick: () -> Unit,
    /** Pressione lunga sul tasto centrale (azione di contesto). */
    onSelectLongClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onMenuLongClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onPreviousClick: () -> Unit = {},
    /** Un "detent" della ruota durante lo scorrimento. */
    onTick: () -> Unit = {},
    /** Pressione di uno dei cinque tasti. */
    onPress: () -> Unit = {}
) {
    val colors = IPodTheme.colors
    var previousAngle by remember { mutableStateOf(0.0) }
    var isCenterPressed by remember { mutableStateOf(false) }

    // Fattore di scala rispetto alla ruota "classica" di 280dp:
    // serve a far crescere in modo omogeneo pastiglie, testi e rientri.
    val scale = wheelSize.value / BASE_WHEEL_SIZE

    // Fix #5 — soglia di scroll adattata alla ruota:
    // 15 dp di scorrimento tangenziale sul raggio della ruota, convertiti in gradi.
    // Restituisce ~6° sia per 280dp che per ruote più grandi, così la sensibilità
    // percepita resta la stessa.
    val scrollThresholdDeg = remember(wheelSize) {
        Math.toDegrees(15.0 / (wheelSize.value / 2.0))
            .coerceIn(6.0, 20.0) // mai troppo rigido né troppo sensibile
    }

    Box(
        modifier = modifier
            .size(wheelSize)
            .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(colors.wheelHighlight, colors.wheelBase, colors.wheelBase),
                    radius = wheelSize.value * (340f / BASE_WHEEL_SIZE)
                ),
                shape = CircleShape
            )
            .pointerInput(Unit) {
                // Utilizziamo awaitPointerEventScope per gestire tutto insieme
                detectTapGestures(
                    onTap = { offset ->
                        // 🟢 TAP: feedback fisico/sonoro
                        onPress()

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
                        onPress()
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val x = offset.x - centerX
                        val y = offset.y - centerY
                        val angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble()))
                        val normalizedAngle = if (angle < 0) angle + 360 else angle

                        if (normalizedAngle in 225.0..315.0) {
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
                            onTick()
                            if (deltaAngle > 0) onScrollNext() else onScrollPrevious()
                            previousAngle = currentAngle
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(wheelSize)) {
            Text(
                "MENU",
                color = colors.wheelText,
                fontSize = (13 * scale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp * scale)
            )
            Text(
                "▶ ❙❙",
                color = colors.wheelText,
                fontSize = (14 * scale).sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp * scale)
            )
            Text(
                "◀◀",
                color = colors.wheelText,
                fontSize = (13 * scale).sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp * scale)
            )
            Text(
                "▶▶",
                color = colors.wheelText,
                fontSize = (13 * scale).sp,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp * scale)
            )
        }

        // Anello leggermente incassato attorno al tasto centrale, per dare profondità
        Box(
            modifier = Modifier
                .size(96.dp * scale)
                .clip(CircleShape)
                .background(colors.bodyEdge.copy(alpha = 0.25f))
        )

        // Tasto centrale (Select) con gradiente e leggero "schiacciamento" al tocco
        Box(
            modifier = Modifier
                .size(88.dp * scale)
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
                            // Il feedback scatta alla pressione, non al rilascio:
                            // è quello che dà la sensazione di tasto meccanico.
                            onPress()
                            try {
                                tryAwaitRelease()
                            } finally {
                                // Con la premessa lunga la coroutine può essere
                                // annullata: senza `finally` il tasto resterebbe
                                // premuto per sempre.
                                isCenterPressed = false
                            }
                        },
                        // Premessa lunga: azione di contesto ("aggiungi a playlist")
                        onLongPress = { onSelectLongClick() },
                        onTap = { onSelectClick() }
                    )
                }
        )
    }
}
