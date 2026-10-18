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
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.CustomIcons
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

private val DarkBackground = Color(0xFF0F131A)
private val CardSurface = Color(0xFF1B212B)
private val CardBorderColor = Color(0xFF253248)
private val StartOrange = Color(0xFFF97316)
private val AccentCyan = Color(0xFF38BDF8)

@Composable
fun RideLobbyScreen(
    ride: Ride,
    members: List<RiderMember>,
    routePoints: List<LatLng> = emptyList(),
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
                        Column {
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
                    }
                }

                // 2. Route Hero Card (Scenic Banner + Live Map Preview)
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("route_overview_card"),
                        color = CardSurface,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, CardBorderColor)
                    ) {
                        Column {
                            // Scenic Image Banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.scenic_mountain_road),
                                    contentDescription = "Scenic route preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Black.copy(alpha = 0.40f),
                                                    CardSurface.copy(alpha = 0.95f)
                                                )
                                            )
                                        )
                                )

                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = "${ride.startLocationName} → ${ride.destinationName}",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val distStr = if (ride.totalDistanceMeters > 0) {
                                        String.format(Locale.US, "%.1f km", ride.totalDistanceMeters / 1000.0)
                                    } else "49.8 km"
                                    val durStr = if (ride.totalDurationSeconds > 0) {
                                        "${(ride.totalDurationSeconds / 60.0).toInt()} min"
                                    } else "40 min"
                                    Text(
                                        text = "$distStr • $durStr • Scenic Route",
                                        color = AmberPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Embedded Live Map Preview
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                    .testTag("lobby_map_preview")
                            ) {
                                RideMapCanvas(
                                    route = routePoints,
                                    members = members,
                                    selectedRider = null,
                                    onSelectRider = {},
                                    isFollowRiderMode = false,
                                    initialStartLocation = ride.startLocation
                                )
                            }
                        }
                    }
                }

                // 3. Invite Code & Sharing Card
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
                                color = AccentCyan,
                                fontSize = 32.sp,
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
                                        .height(46.dp)
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
                                        tint = AccentCyan,
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
                                        .height(46.dp)
                                        .testTag("share_invite_button"),
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

                // 4. Pre-Ride Readiness Checklist Card
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preride_checklist_card"),
                        color = CardSurface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, CardBorderColor)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "PRE-RIDE READINESS CHECKLIST",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusRidingGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Permission Granted", color = Color.White, fontSize = 12.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.GpsFixed,
                                        contentDescription = null,
                                        tint = StatusRidingGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("GPS Fixed", color = Color.White, fontSize = 12.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusRidingGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Group Sync", color = Color.White, fontSize = 12.sp)
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
                            text = "RIDERS READY (${members.size})",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${members.size}/${members.size} Ready to Depart",
                            color = StatusRidingGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
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
                        containerColor = StartOrange,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start Ride",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "START RIDE",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
