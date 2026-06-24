package com.train.ipodclassicemulator.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

private const val ZOOM_DURATION_MS   = 5000   // quanto dura lo zoom su ogni cover
private const val FADE_DURATION_MS   = 700    // durata del cross-fade
private const val MAX_SCALE          = 1.20f
private const val MIN_SCALE          = 1.00f

/**
 * Mostra le cover degli album in loop con effetto Ken Burns (zoom lento + cross-fade).
 * Un singolo loop sequenziale gestisce: zoom → fade-out → avanza → fade-in.
 */
@Composable
fun AlbumCoverKenBurns(
    coverUrls: List<String>,
    modifier: Modifier = Modifier
) {
    if (coverUrls.isEmpty()) return

    // Indice attuale e prossimo derivati da un unico counter
    var page       by remember { mutableIntStateOf(0) }
    val currentIdx  = page % coverUrls.size
    val nextIdx     = (page + 1) % coverUrls.size

    val scale      = remember { Animatable(MIN_SCALE) }
    val alpha      = remember { Animatable(1f) }

    // Un solo loop — tutto in sequenza, nessuna race condition
    LaunchedEffect(coverUrls) {
        while (true) {
            // 1. Zoom in sulla cover corrente
            scale.snapTo(MIN_SCALE)
            alpha.snapTo(1f)
            scale.animateTo(
                targetValue = MAX_SCALE,
                animationSpec = tween(durationMillis = ZOOM_DURATION_MS, easing = LinearEasing)
            )

            // 2. Fade-out (la cover "next" è già visibile sotto come sfondo)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = FADE_DURATION_MS, easing = LinearEasing)
            )

            // 3. Avanza al prossimo
            page++
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        // Cover successiva — sempre sotto, già caricata pronta
        AsyncImage(
            model        = coverUrls[nextIdx],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier     = Modifier.fillMaxSize()
        )
        // Cover corrente — sopra, con zoom e fade-out
        AsyncImage(
            model        = coverUrls[currentIdx],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier     = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha  = alpha.value
                }
        )
    }
}
