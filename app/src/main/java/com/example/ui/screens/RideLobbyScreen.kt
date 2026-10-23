package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.LatLng
import com.example.data.model.Ride
import com.example.data.model.RideRole
import com.example.data.model.RiderMember
import com.example.ui.components.RideMapCanvas
import com.example.ui.components.RiderStatusBadge
import com.example.ui.components.RouteItineraryCard
import com.example.ui.components.RouteOptionSelector
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.CustomIcons
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

private val DarkBackground = com.example.ui.theme.SlateDark900
private val CardSurface = com.example.ui.theme.SlateDark800
private val CardBorderColor = com.example.ui.theme.SlateDark500
private val StartOrange = com.example.ui.theme.AmberPrimary
private val AccentCyan = com.example.ui.theme.TextSecondary

@Composable
fun RideLobbyScreen(
    ride: Ride,
    members: List<RiderMember>,
    routePoints: List<LatLng> = emptyList(),
    routeResult: com.example.data.model.RouteResult? = null,
    routeOptions: List<com.example.data.model.RouteOptionSummary> = emptyList(),
    onSelectRouteOption: (Int) -> Unit = {},
    onStartRide: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUser = members.find { it.isCurrentUser }
    val isLeader = currentUser?.role == RideRole.LEADER || ride.leaderId == currentUser?.id

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("ride_lobby_screen"),
        color = DarkBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
                // The docked Start Ride bar must never cover the last card.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Top App Bar Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("back_from_lobby_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ride.name,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${ride.startLocationName} → ${ride.destinationName}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join my motorcycle ride on RideTogether!\nRide: ${ride.name}\nInvite Code: ${ride.inviteCode}"
                                    )
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share ride invite"))
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("share_invite_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share invite",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 2. The route map — the plan the group is about to ride.
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(258.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(CardSurface)
                            .testTag("route_overview_card")
                    ) {
                        RideMapCanvas(
                            route = routePoints,
                            members = members,
                            selectedRider = null,
                            onSelectRider = {},
                            isFollowRiderMode = false,
                            initialStartLocation = ride.startLocation,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("lobby_map_preview")
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.55f to Color.Transparent,
                                        1f to SlateDark900.copy(alpha = 0.94f)
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "${ride.startLocationName} → ${ride.destinationName}",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Only measured values are shown: a ride whose route
                            // has not been planned yet has no distance or time.
                            val stats = buildList {
                                if (ride.totalDistanceMeters > 0) {
                                    add(String.format(Locale.US, "%.1f km", ride.totalDistanceMeters / 1000.0))
                                }
                                if (ride.totalDurationSeconds > 0) {
                                    add("${Math.round(ride.totalDurationSeconds / 60.0)} min")
                                }
                                val stopCount = (routeResult?.legs?.size ?: 1) - 1
                                if (stopCount > 0) add("$stopCount stop${if (stopCount == 1) "" else "s"}")
                            }
                            Text(
                                text = if (stats.isEmpty()) "Route being planned" else stats.joinToString(" · "),
                                color = AmberPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 3. The plan the group rides: every stop in order, then the
                //    leader's route alternatives when the engine offered some.
                val planStops = routeResult?.stops.orEmpty().ifEmpty {
                    listOfNotNull(
                        com.example.data.model.RouteStop(
                            name = ride.startLocationName,
                            latitude = ride.startLocation?.latitude ?: 0.0,
                            longitude = ride.startLocation?.longitude ?: 0.0,
                            role = com.example.data.model.RouteStopRole.ORIGIN
                        ),
                        ride.destinationLocation?.let {
                            com.example.data.model.RouteStop(
                                name = ride.destinationName,
                                latitude = it.latitude,
                                longitude = it.longitude,
                                role = com.example.data.model.RouteStopRole.DESTINATION
                            )
                        }
                    )
                }
                if (planStops.isNotEmpty() || routeOptions.size > 1) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("route_plan_card"),
                            color = CardSurface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, CardBorderColor)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Route",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                planStops.forEachIndexed { index, stop ->
                                    LobbyStopRow(
                                        stop = stop,
                                        index = index,
                                        isLast = index == planStops.lastIndex
                                    )
                                }

                                if (routeOptions.size > 1) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = if (isLeader) {
                                            "Choose the route for the group"
                                        } else {
                                            "Route chosen by the leader"
                                        },
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    RouteOptionSelector(
                                        options = routeOptions,
                                        selectedIndex = ride.selectedRouteIndex,
                                        onSelect = onSelectRouteOption,
                                        enabled = isLeader,
                                        showHeader = false
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Invite Code & Sharing Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = CardSurface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, CardBorderColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "INVITE CODE",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ride.inviteCode,
                                color = AmberPrimary,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 4.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Ride Code", ride.inviteCode)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Code ${ride.inviteCode} copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("copy_code_button"),
                                    border = BorderStroke(1.dp, CardBorderColor),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Code",
                                        tint = AmberPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Code", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "Join my motorcycle ride on RideTogether!\nRide: ${ride.name}\nInvite Code: ${ride.inviteCode}"
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Ride Invite Code"))
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("share_invite_card_button"),
                                    border = BorderStroke(1.dp, CardBorderColor),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share Invite",
                                        tint = AccentCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share Invite", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // 5. Riders Ready Section Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RIDERS (${members.size})",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Invite code ${ride.inviteCode}",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Rider List Cards
                items(members, key = { it.id }) { rider ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = CardSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, CardBorderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(rider.avatarColorHex)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = rider.name.take(1).uppercase(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val isRiderLeader = (rider.role == RideRole.LEADER) || (rider.id == ride.leaderId)
                                    if (isRiderLeader) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(AmberPrimary.copy(alpha = 0.25f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = "Leader",
                                                    tint = AmberPrimary,
                                                    modifier = Modifier.size(10.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = "LEADER",
                                                    color = AmberPrimary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                if (rider.motorcycleModel.isNotBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = CustomIcons.MotorcycleLogo,
                                            contentDescription = "Motorcycle",
                                            tint = AccentCyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = rider.motorcycleModel,
                                            color = AccentCyan,
                                            fontSize = 12.sp
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (rider.isCurrentUser) "Ride Host" else "Ready to roll",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            RiderStatusBadge(
                                status = rider.status,
                                speedKmh = rider.speedKmh,
                                stoppedDurationSec = rider.stoppedDurationSec,
                                connectionStatus = rider.connectionStatus
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }

            // Stable Bottom Start Ride Action Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                DarkBackground.copy(alpha = 0.95f),
                                DarkBackground
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onStartRide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("start_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color(0xFF1A1205)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF1A1205),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Ride",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


/**
 * One row of the lobby's route plan: a marker, the stop name and its role.
 * The marker colours follow the map legend (green origin, amber stops, red
 * destination) so the list and the map read as the same plan.
 */
@Composable
private fun LobbyStopRow(
    stop: com.example.data.model.RouteStop,
    index: Int,
    isLast: Boolean
) {
    val isOrigin = stop.role == com.example.data.model.RouteStopRole.ORIGIN
    val isDestination = stop.role == com.example.data.model.RouteStopRole.DESTINATION
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            isOrigin -> com.example.ui.theme.StatusRidingGreen
                            isDestination -> com.example.ui.theme.StatusEmergencyRed
                            else -> AmberPrimary
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isDestination) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = Color(0xFF1A1205),
                        modifier = Modifier.size(10.dp)
                    )
                } else if (!isOrigin) {
                    Text(
                        text = "$index",
                        color = Color(0xFF1A1205),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(26.dp)
                        .background(CardBorderColor)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 4.dp)) {
            Text(
                text = if (isOrigin) "Starting point" else if (isDestination) "Destination" else "Stop $index",
                color = TextTertiary,
                fontSize = 10.sp
            )
            Text(
                text = stop.name,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
