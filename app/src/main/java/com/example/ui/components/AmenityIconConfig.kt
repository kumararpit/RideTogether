package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineSeatFlat
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Wc
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.amenities.AmenityType
import com.example.ui.map.AmenityPalette

/**
 * The category symbols of the amenity layer.
 *
 * ## Provenance of the artwork
 *
 * Every symbol here is a **stock Material icon** (Apache License 2.0, the
 * Material Symbols/Icons set that ships with `androidx.compose.material.icons`).
 * Nothing is extracted from, traced out of, or copied from the reference
 * application's own POI artwork — that artwork is proprietary and is not
 * reproduced anywhere in RideTogether.
 *
 * The symbols are chosen to be *recognisable at map size*, which is the
 * property that matters on a pin: a fuel pump, a coffee cup, a fork and
 * knife, a motorcycle, a seat, a shop front, a bed, a WC sign, a landscape.
 */
object AmenityIconConfig {

    /** The category symbol of an amenity type. */
    fun getCategoryIcon(type: AmenityType): ImageVector = when (type) {
        AmenityType.FUEL -> Icons.Default.LocalGasStation
        AmenityType.CAFE -> Icons.Default.Coffee
        AmenityType.RESTAURANT -> Icons.Default.Restaurant
        // A motorcycle repair shop is a motorcycle workshop, not a car garage.
        AmenityType.REPAIR -> Icons.Default.TwoWheeler
        // A rest stop is a place to sit and take a break - not a car park.
        AmenityType.REST_STOP -> Icons.Default.AirlineSeatFlat
        AmenityType.CONVENIENCE_STORE -> Icons.Default.Storefront
        AmenityType.HOTEL -> Icons.Default.Hotel
        AmenityType.TOILET -> Icons.Default.Wc
        AmenityType.SCENIC_VIEWPOINT -> Icons.Default.Landscape
        AmenityType.UNKNOWN -> Icons.Default.Place
    }

    /** The category colour of an amenity type, in the given map theme. */
    fun getCategoryColor(type: AmenityType, theme: AmenityPalette.Theme = AmenityPalette.Theme.DAY): Color =
        AmenityPalette.colorFor(type, theme)

    /** The category name shown to the rider. */
    fun getCategoryDisplayName(type: AmenityType): String = when (type) {
        AmenityType.FUEL -> "Fuel Station"
        AmenityType.CAFE -> "Cafe"
        AmenityType.REPAIR -> "Motorcycle Repair"
        AmenityType.REST_STOP -> "Rest Stop"
        AmenityType.CONVENIENCE_STORE -> "Convenience Store"
        AmenityType.HOTEL -> "Hotel"
        AmenityType.RESTAURANT -> "Restaurant"
        AmenityType.TOILET -> "Restroom"
        AmenityType.SCENIC_VIEWPOINT -> "Scenic Viewpoint"
        AmenityType.UNKNOWN -> "Place"
    }

    /**
     * The "sub category" of a place, when the data carries one (a cuisine, a
     * fuel type, a speciality). It is the second line of the place card, the
     * way a rider reads "North Indian · Vegetarian".
     */
    fun subCategory(type: AmenityType, tags: Map<String, String>): String? = when (type) {
        AmenityType.CAFE -> tags["cuisine"]
        AmenityType.RESTAURANT -> tags["cuisine"]
        AmenityType.FUEL -> tags["fuel:type"]
        AmenityType.HOTEL -> tags["stars"]?.let { "$it stars" }
        else -> null
    }
}
