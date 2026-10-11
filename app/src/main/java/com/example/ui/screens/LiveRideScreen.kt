package com.example.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ActiveSosBar
import com.example.ui.components.NavigationTripHud
import com.example.ui.components.QuickMessageSheet
import com.example.ui.components.RecentMessageToast
import com.example.ui.components.RideHeader
import com.example.ui.components.RideMapCanvas
import com.example.ui.components.RideSettingsSheet
import com.example.ui.components.RiderBottomSheet
import com.example.ui.components.RiderStatusBadge
import com.example.ui.components.SosAlertBanner
import com.example.ui.components.SosConfirmDialog
import com.example.ui.components.TurnByTurnNavHeader
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.viewmodel.RideViewModel

@Composable
fun LiveRideScreen(
    viewModel: RideViewModel,
    modifier: Modifier = Modifier
) {
    val ride by viewModel.currentRide.collectAsState()
    val members by viewModel.members.collectAsState()
    val selectedRider by viewModel.selectedRider.collectAsState()
    val activeSos by viewModel.activeSos.collectAsState()
    val recentMessage by viewModel.recentMessage.collectAsState()

    // Navigation & Routing states
    val isRerouting by viewModel.isRerouting.collectAsState()
    val isOffRoute by viewModel.isOffRoute.collectAsState()
    val isFollowMode by viewModel.isFollowRiderMode.collectAsState()
    val currentStep = viewModel.getCurrentNavigationStep()
    val nextStep = viewModel.getNextNavigationStep()
    val remainingDistText = viewModel.getFormattedRemainingDistance()
    val remainingDurationText = viewModel.getFormattedRemainingDuration()
    val etaText = viewModel.getFormattedEta()

    val showRidersSheet by viewModel.showRidersSheet.collectAsState()
    val showQuickMessageSheet by viewModel.showQuickMessageSheet.collectAsState()
    val showSosConfirmDialog by viewModel.showSosConfirmDialog.collectAsState()
    val showSettingsSheet by viewModel.showSettingsSheet.collectAsState()

    val currentUserId by viewModel.userId.collectAsState()
    val currentUser = members.find { it.isCurrentUser || it.id == currentUserId }
    val myUid = currentUser?.id ?: currentUserId

    val isMySosActive = activeSos != null && (
        activeSos?.riderId == myUid ||
        activeSos?.riderId == currentUserId
    )
    val isOtherRiderSos = activeSos != null && !isMySosActive

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Live Map View with road-following route and bike markers
        RideMapCanvas(
            route = viewModel.getRoutePoints(),
            members = members,
            selectedRider = selectedRider,
            onSelectRider = { viewModel.selectRider(it) },
            isFollowRiderMode = isFollowMode,
            onFollowRiderChange = { viewModel.setFollowRiderMode(it) }
        )

        // 2. Top Header, Turn-by-Turn Nav HUD, and Alert Overlay Stack
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            // Ride pack header
            RideHeader(
                rideName = ride?.name ?: "Group Ride",
                riderCount = members.size,
                inviteCode = ride?.inviteCode ?: "ABC123",
                onOpenRiders = { viewModel.openRidersSheet(true) },
                onOpenSettings = { viewModel.openSettingsSheet(true) }
            )

            // Google Maps-style Turn-by-Turn Navigation HUD
            val isAudioMuted by viewModel.navAudioGuidance.isMuted.collectAsState()
            TurnByTurnNavHeader(
                currentStep = currentStep,
                nextStep = nextStep,
                isOffRoute = isOffRoute,
                isRerouting = isRerouting,
                onReroute = { viewModel.rerouteFromCurrentLocation() },
                isAudioMuted = isAudioMuted,
                onToggleAudioMute = { viewModel.toggleAudioMute() }
            )

            // Alert banner if another rider triggered SOS
            if (isOtherRiderSos && activeSos != null) {
                val isAcked = activeSos!!.acknowledgedBy.contains(myUid) || activeSos!!.acknowledgedBy.contains(currentUserId)
                SosAlertBanner(
                    sosEvent = activeSos!!,
                    onViewLocation = { sos ->
                        val target = members.find { it.id == sos.riderId }
                        if (target != null) {
                            viewModel.selectRider(target)
                        } else {
                            viewModel.selectRider(
                                com.example.data.model.RiderMember(
                                    id = sos.riderId,
                                    name = sos.riderName,
                                    role = com.example.data.model.RideRole.MEMBER,
                                    status = com.example.data.model.MemberStatus.EMERGENCY,
                                    location = sos.location
                                )
                            )
                        }
                    },
                    onAcknowledge = { viewModel.acknowledgeSos(activeSos!!.id) },
                    isAcknowledged = isAcked
                )
            }

            // Banner if YOU have active SOS
            if (isMySosActive) {
                ActiveSosBar(
                    onResolve = { viewModel.resolveSos() },
                    acknowledgedCount = activeSos?.acknowledgedBy?.size ?: 0
                )
            }

            // Quick Message Toast
            RecentMessageToast(message = recentMessage)
        }

        // 3. Bottom Layer Stack: Trip Info HUD + Selected Rider Card + Action Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
    val userLocationUpdate by viewModel.locationTracker.currentLocation.collectAsState()

    // Selected Rider Quick Detail Card (if a rider marker was tapped)
    if (selectedRider != null) {
        val rider = selectedRider!!
        val myLoc = currentUser?.location ?: userLocationUpdate?.latLng
        val gapStr = if (myLoc != null && !rider.isCurrentUser) "📏 Gap: ${rider.getFormattedDistanceTo(myLoc)}" else "Current Device"
                val isLeaderRider = rider.role == com.example.data.model.RideRole.LEADER || rider.id == ride?.leaderId

                Surface(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .testTag("selected_rider_card"),
                    color = SlateDark900.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isLeaderRider) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "⭐",
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Text(
                                text = gapStr,
                                color = AmberPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        RiderStatusBadge(
                            status = rider.status,
                            speedKmh = rider.speedKmh,
                            stoppedDurationSec = rider.stoppedDurationSec,
                            connectionStatus = rider.connectionStatus
                        )

                        IconButton(
                            onClick = { viewModel.selectRider(null) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Google Maps-style Trip HUD (ETA, Remaining Distance, Time, Follow Camera Toggle)
            NavigationTripHud(
                destinationName = ride?.destinationName ?: "Destination",
                remainingDistance = remainingDistText,
                remainingDuration = remainingDurationText,
                etaTime = etaText,
                isFollowMode = isFollowMode,
                onToggleFollowMode = { viewModel.toggleFollowRiderMode() }
            )

            // Primary Bottom Action Bar:
            // [ 👥 Riders ] [ 💬 Chat ] [ 🚨 SOS ]
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bottom_riding_bar"),
                color = SlateDark900.copy(alpha = 0.96f),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 👥 Riders Button
                    Button(
                        onClick = { viewModel.openRidersSheet(true) },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("open_riders_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateDark800,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = "Riders",
                            tint = AmberPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Riders",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    // 💬 Chat Button
                    Button(
                        onClick = { viewModel.openQuickMessageSheet(true) },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("open_message_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateDark800,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Chat",
                            tint = AmberPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Chat",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    // 🚨 Large Emergency SOS Button
                    Button(
                        onClick = { viewModel.openSosConfirmDialog(true) },
                        modifier = Modifier
                            .weight(1.05f)
                            .height(54.dp)
                            .testTag("trigger_sos_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusEmergencyRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "SOS",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SOS",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 4. Modals and Dialogs
        if (showRidersSheet) {
            RiderBottomSheet(
                members = members,
                selectedRider = selectedRider,
                onSelectRider = { viewModel.selectRider(it) },
                onDismiss = { viewModel.openRidersSheet(false) }
            )
        }

        if (showQuickMessageSheet) {
            QuickMessageSheet(
                onSendMessage = { type ->
                    viewModel.sendQuickMessage(type)
                    viewModel.openQuickMessageSheet(false)
                },
                onDismiss = { viewModel.openQuickMessageSheet(false) }
            )
        }

        if (showSosConfirmDialog) {
            SosConfirmDialog(
                onConfirm = {
                    viewModel.triggerSos()
                    viewModel.openSosConfirmDialog(false)
                },
                onDismiss = { viewModel.openSosConfirmDialog(false) }
            )
        }

        if (showSettingsSheet) {
            RideSettingsSheet(
                ride = ride,
                currentRider = currentUser,
                members = members,
                onTransferLeadership = { newLeaderId -> viewModel.transferLeadership(newLeaderId) },
                onEndRide = {
                    viewModel.openSettingsSheet(false)
                    viewModel.endRide()
                },
                onLeaveRide = {
                    viewModel.openSettingsSheet(false)
                    viewModel.leaveRide()
                },
                onDismiss = { viewModel.openSettingsSheet(false) }
            )
        }
    }
}
