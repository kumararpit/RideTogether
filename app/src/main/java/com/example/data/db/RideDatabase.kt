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
        RecentPlaceEntity::class,
        SavedPlaceEntity::class,
        TileEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(com.example.data.amenities.AmenityTypeConverter::class)
abstract class RideDatabase : RoomDatabase() {

    abstract fun feedbackDao(): FeedbackDao
    abstract fun completedRideDao(): CompletedRideDao
    abstract fun amenityDao(): com.example.data.amenities.AmenityDao
    abstract fun activeRideTelemetryDao(): ActiveRideTelemetryDao
    abstract fun recentPlaceDao(): RecentPlaceDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun tileDao(): TileDao

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

        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ridetogether.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
