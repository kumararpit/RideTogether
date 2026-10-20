package com.example.ui.map

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rider-marker presentation rules (Phase B, backlog B-15 / B-16).
 *
 * Pure, testable decisions that the canvas applies when drawing a rider:
 *  - how the **accuracy** of the fix is shown (a ring whose weight and opacity
 *    follow the accuracy bucket);
 *  - how a **stale** rider is shown (a distinct, muted state plus the age);
 *  - what to draw when **no qualified bearing** is available (never a
 *    fabricated north arrow);
 *  - how **overlapping** riders are separated (a deterministic, order-free
 *    offset).
 *
 * Invariant: none of these rules may consume the dead-reckoning prediction —
 * they describe the *measured* fix only.
 */
object RiderMarkerStyle {

    /** Accuracy buckets (metres) for the fix-quality ring. */
    enum class AccuracyBucket {
        /** ≤ 10 m — a good fix: no ring needed. */
        GOOD,

        /** 10–30 m — usable but noisy: a faint ring. */
        FAIR,

        /** 30–100 m — coarse: a visible ring. */
        POOR,

        /** > 100 m — very coarse: a strong ring. */
        VERY_POOR
    }

    /** Freshness buckets (milliseconds since the last update). */
    enum class FreshnessBucket {
        /** < 15 s — live. */
        LIVE,

        /** 15–60 s — slightly old. */
        AGED,

        /** 60–300 s — old. */
        OLD,

        /** > 300 s — effectively offline. */
        STALE
    }

    fun accuracyBucket(accuracyMeters: Float): AccuracyBucket = when {
        !accuracyMeters.isFinite() || accuracyMeters <= 0f -> AccuracyBucket.GOOD
        accuracyMeters <= 10f -> AccuracyBucket.GOOD
        accuracyMeters <= 30f -> AccuracyBucket.FAIR
        accuracyMeters <= 100f -> AccuracyBucket.POOR
        else -> AccuracyBucket.VERY_POOR
    }

    fun freshnessBucket(ageMs: Long): FreshnessBucket = when {
        ageMs < 0 -> FreshnessBucket.LIVE
        ageMs < 15_000L -> FreshnessBucket.LIVE
        ageMs < 60_000L -> FreshnessBucket.AGED
        ageMs < 300_000L -> FreshnessBucket.OLD
        else -> FreshnessBucket.STALE
    }

    /**
     * Ring opacity for the accuracy ring (0 = no ring drawn).
     * Only coarse fixes get a ring; a good fix stays clean.
     */
    fun accuracyRingAlpha(accuracyMeters: Float): Float = when (accuracyBucket(accuracyMeters)) {
        AccuracyBucket.GOOD -> 0f
        AccuracyBucket.FAIR -> 0.30f
        AccuracyBucket.POOR -> 0.55f
        AccuracyBucket.VERY_POOR -> 0.80f
    }

    /** True when the rider's marker should be drawn in the muted "stale" style. */
    fun isStaleStyle(ageMs: Long): Boolean =
        freshnessBucket(ageMs) == FreshnessBucket.STALE || freshnessBucket(ageMs) == FreshnessBucket.OLD

    /** Compact "last seen" label for a stale rider (e.g. "5 min ago"). */
    fun lastSeenLabel(ageMs: Long): String? {
        if (ageMs < 60_000L) return null
        val minutes = (ageMs / 60_000L)
        return if (minutes >= 60) {
            "${minutes / 60} h ago"
        } else {
            "$minutes min ago"
        }
    }

    /**
     * Whether the direction arrow may be drawn.
     *
     * A rider with **no qualified bearing** must not be shown pointing north
     * (that would fabricate a direction). Instead the marker shows a neutral
     * "direction unknown" state.
     */
    fun canDrawDirectionArrow(hasQualifiedBearing: Boolean): Boolean = hasQualifiedBearing

    /**
     * Deterministic separation offset for riders whose markers overlap.
     *
     * The offset is derived from the rider's id, so it is **stable across
     * frames** (no jitter) and independent of the member list order (no
     * reshuffling when the list is re-sorted).
     */
    fun stackingOffset(riderId: String, indexWithinCluster: Int, clusterSize: Int): Pair<Float, Float> {
        if (clusterSize <= 1) return 0f to 0f
        val hash = riderId.fold(7) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }
        val step = (hash % 360) * (Math.PI / 180.0)
        val radius = 10f + 6f * (indexWithinCluster.coerceAtMost(4))
        return (kotlin.math.cos(step) * radius).toFloat() to (kotlin.math.sin(step) * radius).toFloat()
    }

    /**
     * Groups riders into overlapping clusters (screen-space proximity).
     *
     * Deterministic: the result depends only on the input positions, and each
     * rider keeps its own identity so a cluster can always be expanded.
     */
    fun clusterRiders(
        positions: List<Pair<String, Pair<Float, Float>>>,
        maxDistancePx: Float
    ): Map<String, Int> {
        val result = HashMap<String, Int>()
        val n = positions.size
        for (i in 0 until n) {
            var clusterIndex = 0
            for (j in 0 until n) {
                if (i == j) continue
                val dx = positions[i].second.first - positions[j].second.first
                val dy = positions[i].second.second - positions[j].second.second
                if (abs(dx) <= maxDistancePx && abs(dy) <= maxDistancePx) {
                    // Cluster identity = the smallest id in the neighbourhood,
                    // so it does not depend on list order.
                    val key = minOf(positions[i].first, positions[j].first)
                    clusterIndex = maxOf(clusterIndex, key.hashCode().and(0x7fffffff))
                }
            }
            result[positions[i].first] = clusterIndex
        }
        return result
    }
}
