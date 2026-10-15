package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wc
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.amenities.AmenityType

object AmenityIconConfig {
    fun getCategoryColor(type: AmenityType): Color {
        return when (type) {
            AmenityType.FUEL -> Color(0xFF1976D2)
            AmenityType.CAFE -> Color(0xFFE65100)
            AmenityType.REPAIR -> Color(0xFF388E3C)
            AmenityType.REST_STOP -> Color(0xFF7B1FA2)
            AmenityType.CONVENIENCE_STORE -> Color(0xFFD81B60)
            AmenityType.HOTEL -> Color(0xFF00796B)
            AmenityType.RESTAURANT -> Color(0xFF5D4037)
            AmenityType.TOILET -> Color(0xFF455A64)
            AmenityType.SCENIC_VIEWPOINT -> Color(0xFF512DA8)
            AmenityType.UNKNOWN -> Color(0xFF757575)
        }
    }

    fun getCategoryIcon(type: AmenityType): ImageVector {
        return when (type) {
            AmenityType.FUEL -> Icons.Default.LocalGasStation
            AmenityType.CAFE -> Icons.Default.Coffee
            AmenityType.REPAIR -> Icons.Default.Build
            AmenityType.REST_STOP -> Icons.Default.LocalParking
            AmenityType.CONVENIENCE_STORE -> Icons.Default.ShoppingCart
            AmenityType.HOTEL -> Icons.Default.Hotel
            AmenityType.RESTAURANT -> Icons.Default.Restaurant
            AmenityType.TOILET -> Icons.Default.Wc
            AmenityType.SCENIC_VIEWPOINT -> Icons.Default.PhotoCamera
            AmenityType.UNKNOWN -> Icons.Default.Place
        }
    }

    fun getCategoryDisplayName(type: AmenityType): String {
        return when (type) {
            AmenityType.FUEL -> "Fuel Station"
            AmenityType.CAFE -> "Cafe"
            AmenityType.REPAIR -> "Motorcycle Repair"
            AmenityType.REST_STOP -> "Rest Stop"
            AmenityType.CONVENIENCE_STORE -> "Convenience Store"
            AmenityType.HOTEL -> "Hotel"
            AmenityType.RESTAURANT -> "Restaurant"
            AmenityType.TOILET -> "Restroom"
            AmenityType.SCENIC_VIEWPOINT -> "Scenic Viewpoint"
            AmenityType.UNKNOWN -> "Amenity"
        }
    }
}
