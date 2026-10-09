package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionStatus
import com.example.data.model.MemberStatus
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.StatusOfflineGray
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.StatusStoppedAmber

@Composable
fun RiderStatusBadge(
    status: MemberStatus,
    speedKmh: Double = 0.0,
    stoppedDurationSec: Long = 0,
    connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (bgTint, dotColor, label) = when {
        connectionStatus == ConnectionStatus.LOCATION_UNAVAILABLE -> Triple(
            Color(0x2278909C),
            StatusOfflineGray,
            "GPS Lost"
        )
        connectionStatus == ConnectionStatus.LOCATION_STALE -> Triple(
            Color(0x33FFB300),
            StatusStoppedAmber,
            "Stale Signal"
        )
        connectionStatus == ConnectionStatus.DISCONNECTED -> Triple(
            Color(0x2278909C),
            StatusOfflineGray,
            "Disconnected"
        )
        connectionStatus == ConnectionStatus.RECONNECTING -> Triple(
            Color(0x3329B6F6),
            Color(0xFF29B6F6),
            "Reconnecting"
        )
        status == MemberStatus.EMERGENCY -> Triple(
            Color(0x33FF1744),
            StatusEmergencyRed,
            "EMERGENCY"
        )
        status == MemberStatus.STOPPED -> {
            val mins = stoppedDurationSec / 60
            val durationText = if (mins > 0) "${mins}m" else "${stoppedDurationSec}s"
            Triple(
                Color(0x22FFC107),
                StatusStoppedAmber,
                "Stopped • $durationText"
            )
        }
        status == MemberStatus.OFFLINE -> Triple(
            Color(0x2278909C),
            StatusOfflineGray,
            "Offline"
        )
        else -> Triple(
            Color(0x2200E676),
            StatusRidingGreen,
            if (speedKmh > 0) "Riding • ${speedKmh.toInt()} km/h" else "Riding"
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgTint)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .then(
                        if (status == MemberStatus.EMERGENCY || status == MemberStatus.RIDING) {
                            Modifier.alpha(pulseAlpha)
                        } else Modifier
                    )
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = dotColor
            )
        }
    }
}
