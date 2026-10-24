package com.example.data.map.tile

import com.example.data.map.TileMath

/**
 * Immutable key identifying a map tile by zoom level and column/row (slippy map
 * convention: x increases east, y increases south).
 *
 * Evidence: Google Maps uses SnaptileId (zoom/x/y) as the canonical tile
 * identity across its fetcher, cache, and disk-cache layers.
 */
data class TileKey(
    val zoom: Int,
    val x: Int,
    val y: Int
) {
    override fun toString(): String = "$zoom/$x/$y"

    /** OSM standard tile URL for this key. */
    fun url(): String = TileMath.getOsmTileUrl(zoom, x, y)

    /** Parent tile at zoom-1 (integer division). */
    fun parent(): TileKey = TileKey(zoom - 1, x shr 1, y shr 1)

    /** Whether this key can have a parent (zoom > minZoom). */
    fun hasParent(minZoom: Int = 3): Boolean = zoom > minZoom
}
