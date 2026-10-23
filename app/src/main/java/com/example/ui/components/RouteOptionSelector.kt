package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RouteOptionSummary
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Candidate routes for the current plan.
 *
 * The routing engine can return several road paths for the same ordered
 * waypoints; the reference implementation presents them as a list of routes
 * with a "best route" preset. This component renders the same idea: every
 * candidate with its distance and duration, the fastest one labelled as the
 * best route, and the current selection highlighted.
 *
 * The list is a summary only - the geometry stays with the routing layer, so
 * the same list can be shared with the group while each device renders its
 * own map.
 */
@Composable
fun RouteOptionSelector(
    options: List<RouteOptionSummary>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showHeader: Boolean = true
) {
    if (options.isEmpty()) return

    // The engine's own ranking is the fastest candidate; that is the one the
    // "best route" preset refers to.
    val bestIndex = options.minByOrNull { it.durationSeconds }?.index

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader && options.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.AltRoute,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ROUTE OPTIONS",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        options.forEach { option ->
            val isSelected = option.index == selectedIndex
            val isBest = option.index == bestIndex && options.size > 1
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { onSelect(option.index) }
                    .testTag("route_option_${option.index}"),
                color = if (isSelected) SlateDark800 else SlateDark900,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) AmberPrimary else SlateDark800
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Route,
                        contentDescription = null,
                        tint = if (isSelected) AmberPrimary else TextSecondary,
                        modifier = Modifier.width(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${option.formattedDistance} • ${option.formattedDuration}",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isBest) {
                            Text(
                                text = "BEST ROUTE",
                                color = AmberPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (isSelected) {
                        Text(
                            text = "SELECTED",
                            color = AmberPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
