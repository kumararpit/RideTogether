package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.PlaceCardBuilder
import com.example.data.amenities.AmenityType
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark500
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

/**
 * Screen 8 of the reference: places along the route.
 *
 * The list is filtered from the amenity cache the map already holds — nothing
 * new is fetched for typing, and the sheet keeps working offline. A detour is
 * shown only for the places whose detour has actually been calculated (the
 * caller prices a bounded number of them), never as an estimate of an
 * estimate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPlacesSheet(
    amenities: List<AmenityEntity>,
    riderLocation: LatLng?,
    detourSecondsById: Map<Long, Double>,
    onSelect: (AmenityEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(amenities, query, category, riderLocation) {
        amenities
            .asSequence()
            .filter { amenity ->
                category == null || amenity.type.name == category
            }
            .filter { amenity ->
                if (query.isBlank()) return@filter true
                val haystack = buildString {
                    append(amenity.name.orEmpty())
                    append(' ')
                    append(AmenityIconConfig.getCategoryDisplayName(amenity.type))
                }
                haystack.contains(query.trim(), ignoreCase = true)
            }
            .sortedBy { distanceTo(riderLocation, it) ?: Double.MAX_VALUE }
            .toList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SlateDark800
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .testTag("nearby_places_sheet")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Nearby Places",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Places on the map along this ride",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close nearby places",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search along route…", color = TextTertiary) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary,
                    unfocusedBorderColor = SlateDark500,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AmberPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("nearby_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryChip(
                    label = "All",
                    selected = category == null,
                    onClick = { category = null }
                )
                CategoryChip(
                    label = "Fuel",
                    selected = category == AmenityType.FUEL.name,
                    onClick = { category = AmenityType.FUEL.name }
                )
                CategoryChip(
                    label = "Food",
                    selected = category == AmenityType.RESTAURANT.name,
                    onClick = { category = AmenityType.RESTAURANT.name }
                )
                CategoryChip(
                    label = "Café",
                    selected = category == AmenityType.CAFE.name,
                    onClick = { category = AmenityType.CAFE.name }
                )
                CategoryChip(
                    label = "Rest",
                    selected = category == AmenityType.REST_STOP.name,
                    onClick = { category = AmenityType.REST_STOP.name }
                )
                CategoryChip(
                    label = "Repair",
                    selected = category == AmenityType.REPAIR.name,
                    onClick = { category = AmenityType.REPAIR.name }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                EmptyState(
                    title = if (amenities.isEmpty()) "Places are still loading" else "No places match",
                    message = if (amenities.isEmpty()) {
                        "Places appear here as the map loads them."
                    } else {
                        "Try another category or a shorter search."
                    },
                    icon = Icons.Default.Storefront
                )
                Spacer(modifier = Modifier.height(16.dp))
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.osmId }) { amenity ->
                    NearbyPlaceRow(
                        amenity = amenity,
                        distanceMeters = distanceTo(riderLocation, amenity),
                        detourSeconds = detourSecondsById[amenity.osmId],
                        onClick = { onSelect(amenity) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyPlaceRow(
    amenity: AmenityEntity,
    distanceMeters: Double?,
    detourSeconds: Double?,
    onClick: () -> Unit
) {
    val color = AmenityIconConfig.getCategoryColor(amenity.type)
    val tags = remember(amenity.tagsJson) { PlaceCardBuilder.parseTags(amenity.tagsJson) }
    val rating = remember(tags) {
        (tags["stars"] ?: tags["rating"])?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..5.0 }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RTShapes.card)
            .background(com.example.ui.theme.SlateDark700)
            .clickable(onClick = onClick)
            .heightIn(min = 66.dp)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("nearby_place_${amenity.osmId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AmenityIconConfig.getCategoryIcon(amenity.type),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = amenity.name?.takeIf { it.isNotBlank() }
                    ?: AmenityIconConfig.getCategoryDisplayName(amenity.type),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rating != null) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFC24B),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = String.format(Locale.US, "%.1f", rating),
                        color = Color(0xFFFFC24B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(" · ", color = TextTertiary, fontSize = 11.sp)
                }
                Text(
                    text = buildString {
                        append(AmenityIconConfig.getCategoryDisplayName(amenity.type))
                        distanceMeters?.let {
                            append(" · ")
                            append(formatDistance(it))
                        }
                    },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (detourSeconds != null) {
                val label = com.example.data.model.AmenityStopPlan.detourLabel(detourSeconds)
                if (label != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = label,
                        color = AmberPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AmberPrimary)
                .testTag("nearby_add_${amenity.osmId}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Open ${amenity.name ?: "place"}",
                tint = Color(0xFF1A1205),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) AmberPrimary else SlateDark700,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .clickable(onClick = onClick)
            .heightIn(min = 34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
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
}

private fun distanceTo(riderLocation: LatLng?, amenity: AmenityEntity): Double? {
    if (riderLocation == null) return null
    val results = FloatArray(1)
    android.location.Location.distanceBetween(
        riderLocation.latitude, riderLocation.longitude,
        amenity.lat, amenity.lon,
        results
    )
    return results[0].toDouble()
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000.0)
    else "${Math.round(meters).toInt()} m"
