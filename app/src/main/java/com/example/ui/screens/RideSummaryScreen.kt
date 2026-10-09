package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionStatus
import com.example.data.model.MemberStatus
import com.example.data.model.Ride
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.SurfaceCard
import java.util.Locale

@Composable
fun RideSummaryScreen(
    ride: Ride?,
    members: List<RiderMember>,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distanceMeters = ride?.totalDistanceMeters ?: 0.0
    val durationSeconds = ride?.totalDurationSeconds ?: 0.0
    val distKm = if (distanceMeters > 0) distanceMeters / 1000.0 else (ride?.startLocation?.distanceTo(ride.destinationLocation) ?: 0.0)
    val formattedDist = if (distKm > 0) String.format(Locale.getDefault(), "%.1f km", distKm) else "0.0 km"

    val durationMin = (durationSeconds / 60.0).toInt()
    val formattedDuration = if (durationMin >= 60) {
        val hrs = durationMin / 60
        val mins = durationMin % 60
        if (mins > 0) "${hrs}h ${mins}m" else "${hrs}h"
    } else if (durationMin > 0) {
        "${durationMin} min"
    } else {
        "--"
    }

    val avgSpeedKmh = if (durationSeconds > 60 && distKm > 0.5) {
        (distKm / (durationSeconds / 3600.0)).toInt()
    } else {
        0
    }
    val formattedSpeed = if (avgSpeedKmh in 5..180) "$avgSpeedKmh km/h" else "N/A"

    val riderCount = members.size
    val ridersPlural = if (riderCount == 1) "1 rider" else "$riderCount riders"

    val hasEmergency = members.any { it.status == MemberStatus.EMERGENCY }
    val disconnectedCount = members.count { it.connectionStatus == ConnectionStatus.DISCONNECTED }
    val safetyStatusText = when {
        hasEmergency -> "Emergency recorded during ride. Verify all pack members."
        disconnectedCount > 0 -> "Ride completed. $disconnectedCount rider(s) disconnected before final check-in."
        else -> "All pack riders accounted for at destination check-in."
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("ride_summary_screen"),
        color = SlateDark900
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasEmergency) StatusEmergencyRed.copy(alpha = 0.2f)
                            else StatusRidingGreen.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasEmergency) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = if (hasEmergency) StatusEmergencyRed else StatusRidingGreen,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Ride Completed!",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = ride?.name ?: "Group Ride",
                    color = AmberPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = safetyStatusText,
                    color = if (hasEmergency) StatusEmergencyRed else Color(0xFFCFD8DC),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Metrics Summary Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "RECORDED METRICS",
                        color = AmberPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = null,
                                    tint = Color(0xFF90A4AE),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pack", color = Color(0xFF90A4AE), fontSize = 11.sp)
                            }
                            Text(
                                text = ridersPlural,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = Color(0xFF90A4AE),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Distance", color = Color(0xFF90A4AE), fontSize = 11.sp)
                            }
                            Text(
                                text = formattedDist,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = Color(0xFF90A4AE),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Avg Speed", color = Color(0xFF90A4AE), fontSize = 11.sp)
                            }
                            Text(
                                text = formattedSpeed,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Rider Completion Status List
            Text(
                text = "RIDER ARRIVAL STATUS",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(members, key = { it.id }) { rider ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SlateDark800,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
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
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (rider.motorcycleModel.isNotBlank()) rider.motorcycleModel else "Rider",
                                        color = Color(0xFF90A4AE),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            val (statusText, statusColor) = when {
                                rider.status == MemberStatus.EMERGENCY -> "SOS Alert" to StatusEmergencyRed
                                rider.connectionStatus == ConnectionStatus.DISCONNECTED -> "Disconnected" to Color(0xFFEF5350)
                                rider.status == MemberStatus.STOPPED -> "Stopped" to AmberPrimary
                                else -> "Arrived Safe" to StatusRidingGreen
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Return to Home button
            Button(
                onClick = onBackToHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("back_to_home_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberPrimary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Back to Home",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
