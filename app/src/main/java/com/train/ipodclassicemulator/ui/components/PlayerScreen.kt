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
    coverUrl: String?,               // URL reale passato da Spotify
    progressMs: Long,                // Millisecondi passati reali
    durationMs: Long,                // Durata totale reale
    isLiked: Boolean,                // Stato reale del Cuore (da MainActivity/API)
    playbackMode: Int,               // Modalità reale: 0=Off, 1=All, 2=One, 3=Shuffle
    onLikeToggle: () -> Unit,        // Evento tap sul Cuore
    onModeToggle: () -> Unit,        // Evento tap sulla Modalità
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors

    // 🟢 RIMOSSE LE VARIABILI LOCALI 'isFavorite' E 'isShuffleMode' CHE ACCECAVANO I DATI REALI!

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

            // Info Testo e Pulsanti Funzionanti al TAP
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically // 🟢 FONDAMENTALE: Forza l'allineamento perfetto sull'asse verticale
                ) {

                    // 🔴 1. PULSANTE PREFERITI (Solo Icona, senza box quadrato)
                    IconButton(
                        onClick = onLikeToggle,
                        modifier = Modifier.size(24.dp) // 🟢 Dimensione nativa dell'area cliccabile senza background condizionale
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Preferiti",
                            tint = if (isLiked) Color(0xFFD32F2F) else lcdText.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp) // Dimensione effettiva del cuore
                        )
                    }
                    val isShuffleActive = playbackMode == 1

                    // 🔴 2. PULSANTE SHUFFLE (Solo Icona, perfettamente allineato)
                    IconButton(
                        onClick = onModeToggle,
                        modifier = Modifier.size(24.dp) // 🟢 Stessa identica dimensione del contenitore per pareggiare l'allineamento
                    ) {
                        if (isShuffleActive) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.train.ipodclassicemulator.R.drawable.shuffle_on),
                                contentDescription = "Shuffle Attivo",
                                tint = lcdProgressFill, // Colore acceso del tema
                                modifier = Modifier.size(22.dp) // 🟢 Dimensione identica a quella del cuore per non avere disallineamenti
                            )
                        } else {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.train.ipodclassicemulator.R.drawable.shuffle_off),
                                contentDescription = "Shuffle Disattivato",
                                tint = lcdText.copy(alpha = 0.4f), // Grigio spento
                                modifier = Modifier.size(22.dp)
                            )
                        }
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