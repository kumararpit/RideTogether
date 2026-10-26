package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.offline.protocol.PeerId

/**
 * Room entity for the offline mesh store-and-forward queue.
 *
 * Messages that cannot be delivered immediately (recipient not connected) are
 * persisted here and retried when the recipient comes online. Messages with
 * expired TTL are pruned.
 *
 * Clean-room design: schema is an original RideTogether design.
 */
@Entity(tableName = "offline_messages")
data class OfflineMessageEntity(

    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,

    /** Sender peer id (8 bytes). */
    @ColumnInfo(name = "sender_id") val senderId: ByteArray,

    /** Recipient peer id (8 bytes), or BROADCAST (all zeros). */
    @ColumnInfo(name = "recipient_id") val recipientId: ByteArray,

    /** OfflineMessageType ordinal. */
    @ColumnInfo(name = "msg_type") val msgType: Int,

    /** Remaining TTL hops. */
    @ColumnInfo(name = "ttl") val ttl: Int,

    /** Epoch milliseconds when the message originated. */
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,

    /** Opaque message payload. */
    @ColumnInfo(name = "payload") val payload: ByteArray,

    /** Ed25519 signature (64 bytes) or empty if unsigned. */
    @ColumnInfo(name = "signature") val signature: ByteArray,

    /** Epoch milliseconds when the message was enqueued. */
    @ColumnInfo(name = "enqueued_at_ms") val enqueuedAtMs: Long,

    /** Whether the message has been delivered. */
    @ColumnInfo(name = "delivered", defaultValue = "0") val delivered: Boolean = false,
)
