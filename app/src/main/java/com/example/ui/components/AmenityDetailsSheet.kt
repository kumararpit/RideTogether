package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.LocalTime
import com.example.data.amenities.PlaceCard
import com.example.data.amenities.PlaceCardBuilder
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * The place sheet.
 *
 * It reproduces the **information hierarchy** of a place card — photo, name,
 * category, rating, opening status, distance and clear actions — in
 * RideTogether's own visual identity, and it offers the same **contextual**
 * actions the reference offers:
 *
 *  - **Add stop** — the place becomes a stop of this ride. The action carries
 *    the price of the stop ("+2 min") as soon as a reliable estimate exists.
 *  - **Directions** — the place becomes the destination of the ride.
 *  - **Save place** — the place goes on the rider's own shortlist.
 *  - **Show on map** — the map centres on the place.
 *
 * Every action happens **inside RideTogether**: the ride, the group, the plan
 * and the telemetry never leave the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmenityDetailsSheet(
    amenity: AmenityEntity,
    riderLocation: LatLng?,
    onAddAsStop: (() -> Unit)? = null,
    onDirections: (() -> Unit)? = null,
    onToggleSave: (() -> Unit)? = null,
    onShowOnMap: (() -> Unit)? = null,
    detourSeconds: Double? = null,
    isEstimatingDetour: Boolean = false,
    isSaved: Boolean = false,
    onDismiss: () -> Unit
) {
    val categoryColor = AmenityIconConfig.getCategoryColor(amenity.type)
    val categoryIcon = AmenityIconConfig.getCategoryIcon(amenity.type)
    val categoryName = AmenityIconConfig.getCategoryDisplayName(amenity.type)

    val distanceMeters: Double? = riderLocation?.let {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            it.latitude, it.longitude,
            amenity.lat, amenity.lon,
            results
        )
        results[0].toDouble()
    }

    val card: PlaceCard = PlaceCardBuilder.build(
        amenity = amenity,
        categoryName = categoryName,
        distanceMeters = distanceMeters,
        now = rememberNow()
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateDark900
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("amenity_details_sheet")
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            PlaceCard(
                card = card,
                categoryIcon = categoryIcon,
                categoryColor = categoryColor
            )

            Spacer(modifier = Modifier.height(18.dp))

            // The primary action: the place becomes a stop of THIS ride, and
            // the action carries the price of the stop as soon as it is known.
            if (onAddAsStop != null) {
                Button(
                    onClick = onAddAsStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("amenity_add_as_stop"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = addStopLabel(detourSeconds, isEstimatingDetour),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isEstimatingDetour) {
                        Spacer(modifier = Modifier.width(10.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // The secondary actions, in one row.
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                if (onDirections != null) {
                    SheetAction(
                        icon = Icons.Default.Directions,
                        label = "Directions",
                        onClick = onDirections,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (onToggleSave != null) {
                    SheetAction(
                        icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        label = if (isSaved) "Saved" else "Save place",
                        onClick = onToggleSave,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (onShowOnMap != null) {
                    SheetAction(
                        icon = Icons.Default.Place,
                        label = "Show on map",
                        onClick = onShowOnMap,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "The route is re-planned through this place and the group follows.",
                color = TextTertiary,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The label of the add-stop action.
 *
 * The price of the stop is shown as soon as a reliable estimate exists; while
 * the estimate is being made the action says so, and when no reliable
 * estimate is available the action is shown without a price rather than with
 * a made-up one.
 */
internal fun addStopLabel(detourSeconds: Double?, isEstimating: Boolean): String {
    if (isEstimating) return "Add stop…"
    if (detourSeconds == null) return "Add stop"
    val label = com.example.data.model.AmenityStopPlan.detourLabel(detourSeconds)
    return if (label != null) "Add stop · $label" else "Add stop"
}

/** One secondary action of the place sheet. */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** The current local time, for the opening-hours status. */
@Composable
private fun rememberNow(): LocalTime? {
    val calendar = java.util.Calendar.getInstance()
    val dayOfWeek = when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
        java.util.Calendar.MONDAY -> 1
        java.util.Calendar.TUESDAY -> 2
        java.util.Calendar.WEDNESDAY -> 3
        java.util.Calendar.THURSDAY -> 4
        java.util.Calendar.FRIDAY -> 5
        java.util.Calendar.SATURDAY -> 6
        else -> 7
    }
    val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
        calendar.get(java.util.Calendar.MINUTE)
    return LocalTime(dayOfWeek, minutes)
}
