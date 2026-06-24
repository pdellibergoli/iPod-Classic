package com.train.ipodclassicemulator.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.train.ipodclassicemulator.data.model.SpotifyAlbumDetails
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import kotlin.math.abs
import kotlin.math.sign

/**
 * CoverFlow stile iPod Classic.
 *
 * Logica visiva:
 * - La cover centrale è frontale, grande, con ombra profonda.
 * - Le cover laterali sono ruotate attorno all'asse Y (rotationY ±55°),
 *   scalate, traslate in profondità e lateralmente per creare la prospettiva.
 * - Ogni cover ha il riflesso sotto: capovolto, con gradiente che svanisce.
 * - Il titolo + artista dell'album selezionato è mostrato sotto con dissolvenza.
 * - La navigazione è controllata dal ViewModel (ghiera click wheel).
 *
 * @param albums            Lista degli album da visualizzare.
 * @param selectedIndex     Indice dell'album correntemente selezionato (dal ViewModel).
 * @param modifier          Modifier esterno.
 */
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlin.math.min

@Composable
fun CoverFlow(
    albums: List<SpotifyAlbumDetails>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) return

    val colors = IPodTheme.colors

    val visibleSideCount = 3

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBackground),
        contentAlignment = Alignment.Center
    ) {
        // Calcola la dimensione dinamica in base allo schermo
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        // La cover centrale occupa ~55% della dimensione minore (larghezza o altezza)
        // con un cap massimo per evitare che sia enorme su tablet/fold aperti
        val shortSide = min(availableWidth.value, availableHeight.value).dp
        val centerSize = (shortSide * 0.55f).coerceIn(100.dp, 260.dp)
        val sideSize = (centerSize * 0.78f).coerceIn(78.dp, 200.dp)
        val spacing = sideSize * 0.85f

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                for (offset in visibleSideCount downTo 1) {
                    val idx = selectedIndex + offset
                    if (idx < albums.size) {
                        CoverItem(
                            album = albums[idx],
                            offsetFromCenter = offset,
                            isCenter = false,
                            screenBg = colors.screenBackground,
                            centerSize = centerSize,
                            sideSize = sideSize,
                            spacing = spacing
                        )
                    }
                }
                for (offset in visibleSideCount downTo 1) {
                    val idx = selectedIndex - offset
                    if (idx >= 0) {
                        CoverItem(
                            album = albums[idx],
                            offsetFromCenter = -offset,
                            isCenter = false,
                            screenBg = colors.screenBackground,
                            centerSize = centerSize,
                            sideSize = sideSize,
                            spacing = spacing
                        )
                    }
                }
                CoverItem(
                    album = albums[selectedIndex],
                    offsetFromCenter = 0,
                    isCenter = true,
                    screenBg = colors.screenBackground,
                    centerSize = centerSize,
                    sideSize = sideSize,
                    spacing = spacing
                )
            }

            Spacer(Modifier.height(6.dp))
            val currentAlbum = albums[selectedIndex]
            Text(
                text = currentAlbum.name,
                color = colors.screenText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Text(
                text = currentAlbum.artists?.firstOrNull()?.name ?: "",
                color = colors.screenText.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ── Singola cover nel flow ────────────────────────────────────────────────────

@Composable
private fun CoverItem(
    album: SpotifyAlbumDetails,
    offsetFromCenter: Int,
    isCenter: Boolean,
    screenBg: Color,
    centerSize: Dp,      // ← nuovo
    sideSize: Dp,         // ← nuovo
    spacing: Dp
) {
    val coverSize: Dp = if (isCenter) centerSize else sideSize
    val reflectionHeight: Dp = coverSize * 0.36f   // sempre proporzionale

    // Lo spacing laterale scala proporzionalmente alla cover
    val lateralSpacing = sideSize.value * 0.85f
    //val reflectionHeight: Dp = if (isCenter) 36.dp else 24.dp

    // ── Animazioni fluide al cambio di selectedIndex ─────────────────────────
    val animSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    // rotationY: centro = 0°, laterali = ±55°
    val targetRotY = if (isCenter) 0f else offsetFromCenter.sign * 55f
    val rotY by animateFloatAsState(targetRotY, animSpec, label = "rotY")

    // Traslazione X: sposta le cover laterali
    val targetTransX = offsetFromCenter * spacing.value
    val transX by animateFloatAsState(targetTransX, animSpec, label = "transX")

    // Scala: le cover laterali sono più piccole
    val targetScale = if (isCenter) 1f else (1f - abs(offsetFromCenter) * 0.08f).coerceAtLeast(0.7f)
    val scale by animateFloatAsState(targetScale, animSpec, label = "scale")

    // Alpha: le cover più lontane si dissolvono
    val targetAlpha = if (isCenter) 1f else (1f - abs(offsetFromCenter) * 0.2f).coerceAtLeast(0.3f)
    val alpha by animateFloatAsState(targetAlpha, animSpec, label = "alpha")

    val imageUrl = album.images?.firstOrNull()?.url

    Box(
        modifier = Modifier
            .size(width = coverSize, height = coverSize + reflectionHeight)
            .graphicsLayer {
                this.translationX = transX.dp.toPx()
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
                cameraDistance = 10f * density
            },
        contentAlignment = Alignment.TopCenter
    ) {
        // ── Riflesso ─────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(width = coverSize, height = reflectionHeight)
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    scaleY = -1f          // capovolto
                    rotationY = rotY
                    cameraDistance = 10f * density
                    this.alpha = 0.35f
                }
        ) {
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            }
            // Gradiente dissolvenza sopra il riflesso
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                screenBg,
                                screenBg.copy(alpha = 0.1f)
                            )
                        )
                    )
            )
        }

        // ── Cover principale ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(coverSize)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    rotationY = rotY
                    cameraDistance = 10f * density
                }
                .then(
                    if (isCenter) Modifier.shadow(12.dp, RoundedCornerShape(4.dp))
                    else Modifier.shadow(4.dp, RoundedCornerShape(3.dp))
                )
                .clip(RoundedCornerShape(if (isCenter) 4.dp else 3.dp))
        ) {
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = album.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Placeholder con gradiente scuro
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF3A3A3A), Color(0xFF1A1A1A))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = Color.White.copy(alpha = 0.4f), fontSize = 28.sp)
                }
            }

            // Gloss leggero (solo sulla cover centrale)
            if (isCenter) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.18f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 120f
                            )
                        )
                )
            }
        }
    }
}