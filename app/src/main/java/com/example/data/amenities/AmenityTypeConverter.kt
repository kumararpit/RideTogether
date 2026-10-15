package com.example.data.amenities

import androidx.room.TypeConverter

class AmenityTypeConverter {
    @TypeConverter
    fun fromAmenityType(type: AmenityType): String = type.name

    @TypeConverter
    fun toAmenityType(name: String): AmenityType {
        return try {
            AmenityType.valueOf(name)
        } catch (e: Exception) {
            AmenityType.UNKNOWN
        }
    }
}
