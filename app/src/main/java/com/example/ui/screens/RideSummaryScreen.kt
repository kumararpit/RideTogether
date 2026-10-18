package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ConnectionStatus
import com.example.data.model.MemberStatus
import com.example.data.model.Ride
import com.example.data.model.RiderMember
import com.example.ui.components.RideMapCanvas
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Design System Dark Carbon Color Tokens
private val DarkBackground = Color(0xFF0F131A)
private val CardSurface = Color(0xFF1B212B)
private val CardBorder = Color(0xFF2B3444)
private val PrimaryBlue = Color(0xFF2563EB)
private val AccentGreen = Color(0xFF22C55E)
private val AccentOrange = Color(0xFFF97316)
private val AccentPurple = Color(0xFFA855F7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideSummaryScreen(
    ride: Ride?,
    members: List<RiderMember>,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
    onViewRouteOnMap: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Metrics calculations from real ride data
    val distanceMeters = ride?.finalSummaryDistanceMeters ?: 0.0
    val durationSeconds = when {
        (ride?.elapsedDurationSeconds ?: 0.0) > 30.0 -> ride!!.elapsedDurationSeconds
        else -> ride?.totalDurationSeconds ?: 0.0
    }

    val distKm = if (distanceMeters > 0) {
        distanceMeters / 1000.0
    } else {
        ride?.startLocation?.distanceTo(ride.destinationLocation) ?: 0.0
    }

    val formattedDist = if (distKm > 0) {
        String.format(Locale.US, "%.1f km", distKm)
    } else {
        "0.0 km"
    }

    val durationMin = (durationSeconds / 60.0).toInt()
    val formattedDuration = when {
        durationMin >= 60 -> {
            val hrs = durationMin / 60
            val mins = durationMin % 60
            if (mins > 0) "${hrs}h ${mins}m" else "${hrs}h"
        }
        durationMin > 0 -> "${durationMin}m"
        else -> "--"
    }

    val avgSpeedKmh = if (durationSeconds > 60 && distKm > 0.5) {
        (distKm / (durationSeconds / 3600.0)).toInt()
    } else {
        0
    }
    val formattedSpeed = if (avgSpeedKmh in 5..180) "$avgSpeedKmh km/h" else "N/A"

    val riderCount = members.size.coerceAtLeast(1)
    val ridersLabel = if (riderCount == 1) "1 rider" else "$riderCount riders"

    val hasEmergency = members.any { it.status == MemberStatus.EMERGENCY }
    val disconnectedCount = members.count { it.connectionStatus == ConnectionStatus.DISCONNECTED }

    val safetySubtitle = when {
        hasEmergency -> "Emergency recorded during ride. Verify pack."
        disconnectedCount > 0 -> "$disconnectedCount rider(s) disconnected before completion."
        riderCount > 1 -> "All pack riders accounted for at destination."
        else -> "You made it to your destination."
    }

    // Format start and end date/time
    val createdAtMs = ride?.createdAtMs ?: System.currentTimeMillis()
    val startMs = if ((ride?.startedAtMs ?: 0L) > 0L) ride!!.startedAtMs else createdAtMs
    val endMs = startMs + (durationSeconds * 1000).toLong()

    val dateFormat = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US)
    val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
    val formattedDateRange = "${dateFormat.format(Date(startMs))} – ${timeFormat.format(Date(endMs))}"

    val routeTitle = if (ride != null && ride.startLocationName.isNotBlank() && ride.destinationName.isNotBlank()) {
        "${ride.startLocationName} → ${ride.destinationName}"
    } else ride?.name ?: "Pune → Lonavala"

    // Route overview map modal state ("View Route on Map")
    var showRouteMapDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("ride_summary_screen"),
        color = DarkBackground
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Scenic Mountain Journey Image Header
            item {
                ScenicJourneyHeader(
                    onBack = onBackToHome,
                    onShare = {
                        Toast.makeText(context, "Sharing ride summary...", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 2. Completion Status Banner
            item {
                RideCompletionHeader(
                    hasEmergency = hasEmergency,
                    safetySubtitle = safetySubtitle
                )
            }

            // 3. Route Identity Card
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    RideRouteHeaderCard(
                        routeTitle = routeTitle,
                        dateRangeText = formattedDateRange
                    )
                }
            }

            // 4. 2x2 Ride Statistics Grid
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    RideMetricsGrid(
                        distance = formattedDist,
                        duration = formattedDuration,
                        avgSpeed = formattedSpeed,
                        packSize = ridersLabel
                    )
                }
            }

            // 5. Congratulatory Card
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    RideCelebrationCard()
                }
            }

            // 6. Secondary Action — View Route on Map
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    RideSecondaryActionButton(
                        onClick = {
                            if (onViewRouteOnMap != null) {
                                onViewRouteOnMap()
                            } else {
                                showRouteMapDialog = true
                            }
                        }
                    )
                }
            }

            // Optional Rider Status List for Group Rides
            if (members.size > 1) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = "RIDER ARRIVAL STATUS",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                items(members, key = { it.id }) { rider ->
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        RiderStatusRow(rider = rider)
                    }
                }
            }

            // 7. Primary Action Button — Back to Home
            item {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 24.dp)
                ) {
                    Button(
                        onClick = onBackToHome,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("back_to_home_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Home",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Back to Home",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Route overview modal — read-only map sheet showing the completed ride route
            if (showRouteMapDialog) {
                ModalBottomSheet(
                    onDismissRequest = { showRouteMapDialog = false },
                    containerColor = DarkBackground,
                    modifier = Modifier.testTag("route_overview_sheet")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(640.dp)
                            .padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = "Route Overview",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            RideMapCanvas(
                                route = ride?.routePoints ?: emptyList(),
                                members = members,
                                selectedRider = null,
                                onSelectRider = {},
                                isFollowRiderMode = false,
                                onFollowRiderChange = {},
                                initialStartLocation = ride?.startLocation
                            )
                        }

                        Button(
                            onClick = { showRouteMapDialog = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .height(50.dp)
                                .testTag("close_route_map_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryBlue,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Close",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Close", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
    }
}

/**
 * Top scenic image header card with dark gradient and overlay action buttons.
 */
@Composable
private fun ScenicJourneyHeader(
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
    ) {
        // Local bundled vector scenic mountain road image
        Image(
            painter = painterResource(id = R.drawable.scenic_mountain_road),
            contentDescription = "Scenic mountain road journey",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Dark gradient overlay for text and button contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.60f),
                            Color.Transparent,
                            DarkBackground.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        // Top Action Bar Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular Glass Back Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Share Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable { onShare() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // More Options Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Green success checkmark badge and "Ride Completed Successfully!" heading.
 */
@Composable
private fun RideCompletionHeader(
    hasEmergency: Boolean,
    safetySubtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val checkColor = if (hasEmergency) StatusEmergencyRed else AccentGreen

        // Circular Green Success Checkmark Badge with Glow Ring
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(checkColor.copy(alpha = 0.20f))
                .border(2.dp, checkColor.copy(alpha = 0.40f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(checkColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasEmergency) Icons.Default.Warning else Icons.Default.Check,
                    contentDescription = "Success",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (hasEmergency) "Ride Ended — Emergency Alert" else "Ride Completed\nSuccessfully!",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 30.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = safetySubtitle,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Route identity card ("Pune → Lonavala" + date/time range + mini route thumbnail).
 */
@Composable
private fun RideRouteHeaderCard(
    routeTitle: String,
    dateRangeText: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardSurface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = routeTitle,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dateRangeText,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Mini Route/Map Thumbnail
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF151922))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.scenic_mountain_road),
                    contentDescription = "Route map preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Symmetrical 2x2 grid of ride statistics cards.
 */
@Composable
private fun RideMetricsGrid(
    distance: String,
    duration: String,
    avgSpeed: String,
    packSize: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RideMetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Navigation,
                iconBg = Color(0xFF1E3A8A),
                iconTint = Color(0xFF3B82F6),
                value = distance,
                label = "Total Distance"
            )
            RideMetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Timer,
                iconBg = Color(0xFF14532D),
                iconTint = AccentGreen,
                value = duration,
                label = "Total Duration"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RideMetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Speed,
                iconBg = Color(0xFF7C2D12),
                iconTint = AccentOrange,
                value = avgSpeed,
                label = "Average Speed"
            )
            RideMetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Group,
                iconBg = Color(0xFF581C87),
                iconTint = AccentPurple,
                value = packSize,
                label = "Pack Size"
            )
        }
    }
}

/**
 * Individual metric tile card with circular icon badge, value, and label.
 */
@Composable
private fun RideMetricCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    value: String,
    label: String
) {
    Surface(
        modifier = modifier,
        color = CardSurface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Icon Badge
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = value,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = label,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Congratulatory card ("Great Ride!").
 */
@Composable
private fun RideCelebrationCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardSurface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Glowing Trophy Icon Badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF451A03)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = "Trophy",
                    tint = AmberPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Great Ride!",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Another amazing journey together. Keep riding!",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Secondary action button ("View Route on Map").
 */
@Composable
private fun RideSecondaryActionButton(
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = "View Route",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "View Route on Map",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Compact row for rider arrival status (shown for group rides).
 */
@Composable
private fun RiderStatusRow(rider: RiderMember) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardSurface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(rider.avatarColorHex)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rider.name.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (rider.motorcycleModel.isNotBlank()) {
                    Text(
                        text = rider.motorcycleModel,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            val (statusText, statusColor) = when {
                rider.status == MemberStatus.EMERGENCY -> "SOS Alert" to StatusEmergencyRed
                rider.connectionStatus == ConnectionStatus.DISCONNECTED -> "Disconnected" to Color(0xFFEF5350)
                rider.status == MemberStatus.STOPPED -> "Stopped" to AmberPrimary
                else -> "Arrived" to StatusRidingGreen
            }

            Surface(
                color = statusColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}
