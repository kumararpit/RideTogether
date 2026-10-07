package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedbackDao {

    @Query("SELECT * FROM user_feedback ORDER BY timestampMs DESC")
    fun getAllFeedback(): Flow<List<UserFeedback>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedback(feedback: UserFeedback): Long

    @Query("SELECT COUNT(*) FROM user_feedback")
    suspend fun getFeedbackCount(): Int
}
