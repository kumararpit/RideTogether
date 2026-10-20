package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserFeedback::class,
        CompletedRideEntity::class,
        com.example.data.amenities.AmenityEntity::class,
        com.example.data.amenities.AmenityQueryCacheEntity::class,
        ActiveRideTelemetryEntity::class,
        RecentPlaceEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(com.example.data.amenities.AmenityTypeConverter::class)
abstract class RideDatabase : RoomDatabase() {

    abstract fun feedbackDao(): FeedbackDao
    abstract fun completedRideDao(): CompletedRideDao
    abstract fun amenityDao(): com.example.data.amenities.AmenityDao
    abstract fun activeRideTelemetryDao(): ActiveRideTelemetryDao
    abstract fun recentPlaceDao(): RecentPlaceDao

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

        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ridetogether.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
