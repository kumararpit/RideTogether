package com.example.data.repository

import com.example.data.db.FeedbackDao
import com.example.data.db.UserFeedback
import kotlinx.coroutines.flow.Flow

class FeedbackRepository(private val feedbackDao: FeedbackDao) {

    val allFeedback: Flow<List<UserFeedback>> = feedbackDao.getAllFeedback()

    suspend fun submitFeedback(feedback: UserFeedback): Long {
        return feedbackDao.insertFeedback(feedback)
    }

    suspend fun getFeedbackCount(): Int {
        return feedbackDao.getFeedbackCount()
    }
}
