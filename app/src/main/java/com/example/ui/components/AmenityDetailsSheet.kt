package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.amenities.AmenityEntity
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * The sheet that opens when the rider taps a place on the map.
 *
 * The reference implementation opens a place sheet whose primary action is
 * "Directions", and — once a route is on screen — "Add stop", which turns
 * the place into a waypoint of the route. This sheet is the RideTogether
 * equivalent: the place can be added as a **stop of the ride**, and the ride
 * is re-planned through it.
 *
 * There is deliberately **no hand-off to another navigation app**: the ride,
 * the route and the group stay inside RideTogether (the independent-stack
 * invariant).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmenityDetailsSheet(
    amenity: AmenityEntity,
    riderLocation: LatLng?,
    onAddAsStop: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val categoryColor = AmenityIconConfig.getCategoryColor(amenity.type)
    val categoryIcon = AmenityIconConfig.getCategoryIcon(amenity.type)
    val categoryName = AmenityIconConfig.getCategoryDisplayName(amenity.type)

    val distStr: String? = if (riderLocation != null) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            riderLocation.latitude, riderLocation.longitude,
            amenity.lat, amenity.lon,
            results
        )
        val dist = results[0]
        if (dist > 1000) {
            String.format(java.util.Locale.US, "%.1f km", dist / 1000f)
        } else {
            "${dist.toInt()} m"
        }
    } else null

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
            // Header row: category icon + name + distance chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category icon circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(categoryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = categoryName,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = amenity.name ?: categoryName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    Text(
                        text = categoryName,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                if (distStr != null) {
                    Surface(
                        color = categoryColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = distStr,
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
            }

            Spacer(modifier = Modifier.height(20.dp))

            // The primary action: the place becomes a stop of THIS ride and
            // the route is re-planned through it. There is no hand-off to
            // another navigation app — the group, the route and the plan stay
            // in RideTogether.
            if (onAddAsStop != null) {
                Button(
                    onClick = {
                        onAddAsStop()
                        onDismiss()
                    },
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
                        text = "Add as a stop",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "The route is re-planned through this place and the group follows.",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Secondary action: open the place in the map, centred on it.
            OutlinedButton(
                onClick = {
                    val uri = android.net.Uri.parse(
                        "geo:${amenity.lat},${amenity.lon}?q=${amenity.lat},${amenity.lon}" +
                            "(${amenity.name?.trim().orEmpty()})"
                    )
                    try {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    } catch (_: Exception) {
                        // A device without a map app simply keeps the sheet open.
                    }
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Show on map",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
