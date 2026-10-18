package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Bounded cache of user-confirmed selected places for quick reuse (including
 * without internet). Stores structured labels and numeric coordinates; display
 * strings are never substitutes for coordinates.
 */
@Entity(tableName = "recent_places")
data class RecentPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lon: Double,
    val source: String,
    val category: String?,
    val lastUsedAtMs: Long,
    val useCount: Int
)
