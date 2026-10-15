package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.amenities.AmenityEntity
import com.example.data.model.LatLng
import com.example.ui.theme.SlateDark800

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmenityDetailsSheet(
    amenity: AmenityEntity,
    riderLocation: LatLng?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateDark800
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = amenity.name ?: AmenityIconConfig.getCategoryDisplayName(amenity.type),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Category: ${AmenityIconConfig.getCategoryDisplayName(amenity.type)}", color = Color.Gray)
            
            if (riderLocation != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(riderLocation.latitude, riderLocation.longitude, amenity.lat, amenity.lon, results)
                val dist = results[0]
                val distStr = if (dist > 1000) String.format(java.util.Locale.US, "%.1f km", dist / 1000f) else "${dist.toInt()} m"
                Text(text = "Distance: $distStr", color = Color.LightGray)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
