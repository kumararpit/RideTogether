package com.example.data.repository

import com.example.data.db.ActiveRideReferenceEntity
import com.example.data.db.ActiveRideTelemetryEntity
import com.example.data.db.SyncOutboxEntity

/**
 * Local data source abstraction over Room.
 *
 * Implemented by [RoomRideLocalDataSource]. Wraps existing DAOs
 * ([ActiveRideReferenceDao], [ActiveRideTelemetryDao]) plus the new
 * [SyncOutboxDao].
 */
interface RideLocalDataSource {

    // --- Active ride reference (existing ActiveRideReferenceDao) ---

    suspend fun getActiveRideReference(): ActiveRideReferenceEntity?

    suspend fun insertOrReplaceActiveRideReference(entity: ActiveRideReferenceEntity)

    suspend fun clearAllActiveRideReferences()

    // --- Active ride telemetry (existing ActiveRideTelemetryDao) ---

    suspend fun getTelemetry(rideId: String): ActiveRideTelemetryEntity?

    suspend fun insertOrReplaceTelemetry(entity: ActiveRideTelemetryEntity)

    suspend fun clearAllTelemetry()

    // --- Sync outbox (new SyncOutboxDao) ---

    suspend fun insertOutboxEntry(entry: SyncOutboxEntity)

    suspend fun getPendingOutbox(userId: String, nowMs: Long, limit: Int): List<SyncOutboxEntity>

    suspend fun getOutboxEntry(id: String): SyncOutboxEntity?

    suspend fun updateOutboxStatus(id: String, status: String, error: String?, nowMs: Long)

    suspend fun scheduleRetry(id: String, nextRetryMs: Long, nowMs: Long)

    suspend fun deleteOutboxEntry(id: String)

    suspend fun clearOutboxForUser(userId: String)

    suspend fun pruneDoneOlderThan(cutoffMs: Long)
}
