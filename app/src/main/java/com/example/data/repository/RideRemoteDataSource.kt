package com.example.data.repository

import com.example.data.model.MembershipStatus
import com.example.data.model.QuickMessage
import com.example.data.model.Ride
import com.example.data.model.RideStatus
import com.example.data.model.RiderMember
import com.example.data.model.SosEvent

/**
 * Remote data source abstraction over cloud reads/writes.
 *
 * Implemented by [com.example.data.supabase.SupabaseRideRepository] (adapted)
 * and, in future, by a trusted-backend HTTP client for sensitive ops.
 */
interface RideRemoteDataSource {

    // --- Profiles (users/{id} → profiles) ---

    suspend fun upsertProfile(
        id: String,
        name: String,
        email: String?,
        photoUrl: String?,
        motorcycleModel: String
    ): Boolean

    // --- Rides ---

    suspend fun fetchRideById(rideId: String): Ride?

    suspend fun fetchActiveRideForLeader(leaderId: String): Ride?

    suspend fun findRideByInviteCode(code: String): Ride?

    suspend fun createRide(ride: Ride, leaderRider: RiderMember): Boolean

    suspend fun updateRide(rideId: String, updates: Map<String, Any>): Boolean

    suspend fun updateRideStatus(rideId: String, status: RideStatus): Boolean

    suspend fun transferLeadership(rideId: String, newLeaderId: String): Boolean

    // --- Riders (telemetry + membership) ---

    suspend fun upsertRider(rideId: String, rider: RiderMember): Boolean

    suspend fun setRiderMembership(
        rideId: String,
        userId: String,
        status: MembershipStatus
    ): Boolean

    // --- Messages ---

    suspend fun sendMessage(rideId: String, msg: QuickMessage): Boolean

    // --- SOS ---

    suspend fun createSos(rideId: String, sos: SosEvent): Boolean

    suspend fun acknowledgeSos(sosId: String, ackingUserId: String): Boolean

    suspend fun resolveSos(sosId: String): Boolean

    // --- Route geometry ---

    suspend fun saveRouteGeometry(
        rideId: String,
        points: List<Map<String, Any>>,
        steps: List<Map<String, Any>>,
        totalDistanceMeters: Double,
        totalDurationSeconds: Double
    ): Boolean

    suspend fun fetchRouteGeometry(rideId: String): Map<String, Any>?

    // --- Realtime (replaces Firestore snapshot listeners) ---

    fun subscribeToRide(rideId: String, onChange: (Ride) -> Unit): () -> Unit

    fun subscribeToRiders(rideId: String, onChange: (List<RiderMember>) -> Unit): () -> Unit

    fun subscribeToMessages(rideId: String, onChange: (QuickMessage) -> Unit): () -> Unit

    fun subscribeToSos(rideId: String, onChange: (SosEvent) -> Unit): () -> Unit
}
