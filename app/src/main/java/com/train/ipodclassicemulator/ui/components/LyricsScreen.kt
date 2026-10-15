package com.train.ipodclassicemulator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.data.model.SpotifyTrackDetails
import com.train.ipodclassicemulator.data.remote.LyricLine
import com.train.ipodclassicemulator.data.remote.LyricsResult
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import kotlin.math.abs

/** Altezza minima di una riga: serve a non far collassare le righe vuote. */
private val LineHeight = 19.dp

/**
 * Schermata lyrics: si apre col tasto centrale mentre suona un brano e si
 * richiude con la stessa pressione.
 *
 * Con le lyrics sincronizzate la riga corrente è evidenziata e la lista scorre
 * da sola per tenerla al centro, come su Spotify. La lista resta anche
 * scorribile col dito: il ri-centraggio avviene solo quando cambia la riga.
 */
@Composable
fun LyricsScreen(
    track: SpotifyTrackDetails,
    result: LyricsResult?,
    progressMs: Long,
    volumePercent: Float = 0f,
    showVolumeBar: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.screenBackground)
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Text(
                text = track.name,
                color = colors.screenText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = track.artists.firstOrNull()?.name ?: "Artista Sconosciuto",
                color = colors.screenText.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(6.dp))

            when (result) {
                null -> Centered(Modifier.weight(1f)) {
                    CircularProgressIndicator(color = colors.screenAccent)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Cerco le lyrics...", color = colors.screenText, fontSize = 13.sp)
                }

                is LyricsResult.Synced -> SyncedLyrics(
                    lines = result.lines,
                    progressMs = progressMs,
                    modifier = Modifier.weight(1f)
                )

                is LyricsResult.Plain -> Text(
                    text = result.text,
                    color = colors.screenText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )

                LyricsResult.None -> Centered(Modifier.weight(1f)) {
                    Text(
                        "Lyrics non disponibili",
                        color = colors.screenText.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }

            Text(
                "Scorri per leggere ∙ Centro per le info",
                fontSize = 10.sp,
                color = colors.screenText.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ── Overlay volume ────────────────────────────────────────────────────
        // Nelle info la ruota sostituisce la barra di progresso; qui il testo
        // occupa tutto, quindi il volume va disegnato sopra come chip fluttuante
        // altrimenti non si capirebbe che la ruota sta regolando qualcosa.
        AnimatedVisibility(
            visible = showVolumeBar,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.screenBackground, RoundedCornerShape(6.dp))
                    .border(
                        0.5.dp,
                        colors.screenText.copy(alpha = 0.25f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                VolumeBar(volumePercent = volumePercent)
            }
        }
    }
}

/** Contenuto centrato nell'area della lista. */
@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

// ── Lyrics sincronizzate ──────────────────────────────────────────────────────

/**
 * Corpo del testo per le righe normali e per quella in evidenza.
 *
 * La riga attiva è più grande oltre che in grassetto: è l'unico modo di far
 * capire a occhio dove siamo senza dover leggere. L'ingrandimento resta piccolo
 * a mano, perché righe troppo alte falserebbero il centro della lista.
 */
private val NormalFontSize = 14.sp
private val ActiveFontSize = 17.sp

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    progressMs: Long,
    modifier: Modifier
) {
    val colors = IPodTheme.colors
    val listState = rememberLazyListState()

    // La riga attiva è l'ultima il cui timestamp è già passato.
    // È una derivazione del progresso: niente timer aggiuntivi, si aggiorna
    // insieme alla barra (che scatta ogni 500 ms).
    val activeIndex = remember(progressMs, lines) {
        if (lines.isEmpty()) -1 else lines.indexOfLast { it.timeMs <= progressMs }
    }

    // Centra la riga attiva. Si legge da layoutInfo invece di usare lo scrollOffset
    // di scrollToItem, perché qui l'offset è espresso in pixel e le altezze delle
    // righe variano (alcune occupano due righe di testo).
    LaunchedEffect(activeIndex, lines) {
        if (activeIndex < 0 || lines.isEmpty()) return@LaunchedEffect

        repeat(3) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            if (item == null) {
                // Fuori schermo: portalo prima in vista, poi al centro al giro dopo
                listState.scrollToItem(activeIndex)
            } else {
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val delta = (item.offset + item.size / 2) - viewportCenter
                if (abs(delta) <= 1) return@LaunchedEffect
                listState.scrollBy(delta.toFloat())
            }
            // Serve un passo di layout per rileggere posizioni aggiornate
            withFrameNanos { }
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            val active = index == activeIndex
            val color = when {
                active -> colors.screenAccent
                index < activeIndex -> colors.screenText.copy(alpha = 0.45f)
                else -> colors.screenText.copy(alpha = 0.3f)
            }

            if (line.text.isEmpty()) {
                // Le righe senza testo (pause/respiro) servono da separatore:
                // senza un'altezza fissa collasserebbero a zero e sfalserebbero
                // il centro della riga attiva.
                Spacer(modifier = Modifier.height(LineHeight))
            } else {
                Text(
                    text = line.text,
                    color = color,
                    fontSize = if (active) ActiveFontSize else NormalFontSize,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LineHeight)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}
