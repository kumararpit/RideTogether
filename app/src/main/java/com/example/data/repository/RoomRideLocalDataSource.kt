package com.example.data.repository

import com.example.data.db.ActiveRideReferenceDao
import com.example.data.db.ActiveRideReferenceEntity
import com.example.data.db.ActiveRideTelemetryDao
import com.example.data.db.ActiveRideTelemetryEntity
import com.example.data.db.RideDatabase
import com.example.data.db.SyncOutboxDao
import com.example.data.db.SyncOutboxEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed implementation of [RideLocalDataSource].
 *
 * Wraps existing DAOs ([ActiveRideReferenceDao], [ActiveRideTelemetryDao]) plus
 * the new [SyncOutboxDao]. All database access is dispatched off the caller's
 * thread via the provided dispatcher (defaults to Dispatchers.Default).
 */
class RoomRideLocalDataSource(
    private val database: RideDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RideLocalDataSource {

    private val refDao: ActiveRideReferenceDao = database.activeRideReferenceDao()
    private val telDao: ActiveRideTelemetryDao = database.activeRideTelemetryDao()
    private val outboxDao: SyncOutboxDao = database.syncOutboxDao()

    // --- Active ride reference ---

    override suspend fun getActiveRideReference(): ActiveRideReferenceEntity? =
        withContext(dispatcher) { refDao.getReference() }

    override suspend fun insertOrReplaceActiveRideReference(entity: ActiveRideReferenceEntity) =
        withContext(dispatcher) { refDao.insertOrReplace(entity) }

    override suspend fun clearAllActiveRideReferences() =
        withContext(dispatcher) { refDao.clearAll() }

    // --- Active ride telemetry ---

    override suspend fun getTelemetry(rideId: String): ActiveRideTelemetryEntity? =
        withContext(dispatcher) { telDao.getTelemetry(rideId) }

    override suspend fun insertOrReplaceTelemetry(entity: ActiveRideTelemetryEntity) =
        withContext(dispatcher) { telDao.insertOrUpdate(entity) }

    override suspend fun clearAllTelemetry() =
        withContext(dispatcher) { telDao.clearAll() }

    // --- Sync outbox ---

    override suspend fun insertOutboxEntry(entry: SyncOutboxEntity) =
        withContext(dispatcher) { outboxDao.insert(entry) }

    override suspend fun getPendingOutbox(userId: String, nowMs: Long, limit: Int): List<SyncOutboxEntity> =
        withContext(dispatcher) { outboxDao.getPendingForUser(userId, nowMs, limit) }

    override suspend fun getOutboxEntry(id: String): SyncOutboxEntity? =
        withContext(dispatcher) { outboxDao.getById(id) }

    override suspend fun updateOutboxStatus(id: String, status: String, error: String?, nowMs: Long) =
        withContext(dispatcher) { outboxDao.updateStatus(id, status, error, nowMs) }

    override suspend fun scheduleRetry(id: String, nextRetryMs: Long, nowMs: Long) =
        withContext(dispatcher) { outboxDao.scheduleRetry(id, nextRetryMs, nowMs) }

    override suspend fun deleteOutboxEntry(id: String) =
        withContext(dispatcher) { outboxDao.deleteById(id) }

    override suspend fun clearOutboxForUser(userId: String) =
        withContext(dispatcher) { outboxDao.clearForUser(userId) }

    override suspend fun pruneDoneOlderThan(cutoffMs: Long) =
        withContext(dispatcher) { outboxDao.pruneDoneOlderThan(cutoffMs) }
}
