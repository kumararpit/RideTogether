package com.example.data.model

import java.util.UUID

/**
 * Firestore serialization for the leader-authoritative ordered route plan and
 * the stale-snapshot guard.
 *
 * Schema (versioned, backward-compatible; stored on the ride document):
 *  - `routeStops`: ordered array of { id, name, address?, role, lat, lng }
 *    (origin first, destination last; intermediates in leader-defined order)
 *  - `routePlanVersion`: Long, incremented by the leader on every plan change
 *
 * Legacy rides with no `routeStops` field parse to an empty list and keep
 * working unchanged. Rider-location updates never touch these fields, and a
 * snapshot older than the locally known [routePlanVersion] is rejected by
 * [mergeRoutePlan] instead of overwriting the newer plan.
 */
object RouteStopCodec {

    fun toFirestore(stops: List<RouteStop>): List<Map<String, Any>> =
        stops.mapIndexed { index, stop ->
            val map = linkedMapOf<String, Any>(
                "id" to stop.id,
                "name" to stop.name,
                "role" to stop.role.name,
                "lat" to stop.latitude,
                "lng" to stop.longitude,
                "order" to index
            )
            stop.address?.takeIf { it.isNotBlank() }?.let { map["address"] = it }
            map
        }

    fun fromFirestore(raw: List<*>?): List<RouteStop> {
        if (raw.isNullOrEmpty()) return emptyList()
        val stops = mutableListOf<RouteStop>()
        for (item in raw) {
            val map = item as? Map<*, *> ?: continue
            val lat = (map["lat"] as? Number)?.toDouble() ?: continue
            val lng = (map["lng"] as? Number)?.toDouble() ?: continue
            val roleStr = map["role"] as? String
            val role = RouteStopRole.entries.firstOrNull { it.name == roleStr }
                ?: RouteStopRole.INTERMEDIATE
            stops.add(
                RouteStop(
                    id = (map["id"] as? String) ?: UUID.randomUUID().toString(),
                    name = (map["name"] as? String) ?: "Stop",
                    address = map["address"] as? String,
                    latitude = lat,
                    longitude = lng,
                    role = role
                )
            )
        }
        return stops
    }

    /**
     * Stale-route-plan guard: the locally known plan wins over an incoming
     * snapshot whose position in the plan version history is older; strictly
     * newer plans are adopted. Equal versions keep the local state (no churn).
     */
    fun mergeRoutePlan(
        localVersion: Long,
        localStops: List<RouteStop>,
        incomingVersion: Long,
        incomingStops: List<RouteStop>
    ): Triple<Long, List<RouteStop>, Boolean> =
        if (incomingVersion > localVersion) {
            Triple(incomingVersion, incomingStops, true)
        } else {
            Triple(localVersion, localStops, false)
        }
}
