package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import androidx.room.TypeConverters

@Database(
    entities = [UserFeedback::class, CompletedRideEntity::class, com.example.data.amenities.AmenityEntity::class, com.example.data.amenities.AmenityQueryCacheEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(com.example.data.amenities.AmenityTypeConverter::class)
abstract class RideDatabase : RoomDatabase() {

    abstract fun feedbackDao(): FeedbackDao
    abstract fun completedRideDao(): CompletedRideDao
    abstract fun amenityDao(): com.example.data.amenities.AmenityDao

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

        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ridetogether.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
