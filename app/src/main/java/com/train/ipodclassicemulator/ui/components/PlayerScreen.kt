package com.train.ipodclassicemulator.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.train.ipodclassicemulator.R
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.delay

// Gradiente progress/volume: top→bottom, blu stile iPod originale
private val blueBarGradient = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFF91B7F1),
        0.4f to Color(0xFF3388FF),
        1.0f to Color(0xFF96DFFC)
    )
)

// Track background della barra (sfondo grigio chiaro con bordi)
private val trackBackground = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFFFFFFFF),
        0.4f to Color(0xFFEFEFEF),
        1.0f to Color(0xFFD0D0D0)
    )
)

@Composable
fun PlayerScreen(
    track: SpotifyTrackDetails,
    coverUrl: String?,
    progressMs: Long,
    durationMs: Long,
    isLiked: Boolean,
    playbackMode: Int,
    volumePercent: Float = 0f,       // 0..1
    showVolumeBar: Boolean = false,   // true → mostra volume al posto del progress
    onLikeToggle: () -> Unit,
    onModeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors
    val lcdText = colors.screenText

    val progressPercent = if (durationMs > 0) progressMs.toFloat() / durationMs.toFloat() else 0f

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
                AlbumCover3D(
                    coverUrl = coverUrl,
                    fallbackColor = colors.screenSecondary,
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
                    fun AutoScrollText(
                        text: String,
                        fontSize: androidx.compose.ui.unit.TextUnit,
                        isBold: Boolean = false,
                        color: Color
                    ) {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(text) {
                            delay(1500)
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

                    AutoScrollText(track.name, 17.sp, true, lcdText)
                    Spacer(modifier = Modifier.height(4.dp))
                    AutoScrollText(track.artists.firstOrNull()?.name ?: "Artista Sconosciuto", 14.sp, false, lcdText)
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
                        Icon(
                            if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null,
                            tint = if (isLiked) Color(0xFFD32F2F) else lcdText.copy(alpha = 0.6f),
                            modifier = Modifier.size(35.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(onClick = onModeToggle, modifier = Modifier.size(35.dp)) {
                        Icon(
                            painter = painterResource(id = if (playbackMode == 1) R.drawable.shuffle_on else R.drawable.shuffle_off),
                            contentDescription = "Shuffle",
                            tint = if (playbackMode == 1) colors.progressBar else lcdText.copy(alpha = 0.4f),
                            modifier = Modifier.size(35.dp)
                        )
                    }
                }
            }
        }

        // ── PROGRESS / VOLUME BAR ─────────────────────────────────────────────
        AnimatedContent(
            targetState = showVolumeBar,
            transitionSpec = {
                fadeIn(tween(250)) togetherWith fadeOut(tween(250))
            },
            label = "progress_volume_switch"
        ) { isVolume ->
            if (isVolume) {
                VolumeBar(volumePercent = volumePercent, lcdText = lcdText)
            } else {
                ProgressBar(
                    progressMs = progressMs,
                    durationMs = durationMs,
                    progressPercent = progressPercent,
                    lcdText = lcdText,
                    formatTime = ::formatTime
                )
            }
        }
    }
}

// ── Progress Bar ──────────────────────────────────────────────────────────────
@Composable
private fun ProgressBar(
    progressMs: Long,
    durationMs: Long,
    progressPercent: Float,
    lcdText: Color,
    formatTime: (Long) -> String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tempo trascorso (larghezza fissa per evitare salti)
        Text(
            text = formatTime(progressMs),
            color = lcdText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(36.dp)
        )

        // Barra con sfondo grigio sfumato + fill blu sfumato sovrapposto
        Box(
            modifier = Modifier
                .weight(1f)
                .height(17.dp)
        ) {
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(trackBackground)
                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFC7C7C7)))
            )
            // Fill blu
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progressPercent.coerceIn(0f, 1f))
                    .background(blueBarGradient)
            )
        }

        // Tempo rimanente (larghezza fissa, allineato a destra)
        Text(
            text = "-${formatTime(durationMs - progressMs)}",
            color = lcdText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}

// ── Volume Bar (sostituisce il progress quando si cambia volume) ──────────────
@Composable
private fun VolumeBar(
    volumePercent: Float,
    lcdText: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icona volume basso
        Icon(
            imageVector = Icons.Default.VolumeDown,
            contentDescription = null,
            tint = Color(0xFF595959),
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Barra volume (stessa struttura del progress)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(17.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(trackBackground)
                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFC7C7C7)))
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = volumePercent.coerceIn(0f, 1f))
                    .background(blueBarGradient)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Icona volume alto
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = null,
            tint = Color(0xFF595959),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ── COPERTINA 3D ─────────────────────────────────────────────────────────────
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

    Box(modifier = Modifier.size(coverSize + reflectionHeight)) {

        // 1. RIFLESSO
        Box(
            modifier = Modifier
                .size(width = coverSize - 5.dp, height = reflectionHeight)
                .align(Alignment.BottomCenter)
                .offset(x = horizontalOffset)
                .graphicsLayer {
                    scaleY = -1f
                    translationY = -10f
                    translationX = -8f
                    rotationY = 1f
                    rotationX = 3f
                }
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
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(screenBg, screenBg.copy(alpha = 0.3f))
                )
            ))
        }

        // 2. COVER PRINCIPALE
        Box(
            modifier = Modifier
                .size(coverSize)
                .align(Alignment.TopCenter)
                .offset(x = horizontalOffset)
                .graphicsLayer {
                    cameraDistance = 8f * density
                    rotationY = tiltY
                    rotationX = tiltX
                }
                .shadow(20.dp)
        ) {
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "Cover Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(fallbackColor))
            }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(0.3f), Color.Transparent))))
        }
    }
}
