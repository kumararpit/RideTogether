package com.example.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.CompletedRideEntity
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.SurfaceCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistorySheet(
    rides: List<CompletedRideEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDetailRide by remember { mutableStateOf<CompletedRideEntity?>(null) }

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
        modifier = Modifier.testTag("ride_history_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
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
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Ride History",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val count = rides.size
                        Text(
                            text = if (count == 1) "1 completed ride" else "$count completed rides",
                            color = Color(0xFF90A4AE),
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_history_sheet")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (rides.isEmpty()) {
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "🏍️", fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No Recorded Rides Yet",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Complete your first group convoy to see recorded metrics and ride summaries here.",
                            color = Color(0xFF90A4AE),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rides, key = { it.id }) { item ->
                        Surface(
                            color = SurfaceCard,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedDetailRide = item }
                                .testTag("history_item_${item.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.rideName,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                                        .format(Date(item.completedAtMs))
                                    Text(
                                        text = dateStr,
                                        color = Color(0xFF90A4AE),
                                        fontSize = 11.sp
                                    )
                                }

                                Text(
                                    text = "${item.startLocationName} → ${item.destinationName}",
                                    color = AmberPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🛣️ ${item.formattedDistance} • ⏱️ ${item.formattedDuration} • ${item.riderCount} rider(s)",
                                        color = Color(0xFFCFD8DC),
                                        fontSize = 12.sp
                                    )

                                    OutlinedButton(
                                        onClick = { selectedDetailRide = item },
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberPrimary)
                                    ) {
                                        Text(text = "Details", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (selectedDetailRide != null) {
            RideDetailsDialog(
                ride = selectedDetailRide!!,
                onDismiss = { selectedDetailRide = null }
            )
        }
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
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateStr,
                    color = Color(0xFF90A4AE),
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
                    color = SurfaceCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("ROUTE", color = AmberPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${ride.startLocationName} → ${ride.destinationName}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("DISTANCE", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(text = ride.formattedDistance, color = AmberPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("DURATION", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(text = ride.formattedDuration, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("AVG SPEED", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            val speedText = if (ride.avgSpeedKmh > 0) String.format(Locale.getDefault(), "%.1f km/h", ride.avgSpeedKmh) else "N/A"
                            Text(text = speedText, color = StatusRidingGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("CONVOY SIZE", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(text = "${ride.riderCount} rider(s)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
                            "Tracked on RideTogether"
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Ride Summary"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary, contentColor = Color.Black)
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close", color = Color.White)
            }
        }
    )
}
