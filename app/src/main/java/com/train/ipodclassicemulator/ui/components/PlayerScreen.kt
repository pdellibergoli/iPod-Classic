package com.train.ipodclassicemulator.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.train.ipodclassicemulator.R
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    track: SpotifyTrackDetails,
    coverUrl: String?,
    progressMs: Long,
    durationMs: Long,
    isLiked: Boolean,
    playbackMode: Int,
    onLikeToggle: () -> Unit,
    onModeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors
    val lcdText = colors.screenText
    val lcdProgressTrack = colors.screenSecondary
    val lcdProgressFill = colors.progressBar

    val progressPercent = if (durationMs > 0) progressMs.toFloat() / durationMs.toFloat() else 0f
    val isShuffleActive = playbackMode == 1

    val artistName = track.artists.firstOrNull()?.name ?: "Artista Sconosciuto"
    val albumName  = track.album?.name.orEmpty()

    fun formatTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBackground)
            .padding(horizontal = 1.dp, vertical = 6.dp)
    ) {

        // ── AREA PRINCIPALE: copertina 3D + info ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(modifier = Modifier.weight(2f)) {
                // ── COPERTINA CON EFFETTO 3D ──────────────────────────────────────
                AlbumCover3D(
                    coverUrl = coverUrl,
                    fallbackColor = lcdProgressTrack,
                    screenBg = colors.screenBackground
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // ── INFO + PULSANTI ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1.8f)
                    .fillMaxHeight()
                    .padding(start = 0.dp),
                verticalArrangement = Arrangement.Center

            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 0.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    @Composable
                    fun AutoScrollText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, isBold: Boolean = false, color: Color) {
                        val scrollState = rememberScrollState()

                        LaunchedEffect(text) {
                            delay(1500) // Pausa iniziale
                            val maxScroll = scrollState.maxValue
                            if (maxScroll > 0) {
                                while (true) {
                                    scrollState.animateScrollTo(maxScroll, animationSpec = tween(4000, easing = LinearEasing))
                                    delay(2000)
                                    scrollState.animateScrollTo(0, animationSpec = tween(1000))
                                    delay(1000)
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                            Text(
                                text = text,
                                color = color,
                                fontSize = fontSize,
                                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // 1. Titolo
                    AutoScrollText(track.name, 17.sp, true, lcdText)

                    // 2. Artista
                    Spacer(modifier = Modifier.height(4.dp))
                    AutoScrollText(track.artists.firstOrNull()?.name ?: "Artista Sconosciuto", 14.sp, false, lcdText)

                    // 3. Album
                    Spacer(modifier = Modifier.height(2.dp))
                    AutoScrollText(track.album?.name ?: "Album Sconosciuto", 13.sp, false, Color.Gray)
                }
                // Cuore + Shuffle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onLikeToggle, modifier = Modifier.size(35.dp)) {
                        Icon(if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null, tint = if (isLiked) Color(0xFFD32F2F) else lcdText.copy(alpha = 0.6f), modifier = Modifier.size(35.dp))
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(onClick = onModeToggle, modifier = Modifier.size(35.dp)) {
                        Icon(
                            painter = painterResource(id = if (playbackMode == 1) R.drawable.shuffle_on else R.drawable.shuffle_off),
                            contentDescription = "Shuffle",
                            tint = if (playbackMode == 1) lcdProgressFill else lcdText.copy(alpha = 0.4f),
                            modifier = Modifier.size(35.dp)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── PROGRESS BAR ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(0.3f)
                    .padding(start = 2.dp)

            ) {
                Text(text = formatTime(progressMs), color = lcdText, fontSize = 11.sp)
            }
            Column(
                modifier = Modifier
                    .weight(3f)
                    .padding(start = 1.dp)

            ) {
                LinearProgressIndicator(
                    progress = progressPercent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .padding(5.dp, 0.dp),
                        //.clip(RoundedCornerShape(3.dp)),
                    color = lcdProgressFill,
                    trackColor = lcdProgressTrack
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.3f)
                    .padding(end = 2.dp)

            ) {
                Text(
                    text = "-${formatTime(durationMs - progressMs)}",
                    color = lcdText,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ── COPERTINA 3D ─────────────────────────────────────────────────────────────
/**
 * Album art con effetto Cover Flow stile iPod:
 * 1. Inclinazione diagonale tramite rotationY + rotationX (prospettiva 3D)
 * 2. Ombra asimmetrica che segue la direzione dell'inclinazione
 * 3. Gloss diagonale in alto a sinistra
 * 4. Riflesso sotto: capovolto, dissolvenza progressiva + sfocatura simulata
 *    tramite layers con alpha decrescente
 */
@Composable
private fun AlbumCover3D(
    coverUrl: String?,
    fallbackColor: Color,
    screenBg: Color
) {
    val coverSize = 200.dp
    val reflectionHeight = 70.dp
    val tiltY = 15f
    val tiltX = 4f

    val horizontalOffset = (0).dp

    // Usiamo un Box per avere controllo assoluto sulla posizione dei layer
    Box(modifier = Modifier.size(coverSize + reflectionHeight)) {

        // 1. RIFLESSO (disegnato per primo così sta sotto)
        Box(
            modifier = Modifier
                .size(width = coverSize - 5.dp, height = reflectionHeight)
                .align(Alignment.BottomCenter) // Allineato al fondo del Box padre
                .offset(x = horizontalOffset)
                .graphicsLayer {
                    scaleY = -1f
                    translationY = -10f // Sposta leggermente per staccare dal bordo
                    translationX = -8f
                    rotationY = 1f
                    rotationX = 3f
                }
                //.clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
        ) {
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.4f },
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter
                )
            }
            // Gradiente corretto: parte trasparente (vicino alla cover) e finisce col colore sfondo
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        screenBg,
                        screenBg.copy(alpha = 0.3f)
                    )
                )
            ))
        }

        // 2. COVER PRINCIPALE (disegnata per seconda così copre il riflesso)
        Box(
            modifier = Modifier
                .size(coverSize)
                .align(Alignment.TopCenter) // Posizionata sopra
                .offset(x = horizontalOffset)
                .graphicsLayer {
                    cameraDistance = 8f * density
                    rotationY = tiltY
                    rotationX = tiltX
                }
                .shadow(20.dp)
        ) {
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(model = coverUrl, contentDescription = "Cover Art", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(fallbackColor))
            }
            // Gloss
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(0.3f), Color.Transparent))))
        }
    }
}