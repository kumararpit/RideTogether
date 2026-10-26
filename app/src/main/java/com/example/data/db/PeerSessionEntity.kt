package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.offline.protocol.PeerId

/**
 * Room entity for tracking known offline-mesh peers and their session state.
 *
 * Records peers discovered via BLE advertising or connected via GATT, along
 * with their public keys and trust state.
 *
 * Clean-room design: schema is an original RideTogether design.
 */
@Entity(tableName = "peer_sessions")
data class PeerSessionEntity(

    @PrimaryKey
    @ColumnInfo(name = "peer_id") val peerId: ByteArray,

    /** X25519 static public key (32 bytes). */
    @ColumnInfo(name = "x25519_public_key") val x25519PublicKey: ByteArray,

    /** Ed25519 signing public key (32 bytes). */
    @ColumnInfo(name = "ed25519_public_key") val ed25519PublicKey: ByteArray,

    /** Epoch milliseconds when this peer was first observed. */
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,

    /** Epoch milliseconds when this peer was last seen. */
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,

    /** Whether this peer has been explicitly trusted by the user. */
    @ColumnInfo(name = "trusted", defaultValue = "0") val trusted: Boolean = false,

    /** Current connection state: "connected", "disconnected", or "unknown". */
    @ColumnInfo(name = "connection_state", defaultValue = "'unknown'") val connectionState: String = "unknown",
)
