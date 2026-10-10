package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TwoWheeler
import com.example.data.db.CompletedRideEntity
import com.example.data.model.Ride
import com.example.data.model.RideStatus
import com.example.ui.components.RideHistorySheet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberDark
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.SurfaceCard

@Composable
fun WelcomeScreen(
    currentName: String,
    onNameChange: (String) -> Unit,
    currentMotorcycle: String = "",
    onMotorcycleChange: (String) -> Unit = {},
    activeRide: Ride? = null,
    onRejoinRideClick: () -> Unit = {},
    onCreateRideClick: () -> Unit,
    onJoinRideClick: () -> Unit,
    onSignOutClick: (() -> Unit)? = null,
    isSignedIn: Boolean = false,
    userEmail: String? = null,
    pastRides: List<CompletedRideEntity> = emptyList(),
    modifier: Modifier = Modifier
) {
    var nameInput by remember { mutableStateOf(currentName) }
    var bikeInput by remember { mutableStateOf(currentMotorcycle) }
    var isEditingProfile by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("welcome_screen"),
        color = SlateDark900
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Compact Header Branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AmberPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TwoWheeler,
                        contentDescription = "RideTogether",
                        tint = AmberPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "RideTogether",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "Real-time group motorcycle convoy tracking",
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                }
            }

            // Rejoin Active Ride Banner (if a ride session is active)
            if (activeRide != null && activeRide.status != RideStatus.COMPLETED && activeRide.status != RideStatus.CANCELLED) {
                Surface(
                    color = StatusRidingGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusRidingGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rejoin_active_ride_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🟢", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "CONVOY IN PROGRESS",
                                    color = StatusRidingGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Code: ${activeRide.inviteCode}",
                                color = AmberPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = activeRide.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "${activeRide.startLocationName} → ${activeRide.destinationName}",
                            color = Color(0xFFCFD8DC),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = onRejoinRideClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusRidingGreen,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("rejoin_ride_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = "Rejoin",
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "REJOIN LIVE RIDE",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            // Rider Identity Profile Summary
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AmberPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (currentName.ifBlank { "Rider" }).take(1).uppercase(),
                                    color = SlateDark900,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = currentName.ifBlank { "Rider" },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (currentMotorcycle.isNotBlank()) "🏍️ $currentMotorcycle" else "Motorcycle not set",
                                    color = if (currentMotorcycle.isNotBlank()) AmberPrimary else Color(0xFF90A4AE),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SlateDark800)
                                .clickable { isEditingProfile = !isEditingProfile }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Profile",
                                tint = AmberPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isEditingProfile) "Done" else "Edit",
                                color = AmberPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    AnimatedVisibility(visible = isEditingProfile) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            Text(
                                text = "RIDER NAME",
                                color = AmberPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = {
                                    nameInput = it
                                    onNameChange(it)
                                },
                                leadingIcon = { Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = AmberLight) },
                                placeholder = { Text("Enter rider name", color = Color(0xFF78909C)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AmberPrimary,
                                    unfocusedBorderColor = SlateDark700,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("rider_name_input"),
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "MOTORCYCLE MODEL (OPTIONAL)",
                                color = Color(0xFF90A4AE),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = bikeInput,
                                onValueChange = {
                                    bikeInput = it
                                    onMotorcycleChange(it)
                                },
                                leadingIcon = { Icon(imageVector = Icons.Default.TwoWheeler, contentDescription = null, tint = AmberLight) },
                                placeholder = { Text("e.g. Triumph Tiger 900, Duke 390", color = Color(0xFF78909C)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AmberPrimary,
                                    unfocusedBorderColor = SlateDark700,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("motorcycle_model_input"),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }

            // Primary Action Buttons (56dp Large Touch Targets for Gloves)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onCreateRideClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("create_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create Group Ride",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onJoinRideClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("join_ride_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.GroupAdd, contentDescription = null, tint = AmberLight)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Join Ride with Code",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (pastRides.isNotEmpty()) {
                    var selectedHistoryRideForDetails by remember { mutableStateOf<CompletedRideEntity?>(null) }

                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("home_history_section")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "History",
                                        tint = AmberPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "RECENT RIDE HISTORY",
                                        color = AmberPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = "View All (${pastRides.size}) →",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showHistorySheet = true }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Show top 3 recent history rides directly on home page
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                pastRides.take(3).forEach { ride ->
                                    Surface(
                                        color = SlateDark800,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedHistoryRideForDetails = ride }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = ride.rideName,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "${ride.startLocationName} → ${ride.destinationName}",
                                                    color = Color(0xFFCFD8DC),
                                                    fontSize = 11.sp
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "🛣️ ${ride.formattedDistance} • ⏱️ ${ride.formattedDuration} • ${ride.riderCount} rider(s)",
                                                    color = AmberPrimary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                            Text(
                                                text = "Details →",
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

                    if (selectedHistoryRideForDetails != null) {
                        com.example.ui.components.RideDetailsDialog(
                            ride = selectedHistoryRideForDetails!!,
                            onDismiss = { selectedHistoryRideForDetails = null }
                        )
                    }
                }

                // Cloud Status Pill
                Surface(
                    color = SlateDark800,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Cloud Sync",
                            tint = if (isSignedIn) StatusRidingGreen else Color(0xFF78909C),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSignedIn) {
                                "Cloud Sync Active • ${userEmail ?: currentName}"
                            } else {
                                "Local Mode • Sign in required when joining remote rides"
                            },
                            color = if (isSignedIn) StatusRidingGreen else Color(0xFF90A4AE),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (isSignedIn && onSignOutClick != null) {
                    androidx.compose.material3.TextButton(
                        onClick = onSignOutClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sign_out_button")
                    ) {
                        Text(
                            text = "Sign Out Account",
                            color = Color(0xFFEF5350),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (showHistorySheet) {
            RideHistorySheet(
                rides = pastRides,
                onDismiss = { showHistorySheet = false }
            )
        }
    }
}

