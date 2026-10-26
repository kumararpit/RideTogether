package com.example.data.offline.model

/**
 * Delivery lifecycle state of an offline message, tracked per-device per-message.
 *
 * PENDING   → queued locally, not yet sent over mesh or Firestore
 * SENT      → handed to mesh transport / Firestore, awaiting confirmation
 * DELIVERED → mesh ACK received or Firestore write confirmed
 * ACKED     → recipient explicitly acknowledged (SOS ack, message read)
 * FAILED    → exhausted retries, will not retry without user action
 */
enum class MessageDeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    ACKED,
    FAILED
}
