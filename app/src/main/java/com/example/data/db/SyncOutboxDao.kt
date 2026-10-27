package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncOutboxEntity)

    @Query(
        "SELECT * FROM sync_outbox WHERE status = 'PENDING' AND next_retry_at_ms <= :nowMs " +
        "AND user_id = :userId ORDER BY created_at_ms ASC LIMIT :limit"
    )
    suspend fun getPendingForUser(userId: String, nowMs: Long, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE id = :id")
    suspend fun getById(id: String): SyncOutboxEntity?

    @Query(
        "UPDATE sync_outbox SET status = :status, last_error = :error, updated_at_ms = :nowMs " +
        "WHERE id = :id"
    )
    suspend fun updateStatus(id: String, status: String, error: String?, nowMs: Long)

    @Query(
        "UPDATE sync_outbox SET retry_count = retry_count + 1, next_retry_at_ms = :nextRetryMs, " +
        "status = 'PENDING', updated_at_ms = :nowMs WHERE id = :id"
    )
    suspend fun scheduleRetry(id: String, nextRetryMs: Long, nowMs: Long)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sync_outbox WHERE user_id = :userId")
    suspend fun clearForUser(userId: String)

    @Query(
        "DELETE FROM sync_outbox WHERE status IN ('DONE','PERMANENT_FAILURE') " +
        "AND created_at_ms < :cutoffMs"
    )
    suspend fun pruneDoneOlderThan(cutoffMs: Long)
}
