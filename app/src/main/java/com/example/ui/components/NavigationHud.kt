package com.example.ui.components

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
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Navigation
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.NavigationStep
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextSecondary

private val CardBorderColor = Color(0xFF253248)
private val PrimaryBlue = Color(0xFF2563EB)
private val AccentCyan = Color(0xFF38BDF8)

/**
 * Returns a suitable Material Icon for the given maneuver type and modifier.
 */
fun getManeuverIcon(maneuverType: String, modifier: String): ImageVector {
    val mod = modifier.lowercase()
    val type = maneuverType.lowercase()

    return when {
        type.contains("arrive") -> Icons.Default.Flag
        type.contains("roundabout") || type.contains("rotary") -> Icons.Default.ChangeCircle
        type.contains("fork") -> Icons.Default.AltRoute
        mod.contains("slight left") -> Icons.Default.TurnSlightLeft
        mod.contains("slight right") -> Icons.Default.TurnSlightRight
        mod.contains("sharp left") || mod == "left" -> Icons.Default.TurnLeft
        mod.contains("sharp right") || mod == "right" -> Icons.Default.TurnRight
        mod.contains("u-turn") || mod.contains("uturn") -> Icons.Default.AltRoute
        else -> Icons.Default.Navigation
    }
}

/**
 * Top Turn-by-Turn Navigation Header — Google Maps inspired high-contrast design.
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
    modifier: Modifier = Modifier
) {
    val cardBg = when {
        isOffRoute -> Color(0xFF3B1212)
        isRerouting -> Color(0xFF1E2812)
        else -> Color(0xFF0E1A30)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("nav_turn_header"),
        color = cardBg,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (isOffRoute) StatusEmergencyRed.copy(alpha = 0.5f) else CardBorderColor),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Maneuver Icon Tile — prominent circular blue badge
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isOffRoute -> StatusEmergencyRed
                            isRerouting -> AmberPrimary
                            else -> PrimaryBlue
                        }
                    ),
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
                        modifier = Modifier.size(28.dp)
                    )
                    else -> {
                        val icon = getManeuverIcon(
                            currentStep?.maneuverType ?: "turn",
                            currentStep?.modifier ?: "straight"
                        )
                        Icon(
                            imageVector = icon,
                            contentDescription = "Maneuver",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Distance + Road Name column
            Column(modifier = Modifier.weight(1f)) {
                when {
                    isOffRoute -> {
                        Text(
                            text = "Off Route",
                            color = StatusEmergencyRed,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                        Text(
                            text = "Recalculating route...",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    currentStep != null -> {
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
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1
                        )
                        Text(
                            text = currentStep.instruction,
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (nextStep != null) {
                            Text(
                                text = "Then: ${nextStep.instruction}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "Follow Route",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "Heading toward destination",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // Action Buttons (Mute & Reroute)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onToggleAudioMute,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("audio_mute_button")
                ) {
                    Icon(
                        imageVector = if (isAudioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isAudioMuted) "Unmute Navigation Voice" else "Mute Navigation Voice",
                        tint = if (isAudioMuted) TextSecondary else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onReroute,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("reroute_button")
                ) {
                    if (isRerouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AmberPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reroute",
                            tint = if (isOffRoute) StatusEmergencyRed else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom Navigation Metrics Panel — 3-Column Grid (ETA, Remaining Distance, Speed), Progress Bar, and Route Sub-Row.
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
    totalTripDistanceAndDuration: String = ""
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("nav_trip_hud"),
        color = Color(0xFF141A24),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, CardBorderColor),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // 3-Column Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Column 1: ETA
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF143825)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "ETA",
                            tint = StatusRidingGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = remainingDuration.ifBlank { "7 min" },
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1
                        )
                        Text(
                            text = "ETA ${etaTime.ifBlank { "12:45" }}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                // Column 2: Remaining Distance
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF152E52)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Remaining",
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = remainingDistance.ifBlank { "4.2 km" },
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1
                        )
                        Text(
                            text = "Remaining",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                // Column 3: Distance Traveled (Odometer) — replaces duplicate speed readout
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0C2E4E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = "Traveled",
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = traveledDistance.ifBlank { "0.0 km" },
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1
                        )
                        Text(
                            text = "Traveled",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = PrimaryBlue,
                trackColor = Color(0xFF253248)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sub-row: Route Name & Total Info + Follow Mode Toggle Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$startLocationName → $destinationName",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (totalTripDistanceAndDuration.isNotBlank()) {
                        Text(
                            text = totalTripDistanceAndDuration,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Follow / Tracking mode toggle pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isFollowMode) PrimaryBlue else SlateDark800)
                        .clickable { onToggleFollowMode() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("toggle_follow_mode_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isFollowMode) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                            contentDescription = if (isFollowMode) "Following rider" else "Free pan mode",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFollowMode) "Tracking" else "Free Pan",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
