package com.example.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.example.data.model.MemberStatus
import com.example.ui.theme.CustomIcons
import com.example.data.model.RideRole
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.DividerColor
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.SurfaceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiderBottomSheet(
    ride: com.example.data.model.Ride?,
    members: List<RiderMember>,
    selectedRider: RiderMember?,
    onSelectRider: (RiderMember) -> Unit,
    onTransferLeadership: ((newLeaderId: String) -> Unit)? = null,
    onEndRide: () -> Unit,
    onLeaveRide: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val currentUser = members.find { it.isCurrentUser }
    val userLoc = currentUser?.location
    
    var showEndRideConfirm by remember { mutableStateOf(false) }
    var selectedTargetRiderForLeader by remember { mutableStateOf<RiderMember?>(null) }
    val isLeader = (currentUser?.role == RideRole.LEADER) || (ride?.leaderId == currentUser?.id)

    // Sort order per spec: 1. Emergency, 2. Stopped, 3. Riding, 4. Offline
    val sortedMembers = members.sortedWith(
        compareBy { member ->
            when (member.status) {
                MemberStatus.EMERGENCY -> 0
                MemberStatus.STOPPED -> 1
                MemberStatus.RIDING -> 2
                MemberStatus.OFFLINE -> 3
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateDark900,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Gray.copy(alpha = 0.5f))
            )
        },
        modifier = Modifier.testTag("riders_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ride Controls",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = ride?.name ?: "Group Ride",
                            color = Color(0xFFCFD8DC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                        Text(
                            text = "Code: ",
                            color = Color(0xFF90A4AE),
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = ride?.inviteCode ?: "ABC123",
                            color = AmberPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF3A2E14)),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_riders_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close sheet",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions: Member gets ONLY "Leave Ride". Leader gets "Leave Ride" and "End Ride".
            if (isLeader) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onLeaveRide,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("leave_ride_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Leave", color = Color(0xFFCFD8DC))
                    }

                    Button(
                        onClick = { showEndRideConfirm = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("end_ride_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusEmergencyRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("End Ride", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onLeaveRide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("leave_ride_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Leave Convoy Session", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "${members.size} RIDERS IN PACK",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Rider List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sortedMembers, key = { it.id }) { rider ->
                    val gapText = if (userLoc != null && !rider.isCurrentUser) {
                        "📏 Gap: ${rider.getFormattedDistanceTo(userLoc)}"
                    } else if (rider.isCurrentUser) {
                        "Current device (You)"
                    } else ""

                    RiderCardItem(
                        rider = rider,
                        isSelected = rider.id == selectedRider?.id,
                        relativePositionText = gapText,
                        onClick = {
                            onSelectRider(rider)
                            onDismiss()
                        },
                        showLeaderAction = isLeader && !rider.isCurrentUser,
                        onMakeLeader = { selectedTargetRiderForLeader = rider }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
        
        // Dialog to confirm Transfer Leadership
        if (selectedTargetRiderForLeader != null && onTransferLeadership != null) {
            val target = selectedTargetRiderForLeader!!
            AlertDialog(
                onDismissRequest = { selectedTargetRiderForLeader = null },
                containerColor = SlateDark900,
                title = {
                    Text("Transfer Convoy Leadership?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Appoint ${target.name} as the new Convoy Leader? They will receive full control over ride settings and termination.",
                        color = Color(0xFFCFD8DC),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetId = target.id
                            selectedTargetRiderForLeader = null
                            onTransferLeadership(targetId)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary, contentColor = Color.Black)
                    ) {
                        Text("Make Leader", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { selectedTargetRiderForLeader = null }) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }

        // Dialog to confirm End Group Ride
        if (showEndRideConfirm) {
            AlertDialog(
                onDismissRequest = { showEndRideConfirm = false },
                containerColor = SlateDark900,
                title = {
                    Text("End Group Ride?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "As Convoy Leader, ending this ride will close the live radar and end navigation for all convoy members.",
                        color = Color(0xFFCFD8DC),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showEndRideConfirm = false
                            onEndRide()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusEmergencyRed, contentColor = Color.White)
                    ) {
                        Text("End Ride Session", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showEndRideConfirm = false }) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }
    }
}

@Composable
fun RiderCardItem(
    rider: RiderMember,
    isSelected: Boolean,
    relativePositionText: String,
    onClick: () -> Unit,
    showLeaderAction: Boolean = false,
    onMakeLeader: (() -> Unit)? = null
) {
    val isEmergency = rider.status == MemberStatus.EMERGENCY

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("rider_item_${rider.name}"),
        color = if (isEmergency) StatusEmergencyRed.copy(alpha = 0.15f) else SurfaceCard,
        tonalElevation = if (isSelected) 8.dp else 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with initials
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(rider.avatarColorHex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = rider.name.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Name and relative distance
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (rider.isCurrentUser) "${rider.name} (You)" else rider.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (rider.role == RideRole.LEADER) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AmberPrimary.copy(alpha = 0.2f))
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // Motorcycle model if present
                    if (rider.motorcycleModel.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = CustomIcons.MotorcycleLogo,
                                contentDescription = "Motorcycle",
                                tint = AmberPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = rider.motorcycleModel,
                                color = AmberPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // Relative position
                    val positionDesc = if (rider.isCurrentUser) "Current device" else if (relativePositionText.isNotBlank()) relativePositionText else ""
                    
                    if (positionDesc.isNotBlank()) {
                        Text(
                            text = positionDesc,
                            color = Color(0xFFCFD8DC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Status Badge
                Column(horizontalAlignment = Alignment.End) {
                    RiderStatusBadge(
                        status = rider.status,
                        speedKmh = rider.speedKmh,
                        stoppedDurationSec = rider.stoppedDurationSec,
                        connectionStatus = rider.connectionStatus
                    )
                }
            }
            
            // Inline Leader Transfer Action
            if (showLeaderAction && onMakeLeader != null) {
                HorizontalDivider(color = DividerColor.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMakeLeader() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Transfer",
                        tint = AmberPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Make Leader",
                        color = AmberPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
