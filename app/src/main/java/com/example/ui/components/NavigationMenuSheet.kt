package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark500
import com.example.ui.theme.SlateDark600
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * Screen 7 of the reference: the navigation menu.
 *
 * Grouped, quiet actions that keep the map in context. Everything listed is
 * wired to a real behaviour — nothing here is a decorative row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationMenuSheet(
    isAudioMuted: Boolean,
    onToggleAudioMute: () -> Unit,
    onAddStop: () -> Unit,
    onNearbyPlaces: () -> Unit,
    onRouteOverview: () -> Unit,
    onGroupRide: () -> Unit,
    onReportIssue: () -> Unit,
    onEndRide: () -> Unit,
    endRideLabel: String = "End ride",
    routeStops: List<RouteStop> = emptyList(),
    onRemoveStop: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SlateDark800
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag("navigation_menu_sheet")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Navigation",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close navigation menu",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Truthful status, not a fake switch: the group is receiving this
            // rider's position for the duration of the ride.
            MenuRow(
                icon = Icons.Default.ShareLocation,
                title = "Sharing live location",
                subtitle = "Your group sees your position",
                tint = StatusRidingGreen,
                trailing = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(StatusRidingGreen)
                    )
                }
            )

            val intermediateStops = routeStops.filter {
                it.role == RouteStopRole.INTERMEDIATE
            }
            if (intermediateStops.isNotEmpty()) {
                MenuDivider()
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "Stops on this ride",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Remove one to skip it and re-plan the rest",
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
                intermediateStops.forEachIndexed { index, stop ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(AmberPrimary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = AmberPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stop.name,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onRemoveStop(stop.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("menu_remove_stop_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove ${stop.name}",
                                tint = StatusEmergencyRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            MenuDivider()

            MenuRow(
                icon = Icons.Default.AddLocationAlt,
                title = "Add a stop",
                subtitle = "Places on the map the ride can pass through",
                onClick = onAddStop,
                testTag = "menu_add_stop"
            )
            MenuRow(
                icon = Icons.Default.Storefront,
                title = "Find nearby places",
                subtitle = "Fuel, food, repair and rest stops",
                onClick = onNearbyPlaces,
                testTag = "menu_nearby_places"
            )
            MenuRow(
                icon = Icons.Default.Explore,
                title = "Route overview",
                subtitle = "Fit the whole route on the map",
                onClick = onRouteOverview,
                testTag = "menu_route_overview"
            )
            MenuRow(
                icon = Icons.Default.Group,
                title = "Group ride",
                subtitle = "Riders, positions and ride controls",
                onClick = onGroupRide,
                testTag = "menu_group_ride"
            )
            MenuRow(
                icon = if (isAudioMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                title = "Voice guidance",
                subtitle = if (isAudioMuted) "Muted" else "On",
                onClick = onToggleAudioMute,
                testTag = "menu_mute_voice",
                trailing = {
                    Switch(
                        checked = !isAudioMuted,
                        onCheckedChange = { onToggleAudioMute() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF1A1205),
                            checkedTrackColor = AmberPrimary,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = SlateDark600,
                            uncheckedBorderColor = SlateDark500
                        ),
                        modifier = Modifier.testTag("menu_mute_switch")
                    )
                }
            )
            MenuRow(
                icon = Icons.Default.Report,
                title = "Report an issue",
                subtitle = "Tell us what went wrong on this ride",
                onClick = onReportIssue,
                testTag = "menu_report_issue"
            )

            MenuDivider()

            MenuRow(
                icon = Icons.Default.Close,
                title = endRideLabel,
                tint = StatusEmergencyRed,
                titleColor = StatusEmergencyRed,
                onClick = onEndRide,
                testTag = "menu_end_ride"
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    tint: Color = AmberPrimary,
    titleColor: Color = TextPrimary,
    testTag: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 20.dp)
                else Modifier.padding(horizontal = 20.dp)
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(1.dp)
            .background(SlateDark600)
    )
}

/** Confirm ending the shared ride (leader) or leaving it (member). */
@Composable
fun EndRideConfirmDialog(
    isLeader: Boolean,
    rideName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark800,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(if (isLeader) "End this ride?" else "Leave this ride?") },
        text = {
            Text(
                if (isLeader) {
                    "“$rideName” will be completed for every rider and saved to your ride history."
                } else {
                    "You will leave “$rideName”. The group keeps riding."
                },
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_end_ride_button")
            ) {
                Text(
                    text = if (isLeader) "End ride" else "Leave ride",
                    color = StatusEmergencyRed,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

/** A minimal, honest issue report saved to the app's own feedback store. */
@Composable
fun ReportIssueDialog(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark800,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Report an issue") },
        text = {
            Column {
                Text(
                    "What went wrong? The report is stored on this device with this ride.",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. the turn instruction was wrong", color = TextTertiary) },
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary,
                        unfocusedBorderColor = SlateDark500,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AmberPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report_issue_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) onSend(trimmed) else onDismiss()
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("send_report_button")
            ) {
                Text("Send", color = AmberPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
