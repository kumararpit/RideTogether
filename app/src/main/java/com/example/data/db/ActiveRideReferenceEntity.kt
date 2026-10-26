package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable, on-device reference to the ride the user is currently part of.
 *
 * Written whenever [com.example.data.firebase.RideRepository] sets `_currentRide`
 * (create / join / restore) and cleared on leave/end. Survives process death so
 * startup restoration does not depend on a live Firestore query or on the user
 * being the ride leader (the Firestore-side [com.example.data.firebase.RideRepository.fetchActiveRideForUser]
 * query only matches leaderId == uid, so members' rides would otherwise be lost).
 *
 * The reference alone is never treated as proof the ride is still active — on
 * restore the ride document is re-verified (non-terminal status) before any
 * navigation, and a terminal/stale reference is cleared.
 */
@Entity(tableName = "active_ride_reference")
data class ActiveRideReferenceEntity(
    @PrimaryKey
    val rideId: String,
    val leaderId: String,
    val status: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)
