package com.example.data.place

import com.example.data.model.LatLng
import java.util.Locale

/**
 * Domain model for a chosen place. Coordinates are always numeric and are the
 * single source of truth used for routing; display strings are never used as
 * identifiers or coordinate substitutes.
 */
data class SelectedPlace(
    val id: String,
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val source: PlaceSource,
    val category: String? = null
) {
    /** Coordinates in the app's LatLng(latitude, longitude) model. */
    fun latLng(): LatLng = LatLng(latitude, longitude)

    fun coordinateText(): String =
        String.format(Locale.US, "%.4f, %.4f", latitude, longitude)
}

enum class PlaceSource {
    PHOTON,
    CURRENT_LOCATION,
    MAP_SELECTION
}
