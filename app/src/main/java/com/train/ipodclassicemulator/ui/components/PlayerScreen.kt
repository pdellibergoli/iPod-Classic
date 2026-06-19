package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.ui.theme.IPodTheme

@Composable
fun PlayerScreen(
    track: SpotifyTrackDetails,
    coverUrl: String?, // URL reale passato da Spotify
    progressMs: Long,  // Millisecondi passati reali
    durationMs: Long,  // Durata totale reale
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors
    var isFavorite by remember { mutableStateOf(false) }
    var isShuffleMode by remember { mutableStateOf(false) }

    val lcdText = colors.screenText
    val lcdProgressTrack = colors.screenSecondary
    val lcdProgressFill = colors.screenAccent

    // Calcolo percentuale progress bar
    val progressPercent = if (durationMs > 0) progressMs.toFloat() / durationMs.toFloat() else 0f

    // Convertitore millisecondi -> Formato MM:SS
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBackground)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IPodStatusBar(title = "Now Playing")

        Spacer(modifier = Modifier.height(4.dp))

        // Cover e Informazioni
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Copertina Reale tramite AsyncImage di Coil
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "Cover Art",
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Gray),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback se non c'è internet o manca l'URL
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(lcdProgressTrack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Cover", color = lcdText, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info Testo e Pulsanti Funzionanti
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    color = lcdText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artists.firstOrNull()?.name ?: "Artista Sconosciuto",
                    color = lcdText,
                    fontSize = 13.sp,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Azioni interattive intercettabili
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(
                        onClick = { isFavorite = !isFavorite },
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                color = if (isFavorite) Color(0xFFD32F2F) else lcdProgressTrack,
                                shape = RoundedCornerShape(4.dp)
                            )
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Preferiti",
                            tint = if (isFavorite) Color.White else lcdText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { isShuffleMode = !isShuffleMode },
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                color = if (isShuffleMode) lcdProgressFill else lcdProgressTrack,
                                shape = RoundedCornerShape(4.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Modalità casuale",
                            tint = if (isShuffleMode) Color.White else lcdText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Progress Bar Reale
        LinearProgressIndicator(
            progress = progressPercent,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = lcdProgressFill,
            trackColor = lcdProgressTrack
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(progressMs), color = lcdText, fontSize = 11.sp)
            Text(text = "-" + formatTime(durationMs - progressMs), color = lcdText, fontSize = 11.sp)
        }
    }
}