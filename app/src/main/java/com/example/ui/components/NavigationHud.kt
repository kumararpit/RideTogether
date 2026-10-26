package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ManeuverType
import com.example.data.model.NavigationStep
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.RideGuidanceGreen
import com.example.ui.theme.RideGuidanceGreenDark
import com.example.ui.theme.RouteBlue
import com.example.ui.theme.SlateDark500
import com.example.ui.theme.SlateDark600
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

private val TabularNumericStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * The maneuver icon for RideTogether's own maneuver vocabulary.
 *
 * The vocabulary separates the manoeuvres the raw `(type, modifier)` pair
 * cannot: a bear-left is not a keep-left, a fork is not a merge, a ramp is
 * not a turn, and a roundabout exit is not a plain turn. Every icon used
 * here is a stock Material icon.
 */
fun getManeuverIcon(maneuver: ManeuverType): ImageVector = when (maneuver) {
    ManeuverType.DEPART -> Icons.Default.Navigation
    ManeuverType.ARRIVE -> Icons.Default.Flag
    ManeuverType.TURN_LEFT -> Icons.Default.TurnLeft
    ManeuverType.TURN_SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
    ManeuverType.TURN_SHARP_LEFT -> Icons.Default.TurnLeft
    ManeuverType.TURN_RIGHT -> Icons.Default.TurnRight
    ManeuverType.TURN_SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
    ManeuverType.TURN_SHARP_RIGHT -> Icons.Default.TurnRight
    ManeuverType.KEEP_LEFT -> Icons.Default.TurnSlightLeft
    ManeuverType.KEEP_RIGHT -> Icons.Default.TurnSlightRight
    ManeuverType.CONTINUE, ManeuverType.NEW_NAME -> Icons.Default.Navigation
    ManeuverType.ON_RAMP -> Icons.Default.RampRight
    ManeuverType.OFF_RAMP -> Icons.Default.RampRight
    ManeuverType.FORK_LEFT -> Icons.Default.ForkLeft
    ManeuverType.FORK_RIGHT -> Icons.Default.ForkRight
    ManeuverType.MERGE_LEFT, ManeuverType.MERGE_RIGHT -> Icons.Default.Merge
    ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT -> Icons.Default.ChangeCircle
    ManeuverType.END_OF_ROAD -> Icons.Default.Route
    ManeuverType.U_TURN -> Icons.Default.AltRoute
    ManeuverType.FERRY -> Icons.Default.CallMade
    ManeuverType.UNKNOWN -> Icons.Default.Navigation
}

/**
 * The live guidance card.
 *
 * The reference gives the maneuver its own high-contrast green surface: it is
 * the one element a rider reads at a glance while moving. The card carries
 * the distance, the maneuver, the road name when the engine reports one, the
 * following instruction, the lane strip (only when the engine supplied lane
 * data) and the audio/reroute controls.
 */
@Composable
fun TurnByTurnNavHeader(
    currentStep: NavigationStep?,
    nextStep: NavigationStep?,
    isOffRoute: Boolean,
    isRerouting: Boolean,
    onReroute: () -> Unit,
    distanceToNextManeuverMeters: Double = 0.0,
    isAudioMuted: Boolean = false,
    onToggleAudioMute: () -> Unit = {},
    /**
     * Distance (meters) beyond which the turn card is hidden. Mirrors Google Maps'
     * CheckThresholds(meters=) — when the next maneuver is farther than this, the
     * banner is suppressed so the map isn't cluttered with a turn the rider
     * cannot act on yet. Default 1000 m.
     */
    showThresholdMeters: Double = 1000.0,
    modifier: Modifier = Modifier
) {
    // Distance-based show/hide: hide the card when the maneuver is beyond the
    // threshold. The card fades in/out so the appearance isn't jarring.
    val shouldShowCard = currentStep != null && distanceToNextManeuverMeters <= showThresholdMeters

    val cardBg = when {
        isOffRoute -> Color(0xFF4A1717)
        isRerouting -> Color(0xFF3A2A0E)
        else -> RideGuidanceGreen
    }
    val accent = when {
        isOffRoute -> StatusEmergencyRed
        isRerouting -> AmberPrimary
        else -> RideGuidanceGreenDark
    }

    AnimatedVisibility(
        visible = shouldShowCard,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("nav_turn_header"),
        color = cardBg,
        shape = RoundedCornerShape(20.dp),
        border = if (isOffRoute) BorderStroke(1.dp, StatusEmergencyRed.copy(alpha = 0.6f)) else null,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Maneuver glyph — large, white, on the deeper green disc.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRerouting -> CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                    isOffRoute -> Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Off route",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                    else -> Icon(
                        imageVector = getManeuverIcon(currentStep?.maneuver ?: ManeuverType.UNKNOWN),
                        contentDescription = "Maneuver",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                when {
                    isOffRoute -> {
                        Text(
                            text = "Off route",
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                        Text(
                            text = "Recalculating route…",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    currentStep != null -> {
                        // Lane guidance is rendered only when this maneuver's own
                        // node carried lane data.
                        currentStep.lanes?.takeIf { !it.isEmpty }?.let { lanes ->
                            LaneGuidanceStrip(
                                guidance = lanes,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }
                        val formattedDist = if (distanceToNextManeuverMeters >= 1000) {
                            String.format(
                                java.util.Locale.US,
                                "%.1f km",
                                distanceToNextManeuverMeters / 1000.0
                            )
                        } else {
                            "${kotlin.math.round(distanceToNextManeuverMeters).toInt()} m"
                        }
                        Text(
                            text = formattedDist,
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            style = TabularNumericStyle,
                            maxLines = 1
                        )
                        Text(
                            text = currentStep.instruction,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val roadName = currentStep.roadName.trim()
                        if (roadName.isNotEmpty() && !roadName.equals("unnamed", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = RideGuidanceGreenDark,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = roadName,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (nextStep != null) {
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = "Then ${nextStep.instruction}",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "Follow route",
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "Heading toward the destination",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // Audio + reroute stay one tap away.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onToggleAudioMute,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("audio_mute_button")
                ) {
                    Icon(
                        imageVector = if (isAudioMuted) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isAudioMuted) "Unmute navigation voice"
                        else "Mute navigation voice",
                        tint = if (isAudioMuted) Color.White.copy(alpha = 0.55f) else Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }
                IconButton(
                    onClick = onReroute,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("reroute_button")
                ) {
                    if (isRerouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reroute",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * The bottom trip bar.
 *
 * Collapsed it mirrors the reference: the remaining time, the remaining
 * distance and the ETA, with a one-tap end action and a chevron that expands
 * the full telemetry (traveled distance, progress and the tracking toggle).
 * Nothing is lost — the detailed panel is one tap away — and the map gains
 * back the space the old always-open card occupied.
 */
@Composable
fun NavigationTripHud(
    destinationName: String,
    remainingDistance: String,
    remainingDuration: String,
    etaTime: String,
    isFollowMode: Boolean,
    onToggleFollowMode: () -> Unit,
    modifier: Modifier = Modifier,
    traveledDistance: String = "",
    startLocationName: String = "Start",
    progressFraction: Float = 0f,
    totalTripDistanceAndDuration: String = "",
    onEndNavigation: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("nav_trip_hud"),
        color = SlateDark800,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, SlateDark500),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetricCell(
                            icon = Icons.Default.Timer,
                            value = remainingDuration.ifBlank { "—" },
                            label = "Time remaining",
                            tint = StatusRidingGreen,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            icon = Icons.Default.Timer,
                            value = etaTime.ifBlank { "—" },
                            label = "ETA",
                            tint = StatusRidingGreen,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            icon = Icons.Default.Navigation,
                            value = remainingDistance.ifBlank { "—" },
                            label = "Remaining",
                            tint = RouteBlue,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            icon = Icons.Default.Route,
                            value = traveledDistance.ifBlank { "—" },
                            label = "Traveled",
                            tint = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AmberPrimary,
                        trackColor = SlateDark600
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$startLocationName → $destinationName",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (totalTripDistanceAndDuration.isNotBlank()) {
                                Text(
                                    text = "Planned · $totalTripDistanceAndDuration",
                                    color = TextTertiary,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isFollowMode) RouteBlue else SlateDark700)
                                .clickable { onToggleFollowMode() }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("toggle_follow_mode_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isFollowMode) Icons.Default.GpsFixed
                                    else Icons.Default.GpsNotFixed,
                                    contentDescription = if (isFollowMode) "Following rider"
                                    else "Free pan mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isFollowMode) "Tracking" else "Free pan",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Collapsed summary row (always visible).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onEndNavigation != null) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A1A1A))
                            .clickable(onClick = onEndNavigation)
                            .testTag("end_navigation_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "End navigation",
                            tint = StatusEmergencyRed,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = remainingDuration.ifBlank { "—" },
                        color = TextPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        style = TabularNumericStyle,
                        maxLines = 1
                    )
                    Text(
                        text = buildString {
                            if (remainingDistance.isNotBlank()) append(remainingDistance)
                            if (etaTime.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(etaTime)
                            }
                            if (isEmpty()) append(destinationName)
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("expand_trip_hud_button")
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                        else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (expanded) "Hide trip details" else "Show trip details",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(7.dp))
        Column {
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                style = TabularNumericStyle,
                maxLines = 1
            )
            Text(
                text = label,
                color = TextTertiary,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}
