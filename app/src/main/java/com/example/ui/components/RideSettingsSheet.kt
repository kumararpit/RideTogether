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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Ride
import com.example.data.model.RideRole
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.DividerColor
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.SurfaceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideSettingsSheet(
    ride: Ride?,
    currentRider: RiderMember?,
    members: List<RiderMember> = emptyList(),
    onTransferLeadership: ((newLeaderId: String) -> Unit)? = null,
    onEndRide: () -> Unit,
    onLeaveRide: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEndRideConfirm by remember { mutableStateOf(false) }
    var selectedTargetRiderForLeader by remember { mutableStateOf<RiderMember?>(null) }

    val isLeader = (currentRider?.role == RideRole.LEADER) || (ride?.leaderId == currentRider?.id)
    val otherMembers = members.filter { !it.isCurrentUser && it.id != currentRider?.id }

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
        modifier = Modifier.testTag("ride_settings_sheet")
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
                        text = "Convoy Controls",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = ride?.name ?: "Group Ride",
                        color = AmberPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_settings_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current Role Badge Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "YOUR CONVOY ROLE",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isLeader) "Convoy Leader (Host)" else "Convoy Rider",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isLeader) AmberPrimary.copy(alpha = 0.25f) else SlateDark800)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLeader) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = AmberPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = if (isLeader) "LEADER" else "MEMBER",
                                color = if (isLeader) AmberPrimary else Color(0xFFCFD8DC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Transfer Leadership Section (Leader only)
            if (isLeader && otherMembers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "PASS LEADER ROLE TO RIDER",
                    color = AmberPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    otherMembers.forEach { rider ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedTargetRiderForLeader = rider },
                            color = SurfaceCard
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    Column {
                                        Text(text = rider.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        if (rider.motorcycleModel.isNotBlank()) {
                                            Text(text = "🏍️ ${rider.motorcycleModel}", color = AmberPrimary, fontSize = 11.sp)
                                        }
                                    }
                                }

                                Button(
                                    onClick = { selectedTargetRiderForLeader = rider },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SlateDark800,
                                        contentColor = AmberPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Make Leader", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = DividerColor)
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
                        Text("Leave Ride", color = Color(0xFFCFD8DC))
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
                // Non-leader members can ONLY leave the ride session
                OutlinedButton(
                    onClick = onLeaveRide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("leave_ride_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Leave Convoy Session", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
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
