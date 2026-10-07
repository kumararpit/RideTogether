package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Ride
import com.example.data.model.RiderMember
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.DividerColor
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SurfaceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideSettingsSheet(
    ride: Ride?,
    currentRider: RiderMember?,
    onAddMember: (name: String) -> Unit,
    onEndRide: () -> Unit,
    onLeaveRide: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ride Settings & Info",
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

            // Zero-Cost OpenStreetMap & MapLibre Architecture Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                tint = AmberPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "MAP ENGINE: OPENSTREETMAP (₹0 COST)",
                                color = AmberPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "🟢 Active",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• MapLibre & OpenStreetMap tiles\n• No Google Cloud billing or credit card required\n• Compliant User-Agent & local disk cache\n• MapProvider abstraction for future custom tile servers",
                        color = Color(0xFFCFD8DC),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stop Detection status card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = AmberPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AUTOMATIC STOP DETECTION",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• Speed < 5 km/h for 2 minutes → Marked 🟡 STOPPED\n• Speed > 8 km/h → Resumes 🟢 RIDING\n• Hysteresis active between 5 and 8 km/h",
                        color = Color(0xFFCFD8DC),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add Pack Member Button (useful for testing multi-rider group experience)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceCard,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add Pack Rider",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Add a companion rider to test group map & relative distance",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            val names = listOf("Rahul", "Amit", "Vikas", "Sameer", "Karan", "Pooja")
                            onAddMember(names.random())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateDark800,
                            contentColor = AmberPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("add_pack_member_button")
                    ) {
                        Icon(imageVector = Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(modifier = Modifier.height(14.dp))

            // Actions: Leave or End
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
                    onClick = onEndRide,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("end_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("End Ride", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
