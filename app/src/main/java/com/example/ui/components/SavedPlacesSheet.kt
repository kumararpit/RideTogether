package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.amenities.AmenityType
import com.example.data.db.SavedPlaceEntity
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rider's saved places.
 *
 * The shortlist lives on the device (Room) and is shared with nobody: a good
 * café or a reliable workshop is the rider's own note, not a group document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesSheet(
    places: List<SavedPlaceEntity>,
    onRemove: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateDark800
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("saved_places_sheet")
        ) {
            Text(
                text = "Saved places",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Text(
                text = "Your own shortlist, kept on this device",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (places.isEmpty()) {
                EmptyState(
                    title = "No saved places yet",
                    message = "Open a place on the map and tap Save place to keep it here.",
                    icon = Icons.Default.BookmarkBorder
                )
                Spacer(modifier = Modifier.height(16.dp))
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(places, key = { it.osmId }) { place ->
                    SavedPlaceRow(place = place, onRemove = { onRemove(place.osmId) })
                }
            }
        }
    }
}

@Composable
private fun SavedPlaceRow(place: SavedPlaceEntity, onRemove: () -> Unit) {
    val type = runCatching { AmenityType.valueOf(place.type) }.getOrDefault(AmenityType.UNKNOWN)
    val color = AmenityIconConfig.getCategoryColor(type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RTShapes.control)
            .background(com.example.ui.theme.SlateDark700)
            .heightIn(min = 60.dp)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("saved_place_${place.osmId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AmenityIconConfig.getCategoryIcon(type),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name?.takeIf { it.isNotBlank() }
                    ?: AmenityIconConfig.getCategoryDisplayName(type),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(AmenityIconConfig.getCategoryDisplayName(type))
                    append(" · saved ")
                    append(SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(place.savedAtMs)))
                },
                color = TextTertiary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(48.dp)
                .testTag("remove_saved_place_${place.osmId}")
        ) {
            Icon(
                imageVector = Icons.Default.BookmarkRemove,
                contentDescription = "Remove ${place.name ?: "place"} from saved places",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
