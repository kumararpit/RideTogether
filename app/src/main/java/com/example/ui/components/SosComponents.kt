package com.example.ui.components

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.SosEvent
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusRidingGreen
import kotlinx.coroutines.delay

@Composable
fun SosConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdownSec by remember { mutableStateOf(5) }
    var isCancelled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (countdownSec > 0 && !isCancelled) {
            delay(1000L)
            countdownSec--
        }
        if (!isCancelled && countdownSec == 0) {
            onConfirm()
        }
    }

    AlertDialog(
        onDismissRequest = {
            isCancelled = true
            onDismiss()
        },
        containerColor = SlateDark900,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = StatusEmergencyRed,
                modifier = Modifier.size(44.dp)
            )
        },
        title = {
            Text(
                text = "Emergency SOS Alert",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Broadcasting your GPS coordinates to all convoy members in real-time.",
                    color = Color(0xFFCFD8DC),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Accidental trigger prevention countdown
                Text(
                    text = "Sending in $countdownSec second(s)...",
                    color = AmberPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { (5 - countdownSec) / 5f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = StatusEmergencyRed,
                    trackColor = SlateDark800,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isCancelled = true
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusEmergencyRed,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_sos_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "🚨 SEND SOS NOW",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    isCancelled = true
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("cancel_sos_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Cancel (Accidental Press)",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        },
        modifier = Modifier.testTag("sos_confirm_dialog")
    )
}

/**
 * Top alert banner when ANOTHER rider in the pack triggers SOS.
 */
@Composable
fun SosAlertBanner(
    sosEvent: SosEvent,
    onViewLocation: (SosEvent) -> Unit,
    onAcknowledge: (() -> Unit)? = null,
    isAcknowledged: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .testTag("sos_alert_banner"),
        color = StatusEmergencyRed,
        tonalElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Emergency",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🚨 SOS: ${sosEvent.riderName.uppercase()}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Needs immediate assistance",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val updateAgoSec = ((System.currentTimeMillis() - sosEvent.timestampMs) / 1000).coerceAtLeast(0)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        val (badgeText, badgeBg) = when (sosEvent.locationSource) {
                            "FRESH_LOCATION_FIX" -> Pair("FRESH GPS FIX", StatusRidingGreen)
                            "LAST_KNOWN_LOCATION" -> Pair("LAST KNOWN LOCATION (${updateAgoSec}s ago)", AmberPrimary)
                            else -> Pair("LOCATION UNAVAILABLE", Color.Gray)
                        }
                        Surface(
                            color = badgeBg,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onAcknowledge != null) {
                    Button(
                        onClick = onAcknowledge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAcknowledged) StatusRidingGreen else Color.White,
                            contentColor = if (isAcknowledged) Color.Black else StatusEmergencyRed
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("acknowledge_sos_button")
                    ) {
                        Text(
                            text = if (isAcknowledged) "✓ ACKNOWLEDGED" else "ACK",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Button(
                    onClick = { onViewLocation(sosEvent) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = StatusEmergencyRed
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("view_sos_location_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = "Map",
                        tint = StatusEmergencyRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Map",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = {
                        val gmmIntentUri = android.net.Uri.parse("google.navigation:q=${sosEvent.location.latitude},${sosEvent.location.longitude}")
                        val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri).apply {
                            setPackage("com.google.android.apps.maps")
                        }
                        try {
                            context.startActivity(mapIntent)
                        } catch (_: Exception) {
                            val webUri = android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${sosEvent.location.latitude},${sosEvent.location.longitude}")
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, webUri))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = SlateDark900
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("get_directions_sos_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = "Get Directions",
                        tint = SlateDark900,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Directions",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Persistent banner on screen when CURRENT USER has activated SOS.
 */
@Composable
fun ActiveSosBar(
    onResolve: () -> Unit,
    acknowledgedCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .testTag("active_sos_bar"),
        color = SlateDark900,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StatusEmergencyRed.copy(alpha = 0.25f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🚨", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "SOS SENT! Group will be here to help soon",
                        color = StatusEmergencyRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                    val ackText = if (acknowledgedCount > 0) {
                        "$acknowledgedCount pack member(s) acknowledged"
                    } else {
                        "Broadcasting location to pack in real-time"
                    }
                    Text(
                        text = ackText,
                        color = Color(0xFFCFD8DC),
                        fontSize = 11.sp
                    )
                }
            }

            Button(
                onClick = onResolve,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusRidingGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(40.dp)
                    .testTag("resolve_sos_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "I'm Safe",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "I'm Safe",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
