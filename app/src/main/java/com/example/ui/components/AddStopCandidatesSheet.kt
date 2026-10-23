package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityType
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * The list of places the rider can add to the ride as a stop.
 *
 * It is the RideTogether equivalent of the reference's "Add stops to your
 * route" sheet: the places that are on the map right now, with their
 * category, their distance, and — when the price of the stop is known — the
 * detour badge. Tapping a row selects that place, which opens its own place
 * sheet with the contextual actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStopCandidatesSheet(
    candidates: List<AmenityEntity>,
    riderLocation: LatLng?,
    detourSecondsById: Map<Long, Double>,
    onSelect: (AmenityEntity) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = com.example.ui.theme.SlateDark800
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag("add_stop_candidates_sheet")) {
            Text(
                text = "Add a stop",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Text(
                text = "Places on the map that the ride can pass through",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (candidates.isEmpty()) {
                Text(
                    text = "No places on the map yet. They appear as the map loads them.",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag("add_stop_candidates_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 24.dp
                )
            ) {
                items(candidates, key = { it.osmId }) { amenity ->
                    CandidateRow(
                        amenity = amenity,
                        distanceMeters = distanceTo(riderLocation, amenity),
                        detourSeconds = detourSecondsById[amenity.osmId],
                        onClick = { onSelect(amenity) }
                    )
                }
            }
        }
    }
}

/** One row of the candidate list. */
@Composable
private fun CandidateRow(
    amenity: AmenityEntity,
    distanceMeters: Double?,
    detourSeconds: Double?,
    onClick: () -> Unit
) {
    val categoryColor = AmenityIconConfig.getCategoryColor(amenity.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("candidate_row_${amenity.osmId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(categoryColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AmenityIconConfig.getCategoryIcon(amenity.type),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = amenity.name?.takeIf { it.isNotBlank() }
                    ?: AmenityIconConfig.getCategoryDisplayName(amenity.type),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(AmenityIconConfig.getCategoryDisplayName(amenity.type))
                    distanceMeters?.let {
                        append(" · ")
                        append(if (it >= 1000) {
                            String.format(java.util.Locale.US, "%.1f km", it / 1000.0)
                        } else {
                            "${Math.round(it).toInt()} m"
                        })
                    }
                },
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        // The price of the stop, when it is known.
        detourSeconds?.let { seconds ->
            val label = com.example.data.model.AmenityStopPlan.detourLabel(seconds)
            if (label != null) {
                Surface(
                    color = AmberPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = label,
                        color = AmberPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.AddLocationAlt,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** The distance from the rider to a place, or null when it is unknown. */
private fun distanceTo(riderLocation: LatLng?, amenity: AmenityEntity): Double? {
    if (riderLocation == null) return null
    val results = FloatArray(1)
    android.location.Location.distanceBetween(
        riderLocation.latitude, riderLocation.longitude,
        amenity.lat, amenity.lon,
        results
    )
    return results[0].toDouble()
}

/** The categories a candidate can be filtered by. */
val amenityCategoryOrder: List<AmenityType> = listOf(
    AmenityType.FUEL,
    AmenityType.CAFE,
    AmenityType.RESTAURANT,
    AmenityType.REPAIR,
    AmenityType.REST_STOP,
    AmenityType.CONVENIENCE_STORE,
    AmenityType.HOTEL,
    AmenityType.TOILET,
    AmenityType.SCENIC_VIEWPOINT
)
