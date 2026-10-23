package com.example

import com.example.data.firebase.SosDeliveryBackend

/**
 * In-memory SOS delivery backend for the offline suite.
 *
 * Mirrors the Firestore contract: a call either completes or throws, so the
 * repository's rollback behaviour is exercised exactly as in production.
 */
class InMemorySosBackend : SosDeliveryBackend {

    val delivered = mutableListOf<String>()
    val resolved = mutableListOf<String>()

    var failDelivery: Boolean = false
    var failResolve: Boolean = false

    override suspend fun deliver(rideId: String, sosId: String, doc: Map<String, Any>) {
        if (failDelivery) throw IllegalStateException("delivery backend unavailable")
        delivered.add(sosId)
    }

    override suspend fun resolve(rideId: String, sosId: String, fields: Map<String, Any>) {
        if (failResolve) throw IllegalStateException("resolve backend unavailable")
        resolved.add(sosId)
    }
}
