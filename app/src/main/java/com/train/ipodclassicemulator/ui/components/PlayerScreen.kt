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
    val lcdProgressFill = colors.screenAccent

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
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {

        // ── AREA PRINCIPALE: copertina 3D + info ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ── COPERTINA CON EFFETTO 3D ──────────────────────────────────────
            AlbumCover3D(
                coverUrl = coverUrl,
                fallbackColor = lcdProgressTrack,
                screenBg = colors.screenBackground
            )

            Spacer(modifier = Modifier.width(14.dp))

            // ── INFO + PULSANTI ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
                verticalArrangement = Arrangement.Center

            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
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

        // ── PROGRESS BAR ──────────────────────────────────────────────────────
        LinearProgressIndicator(
            progress = progressPercent,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = lcdProgressFill,
            trackColor = lcdProgressTrack
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(progressMs),            color = lcdText, fontSize = 11.sp)
            Text(text = "-${formatTime(durationMs - progressMs)}", color = lcdText, fontSize = 11.sp)
        }
    }
}

// ── COPERTINA 3D ─────────────────────────────────────────────────────────────
/**
 * Album art con effetto profondità:
 * 1. Ombra pronunciata (16 dp) → dà senso di floating
 * 2. Overlay gloss in alto a sinistra → simula la luce riflessa sul vinile
 * 3. Riflesso sfumato sotto → look iPod / iTunes Cover Flow
 */
@Composable
private fun AlbumCover3D(
    coverUrl: String?,
    fallbackColor: Color,
    screenBg: Color
) {
    val coverSize = 200.dp
    val reflectionHeight = 70.dp   // ~35 % della copertina

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Cover principale ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(coverSize)
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(6.dp),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                    ambientColor = Color.Black.copy(alpha = 0.25f)
                )
                .clip(RoundedCornerShape(6.dp))
        ) {
            // Immagine
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "Cover Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(fallbackColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", fontSize = 44.sp, color = Color.White.copy(alpha = 0.4f))
                }
            }

            // Gloss in alto a sinistra (riflesso luce)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Ombra interna ai bordi (dà profondità al frame)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.12f)
                            ),
                            radius = 500f
                        )
                    )
            )
        }

        // ── Riflesso sotto ───────────────────────────────────────────────────
        // L'immagine viene capovolta verticalmente e sfumata verso lo sfondo
        Box(
            modifier = Modifier
                .size(width = coverSize, height = reflectionHeight)
                .graphicsLayer { scaleY = -1f } // capovolto
                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
        ) {
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = coverSize, height = reflectionHeight)
                        .graphicsLayer { alpha = 0.45f },
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(fallbackColor)
                        .graphicsLayer { alpha = 0.35f }
                )
            }

            // Sfuma dal riflesso verso lo sfondo dello schermo
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                screenBg.copy(alpha = 0.8f),
                                screenBg
                            )
                        )
                    )
            )
        }
    }
}