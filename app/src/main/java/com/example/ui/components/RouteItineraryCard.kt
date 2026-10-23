package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RouteResult
import com.example.data.model.buildItinerary
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Stop-by-stop itinerary for a route.
 *
 * A multi-waypoint route is a sequence of legs: origin -> stop 1 -> ... ->
 * destination. Each leg carries its own distance, duration and turn list, and
 * the leg boundaries are the exact vertices of the route geometry (the renderer
 * pins them so the drawn line never jumps across a stop).
 *
 * The card mirrors that structure: one row per leg, expandable to show the
 * individual maneuvers of that leg.
 */
@Composable
fun RouteItineraryCard(
    route: RouteResult?,
    modifier: Modifier = Modifier,
    title: String = "ITINERARY",
    initiallyExpandedLeg: Int? = null
) {
    val legs = remember(route) { buildItinerary(route) }
    if (legs.isEmpty()) return
    var expandedLeg by remember(route, initiallyExpandedLeg) {
        mutableStateOf(initiallyExpandedLeg)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Route,
                contentDescription = null,
                tint = AmberPrimary,
                modifier = Modifier.width(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))

        legs.forEach { leg ->
            val isExpanded = expandedLeg == leg.index
            val isLast = leg.index == legs.lastIndex

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expandedLeg = if (isExpanded) null else leg.index }
                    .testTag("itinerary_leg_${leg.index}"),
                color = SlateDark900,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SlateDark800)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isLast) AmberPrimary else SlateDark800),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${leg.index + 1}",
                                color = if (isLast) SlateDark900 else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${leg.fromName} → ${leg.toName}",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${leg.formattedDistance} • ${leg.formattedDuration}",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse leg" else "Expand leg",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            leg.steps.forEach { step ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Flag,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = step.instruction,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = formatLegDistance(step.distanceMeters),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLegDistance(distanceMeters: Double): String =
    if (distanceMeters >= 1000) {
        String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000.0)
    } else {
        "${kotlin.math.round(distanceMeters).toInt()} m"
    }
