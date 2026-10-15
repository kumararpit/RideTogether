package com.example.data.amenities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AmenityType {
    FUEL,
    CAFE,
    REPAIR,
    REST_STOP,
    CONVENIENCE_STORE,
    HOTEL,
    RESTAURANT,
    TOILET,
    SCENIC_VIEWPOINT,
    UNKNOWN
}

@Entity(tableName = "amenities")
data class AmenityEntity(
    @PrimaryKey val osmId: Long,
    val type: AmenityType,
    val lat: Double,
    val lon: Double,
    val name: String?,
    val tagsJson: String?
)

@Entity(tableName = "amenity_queries")
data class AmenityQueryCacheEntity(
    @PrimaryKey val cellKey: String,
    val timestampMs: Long
)
