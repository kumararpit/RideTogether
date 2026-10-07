package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.ui.theme.StatusStoppedAmber

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
 * Google Maps-style Turn-by-Turn Navigation Header Banner (Top HUD).
 */
@Composable
fun TurnByTurnNavHeader(
    currentStep: NavigationStep?,
    nextStep: NavigationStep?,
    isOffRoute: Boolean,
    isRerouting: Boolean,
    onReroute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("nav_turn_header"),
        color = if (isOffRoute) Color(0xFF3E1A1A) else Color(0xFF0F2E1E), // Emerald navigation green or off-route dark red
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 10.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Maneuver Icon Tile
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isOffRoute) StatusEmergencyRed.copy(alpha = 0.25f)
                            else StatusRidingGreen.copy(alpha = 0.25f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRerouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AmberPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else if (isOffRoute) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Off route",
                            tint = StatusEmergencyRed,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        val icon = getManeuverIcon(
                            currentStep?.maneuverType ?: "turn",
                            currentStep?.modifier ?: "straight"
                        )
                        Icon(
                            imageVector = icon,
                            contentDescription = "Maneuver",
                            tint = StatusRidingGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Instruction & Road Name
                Column(modifier = Modifier.weight(1f)) {
                    if (isOffRoute) {
                        Text(
                            text = "Off Route",
                            color = StatusEmergencyRed,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Tap reroute to find road path",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    } else if (currentStep != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "In ${currentStep.formattedDistance}",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            text = currentStep.instruction,
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "Follow Road Route",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Heading toward destination",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }

                // 🔄 Reroute Button
                IconButton(
                    onClick = onReroute,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("reroute_button")
                ) {
                    if (isRerouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AmberPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reroute",
                            tint = if (isOffRoute) StatusEmergencyRed else AmberPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Next Step Sub-preview (if available)
            if (nextStep != null && !isOffRoute) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 62.dp)
                ) {
                    Text(
                        text = "Then: ${nextStep.instruction}",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Google Maps-style Trip / Navigation Summary HUD Bar (Remaining time, ETA, Distance).
 */
@Composable
fun NavigationTripHud(
    destinationName: String,
    remainingDistance: String,
    remainingDuration: String,
    etaTime: String,
    isFollowMode: Boolean,
    onToggleFollowMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("nav_trip_hud"),
        color = SlateDark900.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Remaining Duration & ETA
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = remainingDuration,
                        color = StatusRidingGreen,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• $etaTime ETA",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$remainingDistance remaining",
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • to $destinationName",
                        color = AmberPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Follow Bike Navigation Lock Button
            Surface(
                onClick = onToggleFollowMode,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("toggle_follow_mode_button"),
                color = if (isFollowMode) AmberPrimary else SlateDark800
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isFollowMode) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        contentDescription = "Follow bike",
                        tint = if (isFollowMode) SlateDark900 else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isFollowMode) "Tracking" else "Free Pan",
                        color = if (isFollowMode) SlateDark900 else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
