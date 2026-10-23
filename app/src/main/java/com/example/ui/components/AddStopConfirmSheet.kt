package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.EditRoad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale
import kotlin.math.abs

/**
 * Screen 10 of the reference: confirm a stop before it is committed.
 *
 * The sheet shows the real route geometry, the place being considered and
 * what the stop is measured to cost. When no reliable preview exists it says
 * so — it never shows an invented number. Confirming is idempotent: the
 * action is disabled while the commit is in flight, so a double tap cannot
 * add the same stop twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStopConfirmSheet(
    amenityName: String,
    route: List<LatLng>,
    rider: LatLng?,
    candidate: LatLng,
    destination: LatLng?,
    detourSeconds: Double?,
    detourMeters: Double?,
    isEstimating: Boolean = false,
    isCommitting: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = { if (!isCommitting) onCancel() },
        containerColor = SlateDark800
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .testTag("add_stop_confirm_sheet")
        ) {
            Text(
                text = "Add this stop to your ride?",
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$amenityName will be added before the final destination.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            RouteMiniMap(
                route = route,
                rider = rider,
                candidate = candidate,
                destination = destination,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            RTCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ROUTE CHANGE",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.EditRoad,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    isEstimating -> Text(
                        text = "Calculating the detour…",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    detourSeconds == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Detour not available yet — the route will still be planned through this place.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    else -> Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Metric(
                            icon = Icons.Default.Route,
                            text = formatDistanceDelta(detourMeters)
                        )
                        Metric(
                            icon = Icons.Default.Timer,
                            text = formatTimeDelta(detourSeconds)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "Cancel",
                    onClick = onCancel,
                    enabled = !isCommitting,
                    modifier = Modifier.weight(1f),
                    testTag = "cancel_add_stop"
                )
                PrimaryButton(
                    text = "Add Stop",
                    onClick = onConfirm,
                    enabled = !isCommitting,
                    loading = isCommitting,
                    icon = Icons.Default.AddLocationAlt,
                    modifier = Modifier.weight(1f),
                    testTag = "confirm_add_stop"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "The route is re-planned and the group follows the updated plan.",
                color = TextTertiary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AmberPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** "+1.8 km" / "−0.3 km" — the real difference between the two routes. */
internal fun formatDistanceDelta(meters: Double?): String {
    if (meters == null) return "—"
    val km = meters / 1000.0
    val sign = if (km < 0) "−" else "+"
    return if (abs(km) < 1.0) {
        String.format(Locale.US, "%s%d m", sign, Math.round(abs(meters)).toInt())
    } else {
        String.format(Locale.US, "%s%.1f km", sign, abs(km))
    }
}

/** "+3 min" / "−1 min" — the real difference between the two route times. */
internal fun formatTimeDelta(seconds: Double?): String {
    if (seconds == null) return "—"
    val sign = if (seconds < 0) "−" else "+"
    val absolute = abs(seconds)
    return when {
        absolute < 60 -> "${sign}under a minute"
        absolute < 3600 -> String.format(Locale.US, "%s%d min", sign, Math.round(absolute / 60.0).toInt())
        else -> String.format(Locale.US, "%s%.1f h", sign, absolute / 3600.0)
    }
}
