package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

/**
 * Screen 11 of the reference: the route after the stop was added.
 *
 * It renders the authoritative committed plan — origin, ordered stops and
 * destination — with the newly added stop called out and the measured cost
 * of the change. There is no second state to reconcile: the list is the
 * plan the group was given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatedRouteSheet(
    stops: List<RouteStop>,
    addedStopName: String?,
    detourSeconds: Double?,
    addedDistanceMeters: Double?,
    totalDistanceMeters: Double?,
    totalDurationSeconds: Double?,
    onDone: () -> Unit
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDone,
        containerColor = SlateDark800
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .testTag("updated_route_sheet")
        ) {
            Text(
                text = "Route updated",
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (addedStopName != null) {
                    "Your ride now passes through $addedStopName."
                } else {
                    "The new plan is on every rider's map."
                },
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            RTCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                var intermediateIndex = 0
                stops.forEach { stop ->
                    val isAdded = addedStopName != null &&
                        stop.role == RouteStopRole.INTERMEDIATE &&
                        stop.name == addedStopName
                    val number = if (stop.role == RouteStopRole.INTERMEDIATE) {
                        intermediateIndex += 1
                        intermediateIndex
                    } else {
                        0
                    }
                    StopRow(
                        stop = stop,
                        number = number,
                        isAdded = isAdded,
                        detourSeconds = if (isAdded) detourSeconds else null,
                        addedDistanceMeters = if (isAdded) addedDistanceMeters else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (totalDistanceMeters != null && totalDurationSeconds != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "New route",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${formatTotalDistance(totalDistanceMeters)} · ${formatTotalDuration(totalDurationSeconds)}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            PrimaryButton(
                text = "Done",
                onClick = onDone,
                testTag = "updated_route_done"
            )
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

@Composable
private fun StopRow(
    stop: RouteStop,
    number: Int,
    isAdded: Boolean,
    detourSeconds: Double?,
    addedDistanceMeters: Double?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (isAdded) Modifier.background(AmberPrimary.copy(alpha = 0.07f)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (stop.role) {
            RouteStopRole.ORIGIN -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(StatusRidingGreen),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            RouteStopRole.DESTINATION -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(AmberPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = Color(0xFF1A1205),
                    modifier = Modifier.size(13.dp)
                )
            }
            else -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(SlateDark700),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stop.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isAdded) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (stop.role) {
                    RouteStopRole.ORIGIN -> "Starting point"
                    RouteStopRole.DESTINATION -> "Destination"
                    else -> "Stop $number"
                },
                color = TextTertiary,
                fontSize = 11.sp
            )
        }

        if (isAdded) {
            val detourLabel = detourSeconds?.let { com.example.data.model.AmenityStopPlan.detourLabel(it) }
            StatusChip(
                text = detourLabel ?: formatDistanceDelta(addedDistanceMeters),
                color = AmberPrimary,
                testTag = "added_stop_chip"
            )
        }
    }
}

private fun formatTotalDistance(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000.0)
    else "${Math.round(meters).toInt()} m"

private fun formatTotalDuration(seconds: Double): String {
    val minutes = Math.round(seconds / 60.0).toInt()
    return if (minutes >= 60) {
        val h = minutes / 60
        val m = minutes % 60
        if (m > 0) "${h}h ${m}m" else "${h}h"
    } else {
        "$minutes min"
    }
}
