package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OfflineMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: OfflineMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<OfflineMessageEntity>)

    @Query("SELECT * FROM offline_messages WHERE delivered = 0 ORDER BY enqueued_at_ms ASC")
    suspend fun getUndelivered(): List<OfflineMessageEntity>

    @Query(
        "SELECT * FROM offline_messages " +
            "WHERE delivered = 0 AND recipient_id = :recipientId " +
            "ORDER BY enqueued_at_ms ASC"
    )
    suspend fun getUndeliveredForPeer(recipientId: ByteArray): List<OfflineMessageEntity>

    @Query("DELETE FROM offline_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM offline_messages WHERE delivered = 1")
    suspend fun deleteDelivered()

    @Query(
        "DELETE FROM offline_messages " +
            "WHERE delivered = 0 AND (ttl <= 0 OR enqueued_at_ms + :ttlMs < :nowMs)"
    )
    suspend fun expireExpired(ttlMs: Long, nowMs: Long)

    @Query("UPDATE offline_messages SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("SELECT COUNT(*) FROM offline_messages WHERE delivered = 0")
    suspend fun undeliveredCount(): Int
}
