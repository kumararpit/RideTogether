package com.example.ui.map

/**
 * Screen-space clustering of amenity markers (Phase B, backlog B-14).
 *
 * The reference clusters dense POI sets natively (`VISUAL_EXPLORE_CLUSTER`,
 * `FRIEND_CLUSTER` overlay slots); its algorithm is not recoverable, so this is
 * an independent implementation.
 *
 * Requirements that shaped it:
 *  - **deterministic** — the same positions always produce the same clusters
 *    (no flicker between frames, no reshuffling when the amenity list order
 *    changes);
 *  - **order independent** — the cluster of a place never depends on where the
 *    place appears in the list;
 *  - the **representative** is stable (the smallest OSM id in the cluster), so
 *    the surviving pin does not jump around.
 */
object AmenityClustering {

    /** A group of amenities that are drawn as ONE marker. */
    data class Cluster(
        /** The OSM id of the marker that is drawn (the smallest id in the group). */
        val representativeId: Long,
        /** Every member of the group, ascending by id. */
        val memberIds: List<Long>,
        /** The screen position of the drawn marker. */
        val x: Float,
        val y: Float
    ) {
        /** How many amenities the drawn marker represents. */
        val count: Int get() = memberIds.size

        /** True when the cluster holds more than one amenity (a badge is drawn). */
        val isGroup: Boolean get() = memberIds.size > 1
    }

    /**
     * Groups amenities whose screen positions are within [maxDistancePx] of each
     * other (in both axes).
     *
     * @param points amenity id → screen position
     */
    fun cluster(
        points: List<Pair<Long, Pair<Float, Float>>>,
        maxDistancePx: Float
    ): List<Cluster> {
        if (points.isEmpty()) return emptyList()

        val ids = points.map { it.first }
        val positions = points.associate { it.first to it.second }

        // Union-find over the (small) proximity graph.
        val parent = ids.associateWith { it }.toMutableMap()
        fun find(x: Long): Long {
            var root = x
            while (true) {
                val next = parent[root] ?: break
                if (next == root) break
                root = next
            }
            var cur = x
            while (true) {
                val next = parent[cur] ?: break
                if (next == root) break
                parent[cur] = root
                cur = next
            }
            return root
        }
        fun union(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            // Always merge towards the SMALLER id, so the representative is the
            // smallest id of the group and the result is order independent.
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val a = positions[ids[i]] ?: continue
                val b = positions[ids[j]] ?: continue
                if (kotlin.math.abs(a.first - b.first) <= maxDistancePx &&
                    kotlin.math.abs(a.second - b.second) <= maxDistancePx
                ) {
                    union(ids[i], ids[j])
                }
            }
        }

        val groups = HashMap<Long, MutableList<Long>>()
        for (id in ids) {
            val root = find(id)
            groups.getOrPut(root) { mutableListOf() }.add(id)
        }

        return groups.values.map { members ->
            val sorted = members.sorted()
            val representative = sorted.first()
            val pos = positions[representative] ?: (0f to 0f)
            Cluster(
                representativeId = representative,
                memberIds = sorted,
                x = pos.first,
                y = pos.second
            )
        }.sortedBy { it.representativeId }
    }
}
