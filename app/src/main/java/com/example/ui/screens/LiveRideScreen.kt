package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ActiveSosBar
import com.example.ui.components.NavigationTripHud
import com.example.ui.components.QuickMessageSheet
import com.example.ui.components.RecentMessageToast
import com.example.ui.components.RideMapCanvas
import com.example.ui.components.RiderBottomSheet
import com.example.ui.components.RiderStatusBadge
import com.example.ui.components.SosAlertBanner
import com.example.ui.components.SosConfirmDialog
import com.example.ui.components.TurnByTurnNavHeader
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.RideViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val CardBorderColor = Color(0xFF253248)

/** Tabular figures keep live-updating numeric readouts from jittering horizontally. */
private val TabularNumericStyle = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

@Composable
fun LiveRideScreen(
    viewModel: RideViewModel,
    modifier: Modifier = Modifier
) {
    val ride by viewModel.currentRide.collectAsState()
    val members by viewModel.members.collectAsState()
    val selectedRider by viewModel.selectedRider.collectAsState()
    val activeSos by viewModel.activeSos.collectAsState()
    val recentMessages by viewModel.recentMessages.collectAsState()

    // Navigation & Routing states
    val navProgress by viewModel.navEngine.progress.collectAsState()
    val routeResult by viewModel.routeResult.collectAsState()
    val isRerouting by viewModel.isRerouting.collectAsState()
    val isFollowMode by viewModel.isFollowRiderMode.collectAsState()
    val currentStep = viewModel.navEngine.getCurrentStep()
    val nextStep = viewModel.navEngine.getNextStep()
    val remainingDistText = viewModel.getFormattedRemainingDistance(navProgress.remainingRouteDistanceMeters)
    val remainingDurationText = viewModel.getFormattedRemainingDuration(navProgress.remainingDurationSeconds)
    val etaText = viewModel.getFormattedEta(navProgress.remainingDurationSeconds)

    val showRidersSheet by viewModel.showRiderListSheet.collectAsState()
    val showQuickMessageSheet by viewModel.showQuickMessageSheet.collectAsState()
    val showSosConfirmDialog by viewModel.showSosConfirmDialog.collectAsState()

    val currentUserId by viewModel.userId.collectAsState()
    val currentUser = members.find { it.isCurrentUser || it.id == currentUserId }
    val myUid = currentUser?.id ?: currentUserId

    val isMySosActive = activeSos != null && (
        activeSos?.riderId == myUid ||
        activeSos?.riderId == currentUserId
    )
    val isOtherRiderSos = activeSos != null && !isMySosActive

    val currentRide by viewModel.currentRide.collectAsState()
    val amenities by viewModel.amenities.collectAsState()
    val selectedAmenity by viewModel.selectedAmenity.collectAsState()
    val visibleCategories by viewModel.visibleCategories.collectAsState()
    // Phase 6 — memoized category filter: without this, every unrelated recomposition
    // (each GPS tick / camera move) re-ran the filter, reallocated the list, and
    // churned the RideMapCanvas `pointerInput(amenities)` keys…
    val visibleAmenities = remember(amenities, visibleCategories) {
        amenities.filter { it.type in visibleCategories }
    }

    // Bottom action bar (Control | Chat | SOS) is permanently docked during an active
    // ride so one-tap SOS/Chat is always reachable with riding gloves.
    var isBottomActionBarVisible by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // Auto-hide behavior removed: bar is always visible. Kept as no-ops so existing
    // interaction call sites remain valid.
    fun scheduleAutoHide() {
        isBottomActionBarVisible = true
    }

    fun toggleActionBar() {
        isBottomActionBarVisible = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Live Map View — dominant surface filling full screen
        RideMapCanvas(
            route = viewModel.getRoutePoints(),
            routeLegStartIndices = viewModel.getRouteLegStartIndices(),
            members = members,
            selectedRider = selectedRider,
            onSelectRider = {
                viewModel.selectRider(it)
                if (it != null && isBottomActionBarVisible) scheduleAutoHide()
            },
            isFollowRiderMode = isFollowMode,
            onFollowRiderChange = { viewModel.setFollowRiderMode(it) },
            distanceToNextManeuverMeters = navProgress.distanceToNextManeuverMeters,
            topInsetDp = 116.dp,
            bottomInsetDp = 180.dp, // full telemetry card + permanently docked action bar
            amenities = visibleAmenities,
            selectedAmenity = selectedAmenity,
            onSelectAmenity = {
                viewModel.selectAmenity(it)
                if (it != null && isBottomActionBarVisible) scheduleAutoHide()
            },
            onViewportChanged = { minLat, maxLat, minLon, maxLon ->
                viewModel.onViewportChanged(minLat, maxLat, minLon, maxLon)
            },
            initialStartLocation = currentRide?.startLocation,
            onMapTapped = { toggleActionBar() },
            // Task 18 — Automatic day/night map appearance from the single theme owner.
            mapLighting = viewModel.mapLighting.collectAsState().value,
            allowThemeControl = true,
            mapThemeMode = viewModel.mapThemeMode.collectAsState().value,
            onMapThemeModeChange = { viewModel.setMapThemeMode(it) },
            onThemeResumed = { viewModel.onMapThemeResumed() },
            onLightingContext = { lat, lng -> viewModel.onMapLightingContext(lat, lng) }
        )

        // 2. Top HUD stack — turn-by-turn header + alert banners + message toast
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            val isAudioMuted by viewModel.navAudioGuidance.isMuted.collectAsState()
            TurnByTurnNavHeader(
                currentStep = currentStep,
                nextStep = nextStep,
                isOffRoute = navProgress.isOffRoute,
                isRerouting = isRerouting,
                onReroute = { viewModel.rerouteFromCurrentLocation() },
                distanceToNextManeuverMeters = navProgress.distanceToNextManeuverMeters,
                isAudioMuted = isAudioMuted,
                onToggleAudioMute = { viewModel.toggleAudioMute() }
            )

            // SOS alert banner — another rider in distress
            if (isOtherRiderSos && activeSos != null) {
                val isAcked = activeSos!!.acknowledgedBy.contains(myUid) ||
                    activeSos!!.acknowledgedBy.contains(currentUserId)
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
                    isAcknowledged = isAcked,
                    onDismiss = { viewModel.dismissSosBanner() }
                )
            }

            // My own active SOS bar — shows WHO acknowledged directly to the sender so
            // acknowledgments are visible as they arrive from Firestore, not just locally.
            if (isMySosActive) {
                val ackNames = activeSos?.acknowledgedBy?.mapNotNull { ackUid ->
                    members.find { it.id == ackUid }?.name
                } ?: emptyList()
                ActiveSosBar(
                    onResolve = { viewModel.resolveSos() },
                    acknowledgedCount = activeSos?.acknowledgedBy?.size ?: 0,
                    acknowledgedNames = ackNames
                )
            }

            // Incoming quick-message toast — each new message id presented exactly once,
            // sequentially (rapid bursts can no longer overwrite an earlier message).
            RecentMessageToast(messages = recentMessages)
        }

        // 3. Floating Speed Indicator + Speed Limit Badge (Bottom-Left above ETA panel)
        if (currentUser != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 240.dp)
                    .testTag("speed_hud"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Speed Circle Gauge
                Surface(
                    color = Color(0xFF101318),
                    shape = CircleShape,
                    border = BorderStroke(1.5.dp, CardBorderColor),
                    tonalElevation = 6.dp
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${currentUser.speedKmh.toInt()}",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                lineHeight = 20.sp,
                                style = TabularNumericStyle
                            )
                            Text(
                                text = "km/h",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Speed Limit Badge (60 km/h)
                Surface(
                    color = Color.White,
                    shape = CircleShape,
                    border = BorderStroke(2.5.dp, Color(0xFFDC2626)),
                    tonalElevation = 6.dp
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "60",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // 4. Bottom stack — selected rider card + ETA panel + tap-to-reveal action bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            val userLocationUpdate by viewModel.locationTracker.currentLocation.collectAsState()

            // Selected Rider quick-detail card
            if (selectedRider != null) {
                val rider = selectedRider!!
                val myLoc = currentUser?.location ?: userLocationUpdate?.latLng
                val gapStr = if (myLoc != null && !rider.isCurrentUser) {
                    "Gap: ${rider.getFormattedDistanceTo(myLoc)}"
                } else {
                    "Current Device"
                }
                val isLeaderRider = rider.role == com.example.data.model.RideRole.LEADER ||
                    rider.id == ride?.leaderId

                Surface(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .testTag("selected_rider_card"),
                    color = Color(0xFF141A24),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, CardBorderColor),
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (isLeaderRider) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "⭐", fontSize = 10.sp)
                                }
                            }
                            Text(
                                text = gapStr,
                                color = AmberPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

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
                                contentDescription = "Dismiss rider card",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Calculation for Trip HUD
            val initPlannedDist = currentRide?.initialPlannedDistanceMeters ?: 0.0
            val progressFraction = if (initPlannedDist > 0.0) {
                ((initPlannedDist - navProgress.remainingRouteDistanceMeters) / initPlannedDist).toFloat().coerceIn(0f, 1f)
            } else 0f

            val totalDistKmStr = currentRide?.finalSummaryDistanceMeters?.let {
                String.format(Locale.US, "%.1f km", it / 1000.0)
            } ?: "49.8 km"
            val totalDurMinStr = currentRide?.totalDurationSeconds?.let {
                "${(it / 60.0).toInt()} min"
            } ?: "40 min"
            val totalTripStr = "$totalDistKmStr · $totalDurMinStr"

            // Traveled odometer readout from the GPS odometer, not duplicate speed
            val traveledKmStr = String.format(
                Locale.US,
                "%.1f km",
                (currentRide?.actualRecordedDistanceMeters ?: 0.0) / 1000.0
            )

            // Arrival latch banner — prominent call-to-action when destination reached
            if (navProgress.isArrived) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("arrived_banner"),
                    color = Color(0xFF123C22),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, StatusRidingGreen.copy(alpha = 0.6f)),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Arrived at Destination",
                                color = StatusRidingGreen,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Complete Ride",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = { viewModel.endRide() },
                            modifier = Modifier.height(46.dp).testTag("finish_ride_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusRidingGreen,
                                contentColor = Color(0xFF062513)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = "Finish Ride",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            // Next-stop strip: which stop of the ordered plan the group is
            // heading to, and how far it is. Only shown for multi-stop plans.
            val activeRoute = routeResult
            val nextStop = remember(activeRoute, navProgress.currentLegIndex) {
                activeRoute?.stops?.getOrNull(navProgress.currentLegIndex + 1)
            }
            if (activeRoute != null && activeRoute.legs.size > 1 && nextStop != null) {
                val legIndex = navProgress.currentLegIndex.coerceIn(0, activeRoute.legs.lastIndex)
                val legDistance = viewModel.getRemainingLegDistanceMeters()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("next_stop_strip"),
                    color = Color(0xFF101318),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CardBorderColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Flag,
                            contentDescription = null,
                            tint = AmberPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NEXT STOP",
                            color = TextTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = nextStop.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${viewModel.getFormattedRemainingDistance(legDistance)} · Leg ${legIndex + 1}/${activeRoute.legs.size}",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Trip HUD (ETA, distance, traveled, route info, tracking toggle)
            // Stays anchored at bottom
            NavigationTripHud(
                destinationName = currentRide?.destinationName ?: "Lonavala",
                startLocationName = currentRide?.startLocationName ?: "Pune",
                remainingDistance = remainingDistText,
                remainingDuration = remainingDurationText,
                etaTime = etaText,
                traveledDistance = traveledKmStr,
                progressFraction = progressFraction,
                totalTripDistanceAndDuration = totalTripStr,
                isFollowMode = isFollowMode,
                onToggleFollowMode = { viewModel.toggleFollowRiderMode() },
                modifier = Modifier
            )

            // Tap-to-Reveal Primary Bottom Action Bar: Controls | Chat | SOS
            AnimatedVisibility(
                visible = isBottomActionBarVisible,
                enter = fadeIn() + slideInVertically { fullHeight -> fullHeight },
                exit = fadeOut() + slideOutVertically { fullHeight -> fullHeight }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bottom_riding_bar"),
                    color = Color(0xFF101318),
                    border = BorderStroke(1.dp, CardBorderColor),
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Controls button
                        Button(
                            onClick = {
                                scheduleAutoHide()
                                viewModel.openRiderListSheet(true)
                            },
                            modifier = Modifier
                                .weight(1.1f)
                                .height(52.dp)
                                .testTag("open_riders_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1F2836),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Ride Controls",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Controls",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        // Chat button
                        Button(
                            onClick = {
                                scheduleAutoHide()
                                viewModel.openQuickMessageSheet(true)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("open_message_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1F2836),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "Quick Chat",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Chat",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        // SOS — prominent high-contrast red button
                        Button(
                            onClick = {
                                scheduleAutoHide()
                                viewModel.openSosConfirmDialog(true)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("trigger_sos_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF04452),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Emergency SOS",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
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
        }

        // 5. Modals and Dialogs
        if (showRidersSheet) {
            RiderBottomSheet(
                ride = ride,
                members = members,
                selectedRider = selectedRider,
                onSelectRider = { viewModel.selectRider(it) },
                onTransferLeadership = { newLeaderId -> viewModel.transferLeadership(newLeaderId) },
                onEndRide = {
                    viewModel.openRiderListSheet(false)
                    viewModel.endRide()
                },
                onLeaveRide = {
                    viewModel.openRiderListSheet(false)
                    viewModel.leaveRide()
                },
                onDismiss = {
                    viewModel.openRiderListSheet(false)
                    if (isBottomActionBarVisible) scheduleAutoHide()
                }
            )
        }

        if (selectedAmenity != null) {
            com.example.ui.components.AmenityDetailsSheet(
                amenity = selectedAmenity!!,
                riderLocation = currentUser?.location,
                onDismiss = {
                    viewModel.selectAmenity(null)
                    if (isBottomActionBarVisible) scheduleAutoHide()
                }
            )
        }

        if (showQuickMessageSheet) {
            QuickMessageSheet(
                onSendMessage = { type ->
                    viewModel.sendQuickMessage(type)
                    viewModel.openQuickMessageSheet(false)
                    if (isBottomActionBarVisible) scheduleAutoHide()
                },
                onDismiss = {
                    viewModel.openQuickMessageSheet(false)
                    if (isBottomActionBarVisible) scheduleAutoHide()
                }
            )
        }

        if (showSosConfirmDialog) {
            SosConfirmDialog(
                onConfirm = {
                    viewModel.triggerSos()
                    viewModel.openSosConfirmDialog(false)
                    if (isBottomActionBarVisible) scheduleAutoHide()
                },
                onDismiss = {
                    viewModel.openSosConfirmDialog(false)
                    if (isBottomActionBarVisible) scheduleAutoHide()
                }
            )
        }
    }
}
