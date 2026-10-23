package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.example.data.amenities.OpenStatus
import com.example.data.amenities.PlaceCard
import com.example.ui.map.AmenityPalette
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * The place card.
 *
 * It reproduces the **information hierarchy** a rider expects from a place
 * card — the place's own photo, its name, what it is, how it is rated, whether
 * it is open, how far away it is and how to reach it — in RideTogether's own
 * visual identity. Every line is shown **only when the data carries it**: a
 * place without a rating has no rating row, a place whose opening hours
 * cannot be read has no "Open" badge, and a place without a photo shows the
 * category symbol instead of a broken image.
 */
@Composable
fun PlaceCard(
    card: PlaceCard,
    categoryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    categoryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().testTag("place_card")) {

        // The hero: the place's own photo when OSM carries one, otherwise a
        // category-tinted panel with the same symbol used on the map. Both
        // paths produce the same information hierarchy: image, name, facts.
        if (card.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(card.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = card.name?.let { "Photo of $it" } ?: "Photo of the place",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SlateDark800)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                categoryColor.copy(alpha = 0.45f),
                                categoryColor.copy(alpha = 0.12f),
                                SlateDark800
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = categoryIcon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(46.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name ?: card.categoryName,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 27.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = card.categoryName,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            card.distanceText?.let {
                Spacer(modifier = Modifier.width(10.dp))
                DistanceChip(it, categoryColor)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // The rating, when the place has one.
        card.rating?.let { ratingValue ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("place_card_rating")
            ) {
                Text(
                    text = card.ratingText ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                StarRow(ratingValue)
                card.reviewCount?.let { count ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "($count)",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // The opening status, when the hours could be read.
        card.openStatus?.let { status ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("place_card_open_status")
            ) {
                val color = when (status.state) {
                    OpenStatus.State.OPEN -> StatusRidingGreen
                    OpenStatus.State.CLOSED -> StatusEmergencyRed
                    OpenStatus.State.UNKNOWN -> TextSecondary
                }
                Text(
                    text = status.text,
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // The address, when the place has one.
        card.address?.takeIf { it.isNotBlank() }?.let { address ->
            Text(
                text = address,
                color = TextTertiary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/** The distance chip of the place card. */
@Composable
private fun DistanceChip(distanceText: String, categoryColor: Color) {
    Surface(
        color = categoryColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = distanceText,
                color = categoryColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "away",
                color = TextTertiary,
                fontSize = 10.sp
            )
        }
    }
}

/** A row of five stars, filled up to the rating. */
@Composable
private fun StarRow(rating: Double) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (index in 0 until 5) {
            val filled = rating >= index + 0.75
            Icon(
                imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (filled) Color(0xFFFFC107) else Color(0xFF6B7A99),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** The category colour of a place, in the map's current theme. */
@Composable
fun categoryColorFor(theme: AmenityPalette.Theme, type: com.example.data.amenities.AmenityType): Color =
    AmenityPalette.colorFor(type, theme)
