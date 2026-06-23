package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.train.ipodclassicemulator.ui.theme.IPodTheme
import com.train.ipodclassicemulator.ui.theme.MontserratFontFamily
import androidx.compose.ui.geometry.Offset
import com.train.ipodclassicemulator.R

private val selectedGradient = Brush.verticalGradient(listOf(Color(0xFF0088F8), Color(0xFF0055E0)))
private val unselectedGradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF9F9F9)))

/**
 * Generic row item for Playlists, Albums, Artists, and Tracks lists.
 *
 * @param title        Primary bold text
 * @param subtitle     Secondary smaller text
 * @param imageUrl     URL for the thumbnail (null shows a grey placeholder)
 * @param imageModel   Optional override for the image model (e.g. a drawable resource)
 * @param isSelected   Whether this row is currently highlighted
 * @param isCircleImage Whether to clip the thumbnail as a circle (used for Artists)
 * @param scaleImage   Optional scale factor for the image (used for Favorites cover)
 * @param showArrow    Whether to show the "›" chevron on the right
 */
@Composable
fun MediaListItem(
    title: String,
    subtitle: String,
    imageUrl: String?,
    imageModel: Any? = null,
    isSelected: Boolean,
    isCircleImage: Boolean = false,
    scaleImage: Float = 1f,
    showArrow: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bgBrush = if (isSelected) selectedGradient else unselectedGradient
    val textColor = if (isSelected) Color.White else Color.Black
    val subtitleColor = if (isSelected) Color.White.copy(alpha = 0.7f) else Color.Gray

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgBrush)
            .drawBehind {
                if (!isSelected) {
                    val strokeWidth = 0.5.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(
                        color = Color(0xFFE5E5EA),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth
                    )
                }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(if (isCircleImage) CircleShape else RoundedCornerShape(4.dp))
                .background(Color.LightGray)
        ) {
            val model = imageModel ?: imageUrl ?: "https://picsum.photos/100"
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scaleImage != 1f) Modifier.scale(scaleImage) else Modifier),
                contentScale = if (scaleImage != 1f) ContentScale.Fit else ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = MontserratFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            Text(
                text = subtitle,
                fontFamily = MontserratFontFamily,
                fontSize = 12.sp,
                color = subtitleColor
            )
        }

        if (showArrow) {
            Text(
                text = "›",
                fontFamily = MontserratFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else Color.LightGray
            )
        }
    }
}

/**
 * Simple text-only menu row (used for Main Menu and Spotify sub-menu).
 */
@Composable
fun IPodMenuRow(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = IPodTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) colors.screenSelectedBg else colors.screenBackground)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) colors.screenSelectedText else colors.screenText
        )
        Text(
            text = "›",
            fontSize = 16.sp,
            color = if (isSelected) colors.screenSelectedText else colors.screenText.copy(alpha = 0.5f)
        )
    }
}