package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PeerSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<PeerSessionEntity>)

    @Query("SELECT * FROM peer_sessions WHERE peer_id = :peerId")
    suspend fun get(peerId: ByteArray): PeerSessionEntity?

    @Query("SELECT * FROM peer_sessions ORDER BY last_seen_ms DESC")
    suspend fun getAll(): List<PeerSessionEntity>

    @Query(
        "UPDATE peer_sessions SET last_seen_ms = :lastSeenMs, connection_state = :state " +
            "WHERE peer_id = :peerId"
    )
    suspend fun updateSession(peerId: ByteArray, lastSeenMs: Long, state: String)

    @Query("DELETE FROM peer_sessions WHERE peer_id = :peerId")
    suspend fun delete(peerId: ByteArray)
}
