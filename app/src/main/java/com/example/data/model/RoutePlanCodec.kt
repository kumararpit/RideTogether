package com.example.data.model

/**
 * The leader-authoritative route plan: the ordered stops, the monotonic plan
 * version, the leader's chosen candidate route and the candidate list the
 * choice is made from.
 */
data class RoutePlan(
    val version: Long,
    val stops: List<RouteStop>,
    /** Index into [options] of the route the leader selected. */
    val selectedRouteIndex: Int = 0,
    /** Candidate routes for this plan (summaries only). */
    val options: List<RouteOptionSummary> = emptyList()
)

/**
 * Firestore serialization for the route plan and the stale-snapshot guard.
 *
 * Schema (versioned, backward-compatible; stored on the ride document):
 *  - `routeStops`: ordered array of { id, name, address?, role, lat, lng }
 *  - `routePlanVersion`: Long, incremented by the leader on every plan change
 *  - `selectedRouteIndex`: Int, the leader's chosen candidate route
 *  - `routeOptions`: array of { index, distanceMeters, durationSeconds }
 *
 * Legacy rides without these fields parse to an empty plan and keep working
 * unchanged: a missing `selectedRouteIndex` means "the engine's primary
 * route" (0) and a missing `routeOptions` means "only one candidate existed".
 */
object RoutePlanCodec {

    fun optionsToFirestore(options: List<RouteOptionSummary>): List<Map<String, Any>> =
        options.map { option ->
            mapOf(
                "index" to option.index,
                "distanceMeters" to option.distanceMeters,
                "durationSeconds" to option.durationSeconds
            )
        }

    fun optionsFromFirestore(raw: List<*>?): List<RouteOptionSummary> {
        if (raw.isNullOrEmpty()) return emptyList()
        val options = mutableListOf<RouteOptionSummary>()
        for (item in raw) {
            val map = item as? Map<*, *> ?: continue
            val index = (map["index"] as? Number)?.toInt() ?: continue
            val distance = (map["distanceMeters"] as? Number)?.toDouble() ?: continue
            val duration = (map["durationSeconds"] as? Number)?.toDouble() ?: continue
            options.add(RouteOptionSummary(index, distance, duration))
        }
        return options
    }

    /**
     * Stale-plan guard: the locally known plan wins over an incoming snapshot
     * whose version is older; a strictly newer plan is adopted in full
     * (stops, selection and candidate list travel together); an equal version
     * keeps the local state so a re-delivered snapshot causes no churn.
     */
    fun merge(local: RoutePlan, incoming: RoutePlan): RoutePlan =
        if (incoming.version > local.version) incoming else local

    /**
     * Convenience overload for callers that only track stops: the selection is
     * preserved from whichever side wins the version comparison.
     */
    fun merge(
        localVersion: Long,
        localStops: List<RouteStop>,
        localSelectedRouteIndex: Int,
        localOptions: List<RouteOptionSummary>,
        incomingVersion: Long,
        incomingStops: List<RouteStop>,
        incomingSelectedRouteIndex: Int,
        incomingOptions: List<RouteOptionSummary>
    ): RoutePlan {
        val localPlan = RoutePlan(localVersion, localStops, localSelectedRouteIndex, localOptions)
        val incomingPlan = RoutePlan(incomingVersion, incomingStops, incomingSelectedRouteIndex, incomingOptions)
        return merge(localPlan, incomingPlan)
    }
}
