package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.TextPrimary

/**
 * Small chip shown in the LiveRideScreen top HUD indicating whether the offline
 * mesh is active and how many peers are currently connected.
 *
 * Degrades gracefully: when no BLE transport is available (Phase 12 not yet
 * implemented), shows "Mesh: offline" and no peer count.
 */
@Composable
fun MeshStatusChip(
    isMeshActive: Boolean,
    peerCount: Int,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, label) = if (isMeshActive && peerCount > 0) {
        Triple(
            Color(0x2200E676),
            Color(0xFF00E676),
            "Mesh: $peerCount peer${if (peerCount != 1) "s" else ""}"
        )
    } else if (isMeshActive) {
        Triple(
            Color(0x22FFB300),
            Color(0xFFFFB300),
            "Mesh: scanning…"
        )
    } else {
        Triple(
            Color(0x2278909C),
            Color(0xFF78909C),
            "Mesh: offline"
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .background(bgColor)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}
