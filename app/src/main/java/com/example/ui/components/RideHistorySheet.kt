package com.example.ui.components

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.CompletedRideEntity
import com.example.data.model.Ride
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark500
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen 14 of the reference: My Rides.
 *
 * Two truthful tabs. "Completed" is the local ride history; "Upcoming" is the
 * one ride this device is actually part of, or an empty state when there is
 * none. No ride is invented to fill the tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistorySheet(
    rides: List<CompletedRideEntity>,
    onDismiss: () -> Unit,
    upcomingRide: Ride? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDetailRide by remember { mutableStateOf<CompletedRideEntity?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateDark800,
        modifier = Modifier.testTag("ride_history_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "My Rides",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (rides.size) {
                            0 -> "No completed rides yet"
                            1 -> "1 completed ride"
                            else -> "${rides.size} completed rides"
                        },
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_history_sheet")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close ride history",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tabs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryTab(
                    label = "Completed",
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    testTag = "history_tab_completed"
                )
                HistoryTab(
                    label = "Upcoming",
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    testTag = "history_tab_upcoming"
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (tab == 0) {
                if (rides.isEmpty()) {
                    EmptyState(
                        title = "No rides yet",
                        message = "Completed rides are saved here with their distance, duration and group.",
                        icon = Icons.Default.History
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(rides, key = { it.id }) { item ->
                            RideHistoryCard(
                                ride = item,
                                onClick = { selectedDetailRide = item }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            } else {
                if (upcomingRide == null) {
                    EmptyState(
                        title = "No upcoming ride",
                        message = "Create a ride or join one with an invite code and it will wait here.",
                        icon = Icons.Default.Navigation
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SlateDark800,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SlateDark500)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            StatusChip(
                                text = if (upcomingRide.status == com.example.data.model.RideStatus.ACTIVE) "LIVE NOW" else "UPCOMING",
                                color = if (upcomingRide.status == com.example.data.model.RideStatus.ACTIVE) StatusRidingGreen else AmberPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = upcomingRide.name,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${upcomingRide.startLocationName} → ${upcomingRide.destinationName}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Invite code ${upcomingRide.inviteCode} · ${upcomingRide.members.size} rider${if (upcomingRide.members.size == 1) "" else "s"}",
                                color = TextTertiary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        selectedDetailRide?.let { ride ->
            RideDetailsDialog(
                ride = ride,
                onDismiss = { selectedDetailRide = null }
            )
        }
    }
}

@Composable
private fun HistoryTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AmberPrimary else SlateDark700)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF1A1205) else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun RideHistoryCard(
    ride: CompletedRideEntity,
    onClick: () -> Unit
) {
    val dateStr = SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault())
        .format(Date(ride.completedAtMs))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("history_item_${ride.id}"),
        color = SlateDark800,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SlateDark500)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AmberPrimary.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = AmberPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ride.rideName,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateStr,
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${ride.startLocationName} → ${ride.destinationName}",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HistoryMetric(Icons.Default.Navigation, ride.formattedDistance)
                HistoryMetric(Icons.Default.Schedule, ride.formattedDuration)
                HistoryMetric(Icons.Default.Group, "${ride.riderCount} rider${if (ride.riderCount == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun HistoryMetric(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun RideDetailsDialog(
    ride: CompletedRideEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(ride.completedAtMs))

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark900,
        title = {
            Column {
                Text(
                    text = ride.rideName,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateStr,
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = SlateDark800,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("ROUTE", color = AmberPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${ride.startLocationName} → ${ride.destinationName}",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailCell("DISTANCE", ride.formattedDistance, Modifier.weight(1f), AmberPrimary)
                    DetailCell("DURATION", ride.formattedDuration, Modifier.weight(1f), TextPrimary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailCell(
                        label = "AVG SPEED",
                        value = if (ride.avgSpeedKmh > 0) {
                            String.format(Locale.getDefault(), "%.1f km/h", ride.avgSpeedKmh)
                        } else "Not measured",
                        modifier = Modifier.weight(1f),
                        valueColor = StatusRidingGreen
                    )
                    DetailCell(
                        label = "RIDERS",
                        value = "${ride.riderCount}",
                        modifier = Modifier.weight(1f),
                        valueColor = TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val shareText = "Motorcycle Ride Summary: ${ride.rideName}\n" +
                        "Route: ${ride.startLocationName} → ${ride.destinationName}\n" +
                        "Distance: ${ride.formattedDistance}\n" +
                        "Duration: ${ride.formattedDuration}\n" +
                        "Pack: ${ride.riderCount} riders\n" +
                        "Recorded on RideTogether"
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share ride summary"))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberPrimary,
                    contentColor = Color(0xFF1A1205)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close", color = TextPrimary)
            }
        }
    )
}

@Composable
private fun DetailCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color
) {
    Surface(
        color = SlateDark800,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(text = value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
