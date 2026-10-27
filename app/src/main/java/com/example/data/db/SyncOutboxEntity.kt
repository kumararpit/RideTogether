package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync outbox entry for offline-first writes to Supabase.
 *
 * Each entry represents a pending outbound operation (CREATE, UPDATE, UPSERT,
 * PATCH, DELETE) that will be flushed to Supabase by [RideSyncWorker] when
 * connectivity is available. The [idempotencyKey] prevents duplicate execution
 * on retry.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["user_id", "status", "created_at_ms"]),
        Index(value = ["idempotency_key"], unique = true)
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,

    @ColumnInfo(name = "user_id") val userId: String,

    @ColumnInfo(name = "entity_type") val entityType: String,

    @ColumnInfo(name = "entity_id") val entityId: String,

    @ColumnInfo(name = "op_type") val opType: String,

    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,

    @ColumnInfo(name = "payload") val payload: String,

    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,

    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,

    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,

    @ColumnInfo(name = "max_retries") val maxRetries: Int = 7,

    @ColumnInfo(name = "status") val status: String = "PENDING",

    @ColumnInfo(name = "last_error") val lastError: String? = null,

    @ColumnInfo(name = "next_retry_at_ms") val nextRetryAtMs: Long
)
