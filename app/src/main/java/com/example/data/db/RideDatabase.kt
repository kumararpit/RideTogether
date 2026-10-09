package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UserFeedback::class, CompletedRideEntity::class],
    version = 2,
    exportSchema = false
)
abstract class RideDatabase : RoomDatabase() {

    abstract fun feedbackDao(): FeedbackDao
    abstract fun completedRideDao(): CompletedRideDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        fun getInstance(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ridetogether.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
