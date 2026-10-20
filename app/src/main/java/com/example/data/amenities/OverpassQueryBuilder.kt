package com.example.data.amenities

/**
 * Builds the Overpass QL string for a batch of zoom-14 cells.
 *
 * Phase-3 optimization (2026-10-19): the previous builder emitted 26 statements per
 * cell (13 categories x node/way with a full bbox each) — a 4-cell batch produced a
 * ~9 kB request URL and the public endpoint rejected it with HTTP 414 (header cap).
 * Compatible filters are now combined into one regex union per (element-type, key)
 * — 8 statements per cell, ~3.5 kB for the same 4 cells — with IDENTICAL category
 * and element coverage (locked by tests).
 *
 * POST is the transport (see OverpassApiService); this builder only assembles text.
 */
object OverpassQueryBuilder {

    /**
     * Query-format version, surfaced in cache cell keys ("14_v2_x_y") so any change
     * in QL semantics invalidates stale marks instead of serving old geometry.
     */
    const val QUERY_VERSION = 2

    const val CELL_ZOOM = 14

    private val AMENITY_VALUES = "fuel|cafe|motorcycle_repair|restaurant|fast_food|toilets"
    private val SHOP_VALUES = "motorcycle_repair|convenience|supermarket"
    private val TOURISM_VALUES = "hotel|motel|viewpoint"

    /**
     * Compiles QL covering exactly the 13 original (tag-key,value) selectors, each
     * required for both `node` and `way`:
     *  - amenity: fuel, cafe, motorcycle_repair, restaurant, fast_food, toilets
     *  - shop:    motorcycle_repair, convenience, supermarket
     *  - tourism: hotel, motel, viewpoint
     *  - highway: rest_area
     */
    fun buildQuery(cells: List<Cell>): String {
        val builder = StringBuilder("[out:json][timeout:25];(")
        for (cell in cells) {
            val bbox = "${cell.south},${cell.west},${cell.north},${cell.east}"
            builder
                .append("node[\"amenity\"~\"^($AMENITY_VALUES)\$\"]")
                .append('(').append(bbox).append(");")
                .append("way[\"amenity\"~\"^($AMENITY_VALUES)\$\"]")
                .append('(').append(bbox).append(");")
                .append("node[\"shop\"~\"^($SHOP_VALUES)\$\"]")
                .append('(').append(bbox).append(");")
                .append("way[\"shop\"~\"^($SHOP_VALUES)\$\"]")
                .append('(').append(bbox).append(");")
                .append("node[\"tourism\"~\"^($TOURISM_VALUES)\$\"]")
                .append('(').append(bbox).append(");")
                .append("way[\"tourism\"~\"^($TOURISM_VALUES)\$\"]")
                .append('(').append(bbox).append(");")
                .append("node[\"highway\"=\"rest_area\"]")
                .append('(').append(bbox).append(");")
                .append("way[\"highway\"=\"rest_area\"]")
                .append('(').append(bbox).append(");")
        }
        builder.append(");out center;")
        return builder.toString()
    }

    data class Cell(val south: Double, val west: Double, val north: Double, val east: Double)
}
