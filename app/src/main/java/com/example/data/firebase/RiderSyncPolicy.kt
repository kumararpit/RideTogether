package com.example.data.firebase

import com.example.data.model.ConnectionStatus
import com.example.data.model.EmergencyState
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.MovementState
import com.example.data.model.RideRole
import com.example.data.model.RiderMember

/**
 * Data class representing timestamp provenance for a rider location event.
 * Explicitly distinguishes client event-generation time from server-write time.
 */
data class RiderTimestamp(
    val eventTimestampMs: Long = 0L,
    val serverTimestampMs: Long = 0L
) {
    val hasEventTimestamp: Boolean
        get() = eventTimestampMs > 0L

    val hasServerTimestamp: Boolean
        get() = serverTimestampMs > 0L

    /**
     * GPS measurement freshness timestamp.
     * Strictly event-generation time (0L if missing/invalid).
     * Server-write time is preserved separately and never used as proof of GPS measurement freshness.
     */
    val freshnessTimestampMs: Long
        get() = eventTimestampMs
}

/**
 * Production synchronization, ordering, coordinate validation, and freshness policy logic.
 */
object RiderSyncPolicy {
    const val STALE_THRESHOLD_MS = 25000L
    const val DISCONNECTED_THRESHOLD_MS = 60000L

    /**
     * Validates whether latitude and longitude are finite numbers within Earth bounds [-90..90, -180..180].
     */
    fun validateCoordinates(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (lat.isNaN() || lng.isNaN() || lat.isInfinite() || lng.isInfinite()) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    /**
     * Parses the event timestamp into a [RiderTimestamp] representation.
     * NEVER manufactures an event timestamp using current system time (System.currentTimeMillis())!
     * Preserves raw event-generation time and server timestamp separately without blending them into a single clock scale.
     */
    fun parseTimestamp(rawLastUpdatedMs: Long?, serverTimestampMs: Long?): RiderTimestamp {
        val eventTs = if (rawLastUpdatedMs != null && rawLastUpdatedMs > 0L) rawLastUpdatedMs else 0L
        val serverTs = if (serverTimestampMs != null && serverTimestampMs > 0L) serverTimestampMs else 0L
        return RiderTimestamp(eventTimestampMs = eventTs, serverTimestampMs = serverTs)
    }

    /**
     * Evaluates whether an incoming event snapshot should be accepted over the currently accepted rider state [existing].
     * Conservative Ordering Policy:
     * 1. If both [existing] and incoming have valid event-generation timestamps, event timestamp ordering is authoritative.
     * 2. An incoming event with an older event timestamp is REJECTED even if its sequence number or server timestamp is higher.
     * 3. When an existing rider exists ([existing] != null) and an incoming update lacks a valid event timestamp
     *    (!incomingTimestamp.hasEventTimestamp), the incoming update CANNOT be ordered reliably using client GPS event time.
     *    Server-write time or reset sequence numbers cannot establish GPS event ordering. The incoming update is REJECTED,
     *    preserving the accepted location and timestamps.
     * 4. When [existing] is null (first-seen rider with valid coordinates), an incoming update lacking an event timestamp
     *    is accepted to establish the initial record with lastUpdatedMs = 0L and connectionStatus = DISCONNECTED.
     */
    fun shouldAcceptUpdate(
        existing: RiderMember?,
        incomingSeq: Long,
        incomingTimestamp: RiderTimestamp,
        prevSeq: Long
    ): Boolean {
        if (existing == null) return true

        val existingEventTs = existing.lastUpdatedMs
        val incomingEventTs = incomingTimestamp.eventTimestampMs

        // Case 1: Both events have valid event-generation timestamps
        if (incomingEventTs > 0L && existingEventTs > 0L) {
            if (incomingEventTs < existingEventTs) {
                return false // Genuinely older event
            }
            if (incomingEventTs > existingEventTs) {
                return true // Genuinely newer event
            }
            // Identical event timestamps -> fall back to sequence numbers if present
            if (incomingSeq > 0L && prevSeq > 0L && incomingSeq < prevSeq) {
                return false
            }
            return true
        }

        // Case 2: Incoming update lacks a valid event-generation timestamp
        if (!incomingTimestamp.hasEventTimestamp) {
            // Cannot reliably order an unknown event time against an existing rider -> REJECT, preserving accepted location!
            return false
        }

        // Case 3: Incoming update HAS a valid event timestamp, but existing rider lacked one
        if (incomingTimestamp.hasEventTimestamp && existingEventTs <= 0L) {
            return true // Accept valid GPS event timestamp over unknown event timestamp state
        }

        return true
    }

    /**
     * Calculates connection status based on timestamp provenance and elapsed time to [currentTimeMs].
     * - If event timestamp is missing/unknown (0L), evaluates as DISCONNECTED without claiming fresh connection.
     * - Server timestamps cannot prove that the GPS measurement itself is recent.
     */
    fun calculateConnectionStatus(
        isCurrentUser: Boolean,
        isGpsAvailable: Boolean,
        timestamp: RiderTimestamp,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ConnectionStatus {
        if (isCurrentUser) {
            return if (isGpsAvailable) ConnectionStatus.CONNECTED else ConnectionStatus.LOCATION_UNAVAILABLE
        }

        if (!timestamp.hasEventTimestamp) {
            return ConnectionStatus.DISCONNECTED
        }

        val eventAgeMs = (currentTimeMs - timestamp.eventTimestampMs).coerceAtLeast(0L)
        return when {
            eventAgeMs > DISCONNECTED_THRESHOLD_MS -> ConnectionStatus.DISCONNECTED
            eventAgeMs > STALE_THRESHOLD_MS -> ConnectionStatus.LOCATION_STALE
            else -> ConnectionStatus.CONNECTED
        }
    }

    /**
     * Pure production decision function to process an incoming rider document snapshot.
     */
    fun processRiderSnapshot(
        id: String,
        name: String,
        motorcycleModel: String,
        avatarColorHex: Long,
        role: RideRole,
        status: MemberStatus,
        emergencyState: EmergencyState,
        rawLat: Double?,
        rawLng: Double?,
        defaultLat: Double,
        defaultLng: Double,
        speedKmh: Double,
        headingDeg: Float?,
        accuracyMeters: Float,
        sequenceNumber: Long,
        rawLastUpdatedMs: Long?,
        serverTimestampMs: Long?,
        stoppedDurationSec: Long,
        batteryPct: Int,
        existing: RiderMember?,
        prevSeq: Long,
        isCurrentUser: Boolean,
        isGpsAvailable: Boolean,
        currentTimeMs: Long = System.currentTimeMillis()
    ): RiderMember? {
        val isCoordValid = validateCoordinates(rawLat, rawLng)

        // Invalid first-seen coordinates -> reject record completely (return null)
        if (!isCoordValid && existing == null) {
            return null
        }

        val latVal = if (isCoordValid) rawLat!! else existing?.location?.latitude ?: defaultLat
        val lngVal = if (isCoordValid) rawLng!! else existing?.location?.longitude ?: defaultLng

        val riderTs = parseTimestamp(rawLastUpdatedMs, serverTimestampMs)

        // Check if update should be accepted
        val acceptUpdate = shouldAcceptUpdate(
            existing = existing,
            incomingSeq = sequenceNumber,
            incomingTimestamp = riderTs,
            prevSeq = prevSeq
        )

        if (!isCoordValid || !acceptUpdate) {
            return existing
        }

        val connStatus = calculateConnectionStatus(
            isCurrentUser = isCurrentUser,
            isGpsAvailable = isGpsAvailable,
            timestamp = riderTs,
            currentTimeMs = currentTimeMs
        )

        val movementState = when {
            status == MemberStatus.STOPPED || stoppedDurationSec > 0L -> MovementState.STOPPED
            status == MemberStatus.RIDING -> MovementState.RIDING
            else -> MovementState.UNKNOWN
        }

        return RiderMember(
            id = id,
            name = name,
            motorcycleModel = motorcycleModel,
            avatarColorHex = avatarColorHex,
            role = role,
            status = status,
            movementState = movementState,
            emergencyState = emergencyState,
            connectionStatus = connStatus,
            location = LatLng(latVal, lngVal),
            speedKmh = speedKmh,
            headingDeg = headingDeg,
            accuracyMeters = accuracyMeters,
            sequenceNumber = sequenceNumber,
            lastUpdatedMs = riderTs.eventTimestampMs, // Strictly event-generation timestamp!
            serverTimestampMs = riderTs.serverTimestampMs, // Server timestamp preserved separately!
            stoppedDurationSec = stoppedDurationSec,
            isCurrentUser = isCurrentUser,
            batteryPct = batteryPct
        )
    }
}
