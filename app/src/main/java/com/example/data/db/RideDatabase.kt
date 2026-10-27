package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.map.tile.TileDao
import com.example.data.map.tile.TileEntity

@Database(
    entities = [
        UserFeedback::class,
        CompletedRideEntity::class,
        com.example.data.amenities.AmenityEntity::class,
        com.example.data.amenities.AmenityQueryCacheEntity::class,
        ActiveRideTelemetryEntity::class,
        ActiveRideReferenceEntity::class,
        RecentPlaceEntity::class,
        SavedPlaceEntity::class,
        TileEntity::class,
        OfflineMessageEntity::class,
        PeerSessionEntity::class,
        SyncOutboxEntity::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(com.example.data.amenities.AmenityTypeConverter::class)
abstract class RideDatabase : RoomDatabase() {

    abstract fun feedbackDao(): FeedbackDao
    abstract fun completedRideDao(): CompletedRideDao
    abstract fun amenityDao(): com.example.data.amenities.AmenityDao
    abstract fun activeRideTelemetryDao(): ActiveRideTelemetryDao
    abstract fun activeRideReferenceDao(): ActiveRideReferenceDao
    abstract fun recentPlaceDao(): RecentPlaceDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun tileDao(): TileDao
    abstract fun offlineMessageDao(): OfflineMessageDao
    abstract fun peerSessionDao(): PeerSessionDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `amenities` (
                        `osmId` INTEGER NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `lat` REAL NOT NULL, 
                        `lon` REAL NOT NULL, 
                        `name` TEXT, 
                        `tagsJson` TEXT, 
                        PRIMARY KEY(`osmId`)
                    )
                """.trimIndent())
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `amenity_queries` (
                        `cellKey` TEXT NOT NULL, 
                        `timestampMs` INTEGER NOT NULL, 
                        PRIMARY KEY(`cellKey`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `completed_rides` ADD COLUMN `isImplausibleData` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `active_ride_telemetry` (
                        `rideId` TEXT NOT NULL,
                        `startedAtMs` INTEGER NOT NULL,
                        `actualRecordedDistanceMeters` REAL NOT NULL,
                        `lastGpsLat` REAL,
                        `lastGpsLng` REAL,
                        `lastGpsTimestampMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`rideId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: existing ride history untouched.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recent_places` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `address` TEXT,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `category` TEXT,
                        `lastUsedAtMs` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: the rider's saved places.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `saved_places` (
                        `osmId` INTEGER NOT NULL,
                        `name` TEXT,
                        `type` TEXT NOT NULL,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        `savedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`osmId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: on-disk tile cache (TQL store).
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tile_cache` (
                        `zoom` INTEGER NOT NULL,
                        `x` INTEGER NOT NULL,
                        `y` INTEGER NOT NULL,
                        `data` BLOB NOT NULL,
                        `etag` TEXT,
                        `cached_at_ms` INTEGER NOT NULL DEFAULT 0,
                        `expires_at_ms` INTEGER NOT NULL DEFAULT 0,
                        `priority` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`zoom`, `x`, `y`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `ix_tile_cache_expiration`
                    ON `tile_cache` (`expires_at_ms`)
                """.trimIndent())
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: empty-tile marker for the disk cache.
                db.execSQL("ALTER TABLE `tile_cache` ADD COLUMN `is_empty` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Schema now matches TileEntity (DEFAULT 0 on cached_at_ms / expires_at_ms /
                // priority / is_empty). Empty migration bumps the version so Room's
                // identity hash stays in sync after the @ColumnInfo(defaultValue = "0")
                // additions on TileEntity fields.
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: offline mesh store-and-forward queue.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `offline_messages` (
                        `id` TEXT NOT NULL,
                        `sender_id` BLOB NOT NULL,
                        `recipient_id` BLOB NOT NULL,
                        `msg_type` INTEGER NOT NULL,
                        `ttl` INTEGER NOT NULL,
                        `timestamp_ms` INTEGER NOT NULL,
                        `payload` BLOB NOT NULL,
                        `signature` BLOB NOT NULL,
                        `enqueued_at_ms` INTEGER NOT NULL,
                        `delivered` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // Additive migration: peer session tracking.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `peer_sessions` (
                        `peer_id` BLOB NOT NULL,
                        `x25519_public_key` BLOB NOT NULL,
                        `ed25519_public_key` BLOB NOT NULL,
                        `first_seen_ms` INTEGER NOT NULL,
                        `last_seen_ms` INTEGER NOT NULL,
                        `trusted` INTEGER NOT NULL DEFAULT 0,
                        `connection_state` TEXT NOT NULL DEFAULT 'unknown',
                        PRIMARY KEY(`peer_id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: durable active-ride reference for startup
                // restoration (survives process death, covers members as well as
                // leaders since it is written on join as well as create).
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `active_ride_reference` (
                        `rideId` TEXT NOT NULL,
                        `leaderId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`rideId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate peer_sessions to match PeerSessionEntity schema
                // exactly (fixes schema hash mismatch from earlier migrations).
                db.execSQL("DROP TABLE IF EXISTS `peer_sessions`")
                db.execSQL("""
                    CREATE TABLE `peer_sessions` (
                        `peer_id` BLOB NOT NULL,
                        `x25519_public_key` BLOB NOT NULL,
                        `ed25519_public_key` BLOB NOT NULL,
                        `first_seen_ms` INTEGER NOT NULL,
                        `last_seen_ms` INTEGER NOT NULL,
                        `trusted` INTEGER NOT NULL DEFAULT 0,
                        `connection_state` TEXT NOT NULL DEFAULT 'unknown',
                        PRIMARY KEY(`peer_id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: sync outbox for offline-first Supabase writes.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `entity_id` TEXT NOT NULL,
                        `op_type` TEXT NOT NULL,
                        `idempotency_key` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `updated_at_ms` INTEGER NOT NULL,
                        `retry_count` INTEGER NOT NULL DEFAULT 0,
                        `max_retries` INTEGER NOT NULL DEFAULT 7,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `last_error` TEXT,
                        `next_retry_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_sync_outbox_user_status_created`
                    ON `sync_outbox` (`user_id`, `status`, `created_at_ms`)
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `idx_sync_outbox_idempotency_key`
                    ON `sync_outbox` (`idempotency_key`)
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ridetogether.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
