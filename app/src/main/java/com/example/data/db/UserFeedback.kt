package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_feedback")
data class UserFeedback(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val riderName: String,
    val rideName: String,
    val rating: Int, // 1 to 5
    val category: String,
    val comments: String,
    val isRealGpsMode: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)
