package com.example.data.amenities

import android.util.Log
import androidx.room.withTransaction
import com.example.data.db.RideDatabase
import com.example.data.map.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Amenity POI provider backed by a Room cache of Overpass zoom-14 cells.
 *
 * 2026-10-19 pipeline rework (Phases 1–5 of the Overpass audit):
 *  - [getAmenitiesCached] reads the local DB only — the ride screen shows cached
 *    POIs instantly without waiting for the network.
 *  - [refreshAmenities] refreshes stale cells with ONE [OverpassQueryBuilder]-built
 *    POST per batch (≤[MAX_CELLS_PER_FETCH] cells), then re-reads the bbox.
 *  - A refresh failure NEVER poisons the cache: cache marks are written only for
 *    successful HTTP responses, all writes go through one Room transaction, and
 *    classification in [OverpassResilience] retries only true-transient failures.
 *  - Cell keys are query-versioned ("14_v2_x_y"): any future QL change bumps
 *    [OverpassQueryBuilder.QUERY_VERSION] and forces one respectful re-fetch.
 */
class AmenityRepository(
    private val database: RideDatabase,
    internal val overpassApi: OverpassApiService = OverpassApiClient.api
) {

    private val dao = database.amenityDao()
    private val queryMutex = Mutex()
    private val inFlightCells = mutableSetOf<String>()

    private val CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private val MAX_CELLS_PER_FETCH = 16

    /** Injectable offline probe (ConnectivityManager); null ⇒ treated as online. */
    var isOfflineProbe: (() -> Boolean)? = null

    /** One-time cleanup of pre-versioning cache marks (see QUERY_VERSION). */
    private var preVersionedKeysCleaned = false

    /**
     * Latest cached POIs for the viewport WITHOUT any network involvement.
     * Acceptance criterion: cached POIs appear without waiting for Overpass.
     */
    suspend fun getAmenitiesCached(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<AmenityEntity> {
        val box = SafeBox(minLat, maxLat, minLon, maxLon)
        return withContext(Dispatchers.IO) {
            dao.getAmenitiesInBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
        }
    }

    /**
     * Refreshes every z14 cell intersecting the viewport whose cache mark is
     * missing or older than [CACHE_EXPIRY_MS], then returns the bbox rows
     * (fresh rows included). Cells carrying a fresh mark are never re-fetched.
     */
    suspend fun refreshAmenities(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<AmenityEntity> = withContext(Dispatchers.IO) {
        val box = SafeBox(minLat, maxLat, minLon, maxLon)
        val cells = cellsForBBox(box)
        val staleCells = findStaleCells(cells)

        if (staleCells.isNotEmpty()) {
            if (!preVersionedKeysCleaned) {
                dao.deletePreV2QueryTimestamps()
                preVersionedKeysCleaned = true
            }
            fetchAndCacheCells(staleCells.take(MAX_CELLS_PER_FETCH))
        }

        Log.d(
            "AmenityPerf",
            "refresh done: cells=%d stale=%d (boundedTo=%d)".format(
                cells.size, staleCells.size, min(staleCells.size, MAX_CELLS_PER_FETCH)
            )
        )
        dao.getAmenitiesInBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
    }

    private suspend fun findStaleCells(cells: List<AmenityCell>): List<AmenityCell> {
        if (cells.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        // One Room round-trip for the whole batch instead of one SELECT per cell.
        val marks = dao.getQueryTimestamps(cells.map { it.key })
            .associateBy({ it.cellKey }, { it.timestampMs })
        return cells.filter { cell ->
            val ts = marks[cell.key]
            ts == null || now - ts > CACHE_EXPIRY_MS
        }
    }

    private suspend fun fetchAndCacheCells(cells: List<AmenityCell>) {
        val cellsToFetch = mutableListOf<AmenityCell>()

        queryMutex.withLock {
            for (cell in cells) {
                if (inFlightCells.add(cell.key)) cellsToFetch.add(cell)
            }
        }

        if (cellsToFetch.isEmpty()) return

        val startedAt = System.currentTimeMillis()
        var parseMs = -1L
        var elementCount = -1
        try {
            // One consolidated QL for the whole batch (8 statements per cell).
            val query = OverpassQueryBuilder.buildQuery(cellsToFetch.map { it.box })
            val fetchStart = System.currentTimeMillis()
            val response = OverpassResilience.queryWithOneRetry(
                isProbablyOffline = isOfflineProbe?.invoke() ?: false
            ) { overpassApi.query(query) }
            val fetchMs = System.currentTimeMillis() - fetchStart

            val parseStart = System.currentTimeMillis()
            val entities = parseOverpassResponse(response)
            parseMs = System.currentTimeMillis() - parseStart
            elementCount = entities.size

            // Cache marks for successful responses (empty or not): an empty cell
            // is a real OSM fact, so a 7-day mark avoids hammering the public
            // endpoint for genuinely sparse rural cells.
            val marks = cellsToFetch.map { cell -> AmenityQueryCacheEntity(cell.key, startedAt) }
            // One Room transaction for data + marks: a crash between the two can
            // only cost one redundant re-fetch, never a half-applied cache.
            database.withTransaction {
                if (entities.isNotEmpty()) dao.insertAmenities(entities)
                dao.insertQueryCaches(marks)
            }

            Log.d(
                "AmenityPerf",
                "fetch ok: cells=%d elements=%d fetchMs=%d parseMs=%d".format(
                    cellsToFetch.size, elementCount, fetchMs, parseMs
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                "AmenityRepository",
                "Failed to fetch amenities after %dms".format(System.currentTimeMillis() - startedAt),
                e
            )
            Log.d(
                "AmenityPerf",
                "fetch failed: cells=%d transient=%s".format(
                    cellsToFetch.size, OverpassResilience.isTransientFailure(e)
                )
            )
        } finally {
            queryMutex.withLock {
                inFlightCells.removeAll(cellsToFetch.map { it.key }.toSet())
            }
        }
    }

    private fun parseOverpassResponse(response: OverpassResponse): List<AmenityEntity> {
        val list = mutableListOf<AmenityEntity>()
        response.elements?.forEach { el ->
            val lat = el.lat ?: el.center?.lat
            val lon = el.lon ?: el.center?.lon
            if (lat != null && lon != null) {
                val tags = el.tags ?: emptyMap()
                val type = when {
                    tags["amenity"] == "fuel" -> AmenityType.FUEL
                    tags["amenity"] == "cafe" -> AmenityType.CAFE
                    tags["shop"] == "motorcycle_repair" || tags["amenity"] == "motorcycle_repair" -> AmenityType.REPAIR
                    tags["highway"] == "rest_area" || tags["amenity"] == "rest_stop" -> AmenityType.REST_STOP
                    tags["shop"] == "convenience" || tags["shop"] == "supermarket" -> AmenityType.CONVENIENCE_STORE
                    tags["tourism"] == "hotel" || tags["tourism"] == "motel" -> AmenityType.HOTEL
                    tags["amenity"] == "restaurant" || tags["amenity"] == "fast_food" -> AmenityType.RESTAURANT
                    tags["amenity"] == "toilets" -> AmenityType.TOILET
                    tags["tourism"] == "viewpoint" -> AmenityType.SCENIC_VIEWPOINT
                    else -> AmenityType.UNKNOWN
                }
                if (type != AmenityType.UNKNOWN) {
                    val tagsJson = tags.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
                        "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\""
                    }
                    list.add(
                        AmenityEntity(
                            osmId = el.id,
                            type = type,
                            lat = lat,
                            lon = lon,
                            name = tags["name"],
                            tagsJson = tagsJson
                        )
                    )
                }
            }
        }
        return list.distinctBy { it.osmId }
    }

    internal data class SafeBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    /** Viewport → zoom-14 cell list with versioned keys (pure; unit-tested). */
    internal fun cellsForBBox(box: SafeBox, zoom: Int = OverpassQueryBuilder.CELL_ZOOM): List<AmenityCell> {
        val safeMinLat = min(box.minLat, box.maxLat)
        val safeMaxLat = max(box.minLat, box.maxLat)
        val safeMinLon = min(box.minLon, box.maxLon)
        val safeMaxLon = max(box.minLon, box.maxLon)

        val minX = TileMath.lonToTileX(safeMinLon, zoom.toFloat()).toInt()
        val maxX = TileMath.lonToTileX(safeMaxLon, zoom.toFloat()).toInt()
        val maxY = TileMath.latToTileY(safeMinLat, zoom.toFloat()).toInt()
        val minY = TileMath.latToTileY(safeMaxLat, zoom.toFloat()).toInt()

        val cells = mutableListOf<AmenityCell>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val south = TileMath.tileYToLat((y + 1).toDouble(), zoom.toFloat())
                val north = TileMath.tileYToLat(y.toDouble(), zoom.toFloat())
                val west = TileMath.tileXToLon(x.toDouble(), zoom.toFloat())
                val east = TileMath.tileXToLon((x + 1).toDouble(), zoom.toFloat())
                cells.add(
                    AmenityCell(
                        key = "${zoom}_v${OverpassQueryBuilder.QUERY_VERSION}_${x}_${y}",
                        box = OverpassQueryBuilder.Cell(south, west, north, east)
                    )
                )
            }
        }
        return cells
    }

    internal data class AmenityCell(
        val key: String,
        val box: OverpassQueryBuilder.Cell
    )
}
