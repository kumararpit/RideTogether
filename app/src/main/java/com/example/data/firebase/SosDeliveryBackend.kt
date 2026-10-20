package com.example.data.firebase

/**
 * Delivery backend for SOS events.
 *
 * Production uses Firestore (the repository writes the event document and the
 * resolution transaction itself). Tests inject a local backend so the SOS
 * lifecycle - trigger, idempotency, acknowledgment, resolution - can be
 * exercised offline: the offline suite must never depend on a live backend or
 * on network state.
 *
 * The contract mirrors the Firestore path exactly: a call either completes
 * (the event is durably stored) or throws (the caller must roll the local
 * state back).
 */
interface SosDeliveryBackend {

    /** Persists a newly triggered SOS event. */
    suspend fun deliver(rideId: String, sosId: String, doc: Map<String, Any>)

    /** Marks a previously delivered SOS event as resolved. */
    suspend fun resolve(rideId: String, sosId: String, fields: Map<String, Any>)
}
